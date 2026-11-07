package com.roomac.controller.driver.smartair2

import com.roomac.controller.domain.model.*

/**
 * Single, authoritative layout definition and codec for Haier SmartAir2.
 * Shared by both encoder and decoder to eliminate any indexing discrepancy.
 *
 * Evidence status:
 * - Frame Header (FF FF): PUBLIC_REFERENCE (bstuff/haier-ac-remote, paveldn/haier-esphome)
 * - Control command (0x00 0x0C): PUBLIC_REFERENCE
 * - Status query (0x00 0x01, payload 0x4D 0x01): PUBLIC_REFERENCE
 * - Control Offsets:
 *     Index 0-1: 0xFF 0xFF (Header)
 *     Index 2-3: Length (2 bytes Big Endian)
 *     Index 4-7: Reserved (0x00 0x00 0x00 0x00)
 *     Index 8-9: Command (0x00 0x0C)
 *     Index 10: Power (0x01 On, 0x00 Off)
 *     Index 11: Mode (0x02 Cool, 0x04 Heat, 0x00 Other)
 *     Index 12: Target Temp (temp - 16)
 *     Index 13: Fan speed placeholder (preserved or default 0)
 *     Index 14: Vertical Swing (0x00 Off, 0x01 Oscillate, 0x02-0x06 Pos 1-5)
 *     Index 15: Horizontal Swing (0x00 Off, 0x01 Oscillate)
 *     Index 16: Checksum (modulo-256 sum from index 2 to 15)
 * - Status Response Offsets:
 *     Index 0-1: 0xFF 0xFF
 *     Index 2-3: Length
 *     Index 4-7: Reserved
 *     Index 8: Power
 *     Index 9: Mode
 *     Index 10: Target Temp (val + 16)
 *     Index 11: Fan speed
 *     Index 12: Vertical Swing
 *     Index 13: Horizontal Swing
 *     Index 14: Indoor ambient temp
 *     Last Byte: Checksum
 */
object SmartAir2Layout {

    val HEADER = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

    // Command IDs
    const val CMD_QUERY: Short = 0x0001
    const val CMD_CONTROL: Short = 0x000C

    // Mode Byte Constants
    const val MODE_COOL: Byte = 0x02
    const val MODE_HEAT: Byte = 0x04
    const val MODE_OTHER: Byte = 0x00

    // Power Byte Constants
    const val POWER_OFF: Byte = 0x00
    const val POWER_ON: Byte = 0x01

    // Swing Constants
    const val SWING_OFF: Byte = 0x00
    const val SWING_OSCILLATE: Byte = 0x01
    const val SWING_POS_1: Byte = 0x02
    const val SWING_POS_2: Byte = 0x03
    const val SWING_POS_3: Byte = 0x04
    const val SWING_POS_4: Byte = 0x05
    const val SWING_POS_5: Byte = 0x06

    // Offsets for Control Frame
    const val CTRL_OFFSET_POWER = 10
    const val CTRL_OFFSET_MODE = 11
    const val CTRL_OFFSET_TEMP = 12
    const val CTRL_OFFSET_FAN = 13
    const val CTRL_OFFSET_VERT_SWING = 14
    const val CTRL_OFFSET_HORIZ_SWING = 15

    // Offsets for Status Response Frame
    const val STATUS_OFFSET_POWER = 8
    const val STATUS_OFFSET_MODE = 9
    const val STATUS_OFFSET_TEMP = 10
    const val STATUS_OFFSET_FAN = 11
    const val STATUS_OFFSET_VERT_SWING = 12
    const val STATUS_OFFSET_HORIZ_SWING = 13
    const val STATUS_OFFSET_INDOOR_TEMP = 14

    fun calculateChecksum(bytes: ByteArray, startIndex: Int = 2, length: Int = bytes.size - 3): Byte {
        var sum = 0
        for (i in startIndex until (startIndex + length)) {
            sum += (bytes[i].toInt() and 0xFF)
        }
        return (sum and 0xFF).toByte()
    }

    fun buildStatusQueryPacket(): ByteArray {
        val payload = byteArrayOf(
            0x00, 0x0A,             // Length = 10
            0x00, 0x00, 0x00, 0x00, // Reserved
            0x00, 0x01,             // Query cmd
            0x4D, 0x01              // Query payload
        )
        val packet = ByteArray(HEADER.size + payload.size + 1)
        HEADER.copyInto(packet, 0)
        payload.copyInto(packet, HEADER.size)
        packet[packet.size - 1] = calculateChecksum(packet)
        return packet
    }

    fun buildControlPacket(
        power: PowerState,
        mode: AcMode,
        targetTemperature: Double,
        verticalSwing: SwingPosition,
        horizontalSwing: SwingPosition,
        fanByte: Byte = 0x00
    ): ByteArray {
        val powerByte = if (power == PowerState.ON) POWER_ON else POWER_OFF
        val modeByte = when (mode) {
            AcMode.COOL -> MODE_COOL
            AcMode.HEAT -> MODE_HEAT
            AcMode.OTHER -> MODE_OTHER
        }
        val tempByte = (targetTemperature.toInt().coerceIn(16, 30) - 16).toByte()
        val vertSwingByte = when (verticalSwing) {
            SwingPosition.OFF -> SWING_OFF
            SwingPosition.OSCILLATE -> SWING_OSCILLATE
            SwingPosition.POSITION_1 -> SWING_POS_1
            SwingPosition.POSITION_2 -> SWING_POS_2
            SwingPosition.POSITION_3 -> SWING_POS_3
            SwingPosition.POSITION_4 -> SWING_POS_4
            SwingPosition.POSITION_5 -> SWING_POS_5
        }
        val horizSwingByte = if (horizontalSwing == SwingPosition.OSCILLATE) SWING_OSCILLATE else SWING_OFF

        val payload = byteArrayOf(
            0x00, 0x0E,             // Length = 14
            0x00, 0x00, 0x00, 0x00, // Reserved
            0x00, 0x0C,             // Control cmd
            powerByte,
            modeByte,
            tempByte,
            fanByte,
            vertSwingByte,
            horizSwingByte
        )
        val packet = ByteArray(HEADER.size + payload.size + 1)
        HEADER.copyInto(packet, 0)
        payload.copyInto(packet, HEADER.size)
        packet[packet.size - 1] = calculateChecksum(packet)
        return packet
    }

    fun parseStatusPacket(raw: ByteArray): AcState? {
        if (raw.size < 14) return null
        if (raw[0] != 0xFF.toByte() || raw[1] != 0xFF.toByte()) return null

        val expectedChecksum = calculateChecksum(raw, 2, raw.size - 3)
        if (raw[raw.size - 1] != expectedChecksum) {
            return null
        }

        // Validate declared length matches frame
        val declaredLength = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        if (declaredLength <= 0 || (declaredLength + 3) > raw.size) {
            return null
        }

        val power = if (raw[STATUS_OFFSET_POWER] == POWER_ON) PowerState.ON else PowerState.OFF
        val mode = when (raw[STATUS_OFFSET_MODE]) {
            MODE_COOL -> AcMode.COOL
            MODE_HEAT -> AcMode.HEAT
            else -> AcMode.OTHER
        }
        val targetTemp = ((raw[STATUS_OFFSET_TEMP].toInt() and 0xFF) + 16).toDouble()
        val vert = when (raw[STATUS_OFFSET_VERT_SWING]) {
            SWING_OSCILLATE -> SwingPosition.OSCILLATE
            SWING_POS_1 -> SwingPosition.POSITION_1
            SWING_POS_2 -> SwingPosition.POSITION_2
            SWING_POS_3 -> SwingPosition.POSITION_3
            SWING_POS_4 -> SwingPosition.POSITION_4
            SWING_POS_5 -> SwingPosition.POSITION_5
            else -> SwingPosition.OFF
        }
        val horiz = if (raw.size > STATUS_OFFSET_HORIZ_SWING && raw[STATUS_OFFSET_HORIZ_SWING] == SWING_OSCILLATE) {
            SwingPosition.OSCILLATE
        } else {
            SwingPosition.OFF
        }

        val indoorTemp = if (raw.size > STATUS_OFFSET_INDOOR_TEMP && raw[STATUS_OFFSET_INDOOR_TEMP] > 0) {
            ValueWithProvenance.confirmed((raw[STATUS_OFFSET_INDOOR_TEMP].toInt() and 0xFF).toDouble())
        } else {
            ValueWithProvenance.unknown()
        }

        return AcState(
            power = ValueWithProvenance.confirmed(power),
            mode = ValueWithProvenance.confirmed(mode),
            targetTemperature = ValueWithProvenance.confirmed(targetTemp),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = vert, horizontal = horiz)),
            indoorTemperature = indoorTemp,
            isReachable = true,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
    }
}

