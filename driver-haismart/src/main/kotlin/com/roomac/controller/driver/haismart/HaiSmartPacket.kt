package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.*

/**
 * HaiSmart packet builder and parser.
 * Encapsulates JSON payload creation, AES-128-CBC encryption with zero IV, and frame structure.
 */
object HaiSmartPacket {

    val MAGIC_HEADER = byteArrayOf(0x55.toByte(), 0xAA.toByte())

    fun buildControlPayload(
        power: PowerState,
        mode: AcMode,
        targetTemperature: Double,
        verticalSwing: SwingPosition,
        horizontalSwing: SwingPosition
    ): String {
        val pwr = if (power == PowerState.ON) 1 else 0
        val md = when (mode) {
            AcMode.COOL -> "cool"
            AcMode.HEAT -> "heat"
            AcMode.OTHER -> "other"
        }
        val vs = when (verticalSwing) {
            SwingPosition.OFF -> "off"
            SwingPosition.OSCILLATE -> "oscillate"
            SwingPosition.POSITION_1 -> "pos1"
            SwingPosition.POSITION_2 -> "pos2"
            SwingPosition.POSITION_3 -> "pos3"
            SwingPosition.POSITION_4 -> "pos4"
            SwingPosition.POSITION_5 -> "pos5"
        }
        val hs = if (horizontalSwing == SwingPosition.OSCILLATE) "oscillate" else "off"

        return """{"power":$pwr,"mode":"$md","temp":${targetTemperature.toInt()},"vSwing":"$vs","hSwing":"$hs"}"""
    }

    fun buildStatusQueryPayload(): String {
        return """{"action":"query"}"""
    }

    fun buildPacket(jsonPayload: String, keyBytes: ByteArray): ByteArray {
        val plainBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val encrypted = HaiSmartCipher.encryptAes128Cbc(plainBytes, keyBytes)

        val packet = ByteArray(MAGIC_HEADER.size + 2 + encrypted.size)
        MAGIC_HEADER.copyInto(packet, 0)
        packet[2] = ((encrypted.size shr 8) and 0xFF).toByte()
        packet[3] = (encrypted.size and 0xFF).toByte()
        encrypted.copyInto(packet, 4)
        return packet
    }

    fun parsePacket(packet: ByteArray, keyBytes: ByteArray): AcState? {
        if (packet.size < 6) return null
        if (packet[0] != 0x55.toByte() || packet[1] != 0xAA.toByte()) return null

        val encLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (packet.size < 4 + encLen) return null

        val encryptedBytes = packet.copyOfRange(4, 4 + encLen)
        val decryptedBytes = try {
            HaiSmartCipher.decryptAes128Cbc(encryptedBytes, keyBytes)
        } catch (_: Exception) {
            return null
        }

        val json = String(decryptedBytes, Charsets.UTF_8)
        return parseStateFromJson(json)
    }

    fun parseStateFromJson(json: String): AcState? {
        val power = if (json.contains("\"power\":1") || json.contains("\"power\": 1")) PowerState.ON else PowerState.OFF
        val mode = when {
            json.contains("\"mode\":\"cool\"") || json.contains("\"mode\": \"cool\"") -> AcMode.COOL
            json.contains("\"mode\":\"heat\"") || json.contains("\"mode\": \"heat\"") -> AcMode.HEAT
            else -> AcMode.OTHER
        }

        val tempRegex = Regex(""""temp"\s*:\s*(\d+(?:\.\d+)?)""")
        val tempMatch = tempRegex.find(json)
        val targetTemp = tempMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 24.0

        val vSwing = when {
            json.contains("\"vSwing\":\"oscillate\"") || json.contains("\"vSwing\": \"oscillate\"") -> SwingPosition.OSCILLATE
            json.contains("\"vSwing\":\"pos1\"") -> SwingPosition.POSITION_1
            json.contains("\"vSwing\":\"pos2\"") -> SwingPosition.POSITION_2
            json.contains("\"vSwing\":\"pos3\"") -> SwingPosition.POSITION_3
            json.contains("\"vSwing\":\"pos4\"") -> SwingPosition.POSITION_4
            json.contains("\"vSwing\":\"pos5\"") -> SwingPosition.POSITION_5
            else -> SwingPosition.OFF
        }

        val hSwing = if (json.contains("\"hSwing\":\"oscillate\"") || json.contains("\"hSwing\": \"oscillate\"")) {
            SwingPosition.OSCILLATE
        } else {
            SwingPosition.OFF
        }

        return AcState(
            power = ValueWithProvenance.confirmed(power),
            mode = ValueWithProvenance.confirmed(mode),
            targetTemperature = ValueWithProvenance.confirmed(targetTemp),
            swing = ValueWithProvenance.confirmed(SwingState(vertical = vSwing, horizontal = hSwing)),
            isReachable = true,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
    }
}
