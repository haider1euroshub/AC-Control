package com.roomac.controller.driver.gree

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.FanSpeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class GreeFormatException(message: String) : Exception(message)

/** A string field of a JSON object, or "" when absent. */
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

/** A decrypted reply: the outer envelope's fields plus the decrypted inner pack. */
data class GreeReply(val pack: JsonObject) {
    val type: String get() = pack.str("t").lowercase()
}

/**
 * Packet building and parsing for the Gree Wi-Fi protocol (UDP 7000). Wire format as documented by
 * greeclimate (GPL-3.0; facts only, no code copied, see THIRD_PARTY_NOTICES.md). Envelope: {"cid":"app","i":0|1,"t":"pack","uid":0,"tcid":mac,
 * "pack":<encrypted inner JSON>[,"tag":<GCM tag>]}; i=1 for scan and bind (generic key), else 0.
 */
object GreeCodec {
    const val PORT = 7000

    fun canonicalMac(mac: String): String = mac.replace(":", "").replace("-", "").replace(".", "").trim().lowercase()

    fun scanRequest(): ByteArray = """{"t":"scan"}""".toByteArray(Charsets.UTF_8)

    fun bindRequest(mac: String, cipher: GreeCipher): ByteArray =
        envelope(mac, bindOrScan = true, cipher = cipher, inner = buildJsonObject {
            put("t", "bind"); put("mac", canonicalMac(mac)); put("uid", 0)
        })

    fun statusRequest(mac: String, cipher: GreeCipher, cols: List<String> = GreeProps.STATUS_COLUMNS): ByteArray =
        envelope(mac, bindOrScan = false, cipher = cipher, inner = buildJsonObject {
            put("t", "status"); put("mac", canonicalMac(mac))
            putJsonArray("cols") { cols.forEach { add(JsonPrimitive(it)) } }
        })

    fun commandRequest(mac: String, cipher: GreeCipher, props: Map<String, Int>): ByteArray =
        envelope(mac, bindOrScan = false, cipher = cipher, inner = buildJsonObject {
            put("t", "cmd"); put("mac", canonicalMac(mac))
            putJsonArray("opt") { props.keys.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("p") { props.values.forEach { add(JsonPrimitive(it)) } }
        })

    private fun envelope(mac: String, bindOrScan: Boolean, cipher: GreeCipher, inner: JsonObject): ByteArray {
        val sealed = cipher.seal(inner.toString())
        return buildJsonObject {
            put("cid", "app"); put("i", if (bindOrScan) 1 else 0); put("t", "pack"); put("uid", 0)
            put("tcid", canonicalMac(mac)); put("pack", sealed.pack)
            sealed.tag?.let { put("tag", it) }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses the outer JSON of a datagram. Null when it is not a Gree packet with a pack. */
    fun outer(datagram: ByteArray): JsonObject? = try {
        (Json.parseToJsonElement(String(datagram, Charsets.UTF_8)) as? JsonObject)?.takeIf { it["pack"] is JsonPrimitive }
    } catch (_: Exception) {
        null
    }

    /** Decrypts the pack. Throws [GreeCryptoException] on a wrong key, [GreeFormatException] on bad JSON. */
    fun decrypt(outer: JsonObject, cipher: GreeCipher): GreeReply {
        val packText = cipher.open(outer.str("pack"), outer["tag"]?.let { (it as? JsonPrimitive)?.content })
        val inner = try { Json.parseToJsonElement(packText) as? JsonObject } catch (_: Exception) { null }
            ?: throw GreeFormatException("pack is not a JSON object")
        return GreeReply(inner)
    }

    /** Maps the property arrays of a "dat" (cols/dat) or "res" (opt/val, falling back to p) reply. */
    fun properties(reply: GreeReply): Map<String, Int> {
        val pack = reply.pack
        val (names, values) = when (reply.type) {
            "dat" -> pack["cols"] to pack["dat"]
            "res" -> pack["opt"] to ((pack["val"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: pack["p"])
            else -> throw GreeFormatException("not a property reply: ${reply.type}")
        }
        val n = names as? JsonArray ?: throw GreeFormatException("missing property names")
        val v = values as? JsonArray ?: throw GreeFormatException("missing property values")
        if (n.size != v.size) throw GreeFormatException("property arrays differ in length")
        val out = LinkedHashMap<String, Int>()
        for (i in n.indices) {
            val name = (n[i] as? JsonPrimitive)?.content ?: continue
            // Only integer values are properties; "" or text means the unit does not have that property.
            val value = (v[i] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: continue
            out[name] = value
        }
        return out
    }

    /** "r" result code when present (200 = OK). */
    fun resultCode(reply: GreeReply): Int? = (reply.pack["r"] as? JsonPrimitive)?.intOrNull

    /** Builds the property changes for one command; null when the protocol has no such control. */
    fun propertiesFor(command: AcCommand): Map<String, Int>? = when (command) {
        is AcCommand.SetPower -> mapOf(GreeProps.POWER to bit(command.on))
        is AcCommand.SetMode -> mapOf(GreeProps.MODE to GreeProps.modeCode(command.mode))
        // A setpoint is always sent with its unit and half-degree bit (Celsius, whole degrees).
        is AcCommand.SetTargetTemperature -> mapOf(
            GreeProps.SET_TEMP to command.celsius.toInt(), GreeProps.TEMP_UNIT to 0, GreeProps.TEMP_BIT to 0
        )
        is AcCommand.SetFanSpeed -> mapOf(GreeProps.FAN to GreeProps.fanCode(command.speed))
        is AcCommand.SetVerticalSwing -> mapOf(GreeProps.SWING_VERTICAL to bit(command.on))
        is AcCommand.SetHorizontalSwing -> mapOf(GreeProps.SWING_HORIZONTAL to bit(command.on))
        is AcCommand.SetFeature -> when (command.feature) {
            AcFeature.TURBO -> mapOf(GreeProps.TURBO to bit(command.on))
            AcFeature.QUIET -> mapOf(GreeProps.QUIET to if (command.on) 2 else 0)
            AcFeature.SLEEP -> mapOf(GreeProps.SLEEP to bit(command.on), GreeProps.SLEEP_MODE to bit(command.on))
            AcFeature.HEALTH -> mapOf(GreeProps.HEALTH to bit(command.on))
            AcFeature.DISPLAY -> mapOf(GreeProps.LIGHT to bit(command.on))
            AcFeature.ENERGY_SAVING -> mapOf(GreeProps.POWER_SAVE to bit(command.on))
        }
        is AcCommand.SetEcoLevel, AcCommand.StartSelfClean -> null
    }

    /** Builds a status from a "dat" reply. Absent properties stay null / out of the feature map. */
    fun status(props: Map<String, Int>, receivedAtEpochMs: Long): AcStatus? {
        val power = props[GreeProps.POWER] ?: return null
        val mode = props[GreeProps.MODE] ?: return null
        val setTemp = props[GreeProps.SET_TEMP] ?: return null
        val fan = props[GreeProps.FAN] ?: -1
        val features = buildMap {
            props[GreeProps.TURBO]?.let { put(AcFeature.TURBO, it != 0) }
            props[GreeProps.QUIET]?.let { put(AcFeature.QUIET, it != 0) }
            props[GreeProps.SLEEP]?.let { put(AcFeature.SLEEP, it != 0) }
            props[GreeProps.HEALTH]?.let { put(AcFeature.HEALTH, it != 0) }
            props[GreeProps.LIGHT]?.let { put(AcFeature.DISPLAY, it != 0) }
            props[GreeProps.POWER_SAVE]?.let { put(AcFeature.ENERGY_SAVING, it != 0) }
        }
        return AcStatus(
            power = power == 1,
            mode = GreeProps.mode(mode),
            rawModeCode = mode,
            targetTemperatureC = setTemp.toDouble(),
            fanSpeed = GreeProps.fan(fan),
            rawFanCode = fan,
            verticalSwing = props[GreeProps.SWING_VERTICAL]?.let { it in GreeProps.VERTICAL_SWINGING } ?: false,
            horizontalSwing = props[GreeProps.SWING_HORIZONTAL]?.let { it == 1 },
            indoorTemperatureC = props[GreeProps.TEMP_SENSOR]?.let { GreeProps.indoorCelsius(it) },
            outdoorTemperatureC = null,
            features = features,
            ecoLevel = null,
            selfCleaning = null,
            heatCapable = null,
            errorCode = null,
            writable = true,
            reportLength = props.size,
            receivedAtEpochMs = receivedAtEpochMs,
            reportedProperties = props.keys
        )
    }

    private fun bit(on: Boolean) = if (on) 1 else 0


}

/** Property names and value tables of the Gree protocol (as listed by greeclimate). */
object GreeProps {
    const val POWER = "Pow"
    const val MODE = "Mod"
    const val SET_TEMP = "SetTem"
    const val TEMP_SENSOR = "TemSen"
    const val TEMP_UNIT = "TemUn"
    const val TEMP_BIT = "TemRec"
    const val FAN = "WdSpd"
    const val HEALTH = "Health"
    const val SLEEP = "SwhSlp"
    const val SLEEP_MODE = "SlpMod"
    const val LIGHT = "Lig"
    const val SWING_HORIZONTAL = "SwingLfRig"
    const val SWING_VERTICAL = "SwUpDn"
    const val QUIET = "Quiet"
    const val TURBO = "Tur"
    const val POWER_SAVE = "SvSt"

    val STATUS_COLUMNS = listOf(
        POWER, MODE, SET_TEMP, TEMP_UNIT, TEMP_BIT, TEMP_SENSOR, FAN, SWING_VERTICAL, SWING_HORIZONTAL,
        TURBO, QUIET, SLEEP, SLEEP_MODE, LIGHT, HEALTH, POWER_SAVE
    )

    /** SwUpDn values that are a sweep (1 full swing, 7–11 partial sweeps); 0 and 2–6 are fixed. */
    val VERTICAL_SWINGING = setOf(1, 7, 8, 9, 10, 11)

    private val modes = mapOf(0 to AcMode.AUTO, 1 to AcMode.COOL, 2 to AcMode.DRY, 3 to AcMode.FAN, 4 to AcMode.HEAT)
    fun mode(code: Int): AcMode? = modes[code]
    fun modeCode(mode: AcMode): Int = modes.entries.first { it.value == mode }.key

    /** WdSpd: 0 auto, 1 low, 2 medium-low, 3 medium, 4 medium-high, 5 high. 2 and 4 have no common equivalent. */
    private val fans = mapOf(0 to FanSpeed.AUTO, 1 to FanSpeed.LOW, 3 to FanSpeed.MEDIUM, 5 to FanSpeed.HIGH)
    fun fan(code: Int): FanSpeed? = fans[code]
    fun fanCode(speed: FanSpeed): Int = fans.entries.first { it.value == speed }.key

    /**
     * TemSen carries a +40 offset on many modules and none on others. As in greeclimate's guess
     * without a firmware profile: 40 and above are offset, below 40 are plain. 0 means no reading.
     * Not yet confirmed on hardware for any model in the compatibility matrix.
     */
    fun indoorCelsius(raw: Int): Double? = when {
        raw == 0 || raw < -20 || raw > 90 -> null
        raw >= 40 -> (raw - 40).toDouble()
        else -> raw.toDouble()
    }
}
