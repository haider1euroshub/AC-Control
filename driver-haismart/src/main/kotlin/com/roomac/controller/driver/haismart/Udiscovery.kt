package com.roomac.controller.driver.haismart

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
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/*
 * UDISCOVERY: Haier's key-free LAN discovery on UDP 7083. Adapted from haismart-local (MIT), see
 * THIRD_PARTY_NOTICES.md. Needs no key, account or cloud, and is read-only.
 *
 * 21-byte header both ways: "Haier" | BE32 command | BE16 flags | 6 zeros | BE32 payload length.
 * Search 0x6915 is answered by device info 0x684D carrying the module MAC (the device ID), the
 * uPlusId, the unit's own IP, its uSS port, firmware strings and a TLV area (type 3 = cloud state).
 */
object UdiscoveryCodec {
    const val PORT = 7083
    private val MAGIC = "Haier".toByteArray(Charsets.US_ASCII)
    private const val CMD_SEARCH = 0x6915L
    private const val CMD_DEVICE_INFO = 0x684DL

    private const val OFF_DEVICE_ID = 0x15
    private const val OFF_UPLUS_ID = 0x25
    private const val OFF_TLV_COUNT = 0x45
    private const val OFF_TLV_AREA = 0x49
    private const val OFF_IP = 0xE5
    private const val OFF_PORT = 0xF5
    private const val OFF_SDK = 0xFD
    private const val OFF_FIRMWARE = 0x105

    fun searchRequest(): ByteArray {
        val payload = ByteArray(56)
        "2.0.0".toByteArray(Charsets.US_ASCII).copyInto(payload, 0x10)
        "UDISCOVERY_SDK".toByteArray(Charsets.US_ASCII).copyInto(payload, 0x18)
        val out = ByteArray(0x15 + payload.size)
        MAGIC.copyInto(out, 0)
        Bytes.putBe32(out, 5, CMD_SEARCH)
        Bytes.putBe32(out, 0x11, payload.size.toLong())
        payload.copyInto(out, 0x15)
        return out
    }

    /** Decodes a device-info reply; null for any other datagram. [sourceHost] is used if the reply omits its IP. */
    fun parse(data: ByteArray, sourceHost: String): DiscoveredDevice? {
        if (data.size < OFF_TLV_AREA) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        if (Bytes.be32(data, 5) != CMD_DEVICE_INFO) return null

        val tlvs = walkTlvs(data)
        val deviceId = text(data, OFF_DEVICE_ID, OFF_UPLUS_ID).ifEmpty { tlvs[1]?.let { text(it, 0, it.size) } ?: "" }
        if (deviceId.isEmpty()) return null
        val cloudState = tlvs[3]?.takeIf { it.size >= 4 }?.let { Bytes.be32(it, 0).toInt() }
        val ip = if (data.size >= OFF_PORT) text(data, OFF_IP, OFF_PORT) else ""
        val port = if (data.size >= OFF_PORT + 2) Bytes.be16(data, OFF_PORT) else 0
        val firmware = if (data.size >= OFF_FIRMWARE + 16) {
            listOf(text(data, OFF_FIRMWARE, OFF_FIRMWARE + 8), text(data, OFF_FIRMWARE + 8, OFF_FIRMWARE + 16))
                .filter { it.isNotEmpty() }.joinToString(" / ")
        } else ""
        return DiscoveredDevice(
            deviceId = deviceId.uppercase(),
            host = ip.ifEmpty { sourceHost },
            port = if (port in 1..65535) port else 56800,
            uPlusId = Bytes.hex(data.copyOfRange(OFF_UPLUS_ID, OFF_TLV_COUNT)),
            firmware = firmware,
            sdkVersion = if (data.size >= OFF_SDK + 5) text(data, OFF_SDK, OFF_SDK + 5) else "",
            cloudState = cloudState,
            protocolId = ProtocolIds.HAIER_USS,
            brand = AcBrands.HAIER.id
        )
    }

    private fun walkTlvs(data: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        val count = Bytes.be32(data, OFF_TLV_COUNT)
        var pos = OFF_TLV_AREA
        for (i in 0 until minOf(count, 32L).toInt()) {
            if (pos + 2 > data.size) break
            val kind = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            if (kind == 0 || pos + 2 + len > data.size) break
            out.putIfAbsent(kind, data.copyOfRange(pos + 2, pos + 2 + len))
            pos += 2 + len
        }
        return out
    }

    private fun text(data: ByteArray, from: Int, to: Int): String {
        val end = minOf(to, data.size)
        var stop = from
        while (stop < end && data[stop] != 0.toByte()) stop++
        return String(data, from, stop - from, Charsets.US_ASCII).trim()
    }
}

/**
 * Finds appliances without an IP address from the user: one broadcast search (from source port 7083,
 * which the modules require for broadcasts) plus a paced unicast sweep of the phone's own subnet,
 * because many access points filter broadcast. At most 1024 addresses are probed.
 */
class UdiscoveryScanner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val udpPort: Int = UdiscoveryCodec.PORT,
    private val queryTimeoutMs: Int = 2000,
    private val scanListenMs: Long = 4000,
    private val lan: LanAccess = LanAccess.Direct,
    private val log: (String) -> Unit = {}
) : DeviceDiscovery {

    override suspend fun query(host: String): DiscoveredDevice? = withContext(ioDispatcher) {
        try {
            lan.datagramSocket().use { socket ->
                val target = InetAddress.getByName(host)
                val req = UdiscoveryCodec.searchRequest()
                // UDP may drop one request (seen on the target AC right after a subnet sweep), so the request
                // is repeated within the same overall timeout. Repeating a search is harmless.
                val start = System.currentTimeMillis()
                val deadline = start + queryTimeoutMs
                val interval = (queryTimeoutMs / QUERY_ATTEMPTS).coerceAtLeast(1)
                var sent = 0
                val buf = ByteArray(2048)
                var result: DiscoveredDevice? = null
                while (result == null) {
                    val now = System.currentTimeMillis()
                    if (now >= deadline) break
                    if (sent < QUERY_ATTEMPTS && now >= start + sent * interval) {
                        socket.send(DatagramPacket(req, req.size, InetSocketAddress(target, udpPort)))
                        sent++
                    }
                    val nextSend = if (sent < QUERY_ATTEMPTS) start + sent * interval else deadline
                    socket.soTimeout = (minOf(nextSend, deadline) - now).coerceAtLeast(1).toInt()
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    // Only the unit we asked: another appliance's reply would be the wrong identity.
                    if (packet.address != target) continue
                    result = UdiscoveryCodec.parse(buf.copyOf(packet.length), packet.address.hostAddress ?: host)
                }
                if (result == null) log("discovery query to $host: no reply in $queryTimeoutMs ms ($sent requests)")
                result
            }
        } catch (e: Exception) {
            log("discovery query to $host failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> =
        withContext(ioDispatcher) {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            val targets = SubnetMath.hosts(network.address, network.prefixLength, limit = 1024)
            val socket = openScanSocket()
            try {
                socket.broadcast = true
                val req = UdiscoveryCodec.searchRequest()
                val broadcasts = BroadcastBursts.targets(network)
                val start = System.currentTimeMillis()
                val bursts = BroadcastBursts(start)
                log("haier scan from ${network.address}/${network.prefixLength} (local port ${socket.localPort}): " +
                    "broadcast to ${broadcasts.joinToString()} x${BroadcastBursts.DEFAULT_COUNT}, sweep of ${targets.size} hosts")
                val listenUntil = start + scanListenMs + targets.size * SEND_PACE_MS
                val buf = ByteArray(2048)
                var next = 0
                while (coroutineContext.isActive && System.currentTimeMillis() < listenUntil) {
                    val now = System.currentTimeMillis()
                    if (bursts.due(now)) broadcasts.forEach { trySend(socket, req, it) }
                    // Interleave paced unicast sends with receiving so replies are never dropped.
                    if (next < targets.size) {
                        trySend(socket, req, targets[next++])
                        socket.soTimeout = SEND_PACE_MS.toInt()
                    } else {
                        val wait = minOf(listenUntil - now, bursts.untilNext(now) ?: Long.MAX_VALUE)
                        socket.soTimeout = maxOf(1L, wait).toInt()
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val info = UdiscoveryCodec.parse(buf.copyOf(packet.length), packet.address.hostAddress ?: "") ?: continue
                    if (found.putIfAbsent(info.deviceId, info) == null) {
                        log("found Haier appliance ${info.deviceId} at ${info.host} after ${System.currentTimeMillis() - start} ms")
                        onFound(info)
                    }
                }
                if (found.isEmpty()) log("haier scan: no reply in ${System.currentTimeMillis() - start} ms")
            } finally {
                socket.close()
            }
            found.values.toList()
        }

    private fun openScanSocket(): DatagramSocket = try {
        // Broadcasts are answered only when sent from port 7083.
        DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(udpPort)); lan.bind(this) }
    } catch (_: SocketException) {
        log("UDP port $udpPort busy; scanning with unicast only")
        lan.datagramSocket()
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
        private const val QUERY_ATTEMPTS = 3
    }
}
