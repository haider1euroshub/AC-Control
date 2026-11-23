package com.roomac.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.NetworkMonitor
import com.roomac.controller.protocol.NetworkSnapshot
import java.net.Inet4Address

/**
 * Reports whether Wi-Fi is on, whether the phone is joined to a Wi-Fi network, and the phone's IPv4
 * address and prefix on it (which decides the subnet discovery searches). Needs only the normal
 * ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE permissions; no location or nearby-devices access.
 *
 * The Wi-Fi network is tracked from its own callback rather than the default network, because a
 * phone on a Wi-Fi without internet often makes mobile data the default while the LAN is still there.
 */
class AndroidNetworkMonitor(context: Context, private val onChange: () -> Unit) : NetworkMonitor {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var wifiLink: LinkProperties? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            wifiLink = cm.getLinkProperties(network)
            onChange()
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) { wifiNetwork = null; wifiLink = null }
            onChange()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == wifiNetwork) wifiLink = linkProperties
            onChange()
        }
    }

    fun register() {
        // No internet requirement: a Wi-Fi without internet (or not yet validated) still reaches the AC.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.registerNetworkCallback(request, callback) } catch (_: SecurityException) {}
    }

    /** The Wi-Fi network LAN sockets are bound to, even when mobile data is the default network. */
    fun wifiNetwork(): Network? = wifiNetwork ?: cm.activeNetwork?.takeIf {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    override fun snapshot(): NetworkSnapshot {
        val enabled = try { wifi?.isWifiEnabled ?: false } catch (_: SecurityException) { true }
        val network = wifiNetwork()
        val link = wifiLink ?: network?.let { cm.getLinkProperties(it) }
        val local = link?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.let { LocalNetwork(it.address.hostAddress ?: "", it.prefixLength) }
        return NetworkSnapshot(wifiEnabled = enabled || network != null, onWifi = network != null && local != null, local = local)
    }

    /** The Wi-Fi network's default-route gateway (normally the router), or null if it has none. */
    fun wifiGateway(): String? {
        val link = wifiLink ?: wifiNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /** True when a VPN is the active network, which can hide the LAN from the app. */
    fun vpnActive(): Boolean =
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
}
