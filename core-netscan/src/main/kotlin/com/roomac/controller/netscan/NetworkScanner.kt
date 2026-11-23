package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Merges observations from every source into one device per address. An observation whose MAC is already
 * known on another address joins that device (its address goes in [NetworkDevice.otherIps]).
 */
class DeviceAggregator(private val ouis: OuiTable = OuiTable.DEFAULT, private val gateway: String? = null) {

    private val byIp = linkedMapOf<String, NetworkDevice>()

    @Synchronized
    fun add(o: Observation) {
        val mac = Mac.normalize(o.mac)
        val key = byIp[o.ip]?.let { o.ip }
            ?: mac?.let { m -> byIp.entries.firstOrNull { it.value.mac == m }?.key }
            ?: o.ip
        val current = byIp[key] ?: NetworkDevice(ip = o.ip, isGateway = o.ip == gateway)
        byIp[key] = current.copy(
            mac = current.mac ?: mac,
            hostname = current.hostname ?: o.hostname,
            // A UPnP friendlyName or mDNS name is better than none; the first one seen is kept.
            name = current.name ?: o.name,
            vendor = current.vendor ?: o.manufacturer,
            model = current.model ?: o.model,
            mdnsServices = current.mdnsServices + o.mdnsServices,
            mdnsPorts = current.mdnsPorts + o.mdnsPorts,
            ssdpLocation = current.ssdpLocation ?: o.ssdpLocation,
            onvifXAddrs = current.onvifXAddrs + o.onvifXAddrs,
            ssdpTypes = current.ssdpTypes + o.ssdpTypes,
            ssdpServer = current.ssdpServer ?: o.ssdpServer,
            openPorts = current.openPorts + o.openPorts,
            sources = current.sources + o.source,
            otherIps = if (key != o.ip) current.otherIps + o.ip else current.otherIps
        )
    }

    /** The merged devices, classified, sorted by address. */
    @Synchronized
    fun devices(): List<NetworkDevice> = byIp.values.map(::finish).sortedBy { ipOrder(it.ip) }

    private fun finish(d: NetworkDevice): NetworkDevice {
        val oui = ouis.lookup(d.mac)
        val randomized = d.mac?.let(Mac::isRandomized) ?: false
        // The MAC's registered maker is more reliable than what a UPnP description claims.
        val withVendor = d.copy(vendor = oui?.name ?: d.vendor, randomizedMac = randomized)
        val result = DeviceClassifier.classify(withVendor, oui)
        return withVendor.copy(type = result.type, typeReason = result.reason)
    }

    private fun ipOrder(ip: String): Long = SubnetMath.toInt(ip) ?: Long.MAX_VALUE
}

/**
 * Runs every discovery source at once and publishes the merged, classified device list as it grows.
 * A source that fails or times out is reported in [ScanState.sources]; the others carry on.
 */
class NetworkScanner(
    private val sources: List<DiscoverySource>,
    private val ouis: OuiTable = OuiTable.DEFAULT,
    private val sourceTimeoutMs: Long = 45_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    suspend fun scan(network: LocalNetwork, gateway: String?): ScanState {
        val aggregator = DeviceAggregator(ouis, gateway)
        val notes = linkedSetOf<String>()
        _state.value = ScanState(running = true, sources = sources.associate { it.source to SourceOutcome.Running })

        fun publish() = _state.update { it.copy(devices = aggregator.devices(), notes = synchronized(notes) { notes.toList() }) }

        coroutineScope {
            sources.forEach { source ->
                launch {
                    val count = AtomicInteger()
                    val sink = object : ObservationSink {
                        override fun observe(observation: Observation) {
                            count.incrementAndGet()
                            aggregator.add(observation)
                            publish()
                        }
                        override fun note(message: String) {
                            synchronized(notes) { notes += message }
                            publish()
                        }
                    }
                    val outcome = try {
                        val finished = withTimeoutOrNull(sourceTimeoutMs) { source.discover(network, sink); true }
                        if (finished == null) SourceOutcome.Failed("timed out") else SourceOutcome.Done(count.get())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Network scan: ${source.source.label} failed: ${e.javaClass.simpleName}")
                        SourceOutcome.Failed(e.message ?: e.javaClass.simpleName)
                    }
                    _state.update { it.copy(sources = it.sources + (source.source to outcome)) }
                }
            }
        }
        publish()
        _state.update { it.copy(running = false, finishedAtEpochMs = clock()) }
        log("Network scan: ${_state.value.devices.size} devices")
        return _state.value
    }
}
