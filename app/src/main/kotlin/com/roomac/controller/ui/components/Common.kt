package com.roomac.controller.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.ConnectionPhase
import com.roomac.controller.ui.theme.Motion
import com.roomac.controller.ui.theme.RoomAcTheme

/** A titled card section: white (or dark) surface, hairline border, 20 dp corners. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SurfaceCard(modifier) {
        Text(
            title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

/** The base card every screen uses, so elevation, border and radius stay identical everywhere. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, contentPadding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val reduced = RoomAcTheme.reducedMotion
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(contentPadding.dp).animateContentSize(Motion.medium(reduced)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

enum class Tone { GOOD, BUSY, WARN, BAD, NEUTRAL }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val s = RoomAcTheme.status
    return when (tone) {
        Tone.GOOD -> s.success to s.onSuccess
        Tone.BUSY -> s.info to s.onInfo
        Tone.WARN -> s.warning to s.onWarning
        Tone.BAD -> s.error to s.onError
        Tone.NEUTRAL -> s.neutral to s.onNeutral
    }
}

@Composable
fun toneDot(tone: Tone): Color {
    val s = RoomAcTheme.status
    return when (tone) {
        Tone.GOOD -> s.successDot
        Tone.BUSY -> s.busyDot
        Tone.WARN -> s.warningDot
        Tone.BAD -> s.errorDot
        Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
}

fun ConnectionPhase.tone(): Tone = when (this) {
    ConnectionPhase.CONNECTED -> Tone.GOOD
    ConnectionPhase.CHECKING_NETWORK, ConnectionPhase.SEARCHING, ConnectionPhase.AC_FOUND,
    ConnectionPhase.AUTHENTICATING, ConnectionPhase.RECONNECTING, ConnectionPhase.RENEWING_KEY -> Tone.BUSY
    ConnectionPhase.UNSUPPORTED, ConnectionPhase.NEEDS_ACCOUNT -> Tone.WARN
    ConnectionPhase.NO_DEVICE -> Tone.NEUTRAL
    ConnectionPhase.WIFI_UNAVAILABLE, ConnectionPhase.OFFLINE, ConnectionPhase.AUTH_FAILED, ConnectionPhase.ERROR -> Tone.BAD
}

fun ConnectionPhase.icon(): ImageVector = when (this) {
    ConnectionPhase.CONNECTED -> Icons.Filled.CheckCircle
    ConnectionPhase.WIFI_UNAVAILABLE -> Icons.Filled.WifiOff
    ConnectionPhase.SEARCHING, ConnectionPhase.AC_FOUND, ConnectionPhase.CHECKING_NETWORK -> Icons.Filled.Search
    ConnectionPhase.AUTHENTICATING -> Icons.Filled.Lock
    ConnectionPhase.NEEDS_ACCOUNT -> Icons.Filled.AccountCircle
    ConnectionPhase.AUTH_FAILED -> Icons.Filled.Key
    ConnectionPhase.RECONNECTING, ConnectionPhase.RENEWING_KEY -> Icons.Filled.SyncProblem
    ConnectionPhase.OFFLINE -> Icons.Filled.CloudOff
    ConnectionPhase.UNSUPPORTED, ConnectionPhase.NO_DEVICE -> Icons.Filled.Info
    ConnectionPhase.ERROR -> Icons.Filled.ErrorOutline
}

val ConnectionPhase.isWorking: Boolean
    get() = tone() == Tone.BUSY

/** The connection state banner: what is happening, and what to do when something is wrong. */
@Composable
fun PhaseBanner(phase: ConnectionPhase, detail: String?, error: AcError?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val reduced = RoomAcTheme.reducedMotion
    val (bgTarget, fgTarget) = toneColors(phase.tone())
    val bg by animateColorAsState(bgTarget, Motion.medium(reduced), label = "bannerBg")
    val fg by animateColorAsState(fgTarget, Motion.medium(reduced), label = "bannerFg")
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(Motion.medium(reduced)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = phase.isWorking to phase.icon(),
                    transitionSpec = { fadeIn(Motion.short(reduced)) togetherWith fadeOut(Motion.short(reduced)) },
                    label = "bannerIcon"
                ) { (working, icon) ->
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = fg)
                        else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(phase.label, style = MaterialTheme.typography.titleSmall)
                    if (!detail.isNullOrEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (error != null) {
                Text(error.userMessage, style = MaterialTheme.typography.bodyMedium)
                Text(error.nextStep, style = MaterialTheme.typography.bodySmall)
            }
            action?.invoke()
        }
    }
}

/** A small coloured dot plus label, for connection status in lists. */
@Composable
fun StatusLabel(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(toneDot(tone)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun relativeTime(epochMs: Long, now: Long): String {
    if (epochMs <= 0) return "never"
    val s = ((now - epochMs) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
