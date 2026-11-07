package com.roomac.controller.driver.hon

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HonDriver : AcDriver {

    override val id: String = "haier-hon"
    override val displayName: String = "Haier hOn (Stub / Cloud-Only Protocol)"
    override val verificationStatus: DriverVerificationStatus = DriverVerificationStatus.STUB_MISSING_INFO
    override val description: String = "Haier hOn ecosystem uses a cloud-bound REST/MQTT protocol. Local direct LAN control without Haier cloud servers is currently undocumented and not implemented."

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _state = MutableStateFlow(AcState(isReachable = false))
    override val stateFlow: StateFlow<AcState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(AcCapabilities(areCapabilitiesKnown = false))
    override val capabilitiesFlow: StateFlow<AcCapabilities> = _capabilities.asStateFlow()

    override fun setDiagnosticsSink(sink: DiagnosticsSink) {}

    override suspend fun connect(config: DeviceConfig): Result<Unit> {
        val err = AcError.ProtocolNotVerified("hOn local protocol is undocumented; cloud-free local control is not available")
        _connectionStatus.value = ConnectionStatus.Failed(err)
        return Result.failure(Exception(err.userMessage))
    }

    override suspend fun disconnect() {
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    override suspend fun executeCommand(command: AcCommand): CommandResult {
        return CommandResult.Failed(
            AcError.ProtocolNotVerified("Haier hOn local protocol is not implemented")
        )
    }

    override suspend fun testConnection(config: DeviceConfig): Result<String> {
        return Result.failure(
            Exception("Haier hOn local protocol is undocumented and cannot be tested locally without cloud servers.")
        )
    }

    override suspend fun probeConnection(config: DeviceConfig, timeoutMs: Long): ProbeResult {
        return ProbeResult.RefusedOrUnreachable("Haier hOn local protocol is undocumented and cannot be tested locally without cloud servers.")
    }
}
