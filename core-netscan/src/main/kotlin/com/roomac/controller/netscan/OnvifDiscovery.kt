package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath
import java.util.UUID

/**
 * ONVIF WS-Discovery (SOAP-over-UDP, multicast 239.255.255.250:3702): a Probe for
 * `dn:NetworkVideoTransmitter` is answered by ONVIF cameras and recorders with their device-service
 * URLs (XAddrs). Uses the same request/reply transport shape as SSDP, so tests use a fake.
 */
class OnvifDiscovery(
    private val transport: SsdpTransport,
    private val listenMs: Long = 3_000
) : DiscoverySource {

    override val source = ScanSource.WSD

    override suspend fun discover(network: LocalNetwork, sink: ObservationSink) {
        transport.search(probe(), listenMs) { ip, payload ->
            if (SubnetMath.toInt(ip) == null) return@search
            val match = parseProbeMatch(String(payload, Charsets.UTF_8)) ?: return@search
            // Keep only service URLs on the responder itself (same rule as SSDP LOCATION).
            val xaddrs = match.xaddrs.filter { SsdpParser.safeLocation(it, ip) != null }.toSet()
            if (xaddrs.isEmpty()) return@search
            sink.observe(Observation(ip = ip, source = ScanSource.WSD, name = match.name, model = match.hardware, onvifXAddrs = xaddrs))
        }
    }

    data class ProbeMatch(val xaddrs: List<String>, val name: String?, val hardware: String?)

    companion object {
        const val PORT = 3702

        fun probe(messageId: String = "uuid:" + UUID.randomUUID()): ByteArray = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
                "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
                "<e:Header><w:MessageID>$messageId</w:MessageID>" +
                "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
                "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
                "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"
            ).toByteArray(Charsets.UTF_8)

        /** Parses a ProbeMatches reply; null for anything else (including our own Probe echoed back). */
        fun parseProbeMatch(xml: String): ProbeMatch? {
            if (!Regex("<(?:\\w+:)?ProbeMatches[\\s>]").containsMatchIn(xml)) return null
            val xaddrs = Regex("<(?:\\w+:)?XAddrs>([^<]*)</(?:\\w+:)?XAddrs>").find(xml)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: return null
            val scopes = Regex("<(?:\\w+:)?Scopes[^>]*>([^<]*)</(?:\\w+:)?Scopes>").find(xml)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
            fun scope(kind: String) = scopes.firstOrNull { it.startsWith("onvif://www.onvif.org/$kind/") }
                ?.removePrefix("onvif://www.onvif.org/$kind/")?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            return ProbeMatch(xaddrs, scope("name"), scope("hardware"))
        }
    }
}

/** Sends one UDP datagram to a multicast group and collects replies; the shape [SsdpTransport] uses. */
class UdpMulticastTransport(private val group: String, private val port: Int) : SsdpTransport {
    private val udp = UdpSsdpTransport()
    override suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) =
        udp.searchTo(group, port, request, timeoutMs, onResponse)
}
