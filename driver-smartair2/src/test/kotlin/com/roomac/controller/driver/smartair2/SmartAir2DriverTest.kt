package com.roomac.controller.driver.smartair2

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.AcDriver
import com.roomac.controller.protocol.DriverContractTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmartAir2DriverTest : DriverContractTest() {

    override fun createDriver(): AcDriver = SmartAir2Driver()

    @Test
    fun controlPacketChecksumIsCorrect() {
        val packet = SmartAir2Layout.buildControlPacket(
            power = PowerState.ON,
            mode = AcMode.COOL,
            targetTemperature = 24.0,
            verticalSwing = SwingPosition.OSCILLATE,
            horizontalSwing = SwingPosition.OFF
        )
        // Verify header
        assertEquals(0xFF.toByte(), packet[0])
        assertEquals(0xFF.toByte(), packet[1])

        // Verify checksum at last position matches calculateChecksum
        val expected = SmartAir2Layout.calculateChecksum(packet, 2, packet.size - 3)
        assertEquals(expected, packet[packet.size - 1])

        // Verify power byte is at offset 10 (not 8)
        assertEquals(SmartAir2Layout.POWER_ON, packet[SmartAir2Layout.CTRL_OFFSET_POWER])
        assertEquals(SmartAir2Layout.MODE_COOL, packet[SmartAir2Layout.CTRL_OFFSET_MODE])
        assertEquals((24 - 16).toByte(), packet[SmartAir2Layout.CTRL_OFFSET_TEMP])
        assertEquals(SmartAir2Layout.SWING_OSCILLATE, packet[SmartAir2Layout.CTRL_OFFSET_VERT_SWING])
    }

    @Test
    fun parseStatusPacketExtractsValuesAccurately() {
        // Construct valid raw status packet (15 bytes)
        val raw = ByteArray(16)
        raw[0] = 0xFF.toByte()
        raw[1] = 0xFF.toByte()
        raw[2] = 0x00
        raw[3] = 0x0D // Length
        raw[8] = SmartAir2Layout.POWER_ON
        raw[9] = SmartAir2Layout.MODE_COOL
        raw[10] = 0x08 // 8 + 16 = 24 C
        raw[11] = 0x00 // Fan
        raw[12] = SmartAir2Layout.SWING_OSCILLATE
        raw[13] = SmartAir2Layout.SWING_OFF
        raw[14] = 0x19 // 25 C indoor ambient temp
        raw[15] = SmartAir2Layout.calculateChecksum(raw, 2, raw.size - 3)

        val state = SmartAir2Layout.parseStatusPacket(raw)
        assertNotNull("Packet should parse successfully", state)
        assertEquals(PowerState.ON, state!!.power.value)
        assertEquals(AcMode.COOL, state.mode.value)
        assertEquals(24.0, state.targetTemperature.value!!, 0.01)
        assertEquals(SwingPosition.OSCILLATE, state.swing.value!!.vertical)
        assertEquals(25.0, state.indoorTemperature.value!!, 0.01)
    }

    @Test
    fun parseCorruptChecksumPacketReturnsNull() {
        val raw = ByteArray(15)
        raw[0] = 0xFF.toByte()
        raw[1] = 0xFF.toByte()
        raw[2] = 0x0A // Non-zero payload
        raw[14] = 0x00 // Incorrect checksum
        val parsed = SmartAir2Layout.parseStatusPacket(raw)
        assertNull(parsed)
    }
}
