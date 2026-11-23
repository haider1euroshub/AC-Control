package com.roomac.controller

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.driver.gree.GreeAdapter
import com.roomac.controller.driver.midea.MideaAccount
import com.roomac.controller.driver.midea.MideaAppIdentity
import com.roomac.controller.driver.midea.MideaClient
import com.roomac.controller.driver.midea.MideaDiscovery
import com.roomac.controller.driver.midea.MideaV2Adapter
import com.roomac.controller.driver.midea.MideaV3Adapter
import com.roomac.controller.driver.haismart.HaierUssAdapter
import com.roomac.controller.driver.haismart.HaierUssClient
import com.roomac.controller.driver.haismart.UdiscoveryScanner
import com.roomac.controller.driver.haismart.cloud.HaierAccount
import com.roomac.controller.driver.haismart.cloud.HaierAppIdentity
import com.roomac.controller.driver.gree.GreeDiscovery
import com.roomac.controller.driver.gree.UdpGreeTransport
import com.roomac.controller.network.AndroidNetworkMonitor
import com.roomac.controller.network.LanSessionGuard
import com.roomac.controller.protocol.socket
import com.roomac.controller.protocol.AcConnectionCoordinator
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.NetworkDiscoveryManager
import com.roomac.controller.protocol.ProtocolRegistry
import com.roomac.controller.storage.EncryptedAccountSessions
import com.roomac.controller.storage.PairedDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the layers together: storage, network state, the brand/protocol registry, multi-brand LAN
 * discovery, manufacturer accounts and the connection coordinator. Control is local; the only cloud
 * use is a manufacturer account fetching an AC's key when it is first connected or its key changes.
 */
class RoomAcApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var store: PairedDeviceStore
        private set
    lateinit var network: AndroidNetworkMonitor
        private set
    lateinit var registry: ProtocolRegistry
        private set
    /** Manufacturer accounts that issue AC keys, by provider id. */
    lateinit var accounts: Map<String, CloudAccountProvider>
        private set
    lateinit var discovery: NetworkDiscoveryManager
        private set
    lateinit var coordinator: AcConnectionCoordinator
        private set

    /**
     * Debug builds only: an address the Add AC search also asks directly, set with
     * `adb shell am start ... --es roomac.debug.probeHost <IPv4>`. Lets an emulator behind NAT (10.0.2.x),
     * which can never see the home subnet's broadcasts, still reach a real AC for testing.
     */
    @Volatile var debugProbeHost: String? = null

    override fun onCreate() {
        super.onCreate()
        val log: (String) -> Unit = { DiagnosticsLogger.i("AC", it) }
        DiagnosticsLogger.i("App", "Starting")

        store = PairedDeviceStore.create(this)
        val sessions = EncryptedAccountSessions.create(this)
        val haierAccount = HaierAccount(HaierAppIdentity(BuildConfig.HAIER_APP_ID, BuildConfig.HAIER_APP_KEY), sessions, log = log, signupEnabled = BuildConfig.HAIER_IN_APP_SIGNUP)
        val mideaAccount = MideaAccount(MideaAppIdentity(appKey = BuildConfig.MIDEA_APP_KEY), sessions, log = log)
        accounts = listOf<CloudAccountProvider>(haierAccount, mideaAccount).associateBy { it.id }
        network = AndroidNetworkMonitor(this) { if (::coordinator.isInitialized) coordinator.wakeUp() }
        // Every LAN socket is bound to the Wi-Fi and every session holds the multicast and Wi-Fi locks.
        val lan = LanSessionGuard(this, network) { DiagnosticsLogger.i("RoomAC", it) }
        // Midea: one scan reports both generations; the V3 adapter only answers direct queries.
        val mideaV3Discovery = MideaDiscovery(versions = setOf(3), scans = false, lan = lan, log = log)
        val greeTransport = UdpGreeTransport(lan = lan)
        // One adapter per manufacturer protocol. A new brand integration is registered here.
        registry = ProtocolRegistry(
            listOf(
                HaierUssAdapter(UdiscoveryScanner(lan = lan, log = log), HaierUssClient(socketFactory = { lan.socket() }, log = log), haierAccount),
                GreeAdapter(greeTransport, log = log, discovery = GreeDiscovery(greeTransport, lan = lan, log = log)),
                MideaDiscovery(lan = lan, log = log).let { d -> MideaV2Adapter(d, MideaClient(d, lan = lan, log = log)) },
                MideaV3Adapter(mideaV3Discovery, MideaClient(mideaV3Discovery, lan = lan, log = log), mideaAccount)
            )
        )
        discovery = NetworkDiscoveryManager(registry, log, lan)
        val coord = AcConnectionCoordinator(appScope, store, network, registry, log = log, lan = lan)
        coordinator = coord
        coord.pollIntervalMs = store.pollIntervalMs.value
        appScope.launch { store.pollIntervalMs.collect { coord.pollIntervalMs = it } }
        network.register()

        // Poll only while the app is visible: no background traffic, and the AC's single local
        // session stays free for other controllers when the app is not in use.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = coord.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = coord.setForeground(false)
        })
        coord.start()
    }

    /** The account that issues keys for [protocolId], if that protocol uses one. */
    fun accountFor(protocolId: String): CloudAccountProvider? =
        registry.forId(protocolId)?.descriptor?.accountProviderId?.let { accounts[it] }

    companion object {
        fun from(app: Application): RoomAcApplication = app as RoomAcApplication
    }
}
