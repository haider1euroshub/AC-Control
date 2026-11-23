package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/** Sends an SSDP M-SEARCH and collects the replies. Replaced by a fake in tests. */
interface SsdpTransport {
    suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (sourceIp: String, payload: ByteArray) -> Unit)
}

/** Fetches a UPnP device description (LOCATION). Replaced by a fake in tests. */
interface DescriptionFetcher {
    suspend fun fetch(url: String): String?
}

data class SsdpResponse(val headers: Map<String, String>) {
    val st: String? get() = headers["st"] ?: headers["nt"]
    val usn: String? get() = headers["usn"]
    val server: String? get() = headers["server"]
    val location: String? get() = headers["location"]
}

data class UpnpDescription(val friendlyName: String?, val manufacturer: String?, val modelName: String?, val deviceType: String?)

object SsdpParser {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    fun mSearch(searchTarget: String = "ssdp:all", mxSeconds: Int = 2): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $searchTarget\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Parses an M-SEARCH reply or NOTIFY; null for anything else (including our own M-SEARCH echoed back). */
    fun parse(payload: ByteArray): SsdpResponse? {
        val lines = String(payload, Charsets.UTF_8).split("\r\n", "\n")
        val start = lines.firstOrNull()?.trim()?.uppercase() ?: return null
        if (!start.startsWith("HTTP/1.1 200") && !start.startsWith("NOTIFY ")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        return SsdpResponse(headers)
    }

    /** The URN part of a USN ("uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1"). */
    fun usnType(usn: String?): String? = usn?.substringAfter("::", "")?.takeIf { it.startsWith("urn:") }

    /** Extracts the first device's identity from a description document. Tolerant of namespaces and sloppy XML. */
    fun parseDescription(xml: String): UpnpDescription {
        fun tag(name: String) = Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
        return UpnpDescription(tag("friendlyName"), tag("manufacturer"), tag("modelName"), tag("deviceType"))
    }

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Only follow a LOCATION that points back at the responder, over plain http on the LAN. */
    fun safeLocation(location: String?, responderIp: String): String? {
        val uri = try { URI(location ?: return null) } catch (_: Exception) { return null }
        if (uri.scheme?.lowercase() != "http" || uri.host != responderIp) return null
        return uri.toString()
    }
}

/** Discovers UPnP devices (routers, TVs, media renderers, printers) with an SSDP M-SEARCH. */
class SsdpDiscovery(
    private val transport: SsdpTransport,
    private val fetcher: DescriptionFetcher?,
    private val listenMs: Long = 3_500,
    private val fetchTimeoutMs: Long = 2_500
) : DiscoverySource {

    override val source = ScanSource.SSDP

    override suspend fun discover(network: LocalNetwork, sink: ObservationSink) {
        val byIp = linkedMapOf<String, MutableList<SsdpResponse>>()
        transport.search(SsdpParser.mSearch(), listenMs) { ip, payload ->
            if (SubnetMath.toInt(ip) == null) return@search
            val response = SsdpParser.parse(payload) ?: return@search
            synchronized(byIp) { byIp.getOrPut(ip) { mutableListOf() }.add(response) }
        }
        val snapshot = synchronized(byIp) { byIp.mapValues { it.value.toList() } }
        coroutineScope {
            snapshot.map { (ip, responses) ->
                async {
                    val types = responses.flatMap { listOfNotNull(it.st, SsdpParser.usnType(it.usn)) }
                        // uuid:/upnp:rootdevice/ssdp:all say nothing about the kind of device.
                        .filterNot { it.startsWith("uuid:") || it.startsWith("upnp:") || it.startsWith("ssdp:") }.toMutableSet()
                    val location = responses.firstNotNullOfOrNull { SsdpParser.safeLocation(it.location, ip) }
                    val description = if (fetcher != null && location != null) {
                        withTimeoutOrNull(fetchTimeoutMs) { fetcher.fetch(location) }?.let(SsdpParser::parseDescription)
                    } else null
                    description?.deviceType?.let(types::add)
                    sink.observe(
                        Observation(
                            ip = ip,
                            source = ScanSource.SSDP,
                            name = description?.friendlyName,
                            manufacturer = description?.manufacturer,
                            model = description?.modelName,
                            ssdpTypes = types,
                            ssdpServer = responses.firstNotNullOfOrNull { it.server },
                            ssdpLocation = location
                        )
                    )
                }
            }.awaitAll()
        }
    }
}

class UdpSsdpTransport(private val io: CoroutineDispatcher = Dispatchers.IO) : SsdpTransport {
    override suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) =
        searchTo(SsdpParser.MULTICAST_ADDRESS, SsdpParser.PORT, request, timeoutMs, onResponse)

    /** Sends [request] (twice: UDP is lossy) to [group]:[port] and reports every reply until [timeoutMs]. */
    suspend fun searchTo(group: String, port: Int, request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) = withContext(io) {
        DatagramSocket().use { socket ->
            val target = InetSocketAddress(InetAddress.getByName(group), port)
            repeat(2) { socket.send(DatagramPacket(request, request.size, target)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            // WS-Discovery ProbeMatches can be several KB.
            val buf = ByteArray(16 * 1024)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                val ip = packet.address?.hostAddress ?: continue
                onResponse(ip, buf.copyOf(packet.length))
            }
        }
    }
}

/**
 * A minimal HTTP/1.0 GET over a plain socket, capped at [maxBytes]. A raw socket keeps the app's
 * cleartext-HTTP policy closed for everything else; only this LAN description read uses plain http.
 */
class SocketDescriptionFetcher(
    private val timeoutMs: Int = 2_000,
    private val maxBytes: Int = 64 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : DescriptionFetcher {
    override suspend fun fetch(url: String): String? = withContext(io) {
        try {
            val uri = URI(url)
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                val input = socket.getInputStream()
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                httpBody(out.toString(Charsets.UTF_8.name()))
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The body of a raw HTTP/1.x reply if its status is 200, else null. */
        fun httpBody(raw: String): String? {
            val status = raw.substringBefore("\r\n")
            if (!status.startsWith("HTTP/1.") || status.split(' ').getOrNull(1) != "200") return null
            val split = raw.indexOf("\r\n\r\n")
            return if (split < 0) null else raw.substring(split + 4)
        }
    }
}
