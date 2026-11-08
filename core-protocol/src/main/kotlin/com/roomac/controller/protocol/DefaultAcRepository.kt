package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*
import com.roomac.controller.domain.repository.AcRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default implementation of AcRepository.
 * Enforces:
 * 1. Command Safety Gate (Phase 1): commands requiring a full frame are rejected if required fields are unknown.
 * 2. Strict timeout hierarchy: connect (3000ms), read (2000ms), overall command execution (5000ms).
 * 3. Never swallows CancellationException.
 * 4. Merges custom capabilities or owner baseline overrides cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAcRepository(
    private val driverRegistry: DriverRegistry,
    private val coroutineScope: CoroutineScope,
    private val commandTimeoutMs: Long = 5000L
) : AcRepository {

    private val _activeDriver = MutableStateFlow<AcDriver?>(null)
    val activeDriver: StateFlow<AcDriver?> = _activeDriver.asStateFlow()

    private val _commandLifecycle = MutableStateFlow<CommandLifecycle>(CommandLifecycle.Idle)
    override val commandLifecycleFlow: Flow<CommandLifecycle> = _commandLifecycle.asStateFlow()

    private val _lastError = MutableStateFlow<AcError?>(null)
    override val lastErrorFlow: Flow<AcError?> = _lastError.asStateFlow()

    private val _activeConfig = MutableStateFlow(DeviceConfig())
    val activeConfig: StateFlow<DeviceConfig> = _activeConfig.asStateFlow()

    override val stateFlow: Flow<AcState> = _activeDriver.flatMapLatest { driver ->
        driver?.stateFlow ?: flowOf(AcState(isReachable = false))
    }

    override val capabilitiesFlow: Flow<AcCapabilities> = combine(
        _activeDriver.flatMapLatest { it?.capabilitiesFlow ?: flowOf(AcCapabilities(areCapabilitiesKnown = false)) },
        _activeConfig
    ) { driverCaps, config ->
        config.customCapabilities ?: driverCaps
    }

    fun applyConfiguration(config: DeviceConfig) {
        _activeConfig.value = config
        val driver = driverRegistry.getDriver(config.protocolId)
        _activeDriver.value = driver

        if (driver != null && config.isConfigured) {
            coroutineScope.launch {
                driver.connect(config)
            }
        }
    }

    override suspend fun setPower(power: PowerState): CommandResult = execute(AcCommand.SetPower(power))
    override suspend fun setMode(mode: AcMode): CommandResult = execute(AcCommand.SetMode(mode))
    override suspend fun setTargetTemperature(target: Double): CommandResult = execute(AcCommand.SetTargetTemperature(target))
    override suspend fun setVerticalSwing(position: SwingPosition): CommandResult = execute(AcCommand.SetVerticalSwing(position))
    override suspend fun setHorizontalSwing(position: SwingPosition): CommandResult = execute(AcCommand.SetHorizontalSwing(position))
    override suspend fun refreshState(): CommandResult = execute(AcCommand.RefreshState)

    private suspend fun execute(command: AcCommand): CommandResult {
        val driver = _activeDriver.value
        if (driver == null || !_activeConfig.value.isConfigured) {
            val err = AcError.NoAcConfigured
            _lastError.value = err
            _commandLifecycle.value = CommandLifecycle.Failed(command, err)
            return CommandResult.Failed(err)
        }

        // --- COMMAND SAFETY GATE (Phase 1) ---
        // If a control command must carry a full frame, verify that required fields are known or baseline asserted.
        // STALE values (device-reported before disconnect) are NOT a valid control basis.
        if (command !is AcCommand.RefreshState && command !is AcCommand.SetPower) {
            val currentState = driver.stateFlow.value
            val isPowerKnown = currentState.power.isKnown  // isKnown returns false for STALE
            if (!isPowerKnown) {
                val staleSuffix = if (currentState.power.provenance == StateProvenance.STALE)
                    " (Last known state is stale — reconnect to refresh.)" else ""
                val err = AcError.CommandBlocked(
                    "AC power state is unknown. Cannot alter mode, temperature, or swing without knowing power state. " +
                    "Refresh status or assert hardware bring-up baseline in Setup.$staleSuffix"
                )
                _lastError.value = err
                _commandLifecycle.value = CommandLifecycle.Failed(command, err)
                return CommandResult.Failed(err)
            }
        }

        _commandLifecycle.value = CommandLifecycle.Pending(command)
        _lastError.value = null

        val result = try {
            withTimeoutOrNull(commandTimeoutMs) {
                driver.executeCommand(command)
            }
        } catch (e: CancellationException) {
            // NEVER swallow CancellationException
            throw e
        } catch (e: Exception) {
            CommandResult.Failed(AcError.GeneralError(e.message ?: "Execution error"))
        }

        return if (result != null) {
            when (result) {
                is CommandResult.Confirmed -> {
                    _commandLifecycle.value = CommandLifecycle.Confirmed(command)
                    result
                }
                is CommandResult.SentUnconfirmed -> {
                    _commandLifecycle.value = CommandLifecycle.SentUnconfirmed(command)
                    result
                }
                is CommandResult.Failed -> {
                    _lastError.value = result.error
                    _commandLifecycle.value = CommandLifecycle.Failed(command, result.error)
                    result
                }
            }
        } else {
            val timeoutError = AcError.Timeout
            _lastError.value = timeoutError
            _commandLifecycle.value = CommandLifecycle.TimedOut(command)
            CommandResult.Failed(timeoutError)
        }
    }
}
