package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.FanSpeed

/*
 * Air-conditioner (0xAC) messages: state query (0x41), state set (0x40) and the state reply (0xC0).
 * Byte layout from msmart-ng device/AC/command.py (revision dd09947, MIT). Only the basic state
 * block is used; capability (0xB5), property (0xB0/B1) and group messages are not implemented.
 */

/** The state block of a 0xC0 reply, field for field as msmart parses it. */
data class MideaAcState(
    val power: Boolean,
    val targetTemperature: Double,
    /** 1 auto, 2 cool, 3 dry, 4 heat, 5 fan, 6 smart dry. */
    val mode: Int,
    /** 20 silent, 40 low, 60 medium, 80 high, 100 max, 102 auto (some units report custom percentages). */
    val fanSpeed: Int,
    /** Low nibble: 0x0 off, 0xC vertical, 0x3 horizontal, 0xF both. */
    val swingMode: Int,
    val turbo: Boolean,
    val eco: Boolean,
    val sleep: Boolean,
    val fahrenheit: Boolean,
    val indoorTemperature: Double?,
    val outdoorTemperature: Double?,
    val displayOn: Boolean,
    val errorCode: Int,
    val freezeProtection: Boolean,
    val purifier: Boolean,
    val followMe: Boolean,
    val auxHeat: Boolean,
    val independentAuxHeat: Boolean,
    val length: Int
)

object MideaAcMessages {
    const val RESPONSE_STATE = 0xC0
    private const val CONTROL_SOURCE = 0x2 // "app control"
    private var messageId = 0

    /** Adds the rolling message id and the CRC-8, then wraps the body in an AC frame. */
    private fun command(frameType: Int, body: ByteArray): ByteArray {
        messageId = (messageId + 1) and 0xFF
        val payload = body + messageId.toByte()
        return MideaFrame.build(MideaFrame.TYPE_AC, frameType, payload + MideaCrc8.calculate(payload).toByte())
    }

    fun getState(): ByteArray = command(
        MideaFrame.QUERY,
        byteArrayOf(0x41, 0x81.toByte(), 0x00, 0xFF.toByte(), 0x03, 0xFF.toByte(), 0x00, 0x02 /* indoor temperature */) +
            ByteArray(12) + byteArrayOf(0x03)
    )

    /**
     * A full 0x40 state write. The protocol has no "change one field" form, so every field is taken
     * from the current state and only the requested one changed; the beep is left on, as the remote
     * control does.
     */
    fun setState(s: MideaAcState): ByteArray {
        val integral = s.targetTemperature.toInt()
        val half = s.targetTemperature - integral > 0
        var temperature: Int
        val temperatureAlt: Int
        if (integral in 17..30) {
            temperature = (integral - 16) and 0xF; temperatureAlt = 0
        } else {
            temperature = 0; temperatureAlt = (integral - 12) and 0x1F
        }
        if (half) temperature = temperature or 0x10
        val body = byteArrayOf(
            0x40,
            (CONTROL_SOURCE or 0x40 or (if (s.power) 0x1 else 0)).toByte(),
            (temperature or ((s.mode and 0x7) shl 5)).toByte(),
            s.fanSpeed.toByte(),
            0x7F, 0x7F, 0x00,
            (0x30 or (s.swingMode and 0x3F)).toByte(),
            ((if (s.followMe) 0x80 else 0) or (if (s.turbo) 0x20 else 0)).toByte(),
            ((if (s.eco) 0x80 else 0) or (if (s.purifier) 0x20 else 0) or (if (s.auxHeat) 0x08 else 0)).toByte(),
            ((if (s.sleep) 0x01 else 0) or (if (s.turbo) 0x02 else 0) or (if (s.fahrenheit) 0x04 else 0)).toByte(),
            0, 0, 0, 0, 0, 0, 0,
            temperatureAlt.toByte(),
            40, // target humidity: unused by plain ACs, msmart's default
            0,
            (if (s.freezeProtection) 0x80 else 0).toByte(),
            (if (s.independentAuxHeat) 0x08 else 0).toByte(),
            0
        )
        return command(MideaFrame.CONTROL, body)
    }

    /**
     * Parses a reply frame. Returns null for frames that are not a state reply (the module may also
     * send unsolicited reports). Throws on a corrupt frame.
     */
    fun parseState(frame: ByteArray): MideaAcState? {
        val body = MideaFrame.validate(frame, MideaFrame.TYPE_AC)
        if (body.isEmpty() || (body[0].toInt() and 0xFF) != RESPONSE_STATE) return null
        // Some units end the body with a CRC-8, others with a second checksum.
        val last = body.last().toInt() and 0xFF
        if (MideaCrc8.calculate(body, 0, body.size - 1) != last && MideaFrame.checksum(body, 0, body.size - 1) != last) {
            throw MideaProtocolException("state reply CRC")
        }
        val p = body.copyOfRange(0, body.size - 1).map { it.toInt() and 0xFF } // msmart: frame[10:-2]
        if (p.size < 17) throw MideaProtocolException("state reply too short")
        val fahrenheit = p[10] and 0x4 != 0
        var target = (p[2] and 0xF) + 16.0 + if (p[2] and 0x10 != 0) 0.5 else 0.0
        val alt = p[13] and 0x1F
        if (alt != 0) target = alt + 12.0 + if (p[2] and 0x10 != 0) 0.5 else 0.0
        return MideaAcState(
            power = p[1] and 0x1 != 0,
            targetTemperature = target,
            mode = (p[2] ushr 5) and 0x7,
            fanSpeed = p[3] and 0x7F,
            swingMode = p[7] and 0xF,
            turbo = (p[8] and 0x20 != 0) || (p[10] and 0x2 != 0),
            eco = p[9] and 0x10 != 0,
            sleep = p[10] and 0x1 != 0,
            fahrenheit = fahrenheit,
            indoorTemperature = temperature(p[11], (p[15] and 0xF) / 10.0, fahrenheit),
            outdoorTemperature = temperature(p[12], (p[15] ushr 4) / 10.0, fahrenheit),
            displayOn = p[14] != 0x70,
            errorCode = p[16],
            freezeProtection = p.size >= 22 && (p[21] and 0x80 != 0),
            purifier = p[9] and 0x20 != 0,
            followMe = p[8] and 0x80 != 0,
            auxHeat = p[9] and 0x08 != 0,
            independentAuxHeat = p[8] and 0x40 != 0,
            length = frame.size
        )
    }

    private fun temperature(raw: Int, decimals: Double, fahrenheit: Boolean): Double? {
        if (raw == 0xFF) return null
        val t = (raw - 50) / 2.0
        if (!fahrenheit && decimals != 0.0) return t.toInt() + if (t >= 0) decimals else -decimals
        if (decimals >= 0.5) return t.toInt() + if (t >= 0) 0.5 else -0.5
        return t
    }

    // ---- mapping to the app's unified model ----

    fun modeOf(code: Int): AcMode? = when (code) {
        1 -> AcMode.AUTO; 2 -> AcMode.COOL; 3 -> AcMode.DRY; 4 -> AcMode.HEAT; 5 -> AcMode.FAN; else -> null
    }

    fun modeCode(mode: AcMode): Int = when (mode) {
        AcMode.AUTO -> 1; AcMode.COOL -> 2; AcMode.DRY -> 3; AcMode.HEAT -> 4; AcMode.FAN -> 5
    }

    fun fanOf(code: Int): FanSpeed? = when (code) {
        102 -> FanSpeed.AUTO; 40 -> FanSpeed.LOW; 60 -> FanSpeed.MEDIUM; 80 -> FanSpeed.HIGH; else -> null
    }

    fun fanCode(speed: FanSpeed): Int = when (speed) {
        FanSpeed.AUTO -> 102; FanSpeed.LOW -> 40; FanSpeed.MEDIUM -> 60; FanSpeed.HIGH -> 80
    }

    fun toStatus(s: MideaAcState, now: Long): AcStatus = AcStatus(
        power = s.power,
        mode = modeOf(s.mode),
        rawModeCode = s.mode,
        targetTemperatureC = s.targetTemperature,
        fanSpeed = fanOf(s.fanSpeed),
        rawFanCode = s.fanSpeed,
        verticalSwing = s.swingMode and 0xC == 0xC,
        horizontalSwing = s.swingMode and 0x3 == 0x3,
        indoorTemperatureC = s.indoorTemperature,
        outdoorTemperatureC = s.outdoorTemperature,
        // Display is reported but its toggle is a different message (not implemented), so it is not offered.
        features = mapOf(AcFeature.TURBO to s.turbo, AcFeature.SLEEP to s.sleep, AcFeature.ENERGY_SAVING to s.eco),
        ecoLevel = null,
        selfCleaning = null,
        heatCapable = null,
        errorCode = s.errorCode,
        // Fahrenheit units would need °F writes; not handled, so they are read-only.
        writable = !s.fahrenheit,
        reportLength = s.length,
        receivedAtEpochMs = now
    )

    /** The full state to write for [command], or null when this command has no 0x40 form. */
    fun apply(current: MideaAcState, command: AcCommand): MideaAcState? = when (command) {
        is AcCommand.SetPower -> current.copy(power = command.on)
        is AcCommand.SetMode -> current.copy(mode = modeCode(command.mode))
        is AcCommand.SetTargetTemperature -> current.copy(targetTemperature = command.celsius)
        is AcCommand.SetFanSpeed -> current.copy(fanSpeed = fanCode(command.speed))
        is AcCommand.SetVerticalSwing -> current.copy(swingMode = if (command.on) current.swingMode or 0xC else current.swingMode and 0xC.inv())
        is AcCommand.SetHorizontalSwing -> current.copy(swingMode = if (command.on) current.swingMode or 0x3 else current.swingMode and 0x3.inv())
        is AcCommand.SetFeature -> when (command.feature) {
            AcFeature.TURBO -> current.copy(turbo = command.on)
            AcFeature.SLEEP -> current.copy(sleep = command.on)
            AcFeature.ENERGY_SAVING -> current.copy(eco = command.on)
            else -> null // display toggle is a different message; not implemented
        }
        else -> null
    }
}
