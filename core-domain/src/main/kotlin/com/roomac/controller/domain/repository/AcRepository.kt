package com.roomac.controller.domain.repository

import com.roomac.controller.domain.model.*
import kotlinx.coroutines.flow.Flow

interface AcRepository {
    val stateFlow: Flow<AcState>
    val capabilitiesFlow: Flow<AcCapabilities>
    val commandLifecycleFlow: Flow<CommandLifecycle>
    val lastErrorFlow: Flow<AcError?>

    suspend fun setPower(power: PowerState): CommandResult
    suspend fun setMode(mode: AcMode): CommandResult
    suspend fun setTargetTemperature(target: Double): CommandResult
    suspend fun setVerticalSwing(position: SwingPosition): CommandResult
    suspend fun setHorizontalSwing(position: SwingPosition): CommandResult
    suspend fun refreshState(): CommandResult
}
