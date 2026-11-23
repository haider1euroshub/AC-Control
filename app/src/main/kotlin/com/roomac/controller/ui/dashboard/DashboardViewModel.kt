package com.roomac.controller.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.formatTemp
import com.roomac.controller.protocol.CommandFeedback
import com.roomac.controller.protocol.DashboardState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUi(
    val conn: DashboardState = DashboardState(),
    val devices: List<PairedDevice> = emptyList(),
    /** Setpoint the user is dialling in but that has not been sent yet. */
    val pendingSetpoint: Double? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = RoomAcApplication.from(application)
    private val coordinator = app.coordinator
    private val store = app.store

    private val pending = MutableStateFlow<Double?>(null)
    private var debounce: Job? = null

    val ui: StateFlow<DashboardUi> = combine(coordinator.state, store.devices, pending) { conn, devices, p ->
        DashboardUi(conn, devices, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            coordinator.feedback.collect { fb ->
                val text = when (fb) {
                    is CommandFeedback.Applied -> "${fb.command.description}: confirmed by the AC"
                    is CommandFeedback.Rejected -> "${fb.command.description}: ${fb.reason}"
                    is CommandFeedback.Failed -> "${fb.command.description} failed. ${fb.error.userMessage} ${fb.error.nextStep}"
                }
                DiagnosticsLogger.i("Command", text)
                _messages.tryEmit(text)
            }
        }
    }

    fun send(command: AcCommand) {
        viewModelScope.launch { coordinator.execute(command) }
    }

    /** Coalesces rapid taps into one setpoint command once the user pauses. */
    fun stepSetpoint(up: Boolean) {
        val conn = ui.value.conn
        val caps = conn.capabilities ?: return
        val current = pending.value ?: conn.status?.targetTemperatureC ?: return
        val next = (current + if (up) caps.temperatureStepC else -caps.temperatureStepC)
            .coerceIn(caps.minTemperatureC, caps.maxTemperatureC)
        if (next == current) {
            _messages.tryEmit("The setpoint range is ${formatTemp(caps.minTemperatureC)}–${formatTemp(caps.maxTemperatureC)}.")
            return
        }
        pending.value = next
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(700)
            val target = pending.value ?: return@launch
            coordinator.execute(AcCommand.SetTargetTemperature(target))
            pending.value = null // the display now shows whatever the AC reported back
        }
    }

    fun scanAgain() = coordinator.reconnect()

    /** The manufacturer account the selected AC's key comes from, when it needs one. */
    fun accountProviderId(protocolId: String): String? = app.accountFor(protocolId)?.id

    fun selectDevice(deviceId: String) {
        store.setActive(deviceId)
        coordinator.reconnect()
    }
}
