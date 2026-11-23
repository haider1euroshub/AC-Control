package com.roomac.controller.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.ui.components.InfoRow
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.Section

/** Technical information for support. Reached from Settings, never from the main screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as RoomAcApplication
    val logs by DiagnosticsLogger.logs.collectAsStateWithLifecycle()
    val conn by app.coordinator.state.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(title = { Text("Troubleshooting") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Note("This page is for support. If something is not working, tap \"Share log\" and send it to whoever helps you with the app.")
            Section("Connection") {
                InfoRow("State", conn.phase.label)
                InfoRow("Device", conn.device?.let { "${it.name} (${it.deviceId})" } ?: "")
                InfoRow("Address", conn.host ?: "")
                InfoRow("Status report", conn.status?.let { "${it.reportLength} bytes" } ?: "")
                InfoRow("Protocol", conn.device?.let { app.registry.forId(it.protocolId)?.descriptor?.displayName } ?: "")
                InfoRow("Cloud use during control", "None")
            }
            Section("Log") {
                Note("Keys, passwords and tokens are removed from the log before it is stored or shared.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Room AC diagnostics")
                            putExtra(Intent.EXTRA_TEXT, DiagnosticsLogger.getExportableLogText())
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                    }) { Text("Share log") }
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { DiagnosticsLogger.clear() }) { Text("Clear") }
                }
                if (logs.isEmpty()) Text("No entries yet.")
                logs.takeLast(120).reversed().forEach {
                    Text("${it.formattedTimestamp.substring(11)} ${it.tag}: ${it.message}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}
