package com.roomac.controller.permissions

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.roomac.controller.RoomAcApplication

/** Reads the phone's current state for [Preflight.evaluate], and opens the screens that fix each row. */
class PreflightReader(private val context: Context) {

    private val prefs = context.getSharedPreferences("preflight", Context.MODE_PRIVATE)
    private val sdk = Build.VERSION.SDK_INT

    fun read(activity: Activity?, acHost: String?): PreflightReport {
        val net = RoomAcApplication.from(context.applicationContext as android.app.Application).network.snapshot()
        return Preflight.evaluate(
            PreflightInputs(
                sdkInt = sdk,
                wifiEnabled = net.wifiEnabled,
                onWifi = net.onWifi,
                local = net.local,
                nearby = permission(activity, Preflight.nearbyKeyPermission(sdk)),
                locationServicesOn = locationOn(),
                bluetoothSupported = bluetoothAdapter() != null,
                bluetoothOn = bluetoothOn(),
                acHost = acHost
            )
        )
    }

    fun permission(activity: Activity?, name: String): PermissionState = Preflight.permissionState(
        granted = granted(name),
        askedBefore = prefs.getBoolean("asked:$name", false),
        showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, name) } ?: false
    )

    fun granted(name: String): Boolean = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    /** Remembers that the system dialog was shown, so a later refusal can be told apart from "never asked". */
    fun markAsked(names: Collection<String>) {
        prefs.edit().apply { names.forEach { putBoolean("asked:$it", true) } }.apply()
    }

    fun powerSaveOn(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private fun locationOn(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    private fun bluetoothAdapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun bluetoothOn(): Boolean = try { bluetoothAdapter()?.isEnabled == true } catch (_: SecurityException) { false }

    companion object {
        /** Android 10+ does not let apps switch Wi-Fi on, so open the Wi-Fi panel (or settings below 10). */
        fun wifiIntent(): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)

        fun locationIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

        fun appSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

        /** The list of apps' battery optimisation; needs no extra permission. */
        fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        fun start(context: Context, intent: Intent) {
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // Some OEM builds lack a panel: fall back to the main settings screen.
                try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: ActivityNotFoundException) {}
            }
        }
    }
}
