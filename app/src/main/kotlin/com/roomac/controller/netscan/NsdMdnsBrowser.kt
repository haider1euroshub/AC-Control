package com.roomac.controller.netscan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * [MdnsBrowser] on Android's NsdManager. Browses every type at once, then resolves found instances one
 * at a time: before Android 14 NsdManager allows only one resolve in flight.
 */
class NsdMdnsBrowser(context: Context, private val log: (String) -> Unit = {}) : MdnsBrowser {

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun browse(serviceTypes: List<String>, durationMs: Long, onResolved: (MdnsService) -> Unit) = coroutineScope {
        val found = Channel<Pair<String, NsdServiceInfo>>(Channel.UNLIMITED)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val listeners = serviceTypes.map { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (seen.add("$type/${info.serviceName}")) found.trySend(type to info)
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { log("mDNS browse $serviceType failed: $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
            listener
        }
        val resolver = launch {
            for ((type, info) in found) resolve(info)?.let { onResolved(it.toMdnsService(type)) }
        }
        try {
            delay(durationMs)
        } finally {
            listeners.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
            found.close()
        }
        // Give queued resolves a little longer, then give up on the rest.
        if (withTimeoutOrNull(RESOLVE_DRAIN_MS) { resolver.join() } == null) resolver.cancel()
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        repeat(3) {
            val result = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Pair<NsdServiceInfo?, Int>> { cont ->
                    try {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) { if (cont.isActive) cont.resume(resolved to 0) }
                            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null to errorCode) }
                        })
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null to -1)
                    }
                }
            } ?: return null
            if (result.first != null) return result.first
            if (result.second != NsdManager.FAILURE_ALREADY_ACTIVE) return null
            delay(250) // a previous (timed-out) resolve is still running
        }
        return null
    }

    private fun NsdServiceInfo.toMdnsService(type: String): MdnsService {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) hostAddresses else @Suppress("DEPRECATION") listOfNotNull(host)
        return MdnsService(
            instanceName = serviceName.orEmpty(),
            serviceType = type,
            host = addresses.firstOrNull { it is Inet4Address }?.hostAddress,
            port = port,
            txt = attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        )
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3_000L
        const val RESOLVE_DRAIN_MS = 8_000L
    }
}
