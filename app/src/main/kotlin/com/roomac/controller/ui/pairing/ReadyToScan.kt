package com.roomac.controller.ui.pairing

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.permissions.CheckId
import com.roomac.controller.permissions.CheckItem
import com.roomac.controller.permissions.CheckStatus
import com.roomac.controller.permissions.Preflight
import com.roomac.controller.permissions.PreflightReader
import com.roomac.controller.permissions.PreflightReport
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.Section
import com.roomac.controller.ui.components.Tone
import com.roomac.controller.ui.components.toneDot
import kotlinx.coroutines.delay

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * The "Ready to scan" checklist shown before every search. Re-checks when the user comes back from
 * Settings (and every couple of seconds while something required is missing), and reports each
 * result to [onReport] so the search starts by itself once every required row is green.
 */
@Composable
fun ReadyToScanCard(acHost: String?, onReport: (PreflightReport) -> Unit, onScanAnyway: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val reader = remember(context) { PreflightReader(context.applicationContext) }
    var report by remember { mutableStateOf(reader.read(activity, acHost)) }
    val refresh = {
        val next = reader.read(activity, acHost)
        if (next != report) DiagnosticsLogger.i("RoomAC", "preflight: " + next.items.joinToString { "${it.id}=${it.status}" })
        report = next
        onReport(next)
    }

    val sdk = android.os.Build.VERSION.SDK_INT
    val nearbyPerms = remember { Preflight.nearbyPermissions(sdk) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reader.markAsked(nearbyPerms)
        refresh()
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Preflight.bluetoothConnectPermission(sdk)?.let { reader.markAsked(listOf(it)) }
        if (granted) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        refresh()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    // Wi-Fi or Location can change without leaving the screen (quick settings, `svc wifi disable`), in
    // either direction, so keep re-checking while the checklist is visible. Each check is a few cheap getters.
    LaunchedEffect(Unit) {
        DiagnosticsLogger.i("RoomAC", "preflight: " + report.items.joinToString { "${it.id}=${it.status}" })
        while (true) { delay(2000); refresh() }
    }

    Section(if (report.ready) "Ready to search" else "Before searching") {
        CheckRow(report[CheckId.WIFI]) {
            when (it.status) {
                CheckStatus.OFF -> ActionButton("Turn on Wi-Fi") { PreflightReader.start(context, PreflightReader.wifiIntent()) }
                CheckStatus.NOT_CONNECTED -> ActionButton("Choose Wi-Fi network") { PreflightReader.start(context, PreflightReader.wifiIntent()) }
                else -> {}
            }
        }
        CheckRow(report[CheckId.NEARBY]) {
            when (it.status) {
                CheckStatus.NEEDS_PERMISSION -> ActionButton("Allow") { permissionLauncher.launch(nearbyPerms.toTypedArray()) }
                CheckStatus.DENIED_PERMANENTLY -> ActionButton("Open app settings") { PreflightReader.start(context, PreflightReader.appSettingsIntent(context)) }
                CheckStatus.OFF -> ActionButton("Turn on Location") { PreflightReader.start(context, PreflightReader.locationIntent()) }
                else -> {}
            }
        }
        CheckRow(report[CheckId.SAME_NETWORK]) {}
        CheckRow(report[CheckId.BLUETOOTH]) {
            if (it.status == CheckStatus.OFF) TextButton(onClick = {
                val perm = Preflight.bluetoothConnectPermission(sdk)
                if (perm == null || reader.granted(perm)) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else if (reader.permission(activity, perm) == com.roomac.controller.permissions.PermissionState.DENIED_PERMANENTLY)
                    PreflightReader.start(context, PreflightReader.appSettingsIntent(context))
                else bluetoothPermission.launch(perm)
            }) { Text("Turn on Bluetooth") }
        }
        if (!report.ready) {
            if (report[CheckId.WIFI].ok) {
                // LAN discovery itself works without the permission on most phones; never trap the user.
                TextButton(onClick = onScanAnyway, modifier = Modifier.fillMaxWidth()) { Text("Search anyway") }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(shape = MaterialTheme.shapes.medium, onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(label) }
}

@Composable
private fun CheckRow(item: CheckItem, action: @Composable (CheckItem) -> Unit) {
    val (title, detail) = describe(item)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            val (icon, tone) = when {
                item.status == CheckStatus.OK -> Icons.Filled.CheckCircle to Tone.GOOD
                item.ok -> Icons.Outlined.RadioButtonUnchecked to Tone.NEUTRAL
                item.required -> Icons.Outlined.ErrorOutline to Tone.BAD
                else -> Icons.Outlined.ErrorOutline to Tone.WARN
            }
            Icon(icon, contentDescription = if (item.ok) "Done" else "Needs attention", tint = toneDot(tone), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action(item)
    }
}

private fun describe(item: CheckItem): Pair<String, String> = when (item.id) {
    CheckId.WIFI -> "Wi-Fi" to when (item.status) {
        CheckStatus.OK -> ""
        CheckStatus.OFF -> "Wi-Fi is off. Turn it on and join your home Wi-Fi, the same one the AC uses."
        else -> "Wi-Fi is on but not joined to a network. Join your home Wi-Fi, the same one the AC uses."
    }
    CheckId.NEARBY -> "Nearby devices" to when (item.status) {
        CheckStatus.OK -> ""
        CheckStatus.NEEDS_PERMISSION -> "Lets RoomAC see your Wi-Fi network and hear replies from ACs on it. RoomAC does not use your location."
        CheckStatus.DENIED_PERMANENTLY -> "This permission was turned off. Open app settings → Permissions and allow it."
        CheckStatus.OFF -> "Location is off. Android needs it switched on to show the Wi-Fi network to apps. RoomAC does not use your location."
        else -> ""
    }
    CheckId.BLUETOOTH -> "Bluetooth (optional)" to when (item.status) {
        CheckStatus.OK -> "Not needed for ACs already on your Wi-Fi."
        CheckStatus.OFF -> "Not needed for ACs already on your Wi-Fi. Only setting up a brand-new AC uses it."
        else -> "This phone has no Bluetooth. Not needed for ACs already on your Wi-Fi."
    }
    CheckId.SAME_NETWORK -> "Same network as the AC" to when (item.status) {
        CheckStatus.OK -> ""
        CheckStatus.DIFFERENT_NETWORK -> "This phone is on a different network from the one your AC was last seen on. Join the same home Wi-Fi (not a guest network)."
        else -> "Checked when an AC answers."
    }
}

/** Shown under "No air conditioner found" when Battery Saver may be holding Wi-Fi replies back. */
@Composable
fun BatterySaverHint() {
    val context = LocalContext.current
    val reader = remember(context) { PreflightReader(context.applicationContext) }
    if (!reader.powerSaveOn()) return
    Note("Battery Saver is on. It can make the phone miss replies from the AC. Turn Battery Saver off for a moment, or let RoomAC run without battery restrictions, then search again.")
    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { PreflightReader.start(context, PreflightReader.batterySettingsIntent()) },
        modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Battery settings") }
}
