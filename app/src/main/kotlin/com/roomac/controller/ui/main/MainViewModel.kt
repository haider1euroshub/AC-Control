package com.roomac.controller.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.domain.model.*
import com.roomac.controller.domain.usecase.AcController
import com.roomac.controller.protocol.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val isConfigured: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val acState: AcState = AcState(isReachable = false),
    val capabilities: AcCapabilities = AcCapabilities(areCapabilitiesKnown = false),
    val commandLifecycle: CommandLifecycle = CommandLifecycle.Idle,
    val lastError: AcError? = null,
    val pendingTemperature: Double? = null,
    val disabledReason: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = RoomAcApplication.from(application)
    private val repository = app.repository
    private val controller = AcController(repository, viewModelScope)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.activeConfig.collect { config ->
                _uiState.update { it.copy(isConfigured = config.isConfigured) }
            }
        }

        viewModelScope.launch {
            repository.activeDriver.flatMapLatest { driver ->
                driver?.connectionStatus ?: flowOf(ConnectionStatus.Disconnected)
            }.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        viewModelScope.launch {
            repository.stateFlow.collect { state ->
                _uiState.update { it.copy(acState = state) }
            }
        }

        viewModelScope.launch {
            repository.capabilitiesFlow.collect { caps ->
                _uiState.update { it.copy(capabilities = caps) }
            }
        }

        viewModelScope.launch {
            repository.commandLifecycleFlow.collect { lifecycle ->
                _uiState.update { it.copy(commandLifecycle = lifecycle) }
            }
        }

        viewModelScope.launch {
            repository.lastErrorFlow.collect { error ->
                _uiState.update { it.copy(lastError = error) }
            }
        }

        viewModelScope.launch {
            controller.pendingTemperature.collect { pending ->
                _uiState.update { it.copy(pendingTemperature = pending) }
            }
        }

        viewModelScope.launch {
            controller.disabledReason.collect { reason ->
                _uiState.update { it.copy(disabledReason = reason) }
            }
        }
    }

    fun onPowerClicked() {
        controller.onPowerClicked(_uiState.value.acState)
    }

    fun onModeSelected(mode: AcMode) {
        controller.onModeSelected(mode, _uiState.value.acState, _uiState.value.capabilities)
    }

    fun onTemperatureStep(increase: Boolean) {
        controller.onTemperatureStep(increase, _uiState.value.acState, _uiState.value.capabilities)
    }

    fun onVerticalSwingSelected(position: SwingPosition) {
        controller.onVerticalSwingSelected(position, _uiState.value.capabilities)
    }

    fun onHorizontalSwingSelected(position: SwingPosition) {
        controller.onHorizontalSwingSelected(position, _uiState.value.capabilities)
    }

    fun onRefresh() {
        controller.refresh()
    }

    fun clearLastError() {
        _uiState.update { it.copy(lastError = null) }
    }

    fun clearDisabledReason() {
        controller.clearDisabledReason()
    }
}
