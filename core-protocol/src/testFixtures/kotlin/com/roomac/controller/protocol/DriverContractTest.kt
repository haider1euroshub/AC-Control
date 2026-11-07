package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.PowerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Abstract contract test for all [AcDriver] implementations.
 * Extend this in every driver module's test to verify the contract is upheld.
 */
abstract class DriverContractTest {

    abstract fun createDriver(): AcDriver

    @Test
    fun driver_has_non_empty_id_and_displayName() {
        val driver = createDriver()
        assertTrue("Driver id must not be blank", driver.id.isNotBlank())
        assertTrue("Driver displayName must not be blank", driver.displayName.isNotBlank())
        assertTrue("Driver description must not be blank", driver.description.isNotBlank())
    }

    @Test
    fun driver_verification_status_is_not_verified_on_hardware_in_this_session() {
        val driver = createDriver()
        assertNotEquals(
            "No driver may be labelled VERIFIED_ON_REAL_AC before hardware day",
            DriverVerificationStatus.VERIFIED_ON_REAL_AC,
            driver.verificationStatus
        )
    }

    @Test
    fun unconnected_driver_returns_typed_result_or_failure_on_command_without_throwing_unexpected_exceptions() = runBlocking {
        val driver = createDriver()
        val result = try {
            driver.executeCommand(AcCommand.SetPower(PowerState.ON))
        } catch (e: Exception) {
            fail("Driver threw unexpected exception instead of returning typed CommandResult: ${e.message}")
            return@runBlocking
        }
        assertNotNull(result)
    }

    @Test
    fun driver_exposes_valid_state_and_capabilities_flows() = runBlocking {
        val driver = createDriver()
        val state = driver.stateFlow.first()
        val caps = driver.capabilitiesFlow.first()
        assertNotNull(state)
        assertNotNull(caps)
    }
}
