package com.roomac.controller.domain

import com.roomac.controller.domain.model.*
import com.roomac.controller.domain.repository.AcRepository
import com.roomac.controller.domain.usecase.AcController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AcControllerTest {

    private val testScope = TestScope()
    private lateinit var fakeRepository: MockAcRepository
    private lateinit var controller: AcController

    @Before
    fun setUp() {
        fakeRepository = MockAcRepository()
        controller = AcController(fakeRepository, testScope, debounceDelayMs = 350L)
    }

    @Test
    fun temperatureSteppingDebouncesRapidTaps() = testScope.runTest {
        val currentState = AcState(
            power = ValueWithProvenance.confirmed(PowerState.ON),
            targetTemperature = ValueWithProvenance.confirmed(24.0)
        )
        val caps = AcCapabilities(areCapabilitiesKnown = true)

        // Rapid tap: +1, +1, +1
        controller.onTemperatureStep(true, currentState, caps)
        assertEquals(25.0, controller.pendingTemperature.value)

        controller.onTemperatureStep(true, currentState, caps)
        assertEquals(26.0, controller.pendingTemperature.value)

        controller.onTemperatureStep(true, currentState, caps)
        assertEquals(27.0, controller.pendingTemperature.value)

        // Advancing time less than debounce delay -> no send yet
        advanceTimeBy(200)
        assertEquals(0, fakeRepository.setTargetTemperatureCallCount)

        // Advancing past debounce window -> exactly ONE network send with coalesced 27.0
        advanceTimeBy(200)
        assertEquals(1, fakeRepository.setTargetTemperatureCallCount)
        assertEquals(27.0, fakeRepository.lastSentTargetTemperature, 0.01)
    }

    @Test
    fun heatingModeIsBlockedWhenCapabilitiesDisallow() = testScope.runTest {
        val state = AcState(power = ValueWithProvenance.confirmed(PowerState.ON))
        val caps = AcCapabilities(supportsHeating = false, areCapabilitiesKnown = true)

        controller.onModeSelected(AcMode.HEAT, state, caps)
        assertNotNull(controller.disabledReason.value)
        assertEquals(0, fakeRepository.setModeCallCount)
    }

    @Test
    fun temperatureIsClampedToCapabilityBounds() {
        val caps = AcCapabilities(minTemperature = 16.0, maxTemperature = 30.0, temperatureStep = 1.0)
        assertEquals(16.0, AcController.clampTemperature(14.0, caps), 0.01)
        assertEquals(30.0, AcController.clampTemperature(35.0, caps), 0.01)
        assertEquals(22.0, AcController.clampTemperature(22.3, caps), 0.01)
    }

    private class MockAcRepository : AcRepository {
        var setTargetTemperatureCallCount = 0
        var lastSentTargetTemperature = 0.0
        var setModeCallCount = 0

        override val stateFlow: Flow<AcState> = MutableStateFlow(AcState()).asStateFlow()
        override val capabilitiesFlow: Flow<AcCapabilities> = MutableStateFlow(AcCapabilities()).asStateFlow()
        override val commandLifecycleFlow: Flow<CommandLifecycle> = MutableStateFlow<CommandLifecycle>(CommandLifecycle.Idle).asStateFlow()
        override val lastErrorFlow: Flow<AcError?> = MutableStateFlow<AcError?>(null).asStateFlow()

        override suspend fun setPower(power: PowerState): CommandResult = CommandResult.Confirmed(AcState())
        override suspend fun setMode(mode: AcMode): CommandResult {
            setModeCallCount++
            return CommandResult.Confirmed(AcState())
        }
        override suspend fun setTargetTemperature(target: Double): CommandResult {
            setTargetTemperatureCallCount++
            lastSentTargetTemperature = target
            return CommandResult.Confirmed(AcState(targetTemperature = ValueWithProvenance.confirmed(target)))
        }
        override suspend fun setVerticalSwing(position: SwingPosition): CommandResult = CommandResult.Confirmed(AcState())
        override suspend fun setHorizontalSwing(position: SwingPosition): CommandResult = CommandResult.Confirmed(AcState())
        override suspend fun refreshState(): CommandResult = CommandResult.Confirmed(AcState())
    }
}
