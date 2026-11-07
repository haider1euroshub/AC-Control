package com.roomac.controller.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.ConnectionStatus
import com.roomac.controller.ui.theme.RoomAcTheme

private val sampleCapabilities = AcCapabilities(
    supportsHeating = true,
    minTemperature = 16.0,
    maxTemperature = 30.0,
    temperatureStep = 1.0,
    temperatureUnit = TemperatureUnit.CELSIUS,
    supportedVerticalSwings = setOf(
        SwingPosition.OFF,
        SwingPosition.OSCILLATE,
        SwingPosition.POSITION_1,
        SwingPosition.POSITION_3,
        SwingPosition.POSITION_5
    ),
    supportedHorizontalSwings = setOf(SwingPosition.OFF, SwingPosition.OSCILLATE),
    source = CapabilitySource.DOCUMENTED_BY_DRIVER,
    areCapabilitiesKnown = true
)

@Preview(name = "1. Not Configured", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewNotConfigured() {
    RoomAcTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            UnconfiguredCard(onNavigateToSetup = {})
        }
    }
}

@Preview(name = "2. Connected - Cooling", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewConnectedCooling() {
    val state = MainUiState(
        isConfigured = true,
        connectionStatus = ConnectionStatus.Connected("192.168.1.105", 56800),
        acState = AcState(
            power = ValueWithProvenance.confirmed(PowerState.ON),
            mode = ValueWithProvenance.confirmed(AcMode.COOL),
            targetTemperature = ValueWithProvenance.confirmed(24.0),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = SwingPosition.OSCILLATE)),
            isReachable = true
        ),
        capabilities = sampleCapabilities
    )

    RoomAcTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ActiveControlsView(
                uiState = state,
                onPowerClicked = {},
                onModeSelected = {},
                onTemperatureStep = {},
                onVerticalSwingSelected = {},
                onHorizontalSwingSelected = {},
                onRefresh = {},
                onNavigateToSetup = {}
            )
        }
    }
}

@Preview(name = "3. Connected - Heating", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewConnectedHeating() {
    val state = MainUiState(
        isConfigured = true,
        connectionStatus = ConnectionStatus.Connected("192.168.1.105", 56800),
        acState = AcState(
            power = ValueWithProvenance.confirmed(PowerState.ON),
            mode = ValueWithProvenance.confirmed(AcMode.HEAT),
            targetTemperature = ValueWithProvenance.confirmed(28.0),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = SwingPosition.POSITION_3)),
            isReachable = true
        ),
        capabilities = sampleCapabilities
    )

    RoomAcTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ActiveControlsView(
                uiState = state,
                onPowerClicked = {},
                onModeSelected = {},
                onTemperatureStep = {},
                onVerticalSwingSelected = {},
                onHorizontalSwingSelected = {},
                onRefresh = {},
                onNavigateToSetup = {}
            )
        }
    }
}

@Preview(name = "4. Sent Unconfirmed State", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewSentUnconfirmed() {
    val state = MainUiState(
        isConfigured = true,
        connectionStatus = ConnectionStatus.Connected("192.168.1.105", 56800),
        acState = AcState(
            power = ValueWithProvenance.optimistic(PowerState.ON),
            mode = ValueWithProvenance.confirmed(AcMode.COOL),
            targetTemperature = ValueWithProvenance.optimistic(22.0),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = SwingPosition.OFF)),
            isReachable = true
        ),
        capabilities = sampleCapabilities,
        commandLifecycle = CommandLifecycle.SentUnconfirmed(AcCommand.SetTargetTemperature(22.0))
    )

    RoomAcTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ActiveControlsView(
                uiState = state,
                onPowerClicked = {},
                onModeSelected = {},
                onTemperatureStep = {},
                onVerticalSwingSelected = {},
                onHorizontalSwingSelected = {},
                onRefresh = {},
                onNavigateToSetup = {}
            )
        }
    }
}
