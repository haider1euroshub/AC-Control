package com.roomac.controller.permissions

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath

/** One row of the "Ready to scan" checklist. */
enum class CheckId { WIFI, NEARBY, BLUETOOTH, SAME_NETWORK }

enum class CheckStatus {
    OK,
    /** Permission not asked yet, or asked and refused once: asking again shows the system dialog. */
    NEEDS_PERMISSION,
    /** Refused with "Don't ask again" (or twice): only the app's settings page can grant it now. */
    DENIED_PERMANENTLY,
    /** A switch is off (Wi-Fi, Location services, Bluetooth). */
    OFF,
    /** Wi-Fi is on but not joined to a network. */
    NOT_CONNECTED,
    /** The phone's subnet does not contain the AC's last known address. */
    DIFFERENT_NETWORK,
    /** Nothing to check yet (for example no AC saved). */
    UNKNOWN
}

data class CheckItem(val id: CheckId, val status: CheckStatus, val required: Boolean) {
    val ok: Boolean get() = status == CheckStatus.OK || status == CheckStatus.UNKNOWN
}

/** Everything the checklist needs, read from the phone (see [PreflightReader]). */
data class PreflightInputs(
    val sdkInt: Int,
    val wifiEnabled: Boolean,
    val onWifi: Boolean,
    val local: LocalNetwork?,
    /** NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (API 32 and lower). */
    val nearby: PermissionState,
    /** Location services switch; only matters on API 32 and lower. */
    val locationServicesOn: Boolean,
    val bluetoothSupported: Boolean,
    val bluetoothOn: Boolean,
    /** Last known address of a saved AC, if any, to check the phone is on the same subnet. */
    val acHost: String?
)

/** A runtime permission's state, as far as the app can tell. */
enum class PermissionState { GRANTED, ASK, DENIED_PERMANENTLY }

data class PreflightReport(val items: List<CheckItem>) {
    /** Every required row is green: the scan can start. */
    val ready: Boolean get() = items.filter { it.required }.all { it.ok }
    operator fun get(id: CheckId): CheckItem = items.first { it.id == id }
}

object Preflight {
    private const val API_TIRAMISU = 33
    private const val API_S = 31

    /** The runtime permissions to request on this Android version for LAN discovery. */
    fun nearbyPermissions(sdkInt: Int): List<String> = when {
        sdkInt >= API_TIRAMISU -> listOf("android.permission.NEARBY_WIFI_DEVICES")
        // Android 12 refuses a fine-location request that does not also ask for coarse.
        sdkInt >= API_S -> listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        else -> listOf("android.permission.ACCESS_FINE_LOCATION")
    }

    /** The permission whose grant decides the "Nearby devices" row. */
    fun nearbyKeyPermission(sdkInt: Int): String = nearbyPermissions(sdkInt).first()

    /** Permission needed to ask the user to switch Bluetooth on (API 31+); none below. */
    fun bluetoothConnectPermission(sdkInt: Int): String? =
        if (sdkInt >= API_S) "android.permission.BLUETOOTH_CONNECT" else null

    /**
     * Android cannot say directly whether a refused permission was refused permanently. After at
     * least one request, "no rationale" means the system will not show the dialog again.
     */
    fun permissionState(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): PermissionState = when {
        granted -> PermissionState.GRANTED
        askedBefore && !showRationale -> PermissionState.DENIED_PERMANENTLY
        else -> PermissionState.ASK
    }

    /** True when [host] is an address inside the phone's own subnet. */
    fun sameSubnet(local: LocalNetwork, host: String): Boolean {
        val a = SubnetMath.toInt(local.address) ?: return false
        val b = SubnetMath.toInt(host) ?: return false
        val prefix = local.prefixLength.coerceIn(0, 32)
        if (prefix == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    /**
     * Whether a checklist result should start a search by itself: the first time it is ready, and again
     * whenever it becomes ready after something required went missing (Wi-Fi off and back on).
     */
    fun shouldAutoScan(previouslyReady: Boolean?, ready: Boolean, scanning: Boolean, onFindStep: Boolean): Boolean =
        ready && previouslyReady != true && !scanning && onFindStep

    fun evaluate(i: PreflightInputs): PreflightReport {
        val wifi = when {
            !i.wifiEnabled -> CheckStatus.OFF
            !i.onWifi || i.local == null -> CheckStatus.NOT_CONNECTED
            else -> CheckStatus.OK
        }
        val nearby = when (i.nearby) {
            PermissionState.DENIED_PERMANENTLY -> CheckStatus.DENIED_PERMANENTLY
            PermissionState.ASK -> CheckStatus.NEEDS_PERMISSION
            // Below Android 13 the permission is location, which is useless while Location is off.
            PermissionState.GRANTED -> if (i.sdkInt < API_TIRAMISU && !i.locationServicesOn) CheckStatus.OFF else CheckStatus.OK
        }
        // Only setting up a brand-new AC over Bluetooth needs it; an AC on Wi-Fi is found over the LAN.
        val bluetooth = when {
            !i.bluetoothSupported -> CheckStatus.UNKNOWN
            i.bluetoothOn -> CheckStatus.OK
            else -> CheckStatus.OFF
        }
        val same = when {
            i.acHost.isNullOrEmpty() || i.local == null -> CheckStatus.UNKNOWN
            sameSubnet(i.local, i.acHost) -> CheckStatus.OK
            else -> CheckStatus.DIFFERENT_NETWORK
        }
        return PreflightReport(
            listOf(
                CheckItem(CheckId.WIFI, wifi, required = true),
                CheckItem(CheckId.NEARBY, nearby, required = true),
                CheckItem(CheckId.BLUETOOTH, bluetooth, required = false),
                // A saved AC on another subnet may simply have moved networks: warn, do not block the search.
                CheckItem(CheckId.SAME_NETWORK, same, required = false)
            )
        )
    }
}
