package com.roomac.controller

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomac.controller.storage.ThemeMode
import com.roomac.controller.ui.AppRoot
import com.roomac.controller.ui.theme.RoomAcTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDebugProbeHost(intent)
        setContent {
            val mode by RoomAcApplication.from(application).store.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            RoomAcTheme(darkTheme = dark) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDebugProbeHost(intent)
    }

    private fun readDebugProbeHost(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val host = intent?.getStringExtra("roomac.debug.probeHost") ?: return
        if (com.roomac.controller.protocol.SubnetMath.toInt(host) == null) return
        RoomAcApplication.from(application).debugProbeHost = host
        com.roomac.controller.diagnostics.DiagnosticsLogger.i("RoomAC", "debug: searches will also ask $host directly")
    }

    override fun onResume() {
        super.onResume()
        // Network or AC state may have changed while away: re-check now rather than at the next poll.
        RoomAcApplication.from(application).coordinator.wakeUp()
    }
}
