package com.roomac.controller.ui.pairing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import com.roomac.controller.ui.theme.Motion
import com.roomac.controller.ui.theme.RoomAcTheme
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.formatTemp
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.ConnectionPhase
import com.roomac.controller.protocol.DiscoveredDevice
import com.roomac.controller.protocol.ExternalProvisioning
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.roomac.controller.ui.account.SignInForm
import com.roomac.controller.ui.account.SignUpForm
import com.roomac.controller.ui.components.InfoRow
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.PhaseBanner
import com.roomac.controller.ui.components.Section
import com.roomac.controller.ui.devices.SUGGESTED_ROOMS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onClose: () -> Unit, vm: PairingViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onClose() }
    // Back from a manufacturer's app (e.g. after putting a new AC on Wi-Fi): search again by itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onReturn() }
    BackHandler { if (!vm.back()) onClose() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Add air conditioner"); Text(ui.step.title, style = MaterialTheme.typography.bodyMedium) } },
            navigationIcon = { IconButton(onClick = { if (!vm.back()) onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
        )
    }) { padding ->
        Column(
            // consumeWindowInsets + imePadding: edge-to-edge ignores adjustResize, so lift the form above the keyboard.
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val reduced = RoomAcTheme.reducedMotion
            val progress by animateFloatAsState((ui.step.ordinal + 1) / PairingStep.entries.size.toFloat(), Motion.medium(reduced), label = "pairingProgress")
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            AnimatedContent(
                targetState = ui.step,
                transitionSpec = {
                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (slideInHorizontally(Motion.medium(reduced)) { w -> dir * w / 10 } + fadeIn(Motion.medium(reduced))) togetherWith fadeOut(Motion.short(reduced))
                },
                label = "pairingStep"
            ) { step ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (step) {
                        PairingStep.FIND -> FindStep(ui, vm)
                        PairingStep.CONNECT -> ConnectStep(ui, vm)
                        PairingStep.NAME -> NameStep(ui, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun FindStep(ui: PairingUi, vm: PairingViewModel) {
    val reduced = RoomAcTheme.reducedMotion
    Text("Looking for air conditioners on your Wi-Fi. Tap the one you want to control.", style = MaterialTheme.typography.bodyLarge)
    ReadyToScanCard(vm.savedAcHost, onReport = vm::onPreflight, onScanAnyway = vm::scan)
    // The checklist above explains a missing Wi-Fi connection and how to fix it.
    if (ui.networkError != null) return
    if (ui.vpnActive) Note("A VPN is on. If your AC is not found, turn the VPN off for a moment: it can hide devices on your home Wi-Fi.")
    Section("Air conditioners found") {
        if (ui.scanning) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text("Searching…", style = MaterialTheme.typography.bodyLarge)
        }
        ui.found.forEach { d ->
            key(d.deviceId) {
                // Each AC fades in as it answers, instead of the list jumping.
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn(Motion.medium(reduced)) + expandVertically(Motion.medium(reduced))) {
                    FoundRow(d, ui.existingIds.any { it.equals(d.deviceId, ignoreCase = true) }) { vm.select(d) }
                }
            }
        }
        if (ui.scanFinished && ui.found.isEmpty()) {
            Text("No air conditioner found.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Note("Check the AC is switched on at the wall and its Wi-Fi light is on, and that this phone is on the same home Wi-Fi (not a guest network). Some routers keep devices apart; if so, the router's \"AP isolation\" setting must be off.")
            vm.wifiSetupSteps().forEach { (brand, p) ->
                Note("A new $brand AC that has never been on Wi-Fi must first be connected once in the ${p.appName} app. That is the only step RoomAC cannot do; it searches again when you come back.")
            }
            BatterySaverHint()
        }
        if (!ui.scanning) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = vm::scan, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Search again", style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
private fun FoundRow(d: DiscoveredDevice, alreadyAdded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Connect", onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AcUnit, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${d.brandName()} air conditioner", style = MaterialTheme.typography.titleMedium)
                val label = d.reportedName.takeIf { it.isNotBlank() && !it.equals(d.deviceId, ignoreCase = true) }
                val model = d.model.takeIf { it.isNotBlank() && !it.equals(d.brand, ignoreCase = true) }
                listOfNotNull(label?.let { "\"$it\"" }, model).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (alreadyAdded) Text("Already added", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Button(shape = MaterialTheme.shapes.medium, onClick = onClick, modifier = Modifier.height(48.dp)) { Text("Connect") }
        }
    }
}

@Composable
private fun ConnectStep(ui: PairingUi, vm: PairingViewModel) {
    val d = ui.selected ?: return
    val provider = vm.provider(ui.signInProviderId)
    if (provider != null) {
        if (ui.creatingAccount) {
            SignUpForm(provider, onCreated = vm::connect)
            TextButton(onClick = vm::cancelCreateAccount, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            return
        }
        if (!ui.addingAccount) ui.connectError?.let { PhaseBanner(ConnectionPhase.NEEDS_ACCOUNT, null, it) }
        val renew = if (ui.addingAccount) null else provider.accounts().firstOrNull { it.needsSignIn }
        SignInForm(provider, initialLogin = renew?.let { provider.loginHint(it.id) } ?: "", renewing = renew != null, onCreateAccount = vm::createAccount, onSignedIn = vm::connect)
        if (ui.addingAccount) TextButton(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        return
    }
    Section("Connecting to the ${d.brandName()} air conditioner") {
        if (ui.connecting) {
            val account = vm.provider(vm.accountProviderFor(d))
            // What is happening, step by step, in plain words.
            ConnectingLine(if (account != null) "Getting this AC's key from your ${account.brandName} account…" else "Pairing with the AC over your Wi-Fi…")
            ConnectingLine("Checking the key with the AC and reading its settings…")
        }
        ui.connectError?.let { err ->
            PhaseBanner(ConnectionPhase.AUTH_FAILED, null, err)
            if (err is AcError.DeviceNotInAccount) NotInAccountHelp(vm, vm.provider(vm.accountProviderFor(d)), vm.provisioningFor(d))
            Button(shape = MaterialTheme.shapes.medium, onClick = vm::connect, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        }
        ui.verifiedStatus?.let { ConnectedSummary(it) }
    }
    Button(shape = MaterialTheme.shapes.medium, onClick = vm::toNameStep, enabled = ui.verifiedStatus != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Continue", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConnectingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The AC is not in any linked account. Registering an AC to an account (Wi-Fi provisioning and
 * binding) runs through Haier's own app and SDK, which has no published interface, so the app
 * offers the two real ways forward instead of pretending to do it.
 */
@Composable
private fun NotInAccountHelp(vm: PairingViewModel, provider: CloudAccountProvider?, provisioning: ExternalProvisioning?) {
    if (provider == null) return
    val context = LocalContext.current
    Text("This usually means one of these:", style = MaterialTheme.typography.titleSmall)
    Text("• The AC was set up with a different ${provider.serviceName} account (for example, a family member's or the installer's).", style = MaterialTheme.typography.bodyLarge)
    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = vm::signInAnotherAccount, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in with that account") }
    if (provisioning == null) return
    Text(
        "• The AC has not been added to any ${provisioning.appName} account yet. In ${provisioning.appName}, ${provisioning.step}. " +
            "${provider.brandName} allows that only in its own app. Do it there once; RoomAC then does everything else and searches again when you come back.",
        style = MaterialTheme.typography.bodyLarge
    )
    provisioning.appPackage?.let { pkg ->
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { openApp(context, pkg) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open ${provisioning.appName}") }
    }
}

/** Opens the installed app, or its Play Store page when it is not installed. */
private fun openApp(context: Context, pkg: String) {
    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ConnectedSummary(s: AcStatus) {
    PhaseBanner(ConnectionPhase.CONNECTED, "The AC answered with its current settings:", null)
    InfoRow("Power", if (s.power) "On" else "Off")
    InfoRow("Mode", s.mode?.label ?: "Unknown")
    InfoRow("Set to", formatTemp(s.targetTemperatureC))
    InfoRow("Room", s.indoorTemperatureC?.let { formatTemp(it) } ?: "Not reported")
    if (!s.writable) Note("This model's settings can be shown but not yet changed by the app.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameStep(ui: PairingUi, vm: PairingViewModel) {
    Section("Which room is this AC in?") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTED_ROOMS.forEach { r ->
                FilterChip(
                    selected = ui.room == r,
                    onClick = {
                        vm.setRoom(if (ui.room == r) "" else r)
                        if (ui.name.isBlank()) vm.setName("$r AC")
                    },
                    label = { Text(r, style = MaterialTheme.typography.bodyLarge) }
                )
            }
        }
        OutlinedTextField(
            value = ui.name, onValueChange = vm::setName, label = { Text("Name for this AC") }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth()
        )
    }
    ui.connectError?.let { PhaseBanner(ConnectionPhase.ERROR, null, it) }
    Button(shape = MaterialTheme.shapes.medium, onClick = vm::save, enabled = ui.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Save", style = MaterialTheme.typography.titleMedium)
    }
}
