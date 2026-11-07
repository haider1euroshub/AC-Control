package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.AcDriver
import com.roomac.controller.protocol.DriverContractTest
import org.junit.Assert.*
import org.junit.Test

class HaiSmartDriverTest : DriverContractTest() {

    override fun createDriver(): AcDriver = HaiSmartDriver()

    private val testKey = HaiSmartCipher.hexToBytes("0123456789abcdef0123456789abcdef")

    @Test
    fun aesCipherRoundtripEncryptsAndDecrypts() {
        val originalText = "Hello Haier Smart AC Test!"
        val plainBytes = originalText.toByteArray(Charsets.UTF_8)

        val encrypted = HaiSmartCipher.encryptAes128Cbc(plainBytes, testKey)
        assertFalse(encrypted.contentEquals(plainBytes))

        val decrypted = HaiSmartCipher.decryptAes128Cbc(encrypted, testKey)
        val resultText = String(decrypted, Charsets.UTF_8)
        assertEquals(originalText, resultText)
    }

    @Test
    fun controlPayloadEncodesAndParsesCorrectly() {
        val jsonPayload = HaiSmartPacket.buildControlPayload(
            power = PowerState.ON,
            mode = AcMode.COOL,
            targetTemperature = 26.0,
            verticalSwing = SwingPosition.OSCILLATE,
            horizontalSwing = SwingPosition.OFF
        )
        val packet = HaiSmartPacket.buildPacket(jsonPayload, testKey)
        val parsed = HaiSmartPacket.parsePacket(packet, testKey)

        assertNotNull(parsed)
        assertEquals(PowerState.ON, parsed!!.power.value)
        assertEquals(AcMode.COOL, parsed.mode.value)
        assertEquals(26.0, parsed.targetTemperature.value!!, 0.01)
        assertEquals(SwingPosition.OSCILLATE, parsed.swing.value!!.vertical)
    }
}
