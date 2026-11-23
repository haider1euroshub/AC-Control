package com.roomac.controller.driver.gree

import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.BroadcastBursts
import com.roomac.controller.protocol.DeviceDiscovery
import com.roomac.controller.protocol.DiscoveredDevice
import com.roomac.controller.protocol.LanAccess
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.datagramSocket
import com.roomac.controller.protocol.SubnetMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** One UDP request/reply with a Gree module. Replaced by a fake in tests. */
interface GreeTransport {
    /** Sends [payload] to host:port and returns the first datagram that comes back from that host, or null. */
    suspend fun request(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray?
}

class UdpGreeTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lan: LanAccess = LanAccess.Direct
) : GreeTransport {
    override suspend fun request(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? =
        withContext(ioDispatcher) {
            lan.datagramSocket().use { socket ->
                val target = InetAddress.getByName(host)
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(target, port)))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                var result: ByteArray? = null
                while (result == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt()
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                    // Only the unit we asked: anything else would be the wrong identity.
                    if (packet.address == target) result = buf.copyOf(packet.length)
                }
                result
            }
        }
}

/** Decodes a scan reply ("t":"dev"). The generic key is tried in V1 then V2, as modules differ. */
object GreeScanReply {
    fun parse(datagram: ByteArray, sourceHost: String, sourcePort: Int = GreeCodec.PORT): DiscoveredDevice? {
        val outer = GreeCodec.outer(datagram) ?: return null
        val reply = listOf(GreeCipherVersion.V1_ECB, GreeCipherVersion.V2_GCM).firstNotNullOfOrNull { v ->
            if (v == GreeCipherVersion.V2_GCM && outer["tag"] == null) return@firstNotNullOfOrNull null
            try { GreeCodec.decrypt(outer, GreeCipher.generic(v)) } catch (_: Exception) { null }
        } ?: return null
        if (reply.type != "dev") return null
        val pack = reply.pack
        val mac = GreeCodec.canonicalMac(pack.str("mac").ifEmpty { pack.str("cid") }.ifEmpty { outer.str("cid") })
        if (mac.length < 6) return null
        return DiscoveredDevice(
            deviceId = mac.uppercase(),
            host = sourceHost,
            port = if (sourcePort in 1..65535) sourcePort else GreeCodec.PORT,
            uPlusId = "",
            firmware = pack.str("ver"),
            sdkVersion = "",
            cloudState = null,
            protocolId = ProtocolIds.GREE_WIFI,
            // Modules report their own brand; OEM and rebranded units keep what they say.
            brand = pack.str("brand").trim().lowercase().ifEmpty { AcBrands.GREE.id },
            model = pack.str("model"),
            reportedName = pack.str("name")
        )
    }
}

/**
 * Finds Gree-protocol modules: a broadcast `{"t":"scan"}` to UDP 7000 plus a paced unicast sweep of
 * the phone's subnet (many access points filter broadcast). Needs no key and changes nothing.
 */
class GreeDiscovery(
    private val transport: GreeTransport = UdpGreeTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val udpPort: Int = GreeCodec.PORT,
    private val queryTimeoutMs: Long = 2000,
    private val scanListenMs: Long = 4000,
    private val lan: LanAccess = LanAccess.Direct,
    private val log: (String) -> Unit = {}
) : DeviceDiscovery {

    override suspend fun query(host: String): DiscoveredDevice? = try {
        transport.request(host, udpPort, GreeCodec.scanRequest(), queryTimeoutMs)?.let { GreeScanReply.parse(it, host) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        log("gree query to $host failed: ${e.javaClass.simpleName}")
        null
    }

    override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> =
        withContext(ioDispatcher) {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            val targets = SubnetMath.hosts(network.address, network.prefixLength, limit = 1024)
            lan.datagramSocket().use { socket ->
                socket.broadcast = true
                val req = GreeCodec.scanRequest()
                val broadcasts = BroadcastBursts.targets(network)
                val start = System.currentTimeMillis()
                val bursts = BroadcastBursts(start)
                val listenUntil = start + scanListenMs + targets.size * SEND_PACE_MS
                val buf = ByteArray(4096)
                var next = 0
                while (coroutineContext.isActive && System.currentTimeMillis() < listenUntil) {
                    val now = System.currentTimeMillis()
                    if (bursts.due(now)) broadcasts.forEach { trySend(socket, req, it) }
                    if (next < targets.size) {
                        trySend(socket, req, targets[next++])
                        socket.soTimeout = SEND_PACE_MS.toInt()
                    } else {
                        val wait = minOf(listenUntil - now, bursts.untilNext(now) ?: Long.MAX_VALUE)
                        socket.soTimeout = maxOf(1L, wait).toInt()
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val info = GreeScanReply.parse(buf.copyOf(packet.length), packet.address.hostAddress ?: "", packet.port) ?: continue
                    if (found.putIfAbsent(info.deviceId, info) == null) {
                        log("found Gree-protocol appliance ${info.deviceId} at ${info.host}")
                        onFound(info)
                    }
                }
            }
            found.values.toList()
        }

    private fun trySend(socket: DatagramSocket, req: ByteArray, host: String) {
        try {
            socket.send(DatagramPacket(req, req.size, InetSocketAddress(InetAddress.getByName(host), udpPort)))
        } catch (_: Exception) {
            // Unreachable addresses are expected during a sweep.
        }
    }

    companion object {
        private const val SEND_PACE_MS = 4L
    }
}
