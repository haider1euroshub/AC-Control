package com.roomac.controller.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.protocol.TrustedUrl
import com.roomac.controller.protocol.WebRegistration

/**
 * Opens a manufacturer's official sign-up page in a Custom Tab: the system browser engine, shown on
 * top of RoomAC with RoomAC's colours. A Custom Tab rather than a WebView, because the app cannot
 * inject scripts into it or read what is typed, its cookies or its tokens, and Google / Apple sign-in
 * buttons on the page work as the identity providers require. The URL is checked again here, so a
 * page can only open on a host its provider declared. Returns false if nothing was opened.
 */
fun openWebRegistration(context: Context, page: WebRegistration, toolbarColor: Int): Boolean {
    if (!TrustedUrl.isAllowed(page.url, page.trustedHosts)) {
        DiagnosticsLogger.w("Account", "registration page refused: untrusted host")
        return false
    }
    val uri = Uri.parse(page.url)
    val tab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
        .build()
    return try {
        tab.launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        // No browser with Custom Tab support: fall back to any browser.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
