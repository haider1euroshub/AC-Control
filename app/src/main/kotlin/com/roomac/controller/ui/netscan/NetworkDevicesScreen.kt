package com.roomac.controller.ui.netscan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomac.controller.netscan.DeviceType
import com.roomac.controller.netscan.NetworkDevice
import com.roomac.controller.netscan.NetworkDevicesViewModel
import com.roomac.controller.netscan.OuiTable
import com.roomac.controller.netscan.SourceOutcome
import com.roomac.controller.ui.components.Note
import com.roomac.controller.ui.components.SurfaceCard

private fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.ROUTER -> Icons.Filled.Router
    DeviceType.TV -> Icons.Filled.Tv
    DeviceType.MEDIA -> Icons.Filled.Cast
    DeviceType.SPEAKER -> Icons.Filled.Speaker
    DeviceType.PRINTER -> Icons.Filled.Print
    DeviceType.CAMERA -> Icons.Filled.Videocam
    DeviceType.PHONE -> Icons.Filled.Smartphone
    DeviceType.COMPUTER -> Icons.Filled.Computer
    DeviceType.IOT -> Icons.Filled.Home
    DeviceType.UNKNOWN -> Icons.Filled.DeviceUnknown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDevicesScreen(onBack: () -> Unit, onOpenDevice: ((ip: String) -> Unit)? = null, vm: NetworkDevicesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notOnWifi by vm.notOnWifi.collectAsStateWithLifecycle()
    val pull = rememberPullToRefreshState()

    if (pull.isRefreshing) LaunchedEffect(Unit) {
        vm.scan()?.join()
        pull.endRefresh()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Network devices") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(pull.nestedScrollConnection)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = vm::scan, enabled = !state.running, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(if (state.running) "Scanning…" else if (state.finishedAtEpochMs == null) "Scan this Wi-Fi" else "Scan again") }
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (notOnWifi) Note("Join a Wi-Fi network first; the scanner only looks at the network this phone is on.")
                        summary(state.devices.size, state.running, state.finishedAtEpochMs != null)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(state.devices, key = { it.ip }) { d -> DeviceRow(d, onOpenDevice?.let { open -> { open(d.ip) } }) }
                if (!state.running && state.finishedAtEpochMs != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        state.sources.forEach { (source, outcome) ->
                            if (outcome is SourceOutcome.Failed) Note("${source.label} did not finish (${outcome.message}); results may be incomplete.")
                        }
                        state.notes.forEach { Note(it) }
                        if (OuiTable.DEFAULT.size == 0) Note("This build has no MAC manufacturer list yet, so makers are shown only when a device names itself.")
                        Note("Types are best guesses from what each device advertises. Devices without a network connection, such as a plain fan, never appear.")
                    }
                }
            }
            PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

private fun summary(count: Int, running: Boolean, finished: Boolean): String? = when {
    running -> "Found $count so far"
    finished -> if (count == 1) "1 device found" else "$count devices found"
    else -> null
}

@Composable
private fun DeviceRow(d: NetworkDevice, onClick: (() -> Unit)?) {
    SurfaceCard(modifier = if (onClick != null) Modifier.clickable(onClickLabel = "Open controls", onClick = onClick) else Modifier, contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(d.type.icon(), contentDescription = d.type.label, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val subtitle = listOfNotNull(
                    d.type.label.takeIf { d.name != null || d.hostname != null },
                    d.ip,
                    d.vendor ?: if (d.randomizedMac) "private MAC" else null
                ).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOfNotNull(d.model, d.hostname?.takeIf { d.name != null && it != d.name }, d.mac).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.typeReason?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
