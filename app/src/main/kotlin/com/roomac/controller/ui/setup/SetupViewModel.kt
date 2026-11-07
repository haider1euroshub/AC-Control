package com.roomac.controller.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.DeviceConfig
import com.roomac.controller.protocol.DriverVerificationStatus
import com.roomac.controller.protocol.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriverItem(
    val id: String,
    val displayName: String,
    val verificationStatus: DriverVerificationStatus,
    val description: String
)

data class SetupUiState(
    val availableDrivers: List<DriverItem> = emptyList(),
    val selectedProtocolId: String = "",
    val host: String = "",
    val port: String = "56800",
    val macAddress: String = "",
    val localKeyHex: String = "",
    val hasCustomCapabilities: Boolean = false,
    val minTemp: String = "16.0",
    val maxTemp: String = "30.0",
    val tempStep: String = "1.0",
    val supportsHeating: Boolean = false,
    // Bring-up baseline
    val hasOwnerBaseline: Boolean = false,
    val baselinePower: PowerState = PowerState.ON,
    val baselineMode: AcMode = AcMode.COOL,
    val baselineTemp: String = "24.0",
    // Connection probe
    val isProbing: Boolean = false,
    val probeResult: String? = null,
    val diagnosticsLog: String = ""
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = RoomAcApplication.from(application)
    private val repository = app.repository
    private val configStorage = app.configStorage

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        loadCurrentSettings()
        refreshDiagnostics()
    }

    private fun loadCurrentSettings() {
        val drivers = app.driverRegistry.getAllDrivers().map {
            DriverItem(
                id = it.id,
                displayName = it.displayName,
                verificationStatus = it.verificationStatus,
                description = it.description
            )
        }

        viewModelScope.launch {
            val saved = configStorage.loadConfiguration()
            val defaultProtocol = if (saved.protocolId.isNotBlank()) {
                saved.protocolId
            } else {
                drivers.firstOrNull()?.id ?: ""
            }

            val customCaps = saved.customCapabilities
            val baseline = saved.ownerBaseline

            _uiState.update {
                it.copy(
                    availableDrivers = drivers,
                    selectedProtocolId = defaultProtocol,
                    host = saved.host,
                    port = saved.port.toString(),
                    macAddress = saved.macAddress,
                    localKeyHex = saved.localKeyHex,
                    hasCustomCapabilities = customCaps != null,
                    minTemp = customCaps?.minTemperature?.toString() ?: "16.0",
                    maxTemp = customCaps?.maxTemperature?.toString() ?: "30.0",
                    tempStep = customCaps?.temperatureStep?.toString() ?: "1.0",
                    supportsHeating = customCaps?.supportsHeating ?: false,
                    hasOwnerBaseline = baseline?.isEnabled ?: false,
                    baselinePower = baseline?.power ?: PowerState.ON,
                    baselineMode = baseline?.mode ?: AcMode.COOL,
                    baselineTemp = baseline?.targetTemperature?.toString() ?: "24.0"
                )
            }
        }
    }

    fun onProtocolSelected(id: String) {
        _uiState.update { it.copy(selectedProtocolId = id, probeResult = null) }
    }

    fun onHostChanged(host: String) {
        _uiState.update { it.copy(host = host.trim()) }
    }

    fun onPortChanged(port: String) {
        _uiState.update { it.copy(port = port.trim()) }
    }

    fun onMacChanged(mac: String) {
        _uiState.update { it.copy(macAddress = mac.trim()) }
    }

    fun onKeyChanged(key: String) {
        _uiState.update { it.copy(localKeyHex = key.trim()) }
    }

    fun onHeatingToggled(enabled: Boolean) {
        _uiState.update { it.copy(supportsHeating = enabled) }
    }

    fun onBaselineToggled(enabled: Boolean) {
        _uiState.update { it.copy(hasOwnerBaseline = enabled) }
    }

    fun probeConnection() {
        val s = _uiState.value
        val portInt = s.port.toIntOrNull() ?: 56800
        val driver = app.driverRegistry.getDriver(s.selectedProtocolId)
        if (driver == null || s.host.isBlank()) {
            _uiState.update { it.copy(probeResult = "Please enter a valid IP address and select a protocol.") }
            return
        }

        val config = DeviceConfig(
            protocolId = s.selectedProtocolId,
            host = s.host,
            port = portInt,
            macAddress = s.macAddress,
            localKeyHex = s.localKeyHex
        )

        _uiState.update { it.copy(isProbing = true, probeResult = null) }

        viewModelScope.launch {
            val result = driver.probeConnection(config, 3000L)
            val msg = when (result) {
                is ProbeResult.ConnectedResponded -> "Connected to ${result.host}:${result.port} and received ${result.byteCount} bytes:\n${result.rawHex}"
                is ProbeResult.ConnectedSilent -> "Connected to ${result.host}:${result.port}, but AC remained silent within ${result.timeoutMs}ms."
                is ProbeResult.RefusedOrUnreachable -> "Connection failed: ${result.error}"
            }
            _uiState.update { it.copy(isProbing = false, probeResult = msg) }
            refreshDiagnostics()
        }
    }

    fun saveSettings(onSuccess: () -> Unit) {
        val s = _uiState.value
        val portInt = s.port.toIntOrNull() ?: 56800

        val customCaps = AcCapabilities(
            supportsHeating = s.supportsHeating,
            minTemperature = s.minTemp.toDoubleOrNull() ?: 16.0,
            maxTemperature = s.maxTemp.toDoubleOrNull() ?: 30.0,
            temperatureStep = s.tempStep.toDoubleOrNull() ?: 1.0,
            temperatureUnit = TemperatureUnit.CELSIUS,
            source = CapabilitySource.ENTERED_BY_OWNER,
            areCapabilitiesKnown = true
        )

        val baseline = if (s.hasOwnerBaseline) {
            OwnerAssertedBaseline(
                power = s.baselinePower,
                mode = s.baselineMode,
                targetTemperature = s.baselineTemp.toDoubleOrNull() ?: 24.0,
                isEnabled = true
            )
        } else {
            null
        }

        val config = DeviceConfig(
            protocolId = s.selectedProtocolId,
            host = s.host,
            port = portInt,
            macAddress = s.macAddress,
            localKeyHex = s.localKeyHex,
            customCapabilities = customCaps,
            ownerBaseline = baseline
        )

        viewModelScope.launch {
            configStorage.saveConfiguration(config)
            repository.applyConfiguration(config)
            onSuccess()
        }
    }

    fun refreshDiagnostics() {
        _uiState.update { it.copy(diagnosticsLog = DiagnosticsLogger.getExportableLogText()) }
    }
}
