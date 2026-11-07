package com.roomac.controller.simulator

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ⚠️ SIMULATION ONLY — Debug builds only.
 * This driver does NOT communicate with any real device.
 * It is physically absent from release builds.
 */
class SimulatedAcDriver : AcDriver {

    override val id: String = "simulator"
    override val displayName: String = "⚠ SIMULATOR (Debug Only - No Real Hardware)"
    override val verificationStatus: DriverVerificationStatus = DriverVerificationStatus.STUB_MISSING_INFO
    override val description: String = "Virtual in-memory AC simulator for UI and interaction testing without hardware."

    private var diagnosticsSink: DiagnosticsSink = NoOpDiagnosticsSink

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _state = MutableStateFlow(
        AcState(
            power = ValueWithProvenance.confirmed(PowerState.OFF),
            mode = ValueWithProvenance.confirmed(AcMode.COOL),
            targetTemperature = ValueWithProvenance.confirmed(24.0),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = SwingPosition.OFF, horizontal = SwingPosition.OFF)),
            isReachable = true
        )
    )
    override val stateFlow: StateFlow<AcState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(
        AcCapabilities(
            supportsHeating = true,
            minTemperature = 16.0,
            maxTemperature = 30.0,
            temperatureStep = 1.0,
            temperatureUnit = TemperatureUnit.CELSIUS,
            supportedVerticalSwings = setOf(
                SwingPosition.OFF,
                SwingPosition.OSCILLATE,
                SwingPosition.POSITION_1,
                SwingPosition.POSITION_2,
                SwingPosition.POSITION_3,
                SwingPosition.POSITION_4,
                SwingPosition.POSITION_5
            ),
            supportedHorizontalSwings = setOf(SwingPosition.OFF, SwingPosition.OSCILLATE),
            source = CapabilitySource.DOCUMENTED_BY_DRIVER,
            areCapabilitiesKnown = true
        )
    )
    override val capabilitiesFlow: StateFlow<AcCapabilities> = _capabilities.asStateFlow()

    override fun setDiagnosticsSink(sink: DiagnosticsSink) {
        diagnosticsSink = sink
    }

    override suspend fun connect(config: DeviceConfig): Result<Unit> {
        _connectionStatus.value = ConnectionStatus.Connected(config.host, config.port)
        _state.value = _state.value.copy(isReachable = true)
        diagnosticsSink.logEvent("SimulatedAcDriver", "SimulatedAcDriver connected to virtual host ${config.host}:${config.port}")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _connectionStatus.value = ConnectionStatus.Disconnected
        _state.value = _state.value.copy(isReachable = false)
        diagnosticsSink.logEvent("SimulatedAcDriver", "SimulatedAcDriver disconnected")
    }

    override suspend fun executeCommand(command: AcCommand): CommandResult {
        val current = _state.value
        val updated = when (command) {
            is AcCommand.SetPower -> current.copy(
                power = ValueWithProvenance.confirmed(command.power)
            )
            is AcCommand.SetMode -> current.copy(
                mode = ValueWithProvenance.confirmed(command.mode)
            )
            is AcCommand.SetTargetTemperature -> current.copy(
                targetTemperature = ValueWithProvenance.confirmed(command.target)
            )
            is AcCommand.SetVerticalSwing -> current.copy(
                swing = ValueWithProvenance.confirmed(
                    (current.swing.value ?: SwingState()).copy(vertical = command.position)
                )
            )
            is AcCommand.SetHorizontalSwing -> current.copy(
                swing = ValueWithProvenance.confirmed(
                    (current.swing.value ?: SwingState()).copy(horizontal = command.position)
                )
            )
            is AcCommand.RefreshState -> current
        }
        _state.value = updated
        diagnosticsSink.logEvent("SimulatedAcDriver", "SimulatedAcDriver executed $command -> state updated")
        return CommandResult.Confirmed(updated)
    }

    override suspend fun testConnection(config: DeviceConfig): Result<String> {
        return Result.success("Simulated connection successful (virtual AC)")
    }

    override suspend fun probeConnection(config: DeviceConfig, timeoutMs: Long): ProbeResult {
        return ProbeResult.ConnectedResponded(config.host, config.port, "SIMULATOR_VIRTUAL_RESPONSE", 26)
    }
}
