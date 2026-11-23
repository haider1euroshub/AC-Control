package com.roomac.controller.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import com.roomac.controller.BuildConfig
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.ui.account.SignInScreen
import com.roomac.controller.ui.account.SignUpScreen
import com.roomac.controller.ui.dashboard.DashboardScreen
import com.roomac.controller.ui.devices.DeviceSettingsScreen
import com.roomac.controller.ui.devices.DevicesScreen
import com.roomac.controller.ui.diagnostics.DiagnosticsScreen
import com.roomac.controller.ui.help.HelpScreen
import com.roomac.controller.ui.netscan.DeviceControlScreen
import com.roomac.controller.ui.netscan.NetworkDevicesScreen
import com.roomac.controller.ui.pairing.PairingScreen
import com.roomac.controller.ui.settings.SettingsScreen
import com.roomac.controller.ui.theme.Motion
import com.roomac.controller.ui.theme.RoomAcTheme

/** Routes are plain strings so the back stack survives process death via rememberSaveable. */
private object Routes {
    const val DASHBOARD = "dashboard"
    const val DEVICES = "devices"
    const val PAIRING = "pairing"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val HELP = "help"
    const val NETWORK_DEVICES = "network-devices"
    const val DEVICE_CONTROL = "device-control/"
    const val DEVICE = "device/"
    /** signin/<provider id>[/<account id> | new] */
    const val SIGN_IN = "signin/"
    /** signup/<provider id> */
    const val SIGN_UP = "signup/"
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as RoomAcApplication
    val stack = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.toList() }, restore = { it.toMutableStateList() }
    )) {
        // First launch: go straight to the automatic search, so finding an AC needs no tap at all.
        (if (app.store.devices.value.isEmpty()) listOf(Routes.DASHBOARD, Routes.PAIRING) else listOf(Routes.DASHBOARD)).toMutableStateList()
    }

    fun push(r: String) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val top = stack.last()
    if (top != Routes.PAIRING) BackHandler(enabled = stack.size > 1) { pop() }

    val reduced = RoomAcTheme.reducedMotion
    // Depth decides the direction: deeper slides in from the end, back slides the other way.
    AnimatedContent(
        targetState = stack.size to top,
        transitionSpec = { screenTransition(forward = targetState.first >= initialState.first, reduced = reduced) },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        label = "screen"
    ) { (_, route) ->
    Box(Modifier.fillMaxSize()) {
    when {
        route == Routes.DASHBOARD -> DashboardScreen(
            onOpenDevices = { push(Routes.DEVICES) },
            onAddDevice = { push(Routes.PAIRING) },
            onOpenDeviceSettings = { push(Routes.DEVICE + it) },
            onOpenSettings = { push(Routes.SETTINGS) },
            onSignIn = { push(Routes.SIGN_IN + it) }
        )
        route == Routes.DEVICES -> DevicesScreen(onBack = ::pop, onAdd = { push(Routes.PAIRING) }, onOpen = { push(Routes.DEVICE + it) })
        route == Routes.PAIRING -> PairingScreen(onClose = ::pop)
        route == Routes.SETTINGS -> SettingsScreen(
            onBack = ::pop,
            onSignIn = { push(Routes.SIGN_IN + it) },
            onOpenDevices = { push(Routes.DEVICES) },
            onOpenDiagnostics = { push(Routes.DIAGNOSTICS) },
            onOpenHelp = { push(Routes.HELP) },
            onOpenNetworkDevices = { push(Routes.NETWORK_DEVICES) }
        )
        route == Routes.DIAGNOSTICS -> DiagnosticsScreen(onBack = ::pop)
        route == Routes.HELP -> HelpScreen(onBack = ::pop)
        route == Routes.NETWORK_DEVICES && BuildConfig.NETWORK_SCANNER -> NetworkDevicesScreen(
            onBack = ::pop,
            onOpenDevice = if (BuildConfig.DEVICE_CONTROL) { ip -> push(Routes.DEVICE_CONTROL + ip) } else null
        )
        route.startsWith(Routes.DEVICE_CONTROL) && BuildConfig.NETWORK_SCANNER && BuildConfig.DEVICE_CONTROL ->
            DeviceControlScreen(ip = route.removePrefix(Routes.DEVICE_CONTROL), onBack = ::pop)
        route.startsWith(Routes.SIGN_IN) -> route.removePrefix(Routes.SIGN_IN).split("/", limit = 2).let { parts ->
            SignInScreen(
                providerId = parts[0], accountId = parts.getOrNull(1),
                onCreateAccount = { push(Routes.SIGN_UP + parts[0]) }, onDone = ::pop
            )
        }
        // Back returns to the sign-in screen; on success the account is already signed in, so leave both.
        route.startsWith(Routes.SIGN_UP) -> SignUpScreen(providerId = route.removePrefix(Routes.SIGN_UP), onBack = ::pop, onCreated = { pop(); pop() })
        route.startsWith(Routes.DEVICE) -> DeviceSettingsScreen(
            deviceId = route.removePrefix(Routes.DEVICE), onBack = ::pop, onSignIn = { push(Routes.SIGN_IN + it) }
        )
    }
    }
    }
}

private fun screenTransition(forward: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(Motion.short(true)) togetherWith fadeOut(Motion.short(true))
    val shift = if (forward) 1 else -1
    return (slideInHorizontally(Motion.medium(false)) { w -> shift * w / 12 } + fadeIn(Motion.medium(false))) togetherWith
        (slideOutHorizontally(Motion.medium(false)) { w -> -shift * w / 24 } + fadeOut(Motion.short(false)))
}
