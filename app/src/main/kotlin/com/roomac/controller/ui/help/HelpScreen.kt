package com.roomac.controller.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.ui.components.Section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("How the app works") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Before searching") {
                P("Each search starts with a short checklist. When everything required has a green tick, the search starts by itself.")
                P("Wi-Fi: must be on and joined to your home Wi-Fi. Android does not let apps switch Wi-Fi on, so \"Turn on Wi-Fi\" opens the Wi-Fi panel; come back and the app carries on.")
                P("Nearby devices: lets the app see your Wi-Fi network and hear the AC's reply. On Android 12 and older this permission is called Location, and Location must be switched on. The app never reads your location. If you refused it for good, \"Open app settings\" takes you to the page where you can allow it.")
                P("Bluetooth is optional. ACs already on your Wi-Fi are found over Wi-Fi, not Bluetooth.")
                P("You do not need to open the HaiSmart app first. Mobile data can stay on: the app always talks to the AC over Wi-Fi.")
            }
            Section("Adding an AC") {
                P("1. The app searches your home Wi-Fi and lists the air conditioners it can control.")
                P("2. Tap \"Connect\" next to yours. The app sets up a secure connection by itself.")
                P("3. Haier ACs: the first time, sign in with the same email or phone number and password you use in the HaiSmart app. Haier gives each AC a secret key, and signing in lets the app fetch it. You do this once.")
                P("4. Gree ACs (and other brands with Gree Wi-Fi): nothing to sign in to. The AC hands the app its key directly over your Wi-Fi.")
                P("5. Choose the room, and you are ready.")
            }
            Section("Using the remote") {
                P("Every button press is sent straight to the AC over your Wi-Fi. The screen only changes once the AC itself confirms the new setting, so what you see is what the AC is really doing.")
                P("Only the controls your AC supports are shown.")
            }
            Section("If the AC is not found") {
                P("Check the AC is switched on at the wall and its Wi-Fi light is on.")
                P("The phone must be on the same home Wi-Fi as the AC. A guest Wi-Fi network, or a router setting called \"AP isolation\" or \"client isolation\", keeps devices apart; mobile data cannot reach the AC.")
                P("A VPN on the phone can hide devices at home. Turn it off for a moment and search again.")
                P("Battery Saver can make the phone miss the AC's reply. If it is on, turn it off for a moment and search again.")
            }
            Section("Internet and privacy") {
                P("Everyday control works without internet: the phone talks to the AC over your Wi-Fi only.")
                P("For Haier, the internet is needed when an AC is first connected, and again only if Haier gives it a new key (for example after the AC is set up again in the HaiSmart app). Your password is sent only to Haier. It is kept on this phone only if you leave \"Stay signed in\" on, locked with the Android Keystore.")
                P("If signing in fails, the message names the step that failed (sign-in, device list, key fetch) and Haier's reason code. \"Not found in the selected country\" means the account was created under another country: choose that country and try again.")
                P("Signed up in HaiSmart with Google or Facebook? Those accounts have no password. In the HaiSmart app, share the AC with a second account that has an email and password, and sign in here with that one.")
            }
            Section("Which ACs work") {
                P("Supported now: ${AcBrands.all.filter { it.protocolIds.isNotEmpty() }.joinToString { it.displayName }}, and other brands that use Gree's Wi-Fi module.")
                P("Being worked on: ${AcBrands.byIntegrationOrder.filter { it.protocolIds.isEmpty() }.joinToString { it.displayName }}.")
                P("ACs that only have a hand remote (no Wi-Fi), and Wi-Fi ACs that only work through their maker's online service, cannot be found by the app.")
            }
        }
    }
}

@Composable
private fun P(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)
