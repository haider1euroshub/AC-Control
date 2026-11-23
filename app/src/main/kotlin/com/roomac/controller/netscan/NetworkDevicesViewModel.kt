package com.roomac.controller.netscan

import android.app.Application
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "Network devices" scanner (feature flag NETWORK_SCANNER). Each scan runs mDNS, SSDP and the
 * subnet sweep together while holding a Wi-Fi multicast lock, released as soon as the scan ends.
 */
class NetworkDevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RoomAcApplication
    private val log: (String) -> Unit = { DiagnosticsLogger.i("NetScan", it) }
    private val wifi = application.applicationContext.getSystemService(WifiManager::class.java)

    private val scanner = NetworkScanner(
        sources = listOf(
            MdnsDiscovery(NsdMdnsBrowser(application, log)),
            SsdpDiscovery(UdpSsdpTransport(), SocketDescriptionFetcher()),
            OnvifDiscovery(UdpMulticastTransport(SsdpParser.MULTICAST_ADDRESS, OnvifDiscovery.PORT)),
            SubnetSweep(TcpPortProber(), InetPinger(), ProcArpSource(), ReverseDnsResolver())
        ),
        log = log
    )

    val state: StateFlow<ScanState> = scanner.state

    private val _notOnWifi = MutableStateFlow(false)
    val notOnWifi: StateFlow<Boolean> = _notOnWifi.asStateFlow()

    private var job: Job? = null

    /** Starts a scan, or returns the one already running; null when the phone is not on Wi-Fi. */
    fun scan(): Job? {
        job?.takeIf { it.isActive }?.let { return it }
        val local = app.network.snapshot().local
        _notOnWifi.value = local == null
        if (local == null) return null
        val gateway = app.network.wifiGateway()
        return viewModelScope.launch {
            val lock = try {
                wifi?.createMulticastLock("RoomAC network scan")?.apply { setReferenceCounted(false); acquire() }
            } catch (e: SecurityException) {
                log("Multicast lock unavailable: ${e.message}")
                null
            }
            try {
                scanner.scan(local, gateway)
            } finally {
                try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) {}
            }
        }.also { job = it }
    }
}
