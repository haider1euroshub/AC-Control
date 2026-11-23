package com.roomac.controller.protocol

import java.net.DatagramSocket
import java.net.Socket

/**
 * Platform hooks every LAN socket goes through. The drivers stay pure Kotlin; the Android app
 * supplies an implementation that binds sockets to the Wi-Fi network (so traffic never leaves via
 * mobile data) and keeps Wi-Fi from filtering broadcast replies while a session runs.
 */
interface LanAccess {
    /** Called on every new, unconnected LAN datagram socket before it sends anything. */
    fun bind(socket: DatagramSocket) {}

    /** Called on every new, unconnected LAN TCP socket before it connects. */
    fun bind(socket: Socket) {}

    /**
     * Runs [block] while the platform keeps LAN packets flowing (Android: multicast lock + Wi-Fi
     * lock). Nested and concurrent calls are allowed; the platform releases once the last one ends.
     */
    suspend fun <T> hold(reason: String, block: suspend () -> T): T = block()

    /** The plain JVM behaviour: no binding, no locks. Used by tests and PC smoke tests. */
    object Direct : LanAccess
}

/** A DatagramSocket on an ephemeral port, already passed through [LanAccess.bind]. */
fun LanAccess.datagramSocket(): DatagramSocket = DatagramSocket().also { bind(it) }

/** An unconnected TCP socket, already passed through [LanAccess.bind]. */
fun LanAccess.socket(): Socket = Socket().also { bind(it) }

/**
 * When to resend a discovery broadcast. Phones in Doze or with Wi-Fi power saving miss single
 * packets, and so do some modules, so a scan repeats its broadcasts [count] times, [intervalMs] apart.
 */
class BroadcastBursts(
    private val start: Long,
    private val count: Int = DEFAULT_COUNT,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    var sent = 0
        private set

    /** True when the next burst is due at [now]; counts it as sent. */
    fun due(now: Long): Boolean {
        if (sent >= count || now < start + sent * intervalMs) return false
        sent++
        return true
    }

    /** Milliseconds until the next burst, or null when all were sent. */
    fun untilNext(now: Long): Long? = if (sent >= count) null else (start + sent * intervalMs - now).coerceAtLeast(0)

    companion object {
        const val DEFAULT_COUNT = 5
        const val DEFAULT_INTERVAL_MS = 300L

        /** The subnet's directed broadcast (e.g. 192.168.18.255) and the limited broadcast. */
        fun targets(network: LocalNetwork): List<String> =
            listOfNotNull(SubnetMath.broadcastAddress(network.address, network.prefixLength), "255.255.255.255").distinct()
    }
}
