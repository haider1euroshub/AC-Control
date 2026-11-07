package com.roomac.controller.domain.usecase

import com.roomac.controller.domain.model.*
import com.roomac.controller.domain.repository.AcRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

class AcController(
    private val repository: AcRepository,
    private val coroutineScope: CoroutineScope,
    private val debounceDelayMs: Long = 350L
) {
    private val _pendingTemperature = MutableStateFlow<Double?>(null)
    val pendingTemperature: StateFlow<Double?> = _pendingTemperature.asStateFlow()

    private val _disabledReason = MutableStateFlow<String?>(null)
    val disabledReason: StateFlow<String?> = _disabledReason.asStateFlow()

    private var debounceTimerJob: Job? = null
    private var inFlightSendJob: Job? = null

    fun onPowerClicked(currentState: AcState) {
        // Toggle power if known, or default to ON if state is unknown
        val currentPower = currentState.power.value
        val next = if (currentPower == PowerState.ON) PowerState.OFF else PowerState.ON
        coroutineScope.launch {
            repository.setPower(next)
        }
    }

    fun onModeSelected(mode: AcMode, currentState: AcState, capabilities: AcCapabilities) {
        if (mode == AcMode.HEAT && !capabilities.supportsHeating) {
            _disabledReason.value = "Heating mode is disabled (AC is cooling-only or heating unsupported)"
            return
        }
        if (!currentState.power.isKnown && currentState.power.value != PowerState.ON) {
            _disabledReason.value = "Cannot change mode: AC power state is unknown. Refresh status or turn ON first."
            return
        }
        coroutineScope.launch {
            repository.setMode(mode)
        }
    }

    fun onTemperatureStep(increase: Boolean, currentState: AcState, capabilities: AcCapabilities) {
        val currentConfirmed = currentState.targetTemperature.value
        if (currentConfirmed == null && _pendingTemperature.value == null) {
            _disabledReason.value = "Target temperature is unknown. Please refresh status or assert baseline in Setup."
            return
        }
        val step = if (capabilities.temperatureStep <= 0.0) 1.0 else capabilities.temperatureStep
        val baseTemp = _pendingTemperature.value ?: currentConfirmed ?: 24.0
        val rawNext = if (increase) baseTemp + step else baseTemp - step
        val clamped = clampTemperature(rawNext, capabilities)

        _pendingTemperature.value = clamped

        // Only the debounce delay timer is cancelled. Any in-flight network send continues undisturbed.
        debounceTimerJob?.cancel()
        debounceTimerJob = coroutineScope.launch {
            delay(debounceDelayMs)
            val targetToSend = _pendingTemperature.value ?: clamped

            // Wait for any existing in-flight send to finish before dispatching the new coalesced target
            inFlightSendJob?.join()
            inFlightSendJob = coroutineScope.launch {
                val result = repository.setTargetTemperature(targetToSend)
                if (result is CommandResult.Failed) {
                    // Revert pending display to last confirmed temperature on failure
                    _pendingTemperature.value = currentState.targetTemperature.value
                    _disabledReason.value = result.error.userMessage
                } else {
                    _pendingTemperature.value = null
                }
            }
        }
    }

    fun onVerticalSwingSelected(position: SwingPosition, capabilities: AcCapabilities) {
        if (position !in capabilities.supportedVerticalSwings) {
            _disabledReason.value = "Selected vertical swing position is not supported by capabilities"
            return
        }
        coroutineScope.launch {
            repository.setVerticalSwing(position)
        }
    }

    fun onHorizontalSwingSelected(position: SwingPosition, capabilities: AcCapabilities) {
        if (position !in capabilities.supportedHorizontalSwings) {
            _disabledReason.value = "Horizontal swing is not supported by capabilities"
            return
        }
        coroutineScope.launch {
            repository.setHorizontalSwing(position)
        }
    }

    fun refresh() {
        coroutineScope.launch {
            repository.refreshState()
        }
    }

    fun clearDisabledReason() {
        _disabledReason.value = null
    }

    companion object {
        fun clampTemperature(target: Double, capabilities: AcCapabilities): Double {
            val step = if (capabilities.temperatureStep <= 0.0) 1.0 else capabilities.temperatureStep
            val stepped = round(target / step) * step
            return stepped.coerceIn(capabilities.minTemperature, capabilities.maxTemperature)
        }
    }
}
