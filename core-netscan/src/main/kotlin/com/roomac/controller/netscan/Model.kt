package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork

/** What a device appears to be. [UNKNOWN] is used whenever the evidence does not support a type. */
enum class DeviceType(val label: String) {
    ROUTER("Router"),
    TV("TV"),
    MEDIA("Media / cast device"),
    SPEAKER("Speaker"),
    PRINTER("Printer"),
    CAMERA("Camera"),
    PHONE("Phone or tablet"),
    COMPUTER("Computer"),
    IOT("Smart-home device"),
    UNKNOWN("Unknown device")
}

/** Which discovery method saw a device. */
enum class ScanSource(val label: String) {
    MDNS("mDNS"),
    SSDP("SSDP/UPnP"),
    SWEEP("Subnet sweep"),
    ARP("ARP table"),
    WSD("ONVIF WS-Discovery")
}

/**
 * One fact about one IP address from one discovery source. Sources only report what they actually saw;
 * [NetworkScanner] merges observations into [NetworkDevice]s and [DeviceClassifier] infers the type.
 */
data class Observation(
    val ip: String,
    val source: ScanSource,
    val mac: String? = null,
    val hostname: String? = null,
    /** A name the device gives itself (mDNS instance name, UPnP friendlyName). */
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    /** mDNS service type (e.g. "_ipp._tcp") to its TXT record. */
    val mdnsServices: Map<String, Map<String, String>> = emptyMap(),
    /** mDNS service type to the port it was advertised on. */
    val mdnsPorts: Map<String, Int> = emptyMap(),
    /** SSDP ST/NT values and UPnP deviceType URNs. */
    val ssdpTypes: Set<String> = emptySet(),
    val ssdpServer: String? = null,
    /** UPnP device description URL (SSDP LOCATION), only when it points back at the device. */
    val ssdpLocation: String? = null,
    /** ONVIF device-service URLs from WS-Discovery (XAddrs). */
    val onvifXAddrs: Set<String> = emptySet(),
    val openPorts: Set<Int> = emptySet()
)

data class NetworkDevice(
    val ip: String,
    val mac: String? = null,
    /** True when [mac] is a randomised (locally administered) address, so it names no manufacturer. */
    val randomizedMac: Boolean = false,
    val hostname: String? = null,
    val name: String? = null,
    /** From the MAC's OUI prefix, or what the device's UPnP description says. Null when neither is known. */
    val vendor: String? = null,
    val model: String? = null,
    val type: DeviceType = DeviceType.UNKNOWN,
    /** Human-readable evidence for [type], e.g. "advertises _ipp._tcp". Null for [DeviceType.UNKNOWN]. */
    val typeReason: String? = null,
    val isGateway: Boolean = false,
    val mdnsServices: Map<String, Map<String, String>> = emptyMap(),
    val mdnsPorts: Map<String, Int> = emptyMap(),
    val ssdpTypes: Set<String> = emptySet(),
    val ssdpServer: String? = null,
    val ssdpLocation: String? = null,
    val onvifXAddrs: Set<String> = emptySet(),
    val openPorts: Set<Int> = emptySet(),
    val sources: Set<ScanSource> = emptySet(),
    /** Other addresses the same MAC was seen on during this scan. */
    val otherIps: Set<String> = emptySet()
) {
    /** The best name to show: the device's own name, then its hostname, then its type. */
    val displayName: String get() = name ?: hostname ?: type.label
}

/** Where a discovery source sends what it finds. Safe to call from any thread. */
interface ObservationSink {
    fun observe(observation: Observation)
    /** A limitation the user should know about (e.g. "MAC addresses are not available on this Android version"). */
    fun note(message: String)
}

/** One way of finding devices. Each is independent and replaceable with a fake in tests. */
interface DiscoverySource {
    val source: ScanSource
    suspend fun discover(network: LocalNetwork, sink: ObservationSink)
}

sealed class SourceOutcome {
    data object Running : SourceOutcome()
    data class Done(val observations: Int) : SourceOutcome()
    data class Failed(val message: String) : SourceOutcome()
}

data class ScanState(
    val running: Boolean = false,
    val devices: List<NetworkDevice> = emptyList(),
    val sources: Map<ScanSource, SourceOutcome> = emptyMap(),
    val notes: List<String> = emptyList(),
    val finishedAtEpochMs: Long? = null
)
