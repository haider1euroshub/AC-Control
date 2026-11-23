package com.roomac.controller.driver.gree

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAC = "f4911e0a1b2c"
private const val HOST = "192.168.1.40"
private const val DEVICE_KEY = "Ab3dEf7hIj1lMn0p"

/**
 * A simulated Gree module speaking the wire protocol: it answers scans and binds with the generic
 * key, and status/commands only with its own key. It applies commands to its state unless told to
 * ignore them. Used to exercise the client end to end through real encryption and JSON.
 */
private class FakeGreeAc(
    val version: GreeCipherVersion = GreeCipherVersion.V1_ECB,
    var key: String = DEVICE_KEY,
    val mac: String = MAC
) : GreeTransport {
    val state = linkedMapOf("Pow" to 0, "Mod" to 1, "SetTem" to 24, "TemUn" to 0, "TemRec" to 0, "TemSen" to 67,
        "WdSpd" to 0, "SwUpDn" to 0, "Tur" to 0, "Quiet" to 0, "SwhSlp" to 0, "SlpMod" to 0, "Lig" to 1)
    var online = true
    var ignoreCommands = false
    var refuseWith: Int? = null
    var silentOnCommand = false
    var requests = 0

    override suspend fun request(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? {
        requests++
        if (!online || host != HOST || port != GreeCodec.PORT) return null
        val outer = Json.parseToJsonElement(String(payload)) as JsonObject
        if (outer["t"]!!.jsonPrimitive.content == "scan") return reply(GreeCipher.generic(version), buildJsonObject {
            put("t", "dev"); put("mac", mac); put("brand", "gree"); put("name", "Lounge"); put("ver", "V1.2.1")
        })
        val isBind = outer["i"]!!.jsonPrimitive.int == 1
        val cipher = if (isBind) GreeCipher.generic(version) else GreeCipher(version, key)
        val inner = try {
            Json.parseToJsonElement(cipher.open(outer["pack"]!!.jsonPrimitive.content, outer["tag"]?.jsonPrimitive?.content)) as JsonObject
        } catch (_: Exception) {
            return null // a real module stays silent on packets it cannot decrypt
        }
        return when (inner["t"]!!.jsonPrimitive.content) {
            "bind" -> reply(cipher, buildJsonObject { put("t", "bindok"); put("mac", mac); put("key", key); put("r", 200) })
            "status" -> {
                val cols = (inner["cols"] as JsonArray).map { it.jsonPrimitive.content }
                reply(cipher, buildJsonObject {
                    put("t", "dat"); put("mac", mac); put("r", 200)
                    putJsonArray("cols") { cols.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("dat") { cols.forEach { add(state[it]?.let { v -> JsonPrimitive(v) } ?: JsonPrimitive("")) } }
                })
            }
            "cmd" -> {
                if (silentOnCommand) return null
                val opt = (inner["opt"] as JsonArray).map { it.jsonPrimitive.content }
                val p = (inner["p"] as JsonArray).map { it.jsonPrimitive.int }
                refuseWith?.let { code -> return reply(cipher, buildJsonObject { put("t", "res"); put("mac", mac); put("r", code) }) }
                if (!ignoreCommands) opt.zip(p).forEach { (k, v) -> state[k] = v }
                reply(cipher, buildJsonObject {
                    put("t", "res"); put("mac", mac); put("r", 200)
                    putJsonArray("opt") { opt.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("val") { p.forEach { add(JsonPrimitive(it)) } }
                })
            }
            else -> null
        }
    }

    private fun reply(cipher: GreeCipher, inner: JsonObject): ByteArray {
        val sealed = cipher.seal(inner.toString())
        return buildJsonObject {
            put("t", "pack"); put("i", 0); put("uid", 0); put("cid", mac); put("tcid", "app"); put("pack", sealed.pack)
            sealed.tag?.let { put("tag", it) }
        }.toString().toByteArray()
    }
}

class GreeClientTest {

    private val endpoint = AcEndpoint(HOST, GreeCodec.PORT, MAC.uppercase())
    private fun client(ac: FakeGreeAc) = GreeClient(ac, GreeDiscovery(ac), clock = { 9L }, replyTimeoutMs = 10, settleMs = 1)
    private fun credential(ac: FakeGreeAc) = GreeCredential(ac.version, ac.key).encode()

    @Test
    fun `binds automatically and returns the device key`() = runTest {
        val ac = FakeGreeAc()
        val r = client(ac).bind(endpoint)
        assertEquals(ProvisionResult.Success("1:$DEVICE_KEY"), r)
    }

    @Test
    fun `falls back to the GCM cipher for newer modules`() = runTest {
        val ac = FakeGreeAc(GreeCipherVersion.V2_GCM)
        assertEquals(ProvisionResult.Success("2:$DEVICE_KEY"), client(ac).bind(endpoint))
        val read = client(ac).readStatus(endpoint, "2:$DEVICE_KEY", null)
        assertTrue(read is ReadResult.Success)
    }

    @Test
    fun `bind to an AC that does not answer fails clearly`() = runTest {
        val ac = FakeGreeAc().apply { online = false }
        val r = client(ac).bind(endpoint) as ProvisionResult.Failure
        assertTrue(r.error is AcError.ProvisioningFailed)
    }

    @Test
    fun `a bind answer from another unit is refused`() = runTest {
        val ac = FakeGreeAc(mac = "001122334455")
        val r = client(ac).bind(endpoint) as ProvisionResult.Failure
        assertEquals(AcError.HandshakeRefused, r.error)
    }

    @Test
    fun `reads status`() = runTest {
        val ac = FakeGreeAc()
        val read = client(ac).readStatus(endpoint, credential(ac), null) as ReadResult.Success
        assertFalse(read.status.power)
        assertEquals(AcMode.COOL, read.status.mode)
        assertEquals(FanSpeed.AUTO, read.status.fanSpeed)
        assertEquals(27.0, read.status.indoorTemperatureC!!, 0.0)
        // Properties the unit did not return are not offered.
        val caps = GreeAdapter(ac).capabilities(PairedDevice(MAC, "x", protocolId = ProtocolIds.GREE_WIFI), read.status)
        assertFalse(caps.horizontalSwing)
        assertTrue(caps.verticalSwing)
        assertFalse(AcFeature.HEALTH in caps.features)
        assertTrue(AcFeature.DISPLAY in caps.features)
        assertTrue(caps.ecoLevels.isEmpty())
    }

    @Test
    fun `a stale key is reported as invalid when the AC still answers discovery`() = runTest {
        val ac = FakeGreeAc()
        val stale = GreeCredential(GreeCipherVersion.V1_ECB, "zzzzzzzzzzzzzzzz").encode()
        assertEquals(ReadResult.Failure(AcError.KeyInvalid), client(ac).readStatus(endpoint, stale, null))
    }

    @Test
    fun `an AC that is off the network is unreachable`() = runTest {
        val ac = FakeGreeAc().apply { online = false }
        assertEquals(ReadResult.Failure(AcError.Unreachable(HOST)), client(ac).readStatus(endpoint, credential(ac), null))
    }

    @Test
    fun `garbage credential is invalid without any network traffic`() = runTest {
        val ac = FakeGreeAc()
        assertEquals(ReadResult.Failure(AcError.KeyInvalid), client(ac).readStatus(endpoint, "nonsense", null))
        assertEquals(0, ac.requests)
    }

    @Test
    fun `command confirmed only by status read-back`() = runTest {
        val ac = FakeGreeAc()
        val out = client(ac).execute(endpoint, credential(ac), AcCommand.SetPower(true), null)
        assertTrue(out is CommandOutcome.Confirmed)
        assertTrue((out as CommandOutcome.Confirmed).status.power)
        assertEquals(1, ac.state["Pow"])
    }

    @Test
    fun `acknowledged but not applied is rejected`() = runTest {
        val ac = FakeGreeAc().apply { ignoreCommands = true }
        val out = client(ac).execute(endpoint, credential(ac), AcCommand.SetTargetTemperature(20.0), null)
        assertTrue(out is CommandOutcome.Rejected)
        assertEquals(24.0, (out as CommandOutcome.Rejected).status!!.targetTemperatureC, 0.0)
    }

    @Test
    fun `refusal code is reported`() = runTest {
        val ac = FakeGreeAc().apply { refuseWith = 400 }
        val out = client(ac).execute(endpoint, credential(ac), AcCommand.SetMode(AcMode.HEAT), null)
        assertTrue(out is CommandOutcome.Rejected)
    }

    @Test
    fun `no acknowledgement still verifies by reading status`() = runTest {
        val ac = FakeGreeAc().apply { silentOnCommand = true }
        val out = client(ac).execute(endpoint, credential(ac), AcCommand.SetFeature(AcFeature.TURBO, true), null)
        assertTrue(out is CommandOutcome.Rejected) // nothing applied, and the status shows it
    }

    @Test
    fun `controls the protocol lacks are not sent`() = runTest {
        val ac = FakeGreeAc()
        val out = client(ac).execute(endpoint, credential(ac), AcCommand.SetEcoLevel(EcoLevel.LEVEL_1), null)
        assertTrue((out as CommandOutcome.Failed).error is AcError.NotSupported)
        assertEquals(0, ac.requests)
    }

    @Test
    fun `discovery query identifies the unit`() = runTest {
        val ac = FakeGreeAc()
        val d = GreeDiscovery(ac, queryTimeoutMs = 10).query(HOST)!!
        assertEquals(MAC.uppercase(), d.deviceId)
        assertEquals("Lounge", d.reportedName)
    }
}
