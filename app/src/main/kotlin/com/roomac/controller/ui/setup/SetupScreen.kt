package com.roomac.controller.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.PowerState
import com.roomac.controller.protocol.DriverVerificationStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    viewModel: SetupViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection & AC Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Section 1: Protocol Selection ──────────────────────────────────────────
            Text("1. SELECT PROTOCOL DRIVER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.availableDrivers.forEach { driver ->
                    val isSelected = driver.id == uiState.selectedProtocolId
                    Card(
                        onClick = { viewModel.onProtocolSelected(driver.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = driver.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                StatusBadge(status = driver.verificationStatus)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = driver.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Section 2: Network Coordinates ────────────────────────────────────────
            Text("2. NETWORK CONFIGURATION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = uiState.host,
                onValueChange = { viewModel.onHostChanged(it) },
                label = { Text("AC IP Address (Required)") },
                placeholder = { Text("e.g. 192.168.1.105") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.port,
                onValueChange = { viewModel.onPortChanged(it) },
                label = { Text("Port (Default: 56800)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.macAddress,
                onValueChange = { viewModel.onMacChanged(it) },
                label = { Text("MAC Address (Optional)") },
                placeholder = { Text("e.g. 00:07:A8:XX:XX:XX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Local Key field (for HaiSmart AES)
            val isHaiSmart = uiState.selectedProtocolId.contains("haismart", ignoreCase = true)
            if (isHaiSmart) {
                OutlinedTextField(
                    value = uiState.localKeyHex,
                    onValueChange = { viewModel.onKeyChanged(it) },
                    label = { Text("Local Key (16-byte Hex / 32 characters)") },
                    placeholder = { Text("Required for AES-128 encrypted control") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Section 3: Owner-Asserted Baseline (Bring-Up Safety) ───────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Owner-Asserted Baseline",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Bootstrap state when AC readback format is unverified",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.hasOwnerBaseline,
                            onCheckedChange = { viewModel.onBaselineToggled(it) }
                        )
                    }

                    AnimatedVisibility(visible = uiState.hasOwnerBaseline) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Enter the actual state the AC is currently set to with its physical remote:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = uiState.baselinePower == PowerState.ON,
                                    onClick = { viewModel.onBaselinePowerSelected(PowerState.ON) },
                                    label = { Text("Power ON") }
                                )
                                FilterChip(
                                    selected = uiState.baselinePower == PowerState.OFF,
                                    onClick = { viewModel.onBaselinePowerSelected(PowerState.OFF) },
                                    label = { Text("Power OFF") }
                                )
                                FilterChip(
                                    selected = uiState.baselineMode == AcMode.COOL,
                                    onClick = { viewModel.onBaselineModeSelected(AcMode.COOL) },
                                    label = { Text("Cool Mode") }
                                )
                                FilterChip(
                                    selected = uiState.baselineMode == AcMode.HEAT,
                                    onClick = { viewModel.onBaselineModeSelected(AcMode.HEAT) },
                                    label = { Text("Heat Mode") }
                                )
                            }
                        }
                    }
                }
            }

            // ── Section 4: Heating Support Setting ────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AC Supports Heating Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Only enable if unit has a heat pump (default: disabled for safety)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.supportsHeating,
                        onCheckedChange = { viewModel.onHeatingToggled(it) }
                    )
                }
            }

            // ── Section 5: Passive Connection Probe & Save Actions ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.probeConnection() },
                    enabled = !uiState.isProbing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isProbing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Probing…")
                    } else {
                        Text("Probe AC (Passive)")
                    }
                }

                Button(
                    onClick = {
                        viewModel.saveSettings(onSuccess = onDone)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Apply")
                }
            }

            // Probe Result Banner
            uiState.probeResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.startsWith("Connected")) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (result.startsWith("Connected")) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (result.startsWith("Connected")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (result.startsWith("Connected")) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ── Section 6: Diagnostics & Logs ─────────────────────────────────────────
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("DIAGNOSTICS & AUDIT LOG", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row {
                    IconButton(onClick = { viewModel.refreshDiagnostics() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Log", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(uiState.diagnosticsLog))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Log", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF212121))
                    .padding(10.dp)
            ) {
                val diagScroll = rememberScrollState()
                Text(
                    text = uiState.diagnosticsLog.ifBlank { "No log events recorded yet." },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = Color(0xFFE0E0E0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(diagScroll)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DriverVerificationStatus) {
    val (label, bg, fg) = when (status) {
        DriverVerificationStatus.VERIFIED_ON_REAL_AC -> Triple("Verified", Color(0xFF2E7D32), Color.White)
        DriverVerificationStatus.UNVERIFIED_FROM_DOCUMENTATION -> Triple("Unverified", Color(0xFFE65100), Color.White)
        DriverVerificationStatus.STUB_MISSING_INFO -> Triple("Stub", Color(0xFF757575), Color.White)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
