package com.roomac.controller.ui.netscan

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.devicecontrol.Command
import com.roomac.controller.devicecontrol.Credentials
import com.roomac.controller.devicecontrol.PtzMove
import com.roomac.controller.devicecontrol.RemoteKey
import com.roomac.controller.devicecontrol.Resolution
import com.roomac.controller.netscan.DeviceControlViewModel
import com.roomac.controller.netscan.NetworkDevicesViewModel
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.Section

/** Controls for one scanned device (feature flag DEVICE_CONTROL). Only protocol-confirmed controls are shown. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceControlScreen(ip: String, onBack: () -> Unit) {
    val scan: NetworkDevicesViewModel = viewModel()
    val vm: DeviceControlViewModel = viewModel(key = "control-$ip")
    val scanState by scan.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val device = scanState.devices.firstOrNull { it.ip == ip }
    LaunchedEffect(device?.ip) { device?.let { vm.open(it) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(device?.displayName ?: ip) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (device == null) { Note("This device is no longer in the scan results. Go back and scan again."); return@Column }
            Text("${device.type.label} · ${device.ip}" + (device.vendor?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            if (ui.checking) Row { CircularProgressIndicator(); Text("  Checking what this device supports…") }

            when (val r = ui.resolution) {
                is Resolution.ViewOnly -> Section("View only — no local control") { r.reasons.forEach { Note(it) } }
                is Resolution.NeedsCredentials -> CredentialsForm(r) { vm.open(device, it) }
                is Resolution.Controllable -> {
                    val c = r.connection
                    if (!r.hardwareVerified) Note("${c.protocol} control has not yet been tested on a real device. If something does nothing, that is the reason; please report it.")
                    Section(c.protocol) {
                        c.status?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        val keys = RemoteKey.entries.filter { it in c.capabilities.keys }
                        if (keys.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { k -> OutlinedButton(enabled = !ui.busy, onClick = { vm.perform(Command.Key(k)) }, modifier = Modifier.height(48.dp)) { Text(k.label) } }
                        }
                        if (c.capabilities.volumeLevel) {
                            var level by remember { mutableFloatStateOf(30f) }
                            Text("Volume ${level.toInt()}")
                            Slider(value = level, onValueChange = { level = it }, valueRange = 0f..100f,
                                onValueChangeFinished = { vm.perform(Command.SetVolume(level.toInt())) })
                        }
                        if (c.capabilities.stream) {
                            Button(enabled = !ui.busy, onClick = { vm.perform(Command.StreamUri) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Show live view") }
                            ui.streamUri?.let { uri ->
                                // Android's built-in player handles RTSP (H.264); codecs it lacks will not play.
                                AndroidView(
                                    factory = { ctx -> VideoView(ctx).apply { setVideoURI(Uri.parse(uri)); setOnPreparedListener { it.start() } } },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                )
                            }
                        }
                        if (c.capabilities.ptz) {
                            Text("Pan / tilt / zoom (short nudges)")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PtzMove.entries.forEach { m -> OutlinedButton(enabled = !ui.busy, onClick = { vm.perform(Command.Ptz(m)) }) { Text(m.name.lowercase().replace('_', ' ')) } }
                            }
                        }
                        if (c.capabilities.scan) {
                            Button(enabled = !ui.busy, onClick = { vm.perform(Command.Scan()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text(if (ui.busy) "Scanning…" else "Scan the page on the glass")
                            }
                            ui.image?.let { img ->
                                val bmp = remember(img) { BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size) }
                                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Scanned page", modifier = Modifier.fillMaxWidth())
                                else Note("Received ${img.mimeType} (${img.bytes.size / 1024} KB); preview is available for images only.")
                            }
                        }
                        ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun CredentialsForm(r: Resolution.NeedsCredentials, onSubmit: (Credentials) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Section(r.protocol) {
        Text(r.reason, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Camera username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Camera password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = user.isNotBlank(), onClick = { onSubmit(Credentials(user.trim(), pass)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Connect") }
        Note("Used only for this session to talk to the camera on your Wi-Fi. Not saved.")
    }
}
