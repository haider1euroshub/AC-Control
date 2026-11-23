package com.roomac.controller.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.formatTemp
import com.roomac.controller.ui.theme.Motion
import com.roomac.controller.ui.theme.RoomAcTheme

fun AcMode.icon(): ImageVector = when (this) {
    AcMode.AUTO -> Icons.Filled.Autorenew
    AcMode.COOL -> Icons.Filled.AcUnit
    AcMode.DRY -> Icons.Filled.WaterDrop
    AcMode.HEAT -> Icons.Filled.WbSunny
    AcMode.FAN -> Icons.Filled.Air
}

/**
 * The main control panel: power, the target temperature in large type with big −/+ buttons, and the
 * room reading. The status line always says whether a value is still being sent or has been
 * confirmed by the AC, so the screen never pretends a change happened before the AC reports it.
 */
@Composable
fun ClimatePanel(
    status: AcStatus,
    caps: AcCapabilities,
    pendingSetpoint: Double?,
    inFlight: AcCommand?,
    enabled: Boolean,
    connected: Boolean,
    onTogglePower: () -> Unit,
    onStep: (up: Boolean) -> Unit
) {
    val reduced = RoomAcTheme.reducedMotion
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (status.power) cs.primaryContainer else cs.surfaceContainerLow, Motion.medium(reduced), label = "panel")
    val content by animateColorAsState(if (status.power) cs.onPrimaryContainer else cs.onSurface, Motion.medium(reduced), label = "panelText")
    // Temperature is dimmed while off: it is still the AC's setting, but not being used.
    val tempAlpha by animateFloatAsState(if (status.power) 1f else 0.55f, Motion.medium(reduced), label = "tempAlpha")

    Surface(
        color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge,
        border = if (status.power) null else BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (status.power) "On" else "Off", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        (status.mode?.label ?: "Mode code ${status.rawModeCode}") +
                            (status.indoorTemperatureC?.let { " · Room ${formatTemp(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (status.selfCleaning == true) Text("Self-clean cycle running", style = MaterialTheme.typography.bodyMedium)
                }
                PowerButton(status.power, busy = inFlight is AcCommand.SetPower, enabled = enabled, onClick = onTogglePower)
            }

            val shown = pendingSetpoint ?: status.targetTemperatureC
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StepButton(Icons.Filled.Remove, "Lower target temperature", enabled && shown > caps.minTemperatureC) { onStep(false) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).alpha(tempAlpha).semantics { contentDescription = "Target temperature ${formatTemp(shown)}" }
                ) {
                    AnimatedContent(
                        targetState = shown,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInVertically(Motion.short(reduced)) { h -> dir * h / 3 } + fadeIn(Motion.short(reduced))) togetherWith
                                (slideOutVertically(Motion.short(reduced)) { h -> -dir * h / 3 } + fadeOut(Motion.short(reduced)))
                        },
                        label = "setpoint"
                    ) { t ->
                        Text(formatTemp(t), style = MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, lineHeight = 84.sp), textAlign = TextAlign.Center)
                    }
                    Text(
                        when {
                            inFlight is AcCommand.SetTargetTemperature -> "Sending to the AC…"
                            pendingSetpoint != null -> "Will send when you stop tapping"
                            else -> "Target temperature"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                StepButton(Icons.Filled.Add, "Raise target temperature", enabled && shown < caps.maxTemperatureC) { onStep(true) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "Connected directly over your Wi-Fi" else "Last state the AC reported",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.weight(1f))
                status.outdoorTemperatureC?.let { Text("Outdoor ${formatTemp(it)}", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(72.dp).semantics {
            contentDescription = if (on) "Turn air conditioner off" else "Turn air conditioner on"
            stateDescription = if (on) "On" else "Off"
        },
        shape = CircleShape,
        colors = if (on) IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary, contentColor = cs.onPrimary)
        else IconButtonDefaults.filledIconButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface)
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = if (on) cs.onPrimary else cs.primary)
        else Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f)),
        modifier = Modifier.size(64.dp)
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(30.dp)) }
}

/**
 * A row of large, labelled options (icon above text), for mode and fan speed. Every option keeps its
 * text label, so meaning never depends on the icon alone.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    icon: ((T) -> ImageVector)? = null,
    enabled: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    val reduced = RoomAcTheme.reducedMotion
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val isEnabled = enabled(option)
            val bg by animateColorAsState(if (isSelected) cs.primary else cs.surfaceContainer, Motion.short(reduced), label = "choiceBg")
            val fg by animateColorAsState(if (isSelected) cs.onPrimary else cs.onSurface, Motion.short(reduced), label = "choiceFg")
            Surface(
                color = bg, contentColor = fg, shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f).heightIn(min = if (icon != null) 72.dp else 52.dp)
                    .alpha(if (isEnabled) 1f else 0.4f)
                    .selectable(selected = isSelected, enabled = isEnabled, role = Role.RadioButton) { if (!isSelected) onSelect(option) }
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    if (icon != null) {
                        Icon(icon(option), contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                    }
                    Box(contentAlignment = Alignment.Center) {
                        Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
