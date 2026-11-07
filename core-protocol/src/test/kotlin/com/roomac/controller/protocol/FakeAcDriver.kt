package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAcDriver(
    override val id: String = "fake-driver",
    override val displayName: String = "Fake AC Driver",
    override val verificationStatus: DriverVerificationStatus = DriverVerificationStatus.UNVERIFIED_FROM_DOCUMENTATION,
    override val description: String = "In-memory driver for unit testing"
) : AcDriver {

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _state = MutableStateFlow(AcState(isReachable = false))
    override val stateFlow: StateFlow<AcState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(
        AcCapabilities(
            supportsHeating = true,
            minTemperature = 16.0,
            maxTemperature = 30.0,
            temperatureStep = 1.0,
            areCapabilitiesKnown = true
        )
    )
    override val capabilitiesFlow: StateFlow<AcCapabilities> = _capabilities.asStateFlow()

    var shouldFailCommands = false
    var commandDelayMs = 0L

    override fun setDiagnosticsSink(sink: DiagnosticsSink) {}

    override suspend fun connect(config: DeviceConfig): Result<Unit> {
        _connectionStatus.value = ConnectionStatus.Connected(config.host, config.port)
        _state.value = _state.value.copy(
            isReachable = true,
            power = ValueWithProvenance.confirmed(PowerState.ON),
            mode = ValueWithProvenance.confirmed(AcMode.COOL),
            targetTemperature = ValueWithProvenance.confirmed(24.0)
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _connectionStatus.value = ConnectionStatus.Disconnected
        _state.value = _state.value.copy(isReachable = false)
    }

    override suspend fun executeCommand(command: AcCommand): CommandResult {
        if (shouldFailCommands) {
            return CommandResult.Failed(AcError.GeneralError("Simulated driver failure"))
        }
        val cur = _state.value
        val updated = when (command) {
            is AcCommand.SetPower -> cur.copy(power = ValueWithProvenance.confirmed(command.power))
            is AcCommand.SetMode -> cur.copy(mode = ValueWithProvenance.confirmed(command.mode))
            is AcCommand.SetTargetTemperature -> cur.copy(targetTemperature = ValueWithProvenance.confirmed(command.target))
            is AcCommand.SetVerticalSwing -> cur.copy(swing = ValueWithProvenance.confirmed(cur.swing.value?.copy(vertical = command.position) ?: SwingState(vertical = command.position)))
            is AcCommand.SetHorizontalSwing -> cur.copy(swing = ValueWithProvenance.confirmed(cur.swing.value?.copy(horizontal = command.position) ?: SwingState(horizontal = command.position)))
            is AcCommand.RefreshState -> cur
        }
        _state.value = updated
        return CommandResult.Confirmed(updated)
    }

    override suspend fun testConnection(config: DeviceConfig): Result<String> {
        return Result.success("Connected to fake driver")
    }

    override suspend fun probeConnection(config: DeviceConfig, timeoutMs: Long): ProbeResult {
        return ProbeResult.ConnectedResponded(config.host, config.port, "55aa", 2)
    }

    /** Test helper: directly set driver state to simulate any AC state without connecting. */
    fun setTestState(state: AcState) {
        _state.value = state
    }

    /** Test helper: set connection status directly. */
    fun setTestConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }
}
