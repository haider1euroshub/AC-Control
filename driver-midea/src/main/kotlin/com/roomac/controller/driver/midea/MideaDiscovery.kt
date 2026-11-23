package com.roomac.controller.driver.midea

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

/** What a Midea module says about itself in its discovery reply. */
data class MideaDiscoveryInfo(
    val deviceId: Long,
    val host: String,
    val port: Int,
    val version: Int,
    val serial: String,
    val ssid: String,
    val deviceType: Int
) {
    val isAirConditioner: Boolean get() = deviceType == MideaFrame.TYPE_AC
}

/** Discovery request and reply parsing (msmart-ng discover.py / const.py, revision dd09947, MIT). */
object MideaDiscoveryCodec {
    val PORTS = intArrayOf(6445, 20086)

    /** The fixed 72-byte discovery request (msmart const.py `DISCOVERY_MSG`). */
    val REQUEST: ByteArray = intArrayOf(
        0x5a, 0x5a, 0x01, 0x11, 0x48, 0x00, 0x92, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x7f, 0x75, 0xbd, 0x6b, 0x3e, 0x4f, 0x8b, 0x76,
        0x2e, 0x84, 0x9c, 0x6e, 0x57, 0x8d, 0x65, 0x90,
        0x03, 0x6e, 0x9d, 0x43, 0x42, 0xa5, 0x0f, 0x1f,
        0x56, 0x9e, 0xb8, 0xec, 0x91, 0x8e, 0x92, 0xe5
    ).map { it.toByte() }.toByteArray()

    /**
     * Parses a V2 (5A5A) or V3 (8370) discovery reply. V1 (XML) modules are not supported and give
     * null, as does anything that does not decrypt.
     */
    fun parse(data: ByteArray, sourceIp: String): MideaDiscoveryInfo? = try {
        val version = when {
            data.size >= 2 && data[0] == 0x5A.toByte() && data[1] == 0x5A.toByte() -> 2
            data.size >= 2 && data[0] == 0x83.toByte() && data[1] == 0x70.toByte() -> 3
            else -> null
        }
        if (version == null) null else {
            val body = if (version == 3) data.copyOfRange(8, data.size - 16) else data
            var id = 0L
            for (i in 0 until 6) id = id or ((body[20 + i].toLong() and 0xFF) shl (8 * i))
            val d = MideaSecurity.decryptEcb(body.copyOfRange(40, body.size - 16))
            val port = (d[4].toInt() and 0xFF) or ((d[5].toInt() and 0xFF) shl 8)
            val serial = String(d, 8, 32, Charsets.US_ASCII).trim('\u0000', ' ')
            val nameLength = d[40].toInt() and 0xFF
            val ssid = String(d, 41, nameLength, Charsets.US_ASCII)
            val type = ssid.split("_").getOrNull(1)?.toIntOrNull(16) ?: -1
            // The reply's own IP field is ignored, as in msmart: the address that answered is used.
            MideaDiscoveryInfo(id, sourceIp, port, version, serial, ssid, type)
        }
    } catch (e: Exception) {
        null
    }

    fun protocolFor(version: Int): String = if (version == 3) ProtocolIds.MIDEA_V3 else ProtocolIds.MIDEA_V2

    fun toDiscovered(info: MideaDiscoveryInfo) = DiscoveredDevice(
        deviceId = info.deviceId.toString(),
        host = info.host,
        port = info.port,
        uPlusId = "",
        firmware = "",
        sdkVersion = "V${info.version}",
        cloudState = null,
        protocolId = protocolFor(info.version),
        brand = AcBrands.MIDEA.id
        // The serial carries a model code (sn[9:17]) but not a model name: not shown, to avoid guessing.
    )
}

/**
 * Finds Midea modules: a broadcast to both discovery ports, plus a paced unicast sweep of the phone's
 * subnet (access points often drop broadcast). Each request is sent [attempts] times, as msmart does.
 * Only air conditioners (type 0xAC) are reported. [versions] limits which protocol generation this
 * instance reports, so the V2 and V3 adapters share one scan without listing a unit twice.
 */
class MideaDiscovery(
    private val versions: Set<Int> = setOf(2, 3),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ports: IntArray = MideaDiscoveryCodec.PORTS,
    private val queryTimeoutMs: Int = 2000,
    private val scanListenMs: Long = 4000,
    private val attempts: Int = 3,
    private val scans: Boolean = true,
    private val lan: LanAccess = LanAccess.Direct,
    private val log: (String) -> Unit = {}
) : DeviceDiscovery {

    override suspend fun query(host: String): DiscoveredDevice? = withContext(ioDispatcher) {
        try {
            lan.datagramSocket().use { socket ->
                val target = InetAddress.getByName(host)
                val start = System.currentTimeMillis()
                val deadline = start + queryTimeoutMs
                val interval = (queryTimeoutMs / attempts).coerceAtLeast(1)
                var sent = 0
                val buf = ByteArray(1024)
                while (true) {
                    val now = System.currentTimeMillis()
                    if (now >= deadline) return@use null
                    // Repeated within the timeout: one lost datagram must not make a saved AC look offline.
                    if (sent < attempts && now >= start + sent * interval) {
                        for (port in ports) socket.send(DatagramPacket(MideaDiscoveryCodec.REQUEST, MideaDiscoveryCodec.REQUEST.size, InetSocketAddress(target, port)))
                        sent++
                    }
                    val next = if (sent < attempts) start + sent * interval else deadline
                    socket.soTimeout = (minOf(next, deadline) - now).coerceAtLeast(1).toInt()
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    if (packet.address != target) continue
                    val info = MideaDiscoveryCodec.parse(buf.copyOf(packet.length), host) ?: continue
                    return@use if (info.isAirConditioner && info.version in versions) MideaDiscoveryCodec.toDiscovered(info) else null
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } catch (e: Exception) {
            log("midea query to $host failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> {
        if (!scans) return emptyList()
        return withContext(ioDispatcher) {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            val targets = SubnetMath.hosts(network.address, network.prefixLength, limit = 1024)
            lan.datagramSocket().use { socket ->
                socket.broadcast = true
                val broadcasts = BroadcastBursts.targets(network)
                val start = System.currentTimeMillis()
                val bursts = BroadcastBursts(start, count = maxOf(attempts, BroadcastBursts.DEFAULT_COUNT))
                val listenUntil = start + scanListenMs + targets.size * SEND_PACE_MS
                val buf = ByteArray(1024)
                var next = 0
                while (coroutineContext.isActive && System.currentTimeMillis() < listenUntil) {
                    val now = System.currentTimeMillis()
                    if (bursts.due(now)) broadcasts.forEach { send(socket, it) }
                    if (next < targets.size) {
                        send(socket, targets[next++])
                        socket.soTimeout = SEND_PACE_MS.toInt()
                    } else {
                        val wait = minOf(listenUntil - now, bursts.untilNext(now) ?: Long.MAX_VALUE)
                        socket.soTimeout = maxOf(1L, wait).toInt()
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val info = MideaDiscoveryCodec.parse(buf.copyOf(packet.length), packet.address.hostAddress ?: "") ?: continue
                    if (!info.isAirConditioner) {
                        log("midea appliance type 0x${info.deviceType.toString(16)} found; not an AC, ignored")
                        continue
                    }
                    if (info.version !in versions) continue
                    val d = MideaDiscoveryCodec.toDiscovered(info)
                    if (found.putIfAbsent(d.deviceId, d) == null) {
                        log("found Midea V${info.version} AC ${d.deviceId} at ${d.host}")
                        onFound(d)
                    }
                }
            }
            found.values.toList()
        }
    }

    private fun send(socket: DatagramSocket, host: String) {
        for (port in ports) {
            try {
                socket.send(DatagramPacket(MideaDiscoveryCodec.REQUEST, MideaDiscoveryCodec.REQUEST.size, InetSocketAddress(InetAddress.getByName(host), port)))
            } catch (_: Exception) {
                // Unreachable addresses are expected during a sweep.
            }
        }
    }

    companion object {
        private const val SEND_PACE_MS = 4L
    }
}
