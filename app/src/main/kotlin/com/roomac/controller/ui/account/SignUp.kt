package com.roomac.controller.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.ConnectionPhase
import com.roomac.controller.protocol.RegisterResult
import com.roomac.controller.protocol.SendCodeResult
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.PhaseBanner
import com.roomac.controller.ui.components.Section
import kotlinx.coroutines.launch

/** Shortest password the form will submit. The service enforces its own rules and reports any it rejects. */
private const val MIN_PASSWORD = 6

/**
 * Creates a new manufacturer account inside RoomAC — no redirect to the manufacturer app. Two steps:
 * enter the email, country and a password and request a code; then enter the emailed code to create the
 * account. On success the account is already signed in (the provider links it), so the form just closes.
 * The password lives only in this form's memory until the account is created; it is never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpForm(provider: CloudAccountProvider, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf(provider.defaultRegion) }
    var regionOpen by remember { mutableStateOf(false) }
    var staySignedIn by rememberSaveable { mutableStateOf(true) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AcError?>(null) }

    if (!provider.available || !provider.features.register) {
        PhaseBanner(ConnectionPhase.UNSUPPORTED, null, AcError.AccountUnavailable(provider.brandName))
        return
    }

    val detailsReady = email.isNotBlank() && password.length >= MIN_PASSWORD
    // The password is deliberately not saved with the screen state, so if the phone ended RoomAC while
    // the user was reading the email, it comes back empty: unlock the field and ask for it again.
    val passwordNeeded = codeSent && password.length < MIN_PASSWORD

    fun sendCode() {
        if (busy || !detailsReady) return
        busy = true; error = null; keyboard?.hide()
        scope.launch {
            when (val r = provider.sendVerificationCode(email, region)) {
                SendCodeResult.Success -> { codeSent = true; DiagnosticsLogger.i("Account", "${provider.id}: verification code requested") }
                is SendCodeResult.Failure -> error = r.error
            }
            busy = false
        }
    }

    fun createAccount() {
        if (busy || code.isBlank() || passwordNeeded) return
        busy = true; error = null; keyboard?.hide()
        scope.launch {
            val r = provider.register(email, code, password, region, staySignedIn)
            busy = false
            when (r) {
                RegisterResult.Success -> { password = ""; DiagnosticsLogger.i("Account", "${provider.id}: account created"); onCreated() }
                is RegisterResult.Failure -> error = r.error
            }
        }
    }

    Section("Create a ${provider.serviceName} account") {
        Text(
            "Make a new ${provider.brandName} account here. We email you a code to confirm the address, then you're signed in — " +
                "the same account you'll use in the ${provider.serviceName} app.",
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it; error = null }, singleLine = true, enabled = !codeSent,
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrect = false, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null }, singleLine = true, enabled = !codeSent || passwordNeeded,
            label = { Text("Choose a password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                }
            },
            supportingText = { Text(if (passwordNeeded) "Enter the password you chose again." else "At least $MIN_PASSWORD characters.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
        )
        ExposedDropdownMenuBox(expanded = regionOpen, onExpandedChange = { if (!codeSent) regionOpen = it }) {
            OutlinedTextField(
                value = provider.regions.firstOrNull { it.code == region }?.label ?: region,
                onValueChange = {}, readOnly = true, singleLine = true, enabled = !codeSent,
                label = { Text("Country") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionOpen) },
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = regionOpen, onDismissRequest = { regionOpen = false }) {
                provider.regions.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = { region = r.code; regionOpen = false })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = staySignedIn, onValueChange = { staySignedIn = it }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Stay signed in", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Keeps your password locked on this phone (Android Keystore) so the app can sign in again by itself if ${provider.brandName} asks.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = staySignedIn, onCheckedChange = null)
        }

        if (!codeSent) {
            error?.let { PhaseBanner(ConnectionPhase.NEEDS_ACCOUNT, null, it) }
            Button(
                shape = MaterialTheme.shapes.medium, onClick = ::sendCode,
                enabled = !busy && detailsReady, modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (busy) "Sending code…" else "Email me a code", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text("Enter the code we emailed to $email.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter { c -> c.isDigit() }; error = null }, singleLine = true,
                label = { Text("Verification code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { createAccount() }),
                textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            error?.let { PhaseBanner(ConnectionPhase.NEEDS_ACCOUNT, null, it) }
            Button(
                shape = MaterialTheme.shapes.medium, onClick = ::createAccount,
                enabled = !busy && code.isNotBlank() && !passwordNeeded, modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (busy) "Creating account…" else "Create account", style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (!busy) { codeSent = false; code = ""; error = null } }) { Text("Change details") }
                TextButton(onClick = ::sendCode, enabled = !busy) { Text("Resend code") }
            }
        }
        Note("Your password is sent only to ${provider.brandName}, encrypted. You can sign out any time in Settings.")
    }
}

/** Full-screen account creation, opened from the sign-in screen. Closes itself once the account is made (and signed in). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(providerId: String, onBack: () -> Unit, onCreated: () -> Unit) {
    val app = LocalContext.current.applicationContext as RoomAcApplication
    val provider = app.accounts[providerId]
    Scaffold(topBar = {
        TopAppBar(title = { Text("Create account") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            // consumeWindowInsets + imePadding: edge-to-edge ignores adjustResize, so lift the form above the keyboard.
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (provider == null) {
                Text("This account type is not available.")
                return@Column
            }
            SignUpForm(provider) {
                // The provider has already signed the new account in; connect any AC waiting for it, then close.
                app.coordinator.reconnect()
                onCreated()
            }
        }
    }
}
