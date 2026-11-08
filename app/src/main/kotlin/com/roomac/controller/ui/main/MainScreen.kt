package com.roomac.controller.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSetup: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.lastError) {
        uiState.lastError?.let { err ->
            snackbarHostState.showSnackbar(
                message = err.userMessage,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            viewModel.clearLastError()
        }
    }

    LaunchedEffect(uiState.disabledReason) {
        uiState.disabledReason?.let { reason ->
            snackbarHostState.showSnackbar(
                message = reason,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            viewModel.clearDisabledReason()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Room AC Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        ConnectionSubtitle(uiState)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onRefresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onNavigateToSetup) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!uiState.isConfigured) {
                UnconfiguredCard(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    onNavigateToSetup = onNavigateToSetup
                )
            } else {
                ActiveControlsView(
                    uiState = uiState,
                    onPowerClicked = { viewModel.onPowerClicked() },
                    onModeSelected = { viewModel.onModeSelected(it) },
                    onTemperatureStep = { viewModel.onTemperatureStep(it) },
                    onVerticalSwingSelected = { viewModel.onVerticalSwingSelected(it) },
                    onHorizontalSwingSelected = { viewModel.onHorizontalSwingSelected(it) },
                    onRefresh = { viewModel.onRefresh() },
                    onNavigateToSetup = onNavigateToSetup
                )
            }
        }
    }
}

@Composable
internal fun ConnectionSubtitle(uiState: MainUiState) {
    val text = when (uiState.connectionStatus) {
        is ConnectionStatus.Connected -> "Connected (${(uiState.connectionStatus as ConnectionStatus.Connected).host})"
        ConnectionStatus.Connecting -> "Connecting..."
        ConnectionStatus.Disconnected -> "Disconnected"
        is ConnectionStatus.Failed -> "Connection Failed"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun UnconfiguredCard(
    modifier: Modifier = Modifier,
    onNavigateToSetup: () -> Unit
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Air Conditioner Not Configured", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Please configure your Haier AC's IP address and select the protocol driver to begin.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onNavigateToSetup) {
                Text("Open Setup")
            }
        }
    }
}

@Composable
internal fun ActiveControlsView(
    uiState: MainUiState,
    onPowerClicked: () -> Unit,
    onModeSelected: (AcMode) -> Unit,
    onTemperatureStep: (Boolean) -> Unit,
    onVerticalSwingSelected: (SwingPosition) -> Unit,
    onHorizontalSwingSelected: (SwingPosition) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Temperature Display & Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val tempVal = uiState.pendingTemperature ?: uiState.acState.targetTemperature.value
                val tempText = if (tempVal != null) "${tempVal.toInt()}°C" else "--"
                val provenanceText = when (uiState.acState.targetTemperature.provenance) {
                    StateProvenance.CONFIRMED_BY_AC -> "Confirmed by AC"
                    StateProvenance.STALE -> "Stale (was confirmed; reconnect to refresh)"
                    StateProvenance.OPTIMISTIC_AFTER_SEND -> "Sent (Awaiting Confirmation)"
                    StateProvenance.OWNER_ASSERTED -> "Owner-entered, not verified"
                    StateProvenance.UNKNOWN -> "Unknown State"
                }

                Text(tempText, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(provenanceText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { onTemperatureStep(false) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Text("−", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledIconButton(
                        onClick = { onTemperatureStep(true) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Power Toggle
        // SAFETY (G1): Unknown power state must NEVER render as OFF.
        // We use a tri-state: ON / OFF / UNKNOWN.
        val powerKnown = uiState.acState.power.isKnown || uiState.acState.power.provenance == StateProvenance.STALE
        val isPowerOn = uiState.acState.power.value == PowerState.ON
        val powerLabel = when {
            !uiState.acState.power.hasDisplayValue -> "POWER (Unknown)"
            uiState.acState.power.provenance == StateProvenance.STALE -> if (isPowerOn) "TURN OFF (Stale)" else "TURN ON (Stale)"
            isPowerOn -> "TURN OFF"
            else -> "TURN ON"
        }
        Button(
            onClick = onPowerClicked,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    !uiState.acState.power.hasDisplayValue -> MaterialTheme.colorScheme.secondary
                    isPowerOn -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                }
            )
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(powerLabel, fontWeight = FontWeight.Bold)
        }

        // Mode Chips
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Operating Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentMode = uiState.acState.mode.value
                    FilterChip(
                        selected = currentMode == AcMode.COOL,
                        onClick = { onModeSelected(AcMode.COOL) },
                        label = { Text("Cool") },
                        leadingIcon = { Icon(Icons.Default.AcUnit, contentDescription = null) }
                    )
                    FilterChip(
                        selected = currentMode == AcMode.HEAT,
                        onClick = { onModeSelected(AcMode.HEAT) },
                        label = { Text("Heat") },
                        enabled = uiState.capabilities.supportsHeating,
                        leadingIcon = { Icon(Icons.Default.WbSunny, contentDescription = null) }
                    )
                }
            }
        }

        // Swing Controls
        // SAFETY (G2): Unknown swing state must NEVER render as OFF.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val swingStale = uiState.acState.swing.provenance == StateProvenance.STALE
                val swingLabel = if (swingStale) "Vertical Fan Swing (Stale)" else "Vertical Fan Swing"
                Text(swingLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val swingValue = uiState.acState.swing.value
                if (swingValue == null && uiState.acState.swing.provenance == StateProvenance.UNKNOWN) {
                    // Unknown: show indicator, don't select any chip
                    Text(
                        "Swing state unknown — refresh status or set baseline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val curVert = swingValue?.vertical
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = curVert == SwingPosition.OFF,
                            onClick = { onVerticalSwingSelected(SwingPosition.OFF) },
                            label = { Text("Fixed") }
                        )
                        FilterChip(
                            selected = curVert == SwingPosition.OSCILLATE,
                            onClick = { onVerticalSwingSelected(SwingPosition.OSCILLATE) },
                            label = { Text("Oscillate") }
                        )
                    }
                }
            }
        }
    }
}
