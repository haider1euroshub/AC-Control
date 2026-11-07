package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAcRepositoryTest {

    private val testScope = TestScope()
    private val fakeDriver = FakeAcDriver()
    private lateinit var repository: DefaultAcRepository

    @Before
    fun setUp() {
        val registry = DriverRegistry(listOf(fakeDriver))
        repository = DefaultAcRepository(
            driverRegistry = registry,
            coroutineScope = testScope,
            commandTimeoutMs = 1000L
        )
    }

    @Test
    fun executeCommandWithoutConfigFailsWithNoAcConfigured() = runTest {
        val result = repository.setPower(PowerState.ON)
        assertTrue(result is CommandResult.Failed)
        assertEquals(AcError.NoAcConfigured, (result as CommandResult.Failed).error)
    }

    @Test
    fun commandSafetyGateBlocksTemperatureWhenPowerIsUnknown() = runTest {
        // Driver configured but unconfirmed power state
        val config = DeviceConfig(protocolId = fakeDriver.id, host = "192.168.1.50")
        repository.applyConfiguration(config)

        // Clear power state to UNKNOWN (using test helper to avoid MutableStateFlow cast)
        fakeDriver.setTestState(AcState(
            power = ValueWithProvenance.unknown(),
            isReachable = true
        ))

        val result = repository.setTargetTemperature(26.0)
        assertTrue("Safety gate must block temperature command when power state is unknown", result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).error is AcError.CommandBlocked)
    }

    @Test
    fun configuredDriverExecutesPowerSuccessfully() = runTest {
        val config = DeviceConfig(protocolId = fakeDriver.id, host = "192.168.1.50")
        repository.applyConfiguration(config)

        val result = repository.setPower(PowerState.ON)
        assertTrue("Expected confirmed result", result is CommandResult.Confirmed)
        assertEquals(PowerState.ON, (result as CommandResult.Confirmed).newState.power.value)
    }

    @Test
    fun driverFailureReturnsTypedError() = runTest {
        fakeDriver.shouldFailCommands = true
        val config = DeviceConfig(protocolId = fakeDriver.id, host = "192.168.1.50")
        repository.applyConfiguration(config)

        val result = repository.setPower(PowerState.OFF)
        assertTrue("Expected failure", result is CommandResult.Failed)
    }
}
