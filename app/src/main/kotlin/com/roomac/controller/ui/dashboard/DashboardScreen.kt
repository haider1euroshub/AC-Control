package com.roomac.controller.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.domain.model.formatTemp
import com.roomac.controller.protocol.ConnectionPhase
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.PhaseBanner
import com.roomac.controller.ui.components.Section
import com.roomac.controller.ui.components.SurfaceCard
import androidx.compose.material.icons.filled.AcUnit
import com.roomac.controller.ui.components.relativeTime
import com.roomac.controller.ui.components.isWorking
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenDevices: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDeviceSettings: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: (providerId: String) -> Unit,
    vm: DashboardViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val conn = ui.conn
    val device = conn.device

    Scaffold(
        topBar = {
            TopAppBar(
                title = { DeviceSwitcher(device, ui.devices, onSelect = vm::selectDevice, onManage = onOpenDevices) },
                actions = {
                    if (device != null) {
                        IconButton(onClick = { onOpenDeviceSettings(device.deviceId) }) { Icon(Icons.Filled.Tune, contentDescription = "This AC's settings") }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val detail = buildString {
                if (conn.phase == ConnectionPhase.CONNECTED) append("Home Wi-Fi")
                if (conn.lastUpdateEpochMs > 0) append((if (isEmpty()) "Updated " else " · updated ") + relativeTime(conn.lastUpdateEpochMs, now))
            }
            if (conn.phase != ConnectionPhase.NO_DEVICE) PhaseBanner(conn.phase, detail, conn.error) {
                when (conn.phase) {
                    ConnectionPhase.NEEDS_ACCOUNT -> device?.let { vm.accountProviderId(it.protocolId) }?.let { providerId ->
                        Button(shape = MaterialTheme.shapes.medium, onClick = { onSignIn(providerId) }, modifier = Modifier.height(48.dp)) { Text("Sign in") }
                    }
                    ConnectionPhase.AUTH_FAILED -> Button(shape = MaterialTheme.shapes.medium, onClick = vm::scanAgain, modifier = Modifier.height(48.dp)) { Text("Reconnect") }
                    ConnectionPhase.CONNECTED -> {}
                    else -> if (!conn.phase.isWorking) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = vm::scanAgain) { Text("Scan again") }
                    }
                }
            }

            if (conn.phase == ConnectionPhase.NO_DEVICE) EmptyState(onAddDevice)

            val status = conn.status
            val caps = conn.capabilities
            if (status != null && caps != null && device != null) {
                val enabled = conn.phase == ConnectionPhase.CONNECTED && conn.commandInFlight == null
                ClimatePanel(
                    status, caps, ui.pendingSetpoint, conn.commandInFlight, enabled,
                    connected = conn.phase == ConnectionPhase.CONNECTED,
                    onTogglePower = { vm.send(AcCommand.SetPower(!status.power)) },
                    onStep = vm::stepSetpoint
                )
                ModeCard(status, caps, enabled, device, vm::send)
                FanCard(status, caps, enabled, device, vm::send)
                AirflowCard(status, caps, enabled, device, vm::send)
                FunctionsCard(status, caps, enabled, device, vm::send)
                if (!caps.writable) {
                    Note("This unit reports a ${status.reportLength}-byte status layout that is not confirmed for control. Status is shown read-only so nothing wrong is ever written to the AC.")
                }
                if ((status.errorCode ?: 0) != 0) {
                    Note("The AC reports fault code ${status.errorCode}. Check the indoor unit's display and the AC's manual.")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceSwitcher(device: PairedDevice?, devices: List<PairedDevice>, onSelect: (String) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(device?.name ?: "Room AC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose air conditioner")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text(if (d.room.isNotBlank() && d.room != d.name) "${d.name} · ${d.room}" else d.name, style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { if (d.deviceId == device?.deviceId) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = { open = false; onSelect(d.deviceId) }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Manage air conditioners…") }, onClick = { open = false; onManage() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(status: AcStatus, caps: AcCapabilities, enabled: Boolean, device: PairedDevice, send: (AcCommand) -> Unit) {
    Section("Mode") {
        ChoiceRow(
            options = caps.modes, selected = status.mode, label = { it.label }, icon = { it.icon() },
            enabled = { enabled }, onSelect = { send(AcCommand.SetMode(it)) }
        )
        if (AcMode.HEAT !in caps.modes && status.heatCapable == false) Note("This unit reports it is cooling-only, so Heat is not offered.")
        Unverified(caps.modes.map { AcCommand.SetMode(it) }, device)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FanCard(status: AcStatus, caps: AcCapabilities, enabled: Boolean, device: PairedDevice, send: (AcCommand) -> Unit) {
    if (caps.fanSpeeds.isEmpty()) return
    Section("Fan speed") {
        ChoiceRow(
            options = caps.fanSpeeds, selected = status.fanSpeed, label = { it.label },
            enabled = { speed -> enabled && (caps.autoFanInFanMode || !(speed == FanSpeed.AUTO && status.mode == AcMode.FAN)) },
            onSelect = { send(AcCommand.SetFanSpeed(it)) }
        )
        if (!caps.autoFanInFanMode && status.mode == AcMode.FAN) Note("Auto fan is not available in Fan mode.")
        if (status.fanSpeed == null) Note("The AC reports fan code ${status.rawFanCode}, which is not one of the listed speeds.")
    }
}

@Composable
private fun AirflowCard(status: AcStatus, caps: AcCapabilities, enabled: Boolean, device: PairedDevice, send: (AcCommand) -> Unit) {
    if (!caps.verticalSwing && !caps.horizontalSwing) return
    Section("Airflow") {
        if (caps.verticalSwing) ToggleRow("Up/down swing", status.verticalSwing, enabled, AcCommand.SetVerticalSwing(!status.verticalSwing), device, send)
        if (caps.horizontalSwing) ToggleRow("Left/right swing", status.horizontalSwing == true, enabled, AcCommand.SetHorizontalSwing(status.horizontalSwing != true), device, send)
        Note("Swing is automatic sweep on or off. Fixed louvre positions are not offered because they are not confirmed for this model.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FunctionsCard(status: AcStatus, caps: AcCapabilities, enabled: Boolean, device: PairedDevice, send: (AcCommand) -> Unit) {
    if (caps.features.isEmpty() && caps.ecoLevels.isEmpty() && !caps.selfClean) return
    Section("Functions") {
        AcFeature.entries.filter { it in caps.features }.forEach { f ->
            val on = status.features[f] == true
            ToggleRow(f.label, on, enabled, AcCommand.SetFeature(f, !on), device, send,
                hint = when (f) {
                    AcFeature.TURBO -> if (device.protocolId == ProtocolIds.HAIER_USS) "Turbo and Quiet cannot both be on; turbo also centres the up/down louvre." else null
                    else -> null
                })
        }
        if (caps.ecoLevels.isNotEmpty()) {
            Text("ECO (power limit)", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                caps.ecoLevels.forEach { level ->
                    FilterChip(
                        selected = status.ecoLevel == level, enabled = enabled,
                        onClick = { if (status.ecoLevel != level) send(AcCommand.SetEcoLevel(level)) },
                        label = { Text(level.label) }, modifier = Modifier.height(44.dp)
                    )
                }
            }
            Note("Higher ECO levels cap the compressor harder: less power, slower cooling.")
            Unverified(listOf(AcCommand.SetEcoLevel(com.roomac.controller.domain.model.EcoLevel.OFF)), device)
        }
        if (caps.selfClean) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Self-clean", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (status.selfCleaning == true) "Running. The cycle finishes by itself and cannot be stopped from here."
                        else "Starts a coil-cleaning cycle (needs the AC on, not in Auto, Sleep off).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Unverified(listOf(AcCommand.StartSelfClean), device)
                }
                FilledTonalButton(shape = MaterialTheme.shapes.medium, 
                    onClick = { send(AcCommand.StartSelfClean) },
                    enabled = enabled && status.selfCleaning != true,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                ) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, enabled: Boolean, command: AcCommand, device: PairedDevice, send: (AcCommand) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Unverified(listOf(command), device)
        }
        Switch(checked = on, onCheckedChange = { send(command) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** Marks controls that have never been confirmed by read-back on this particular unit. */
@Composable
private fun Unverified(commands: List<AcCommand>, device: PairedDevice) {
    val kinds = commands.map { PairedDevice.controlKind(it) }.distinct()
    val missing = kinds.filter { it !in device.verifiedControls }
    if (missing.isNotEmpty() && missing.size == kinds.size) {
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text("Not yet verified on this AC", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    SurfaceCard(contentPadding = 24) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AcUnit, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
        }
        Text("Add your first air conditioner", style = MaterialTheme.typography.headlineSmall)
        Text(
            "RoomAC finds air conditioners on your home Wi-Fi and controls them directly. Works so far with Haier, Gree and Midea (including compatible models). " +
                "Haier ACs need a one-time sign-in to the HaiSmart account they were set up with.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(shape = MaterialTheme.shapes.medium, onClick = onAdd, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Find air conditioners", style = MaterialTheme.typography.titleMedium)
        }
    }
}
