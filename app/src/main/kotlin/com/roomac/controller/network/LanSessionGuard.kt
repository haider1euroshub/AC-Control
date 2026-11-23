package com.roomac.controller.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.roomac.controller.protocol.LanAccess
import java.net.DatagramSocket
import java.net.Socket

/**
 * The Android side of [LanAccess], used by every AC discovery, handshake and control session.
 *
 * - **Multicast lock.** With Wi-Fi power saving on, most phones drop incoming broadcast and
 *   multicast frames unless some app holds this lock. Haier modules answer the UDP 7083 search with
 *   a broadcast, so without it the reply is only seen while another app (HaiSmart) holds the lock.
 * - **Wi-Fi lock** (low-latency where available) so the radio does not doze mid-session.
 * - **Socket binding.** Every LAN socket is bound to the Wi-Fi network, so with mobile data on, or a
 *   Wi-Fi that has no internet, the traffic still goes out on the Wi-Fi. Cloud calls are not bound:
 *   they use the default network, which is the one with internet.
 *
 * Locks are not reference-counted by Android; this class counts holders itself and releases both
 * when the last session ends.
 */
class LanSessionGuard(
    context: Context,
    private val network: AndroidNetworkMonitor,
    private val log: (String) -> Unit
) : LanAccess {

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifi?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
    private val wifiLock = wifi?.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        LOCK_TAG
    )?.apply { setReferenceCounted(false) }

    private var holders = 0
    private val lastBindReport = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun <T> hold(reason: String, block: suspend () -> T): T {
        acquire(reason)
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun acquire(reason: String) {
        if (holders++ > 0) return
        try { multicastLock?.acquire() } catch (e: RuntimeException) { log("LAN: multicast lock failed: ${e.javaClass.simpleName}") }
        try { wifiLock?.acquire() } catch (e: RuntimeException) { log("LAN: Wi-Fi lock failed: ${e.javaClass.simpleName}") }
        val net = network.snapshot().local
        log("LAN session start ($reason): wifi network=${network.wifiNetwork() ?: "none"}, local ip=${net?.let { "${it.address}/${it.prefixLength}" } ?: "none"}, " +
            "multicast lock held=${multicastLock?.isHeld == true}, wifi lock held=${wifiLock?.isHeld == true}")
    }

    @Synchronized
    private fun release() {
        if (--holders > 0) return
        holders = 0
        try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: RuntimeException) {}
        try { if (wifiLock?.isHeld == true) wifiLock.release() } catch (_: RuntimeException) {}
        log("LAN session end: locks released")
    }

    override fun bind(socket: DatagramSocket) = bindWith("udp") { it.bindSocket(socket) }

    override fun bind(socket: Socket) = bindWith("tcp") { it.bindSocket(socket) }

    private inline fun bindWith(kind: String, bind: (android.net.Network) -> Unit) {
        val wifiNet = network.wifiNetwork()
        val report = if (wifiNet == null) {
            "LAN: no Wi-Fi network to bind $kind sockets to; using the default route"
        } else try {
            bind(wifiNet)
            "LAN: $kind sockets bound to Wi-Fi network $wifiNet"
        } catch (e: Exception) {
            // Still usable unbound; on a Wi-Fi-only phone the default route is the Wi-Fi anyway.
            "LAN: binding $kind socket to Wi-Fi network $wifiNet failed: ${e.javaClass.simpleName}"
        }
        // One line per change, not one per socket: polling opens a socket every few seconds.
        if (lastBindReport.put(kind, report) != report) log(report)
    }

    private companion object {
        const val LOCK_TAG = "RoomAC:lan"
    }
}
