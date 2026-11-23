package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed

/*
 * The "classic" status report family and its group-set (grSetDAC, 0x6001) writer.
 * Offsets and field positions adapted from haismart-local (MIT), see THIRD_PARTY_NOTICES.md, where
 * they were confirmed one attribute at a time on hardware sharing the target AC's uPlusId.
 *
 * Decrypted report: [0:78] CAE prefix ([2:4] = 27 15) | [78:80] inner length | [80:] EPP frame.
 * The control-word block always starts at byte 92 (word 1 = bytes 92..93, big-endian, bit 0 = LSB).
 */

/** Model-dependent layout, keyed by report length. Both entries are hardware-confirmed upstream. */
data class ClassicLayout(val words: Int, val indoorOffset: Int, val outdoorOffset: Int) {
    val baseline: IntRange get() = OFF_ATTRS until OFF_ATTRS + 2 * words

    companion object {
        const val OFF_ATTRS = 92
        val BY_LENGTH = mapOf(
            127 to ClassicLayout(words = 6, indoorOffset = 104, outdoorOffset = 106),
            125 to ClassicLayout(words = 5, indoorOffset = 102, outdoorOffset = 104)
        )
    }
}

/** Position of a field in the control-word block: 1-based word, bit shift, width. */
data class WordField(val word: Int, val shift: Int, val width: Int)

object ClassicFields {
    val TARGET_TEMPERATURE = WordField(1, 8, 8)      // value = °C - 16
    val VANE_VERTICAL = WordField(1, 0, 4)           // position code; 0x0C = auto sweep, 0 = fixed
    val OPERATION_MODE = WordField(2, 13, 3)         // STD code
    val WIND_SPEED = WordField(2, 8, 3)              // STD code
    val ON_OFF = WordField(3, 0, 1)
    val HEALTH = WordField(3, 1, 1)
    val RAPID = WordField(3, 3, 1)                   // turbo
    val MUTE = WordField(3, 4, 1)                    // quiet
    val SLEEP = WordField(3, 5, 1)
    val SCREEN_DISPLAY = WordField(3, 9, 1)
    val VANE_HORIZONTAL = WordField(4, 0, 3)         // 0 fixed, 7 auto
    val ECO = WordField(4, 3, 3)                     // 0 off, 5/6/7 = L1/L2/L3 (confirmed on this family)
    val SELF_CLEAN = WordField(5, 4, 1)              // start-only trigger, reads back while running

    val FEATURES = mapOf(
        AcFeature.TURBO to RAPID,
        AcFeature.QUIET to MUTE,
        AcFeature.SLEEP to SLEEP,
        AcFeature.HEALTH to HEALTH,
        AcFeature.DISPLAY to SCREEN_DISPLAY
    )

    const val VANE_V_AUTO = 0x0C
    const val VANE_H_AUTO = 0x07
}

object ClassicReport {

    private val PLAUSIBLE_AMBIENT = -40.0..70.0

    /** Why a decrypted blob was not usable as a status report. */
    enum class Kind { STATUS, ALARM, EXTENDED, CHANGED_PARAMS, REFUSAL, NOT_A_REPORT }

    fun classify(blob: ByteArray): Kind {
        val type = EppFrame.frameType(blob)
        if (type == EppFrame.TYPE_REFUSED) return Kind.REFUSAL
        if (!CaeEnvelope.isReport(blob) || type == null) return Kind.NOT_A_REPORT
        val cmd = EppFrame.command(blob) ?: return Kind.NOT_A_REPORT
        return when {
            cmd.contentEquals(EppFrame.RPT_ALARM) -> Kind.ALARM
            cmd.contentEquals(EppFrame.RPT_EXTENDED) -> Kind.EXTENDED
            cmd.contentEquals(EppFrame.RPT_CHANGED_PARAMS) -> Kind.CHANGED_PARAMS
            // Status is identified by exclusion: a query draws 6d01, a group-set may draw 6d5f.
            else -> Kind.STATUS
        }
    }

    /**
     * Decodes a status report. Returns null for anything that is not a status report or is too short
     * to hold the core block. Reports of a length outside [ClassicLayout.BY_LENGTH] decode the
     * layout-independent core (bytes 92..97) and are marked not writable.
     */
    fun decode(blob: ByteArray, nowMs: Long = System.currentTimeMillis()): AcStatus? {
        if (classify(blob) != Kind.STATUS) return null
        if (blob.size <= 97) return null
        val layout = ClassicLayout.BY_LENGTH[blob.size]

        val words = blob.copyOfRange(ClassicLayout.OFF_ATTRS, minOf(blob.size, ClassicLayout.OFF_ATTRS + 2 * (layout?.words ?: 3)))
        val modeCode = read(words, ClassicFields.OPERATION_MODE)!!
        val fanCode = read(words, ClassicFields.WIND_SPEED)!!

        val features = LinkedHashMap<AcFeature, Boolean>()
        var horizontal: Boolean? = null
        var eco: EcoLevel? = null
        var selfCleaning: Boolean? = null
        var indoor: Double? = null
        var outdoor: Double? = null
        var heatCapable: Boolean? = null
        var errorCode: Int? = null

        if (layout != null) {
            for ((feature, field) in ClassicFields.FEATURES) read(words, field)?.let { features[feature] = it == 1 }
            horizontal = read(words, ClassicFields.VANE_HORIZONTAL)?.let { it == ClassicFields.VANE_H_AUTO }
            eco = read(words, ClassicFields.ECO)?.let { EcoLevel.fromWire(it) }
            selfCleaning = read(words, ClassicFields.SELF_CLEAN)?.let { it == 1 }
            indoor = sensor(blob[layout.indoorOffset].toInt() and 0xFF, 0.5, 0.0)
            outdoor = sensor(blob[layout.outdoorOffset].toInt() and 0xFF, 1.0, -64.0)
            // Bit 7 of the byte after the outdoor reading is set on a cooling-only unit.
            heatCapable = ((blob[layout.outdoorOffset + 1].toInt() shr 7) and 1) == 0
            errorCode = blob[layout.outdoorOffset + 2].toInt() and 0xFF
        }

        return AcStatus(
            power = read(words, ClassicFields.ON_OFF) == 1,
            mode = AcMode.fromStdCode(modeCode),
            rawModeCode = modeCode,
            targetTemperatureC = ((blob[ClassicLayout.OFF_ATTRS].toInt() and 0xFF) + 16).toDouble(),
            fanSpeed = FanSpeed.fromStdCode(fanCode),
            rawFanCode = fanCode,
            verticalSwing = (read(words, ClassicFields.VANE_VERTICAL)!! and ClassicFields.VANE_V_AUTO) == ClassicFields.VANE_V_AUTO,
            horizontalSwing = horizontal,
            indoorTemperatureC = indoor,
            outdoorTemperatureC = outdoor,
            features = features,
            ecoLevel = eco,
            selfCleaning = selfCleaning,
            heatCapable = heatCapable,
            errorCode = errorCode,
            writable = layout != null,
            reportLength = blob.size,
            receivedAtEpochMs = nowMs
        )
    }

    /** The control-word block of a writable report, used to seed a group-set. */
    fun baseline(blob: ByteArray): ByteArray? {
        if (classify(blob) != Kind.STATUS) return null
        val layout = ClassicLayout.BY_LENGTH[blob.size] ?: return null
        return blob.copyOfRange(layout.baseline.first, layout.baseline.last + 1)
    }

    fun read(words: ByteArray, f: WordField): Int? {
        val off = (f.word - 1) * 2
        if (off + 1 >= words.size) return null
        val word = ((words[off].toInt() and 0xFF) shl 8) or (words[off + 1].toInt() and 0xFF)
        return (word shr f.shift) and ((1 shl f.width) - 1)
    }

    private fun sensor(raw: Int, scale: Double, offset: Double): Double? {
        if (raw == 0x00 || raw == 0xFF) return null // the unit has no such probe
        val v = raw * scale + offset
        return if (v in PLAUSIBLE_AMBIENT) v else null
    }
}

class GroupSetException(message: String) : Exception(message)

/**
 * Builds the group-set word block for a command, seeded from the unit's own current control words so
 * every other setting is preserved. Only fields whose writes were observed to take effect on hardware
 * are encoded, and only with values from their confirmed value sets.
 */
object ClassicGroupSet {

    fun apply(baseline: ByteArray, command: AcCommand): ByteArray {
        var w = baseline.copyOf()
        when (command) {
            is AcCommand.SetPower -> w = set(w, ClassicFields.ON_OFF, if (command.on) 1 else 0)
            is AcCommand.SetMode -> {
                w = set(w, ClassicFields.OPERATION_MODE, command.mode.stdCode)
                // Fan-only combined with fan=auto is dropped by the AC; the published model's own
                // constraint asks for low fan when entering fan-only.
                if (command.mode == AcMode.FAN && ClassicReport.read(w, ClassicFields.WIND_SPEED) == FanSpeed.AUTO.stdCode) {
                    w = set(w, ClassicFields.WIND_SPEED, FanSpeed.LOW.stdCode)
                }
            }
            is AcCommand.SetTargetTemperature -> {
                val c = command.celsius
                if (c % 1.0 != 0.0 || c < 16.0 || c > 30.0) throw GroupSetException("setpoint $c outside 16..30 whole degrees")
                w = set(w, ClassicFields.TARGET_TEMPERATURE, c.toInt() - 16)
            }
            is AcCommand.SetFanSpeed -> w = set(w, ClassicFields.WIND_SPEED, command.speed.stdCode)
            is AcCommand.SetVerticalSwing ->
                w = set(w, ClassicFields.VANE_VERTICAL, if (command.on) ClassicFields.VANE_V_AUTO else 0)
            is AcCommand.SetHorizontalSwing ->
                w = set(w, ClassicFields.VANE_HORIZONTAL, if (command.on) ClassicFields.VANE_H_AUTO else 0)
            is AcCommand.SetFeature -> {
                val field = ClassicFields.FEATURES[command.feature] ?: throw GroupSetException("no field for ${command.feature}")
                w = set(w, field, if (command.on) 1 else 0)
                // Turbo and quiet are mutually exclusive on the unit; send a consistent pair.
                if (command.on && command.feature == AcFeature.TURBO) w = set(w, ClassicFields.MUTE, 0)
                if (command.on && command.feature == AcFeature.QUIET) w = set(w, ClassicFields.RAPID, 0)
            }
            is AcCommand.SetEcoLevel -> w = set(w, ClassicFields.ECO, command.level.wireValue)
            AcCommand.StartSelfClean -> w = set(w, ClassicFields.SELF_CLEAN, 1)
        }
        return w
    }

    private fun set(words: ByteArray, f: WordField, value: Int): ByteArray {
        if (value < 0 || value >= (1 shl f.width)) throw GroupSetException("value $value does not fit a ${f.width}-bit field")
        val off = (f.word - 1) * 2
        if (off + 1 >= words.size) throw GroupSetException("this report carries only ${words.size / 2} control words")
        val out = words.copyOf()
        var word = ((out[off].toInt() and 0xFF) shl 8) or (out[off + 1].toInt() and 0xFF)
        val mask = ((1 shl f.width) - 1) shl f.shift
        word = (word and mask.inv()) or ((value shl f.shift) and mask)
        out[off] = (word shr 8).toByte()
        out[off + 1] = word.toByte()
        return out
    }
}
