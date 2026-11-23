package com.roomac.controller.driver.gree

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.ProtocolIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GreeCipherTest {

    /** Ciphertexts computed independently with `openssl enc -aes-128-ecb` (PKCS#7) and the generic key. */
    @Test
    fun `V1 matches an independently computed vector`() {
        val c = GreeCipher.generic(GreeCipherVersion.V1_ECB)
        assertEquals("VT6PQOWv+8FeevjTXFZdyQ==", c.seal("""{"t":"scan"}""").pack)
        assertEquals("""{"t":"scan"}""", c.open("VT6PQOWv+8FeevjTXFZdyQ==", null))
    }

    @Test
    fun `V2 round trip carries a separate tag and detects tampering`() {
        val c = GreeCipher(GreeCipherVersion.V2_GCM, "0123456789abcdef")
        val sealed = c.seal("""{"t":"status"}""")
        assertTrue(sealed.tag != null)
        assertEquals("""{"t":"status"}""", c.open(sealed.pack, sealed.tag))
        val badTag = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        expectCrypto { c.open(sealed.pack, badTag) }
        expectCrypto { c.open(sealed.pack, null) }
    }

    @Test
    fun `wrong key is detected, never decoded`() {
        val sealed = GreeCipher(GreeCipherVersion.V1_ECB, "0123456789abcdef").seal("""{"t":"dat","cols":["Pow"],"dat":[1]}""")
        expectCrypto { GreeCipher(GreeCipherVersion.V1_ECB, "fedcba9876543210").open(sealed.pack, null) }
        val gcm = GreeCipher(GreeCipherVersion.V2_GCM, "0123456789abcdef").seal("{}")
        expectCrypto { GreeCipher(GreeCipherVersion.V2_GCM, "fedcba9876543210").open(gcm.pack, gcm.tag) }
    }

    @Test
    fun `credential encoding`() {
        val c = GreeCredential(GreeCipherVersion.V2_GCM, "Ab3dEf7hIj1lMn0p")
        assertEquals("2:Ab3dEf7hIj1lMn0p", c.encode())
        assertEquals(c, GreeCredential.decode(c.encode()))
        assertNull(GreeCredential.decode("Ab3dEf7hIj1lMn0p"))
        assertNull(GreeCredential.decode("3:Ab3dEf7hIj1lMn0p"))
        assertNull(GreeCredential.decode("1:short"))
    }

    private fun expectCrypto(block: () -> Unit) {
        try { block(); fail("expected GreeCryptoException") } catch (_: GreeCryptoException) {}
    }
}

class GreeScanReplyTest {

    @Test
    fun `parses an ECB scan reply (pack computed with openssl)`() {
        val pack = "S8GlvPD6Cdv807vL1HH1f4o7/AWiUio8BOL9Hi3XMUJSMznvK0I6J5Vht4wnw/X1j5SZirgTmDlDPNT7r9B35FTdQLdqrzXMrJiA9bXykFg+bB7E/Htqkv2DjsXghrE9"
        val datagram = """{"t":"pack","i":1,"uid":0,"cid":"f4911e0a1b2c","tcid":"","pack":"$pack"}""".toByteArray()
        val d = GreeScanReply.parse(datagram, "192.168.1.40")!!
        assertEquals("F4911E0A1B2C", d.deviceId)
        assertEquals("192.168.1.40", d.host)
        assertEquals(7000, d.port)
        assertEquals(ProtocolIds.GREE_WIFI, d.protocolId)
        assertEquals("gree", d.brand)
        assertEquals("Bedroom", d.reportedName)
        assertEquals("V1.2.1", d.firmware)
    }

    @Test
    fun `parses a GCM scan reply and keeps an OEM brand as reported`() {
        val sealed = GreeCipher.generic(GreeCipherVersion.V2_GCM).seal("""{"t":"dev","cid":"aabbccddeeff","brand":"SomeOEM","model":"X1"}""")
        val datagram = """{"t":"pack","i":1,"cid":"aabbccddeeff","pack":"${sealed.pack}","tag":"${sealed.tag}"}""".toByteArray()
        val d = GreeScanReply.parse(datagram, "10.0.0.9")!!
        assertEquals("AABBCCDDEEFF", d.deviceId)
        assertEquals("someoem", d.brand)
        assertEquals("X1", d.model)
    }

    @Test
    fun `ignores other datagrams`() {
        assertNull(GreeScanReply.parse("""{"t":"scan"}""".toByteArray(), "1.2.3.4"))
        assertNull(GreeScanReply.parse("not json".toByteArray(), "1.2.3.4"))
        assertNull(GreeScanReply.parse("""{"t":"pack","pack":"AAAA"}""".toByteArray(), "1.2.3.4"))
        val notDev = GreeCipher.generic(GreeCipherVersion.V1_ECB).seal("""{"t":"bindok","mac":"aabbccddeeff","key":"0123456789abcdef"}""")
        assertNull(GreeScanReply.parse("""{"t":"pack","pack":"${notDev.pack}"}""".toByteArray(), "1.2.3.4"))
    }
}

class GreeCodecTest {

    private val cipher = GreeCipher(GreeCipherVersion.V1_ECB, "0123456789abcdef")

    private fun inner(datagram: ByteArray): JsonObject {
        val outer = GreeCodec.outer(datagram)!!
        return Json.parseToJsonElement(cipher.open(outer["pack"]!!.jsonPrimitive.content, null)) as JsonObject
    }

    @Test
    fun `envelopes follow the reference layout`() {
        val req = GreeCodec.statusRequest("F4:91:1E:0A:1B:2C", cipher)
        val outer = Json.parseToJsonElement(String(req)) as JsonObject
        assertEquals("app", outer["cid"]!!.jsonPrimitive.content)
        assertEquals("0", outer["i"]!!.jsonPrimitive.content)
        assertEquals("pack", outer["t"]!!.jsonPrimitive.content)
        assertEquals("f4911e0a1b2c", outer["tcid"]!!.jsonPrimitive.content)
        val pack = inner(req)
        assertEquals("status", pack["t"]!!.jsonPrimitive.content)
        assertEquals("f4911e0a1b2c", pack["mac"]!!.jsonPrimitive.content)

        val bind = Json.parseToJsonElement(String(GreeCodec.bindRequest("f4911e0a1b2c", cipher))) as JsonObject
        assertEquals("1", bind["i"]!!.jsonPrimitive.content)
    }

    @Test
    fun `command properties`() {
        assertEquals(mapOf("Pow" to 1), GreeCodec.propertiesFor(AcCommand.SetPower(true)))
        assertEquals(mapOf("Mod" to 3), GreeCodec.propertiesFor(AcCommand.SetMode(AcMode.FAN)))
        assertEquals(mapOf("Mod" to 4), GreeCodec.propertiesFor(AcCommand.SetMode(AcMode.HEAT)))
        assertEquals(mapOf("SetTem" to 24, "TemUn" to 0, "TemRec" to 0), GreeCodec.propertiesFor(AcCommand.SetTargetTemperature(24.0)))
        assertEquals(mapOf("WdSpd" to 3), GreeCodec.propertiesFor(AcCommand.SetFanSpeed(FanSpeed.MEDIUM)))
        assertEquals(mapOf("WdSpd" to 0), GreeCodec.propertiesFor(AcCommand.SetFanSpeed(FanSpeed.AUTO)))
        assertEquals(mapOf("SwUpDn" to 1), GreeCodec.propertiesFor(AcCommand.SetVerticalSwing(true)))
        assertEquals(mapOf("Quiet" to 2), GreeCodec.propertiesFor(AcCommand.SetFeature(AcFeature.QUIET, true)))
        assertEquals(mapOf("SwhSlp" to 0, "SlpMod" to 0), GreeCodec.propertiesFor(AcCommand.SetFeature(AcFeature.SLEEP, false)))
        assertNull(GreeCodec.propertiesFor(AcCommand.SetEcoLevel(EcoLevel.LEVEL_1)))
        assertNull(GreeCodec.propertiesFor(AcCommand.StartSelfClean))

        val cmd = inner(GreeCodec.commandRequest("aabbccddeeff", cipher, mapOf("Pow" to 1, "Mod" to 1)))
        assertEquals("cmd", cmd["t"]!!.jsonPrimitive.content)
        assertEquals("""["Pow","Mod"]""", cmd["opt"].toString())
        assertEquals("[1,1]", cmd["p"].toString())
    }

    @Test
    fun `status mapping`() {
        val s = GreeCodec.status(
            mapOf("Pow" to 1, "Mod" to 1, "SetTem" to 23, "WdSpd" to 2, "SwUpDn" to 7, "SwingLfRig" to 0,
                "TemSen" to 66, "Quiet" to 2, "Tur" to 0, "Lig" to 1), 5L
        )!!
        assertTrue(s.power)
        assertEquals(AcMode.COOL, s.mode)
        assertEquals(23.0, s.targetTemperatureC, 0.0)
        assertNull(s.fanSpeed) // medium-low has no common equivalent
        assertEquals(2, s.rawFanCode)
        assertTrue(s.verticalSwing)
        assertEquals(false, s.horizontalSwing)
        assertEquals(26.0, s.indoorTemperatureC!!, 0.0)
        assertEquals(true, s.features[AcFeature.QUIET])
        assertEquals(false, s.features[AcFeature.TURBO])
        assertEquals(true, s.features[AcFeature.DISPLAY])
        assertFalse(s.features.containsKey(AcFeature.HEALTH))
        assertTrue("Lig" in s.reportedProperties)
        assertTrue(AcCommand.SetFeature(AcFeature.QUIET, true).isReflectedIn(s))
    }

    @Test
    fun `indoor temperature offset rule`() {
        assertNull(GreeProps.indoorCelsius(0))
        assertEquals(25.0, GreeProps.indoorCelsius(25)!!, 0.0)
        assertEquals(25.0, GreeProps.indoorCelsius(65)!!, 0.0)
        assertEquals(0.0, GreeProps.indoorCelsius(40)!!, 0.0)
    }

    @Test
    fun `status needs the core properties`() {
        assertNull(GreeCodec.status(mapOf("Pow" to 1, "Mod" to 1), 0L))
    }

    @Test
    fun `property arrays skip text values and fall back to p`() {
        val dat = GreeReply(Json.parseToJsonElement("""{"t":"dat","cols":["Pow","Air","SetTem"],"dat":[1,"",24]}""") as JsonObject)
        assertEquals(mapOf("Pow" to 1, "SetTem" to 24), GreeCodec.properties(dat))
        val res = GreeReply(Json.parseToJsonElement("""{"t":"res","opt":["Pow"],"p":[0],"val":[]}""") as JsonObject)
        assertEquals(mapOf("Pow" to 0), GreeCodec.properties(res))
        try {
            GreeCodec.properties(GreeReply(Json.parseToJsonElement("""{"t":"dat","cols":["Pow"],"dat":[]}""") as JsonObject))
            fail("length mismatch must be rejected")
        } catch (_: GreeFormatException) {}
    }
}
