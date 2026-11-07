package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*
import kotlinx.coroutines.flow.StateFlow

enum class DriverVerificationStatus(val label: String) {
    VERIFIED_ON_REAL_AC("Verified on Hardware"),
    UNVERIFIED_FROM_DOCUMENTATION("Unverified, implemented from documentation"),
    STUB_MISSING_INFO("Stub, missing hardware information")
}

data class DeviceConfig(
    val protocolId: String = "",
    val host: String = "",
    val port: Int = 56800,
    val macAddress: String = "",
    val localKeyHex: String = "",
    val customCapabilities: AcCapabilities? = null,
    val ownerBaseline: OwnerAssertedBaseline? = null,
    val codecVariant: String = "STANDARD_UNVERIFIED"
) {
    val isConfigured: Boolean
        get() = protocolId.isNotBlank() && host.isNotBlank()
}

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val host: String, val port: Int) : ConnectionStatus()
    data class Failed(val error: AcError) : ConnectionStatus()
}

/**
 * Diagnostics event sink interface implemented in :app and passed down to protocol and driver layers.
 */
interface DiagnosticsSink {
    fun logEvent(tag: String, message: String)
    fun logRawTx(tag: String, packetHex: String)
    fun logRawRx(tag: String, packetHex: String)
    fun logError(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpDiagnosticsSink : DiagnosticsSink {
    override fun logEvent(tag: String, message: String) {}
    override fun logRawTx(tag: String, packetHex: String) {}
    override fun logRawRx(tag: String, packetHex: String) {}
    override fun logError(tag: String, message: String, throwable: Throwable?) {}
}

/**
 * Structured result of an active connection probe.
 */
sealed class ProbeResult {
    data class RefusedOrUnreachable(val error: String) : ProbeResult()
    data class ConnectedSilent(val host: String, val port: Int, val timeoutMs: Long) : ProbeResult()
    data class ConnectedResponded(val host: String, val port: Int, val rawHex: String, val byteCount: Int) : ProbeResult()
}

interface AcDriver {
    val id: String
    val displayName: String
    val verificationStatus: DriverVerificationStatus
    val description: String

    val connectionStatus: StateFlow<ConnectionStatus>
    val stateFlow: StateFlow<AcState>
    val capabilitiesFlow: StateFlow<AcCapabilities>

    suspend fun connect(config: DeviceConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun executeCommand(command: AcCommand): CommandResult
    suspend fun testConnection(config: DeviceConfig): Result<String>
    suspend fun probeConnection(config: DeviceConfig, timeoutMs: Long = 3000L): ProbeResult
    fun setDiagnosticsSink(sink: DiagnosticsSink)
}

class DriverRegistry(
    private val drivers: List<AcDriver>
) {
    fun getAllDrivers(): List<AcDriver> = drivers

    fun getDriver(id: String): AcDriver? = drivers.find { it.id == id }

    fun getAvailableDriverIds(): List<String> = drivers.map { it.id }
}
