package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath

/** A resolved DNS-SD service instance. */
data class MdnsService(
    val instanceName: String,
    val serviceType: String,
    /** IPv4 address; services that resolve only to IPv6 are skipped. */
    val host: String?,
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
    val hostname: String? = null
)

/** Browses and resolves DNS-SD services. On Android this is NsdManager; tests use a fake. */
interface MdnsBrowser {
    /** Browses [serviceTypes] for [durationMs], calling [onResolved] for each instance that resolves. */
    suspend fun browse(serviceTypes: List<String>, durationMs: Long, onResolved: (MdnsService) -> Unit)
}

/** Discovers devices by the services they advertise over mDNS/DNS-SD. */
class MdnsDiscovery(
    private val browser: MdnsBrowser,
    private val serviceTypes: List<String> = SERVICE_TYPES,
    private val durationMs: Long = 6_000
) : DiscoverySource {

    override val source = ScanSource.MDNS

    override suspend fun discover(network: LocalNetwork, sink: ObservationSink) {
        browser.browse(serviceTypes, durationMs) { service -> toObservation(service)?.let(sink::observe) }
    }

    companion object {
        /** Service types browsed by default: printers, TVs and cast targets, speakers, HomeKit and other smart-home gear. */
        val SERVICE_TYPES = listOf(
            "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_spotify-connect._tcp", "_sonos._tcp",
            "_androidtvremote2._tcp", "_amzn-wplay._tcp",
            "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_uscan._tcp",
            "_hap._tcp", "_matter._tcp", "_hue._tcp", "_esphomelib._tcp",
            "_rtsp._tcp", "_http._tcp", "_apple-mobdev2._tcp", "_smb._tcp", "_workstation._tcp"
        )

        /** "_ipp._tcp." / "_ipp._tcp.local." / "._ipp._tcp" to "_ipp._tcp". */
        fun normalizeType(type: String): String =
            type.trim().trim('.').removeSuffix(".local").trim('.').lowercase()

        fun toObservation(service: MdnsService): Observation? {
            val host = service.host?.takeIf { SubnetMath.toInt(it) != null } ?: return null
            val type = normalizeType(service.serviceType)
            return Observation(
                ip = host,
                source = ScanSource.MDNS,
                hostname = service.hostname?.trim()?.trimEnd('.')?.removeSuffix(".local")?.takeIf { it.isNotBlank() },
                // Cast devices put their room name in "fn" (the instance name is an id). Workstation instances
                // are named "host [aa:bb:cc:dd:ee:ff]"; drop the MAC from the display name.
                name = service.txt["fn"]?.takeIf { it.isNotBlank() }
                    ?: service.instanceName.replace(Regex("\\s*\\[[0-9a-fA-F:]{17}]$"), "").trim().takeIf { it.isNotBlank() },
                model = service.txt["md"] ?: service.txt["model"] ?: service.txt["ty"],
                mdnsServices = mapOf(type to service.txt),
                mdnsPorts = if (service.port in 1..65535) mapOf(type to service.port) else emptyMap()
            )
        }
    }
}
