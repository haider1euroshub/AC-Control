package com.roomac.controller.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.ConnectionPhase
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.ui.components.InfoRow
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.Section
import com.roomac.controller.ui.components.StatusLabel
import com.roomac.controller.ui.components.Tone
import com.roomac.controller.ui.components.tone
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.launch

/** Rooms offered as one-tap choices; any other name can be typed. */
val SUGGESTED_ROOMS = listOf("Bedroom", "Living room", "Drawing room", "Office", "Kids room", "Guest room")

fun PairedDevice.brandName(): String =
    AcBrands.displayNameFor(brand) ?: AcBrands.forProtocol(protocolId).firstOrNull()?.displayName ?: "Air conditioner"

/** "Bedroom · Haier" style subtitle for lists and the switcher. */
fun PairedDevice.subtitle(): String = listOf(room, brandName(), modelNumber).filter { it.isNotBlank() }.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as RoomAcApplication
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val active by app.store.activeDeviceId.collectAsStateWithLifecycle()
    val conn by app.coordinator.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My air conditioners") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Add AC") })
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (devices.isEmpty()) Note("No air conditioners yet. Tap \"Add AC\" and the app will search your Wi-Fi.")
            devices.forEach { d ->
                val isActive = d.deviceId == active
                Card(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Open settings") { onOpen(d.deviceId) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AcUnit, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text(d.subtitle(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            when {
                                isActive -> StatusLabel(conn.phase.label, conn.phase.tone())
                                !d.hasLocalKey -> StatusLabel("Not connected yet", Tone.WARN)
                                else -> StatusLabel("Ready · tap \"Use\" to control", Tone.NEUTRAL)
                            }
                        }
                        if (isActive) Icon(Icons.Filled.CheckCircle, contentDescription = "In use", tint = MaterialTheme.colorScheme.primary)
                        else Button(shape = MaterialTheme.shapes.medium, onClick = { app.store.setActive(d.deviceId); app.coordinator.reconnect() }, modifier = Modifier.height(48.dp)) { Text("Use") }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceSettingsScreen(deviceId: String, onBack: () -> Unit, onSignIn: (providerId: String) -> Unit) {
    val app = LocalContext.current.applicationContext as RoomAcApplication
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val conn by app.coordinator.state.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.deviceId == deviceId }
    val scope = rememberCoroutineScope()

    var name by remember(device?.deviceId) { mutableStateOf(device?.name ?: "") }
    var room by remember(device?.deviceId) { mutableStateOf(device?.room ?: "") }
    var confirmRemove by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(device?.name ?: "Air conditioner") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        if (device == null) {
            androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
            return@Scaffold
        }
        val isActive = conn.device?.deviceId == device.deviceId
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Name and room") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth()
                )
                Text("Room", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTED_ROOMS.forEach { r ->
                        FilterChip(selected = room == r, onClick = { room = if (room == r) "" else r }, label = { Text(r, style = MaterialTheme.typography.bodyLarge) })
                    }
                }
                OutlinedTextField(
                    value = room, onValueChange = { room = it }, singleLine = true, label = { Text("Or type a room") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth()
                )
                Button(shape = MaterialTheme.shapes.medium, 
                    onClick = { app.store.upsert(device.copy(name = name.trim().ifEmpty { device.name }, room = room.trim())) },
                    enabled = name.isNotBlank() && (name.trim() != device.name || room.trim() != device.room),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Save") }
            }

            val adapter = app.registry.forId(device.protocolId)
            Section("Connection") {
                InfoRow("Status", if (isActive) conn.phase.label else if (device.hasLocalKey) "Ready" else "Not connected yet")
                adapter?.descriptor?.let { d ->
                    InfoRow("Support status", d.evidence.label)
                    d.limitations.forEach { Note(it) }
                }
                when (adapter?.descriptor?.credentialMode) {
                    CredentialMode.ACCOUNT -> {
                        val provider = app.accountFor(device.protocolId)
                        val linked = provider?.accountFor(device.deviceId)
                        when {
                            provider == null -> {}
                            linked != null && !linked.needsSignIn -> Text(
                                "Connected through your ${provider.brandName} account (${linked.label}). The key is kept encrypted on this phone, so everyday control works without internet, " +
                                    "and a new key is fetched by itself if ${provider.brandName} changes it.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            else -> {
                                Text(
                                    when {
                                        linked != null -> "${provider.brandName} no longer accepts the saved sign-in for ${linked.label}. The AC keeps working on your Wi-Fi, but sign in again so its key can be renewed when it changes."
                                        device.hasLocalKey -> "This AC works on your Wi-Fi. Sign in to your ${provider.brandName} account so its key can be renewed by itself when it changes."
                                        else -> "Sign in to your ${provider.brandName} account once to connect this AC."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { onSignIn(provider.id + (linked?.let { "/${it.id}" } ?: "")) }, modifier = Modifier.height(48.dp)) {
                                    Text("Sign in to ${provider.brandName}")
                                }
                            }
                        }
                    }
                    CredentialMode.AUTOMATIC -> Text(
                        if (device.hasLocalKey) "Paired directly with the AC over your Wi-Fi. No account is needed."
                        else "The app pairs with this AC by itself when it finds it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {}
                }
                OutlinedButton(shape = MaterialTheme.shapes.medium, 
                    onClick = {
                        scope.launch {
                            // Forget the key and connect afresh: a new key is fetched from the AC or the account.
                            app.store.setLocalKey(device.deviceId, null)
                            app.store.setActive(device.deviceId)
                            app.coordinator.reconnect()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Reconnect this AC") }
                Note("Use this if the AC was reset or set up again in its own app.")
            }

            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide technical details" else "Show technical details (for support)") }
            if (showDetails) Section("Technical details") {
                InfoRow("Brand", device.brandName())
                InfoRow("Model", device.modelNumber)
                InfoRow("Device ID", device.deviceId)
                InfoRow("Last address", device.lastKnownHost)
                InfoRow("Module firmware", device.firmware)
                if (device.protocolId == ProtocolIds.HAIER_USS) InfoRow("Profile", ModelProfiles.forUplusId(device.uPlusId)?.name ?: "Unrecognised")
                InfoRow("Connection type", adapter?.descriptor?.displayName ?: "Not supported in this version")
                if (isActive) conn.status?.let { InfoRow("Status report", "${it.reportLength} bytes, ${if (it.writable) "controllable" else "read-only"}") }
                InfoRow("Controls confirmed by this AC", if (device.verifiedControls.isEmpty()) "none yet" else device.verifiedControls.sorted().joinToString(", "))
            }
            OutlinedButton(shape = MaterialTheme.shapes.medium, 
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Remove this air conditioner")
            }
            if (isActive && conn.phase == ConnectionPhase.CONNECTED) Note("Removing only deletes it from this phone. The AC itself is not changed.")
        }
    }

    if (confirmRemove && device != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("It will be deleted from this phone, together with its key. The AC itself is not changed, and you can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    app.store.remove(device.deviceId)
                    app.coordinator.reconnect()
                    onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}
