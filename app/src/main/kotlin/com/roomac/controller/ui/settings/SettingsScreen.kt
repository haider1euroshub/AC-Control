package com.roomac.controller.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomac.controller.BuildConfig
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.protocol.AcConnectionCoordinator
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.LinkedAccount
import com.roomac.controller.ui.account.NEW_ACCOUNT
import com.roomac.controller.storage.ThemeMode
import com.roomac.controller.ui.components.InfoRow
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.Section
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignIn: (providerId: String) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenNetworkDevices: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as RoomAcApplication
    val theme by app.store.themeMode.collectAsStateWithLifecycle()
    val poll by app.store.pollIntervalMs.collectAsStateWithLifecycle()
    var sliderSec by remember(poll) { mutableFloatStateOf(poll / 1000f) }
    // Account state is not a flow; bump this to re-read it after signing out.
    var accountsVersion by remember { mutableIntStateOf(0) }
    var confirmSignOut by remember { mutableStateOf<Pair<CloudAccountProvider, LinkedAccount>?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Air conditioners") {
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenDevices, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Rename, choose rooms, remove") }
            }

            Section("Manufacturer accounts") {
                accountsVersion // read so sign-out recomposes this section
                app.accounts.values.forEach { provider ->
                    Text(provider.brandName, style = MaterialTheme.typography.titleSmall)
                    val linked = provider.accounts()
                    if (linked.isEmpty()) {
                        Text("Not signed in. Needed only to connect a new ${provider.brandName} AC.", style = MaterialTheme.typography.bodyLarge)
                    }
                    linked.forEach { account ->
                        Text(account.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                account.needsSignIn -> "${provider.brandName} no longer accepts the saved sign-in. Sign in again so ACs can be reconnected when their key changes."
                                account.staysSignedIn -> "Signed in. Stays signed in by itself."
                                else -> "Signed in. If ${provider.brandName} ends the session, you will be asked to sign in again."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (account.needsSignIn && provider.available) {
                                Button(shape = MaterialTheme.shapes.medium, onClick = { onSignIn("${provider.id}/${account.id}") }, modifier = Modifier.height(48.dp)) { Text("Sign in again") }
                            }
                            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { confirmSignOut = provider to account }, modifier = Modifier.height(48.dp)) { Text("Sign out") }
                        }
                    }
                    if (provider.available) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { onSignIn("${provider.id}/$NEW_ACCOUNT") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(if (linked.isEmpty()) "Sign in" else "Add another ${provider.brandName} account")
                        }
                    }
                }
                Note("Gree and Gree-compatible ACs pair directly over Wi-Fi and need no account.")
            }

            Section("Appearance") {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .selectable(selected = theme == mode, onClick = { app.store.setThemeMode(mode) }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = theme == mode, onClick = null)
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            Section("Status updates") {
                Text("Check the AC every ${sliderSec.roundToLong()} seconds while the app is open", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = sliderSec, onValueChange = { sliderSec = it },
                    onValueChangeFinished = { app.store.setPollInterval(sliderSec.roundToLong() * 1000) },
                    valueRange = (AcConnectionCoordinator.MIN_POLL_MS / 1000f)..(AcConnectionCoordinator.MAX_POLL_MS / 1000f), steps = 10
                )
                Note("The app also checks right after every change you make, and searches the Wi-Fi again by itself when the AC moves or the network changes. Nothing runs when the app is closed.")
            }

            Section("Privacy") {
                Text(
                    "Your ACs are controlled directly over your home Wi-Fi; no command goes through the internet. " +
                        "The internet is used only to fetch a Haier AC's key from your HaiSmart account, when it is first connected or its key changes. " +
                        "Keys and sign-ins are stored encrypted on this phone and are never written to the app's log. " +
                        "With \"Stay signed in\" your HaiSmart password is also kept encrypted, used only to sign in to Haier again, and deleted when you sign out. " +
                        "The app collects no analytics and needs no location, camera or contacts permission.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (BuildConfig.NETWORK_SCANNER) Section("Network devices") {
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenNetworkDevices, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("See what else is on this Wi-Fi") }
                Note("Lists TVs, printers, speakers, cameras and other connected devices. Nothing is sent outside your network.")
            }

            Section("Help") {
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenHelp, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("How the app works") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Troubleshooting information") }
            }

            Section("About") {
                InfoRow("App version", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
                Text(
                    "Haier local control and account sign-in are adapted from haismart-local by its contributors (github.com/enapt/haismart-local), " +
                        "MIT License, Copyright (c) 2026 haismart-local contributors. THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Not affiliated with or endorsed by any air-conditioner manufacturer.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirmSignOut?.let { (provider, account) ->
        AlertDialog(
            onDismissRequest = { confirmSignOut = null },
            title = { Text("Sign out of ${account.label}?") },
            text = { Text("Everything saved for this ${provider.brandName} account is deleted from this phone, including the password if it was kept. ACs already connected keep working on your Wi-Fi; to connect a new ${provider.brandName} AC, or if an AC's key changes, you will need to sign in again.") },
            confirmButton = {
                TextButton(onClick = { provider.signOut(account.id); confirmSignOut = null; accountsVersion++ }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = null }) { Text("Cancel") } }
        )
    }
}
