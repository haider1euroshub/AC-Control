package com.roomac.controller

import com.roomac.controller.domain.model.AcError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingTest {

    private val allSampleErrors: List<AcError> = listOf(
        AcError.NoAcConfigured,
        AcError.NoWifi,
        AcError.DifferentNetwork,
        AcError.PermissionMissing,
        AcError.AcUnreachable("192.168.1.105"),
        AcError.Timeout,
        AcError.AuthOrKeyRejected,
        AcError.ProtocolNotVerified("SmartAir2"),
        AcError.CapabilityUnsupported("Heating"),
        AcError.UnexpectedResponse("Malformed frame"),
        AcError.GeneralError("Network socket closed")
    )

    @Test
    fun `every error subtype has meaningful non-blank userMessage`() {
        allSampleErrors.forEach { error ->
            assertFalse(
                "Error ${error::class.simpleName} has blank userMessage",
                error.userMessage.isBlank()
            )
            assertTrue(
                "Error ${error::class.simpleName} userMessage should be reasonably descriptive",
                error.userMessage.length > 5
            )
        }
    }

    @Test
    fun `userMessage does not leak raw stack traces or internal exception words`() {
        allSampleErrors.forEach { error ->
            assertFalse(
                "Error ${error::class.simpleName} userMessage contains raw java/kotlin stack symbols",
                error.userMessage.contains("NullPointerException") ||
                        error.userMessage.contains("at com.") ||
                        error.userMessage.contains("Exception in thread")
            )
        }
    }
}
