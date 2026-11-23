package com.roomac.controller.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.toArgb
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
import com.roomac.controller.protocol.SignInResult
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.PhaseBanner
import com.roomac.controller.ui.components.Section
import kotlinx.coroutines.launch

/**
 * Links a manufacturer account, so the app can fetch the AC's key by itself. With "Stay signed in"
 * the password is kept Keystore-encrypted, only so the app can sign in again by itself if the
 * manufacturer ends the saved session; otherwise it lives only in this form's memory for the one
 * request. It is never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInForm(provider: CloudAccountProvider, initialLogin: String = "", renewing: Boolean = false, onCreateAccount: (() -> Unit)? = null, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var login by rememberSaveable { mutableStateOf(initialLogin) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf(provider.defaultRegion) }
    var regionOpen by remember { mutableStateOf(false) }
    var staySignedIn by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AcError?>(null) }

    if (!provider.available) {
        PhaseBanner(ConnectionPhase.UNSUPPORTED, null, AcError.AccountUnavailable(provider.brandName))
        return
    }

    fun submit() {
        if (busy || login.isBlank() || password.isEmpty()) return
        busy = true; keyboard?.hide()
        error = null
        scope.launch {
            val r = provider.signIn(login, password, region, staySignedIn)
            busy = false
            when (r) {
                SignInResult.Success -> {
                    password = ""
                    DiagnosticsLogger.i("Account", "${provider.id}: signed in")
                    onSignedIn()
                }
                is SignInResult.Failure -> error = r.error
            }
        }
    }

    Section(if (renewing) "Sign in to ${provider.serviceName} again" else "Connect your ${provider.brandName} account") {
        Text(
            if (renewing) "${provider.brandName} no longer accepts the saved sign-in (for example, the password was changed). Sign in once more and the app carries on by itself."
            else "Sign in once with the ${provider.serviceName} account your AC was set up with. RoomAC then gets the AC's key from ${provider.brandName} " +
                "by itself, now and whenever it changes, and controls the AC directly over your Wi-Fi.",
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            value = login, onValueChange = { login = it; error = null }, singleLine = true,
            label = { Text("Email or phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrect = false, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null }, singleLine = true,
            label = { Text("${provider.serviceName} password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
        )
        ExposedDropdownMenuBox(expanded = regionOpen, onExpandedChange = { regionOpen = it }) {
            OutlinedTextField(
                value = provider.regions.firstOrNull { it.code == region }?.label ?: region,
                onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Country of your ${provider.serviceName} account") },
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
                    "Keeps your password locked on this phone (Android Keystore) so the app can sign in again by itself if ${provider.brandName} asks. " +
                        "Turn off to be asked instead.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = staySignedIn, onCheckedChange = null)
        }
        error?.let {
            PhaseBanner(ConnectionPhase.NEEDS_ACCOUNT, null, it)
            // Haier reports an account registered under another country as "not registered".
            val country = provider.regions.firstOrNull { r -> r.code == region }?.label ?: region
            when (it) {
                is AcError.AccountRejected -> Note("Also check the country: it must be the one chosen when the ${provider.serviceName} account was created.")
                // Sent as the account's zone: an account created under another country is "not registered" here.
                is AcError.AccountNotInRegion -> Note("Country sent: $country. In the ${provider.serviceName} app, check which country the account was created in, choose that country above and sign in again.")
                else -> {}
            }
        }
        Button(shape = MaterialTheme.shapes.medium, onClick = ::submit, enabled = !busy && login.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (busy) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium)
        }
        Note("Your password is sent only to ${provider.brandName}, encrypted. Signing out in Settings deletes everything saved for the account; ACs already connected keep working.")
        Note("Signed up in the ${provider.serviceName} app with Google or Facebook? Those accounts have no password. In the ${provider.serviceName} app, share the AC with a second account that uses an email and password, and sign in here with that one.")
        val web = provider.features.webRegistration
        when {
            web != null -> {
                val context = LocalContext.current
                val toolbar = MaterialTheme.colorScheme.surface.toArgb()
                var opened by rememberSaveable { mutableStateOf(false) }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { opened = openWebRegistration(context, web, toolbar) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Create a ${provider.serviceName} account") }
                Note(
                    if (opened) "Finish creating the account on ${provider.brandName}'s page, including the email or SMS check, then come back here and sign in."
                    else "Opens ${provider.brandName}'s own sign-up page inside RoomAC. RoomAC does not see what you type there."
                )
            }
            // In-app account creation (feature flag): a new ${serviceName} account is made right here, no app redirect.
            // Only where the caller can open the form: a button with no handler would do nothing.
            provider.features.register && onCreateAccount != null -> {
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onCreateAccount,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Create a ${provider.serviceName} account") }
                Note("New to ${provider.brandName}? Create a ${provider.serviceName} account here: verify your email with a code, choose a password, and you're signed in.")
            }
            else -> provider.noRegistrationHelp?.let { help ->
                var open by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (open) "Hide" else "No account yet, or forgot your password?", style = MaterialTheme.typography.labelLarge)
                }
                AnimatedVisibility(open) { Note(help) }
            }
        }
    }
}

/** Route value for linking an additional account (no pre-fill). */
const val NEW_ACCOUNT = "new"

/**
 * Full-screen sign-in, opened from the remote screen or Settings. With [accountId] (or when an
 * account is waiting for a repeated sign-in) the login is pre-filled. Closes itself after signing in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(providerId: String, accountId: String?, onCreateAccount: (() -> Unit)? = null, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as RoomAcApplication
    val provider = app.accounts[providerId]
    Scaffold(topBar = {
        TopAppBar(title = { Text("Sign in") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
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
            val renew = when (accountId) {
                NEW_ACCOUNT -> null
                null -> provider.accounts().firstOrNull { it.needsSignIn }
                else -> provider.accounts().firstOrNull { it.id == accountId }
            }
            SignInForm(
                provider,
                initialLogin = renew?.let { provider.loginHint(it.id) } ?: "",
                renewing = renew?.needsSignIn == true,
                onCreateAccount = onCreateAccount
            ) {
                // Connect any AC that was waiting for this sign-in.
                app.coordinator.reconnect()
                onDone()
            }
        }
    }
}
