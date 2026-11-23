package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.ProtocolIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Vectors are captured device traffic published in msmart-ng's own test suite (revision dd09947, MIT:
 * tests/test_lan.py, tests/test_discover.py, device/AC/test_command.py). The V3 session key there is a
 * test fixture of that project, not a credential of any RoomAC user.
 */
private fun h(s: String) = s.unhex()

private const val V2_PACKET = "5a5a01116800208000000000000000000000000060ca0000000e0000000000000000000001000000c6a90377a364cb55af337259514c6f96bf084e8c7a899b50b68920cdea36cecf11c882a88861d1f46cd87912f201218c66151f0c9fbe5941c5384e707c36ff76"
private const val V2_FRAME = "aa22ac00000000000303c0014566000000300010045cff2070000000000000008bed19"
private const val V3_PACKET = "8370008e2063ec2b8aeb17d4e3aff77094dde7fa65cf22671adf807f490a97b927347943626e9b4f58362cf34b97a0d641f8bf0c8fcbf69ad8cca131d2d7baa70ef048c5e3f3dc78da8af4598ff47aee762a0345c18815d91b50a24dedcacde0663c4ec5e73a963dc8bbbea9a593859996eb79dcfcc6a29b96262fcaa8ea6346366efea214e4a2e48caf83489475246b6fef90192b00"
private const val V3_SESSION_KEY = "55a0a178746a424bf1fc6bb74b9fb9e4515965048d24ce8dc72aca91597d05ab"
private const val V3_FRAME = "aa23ac00000000000303c00145660000003c0010045c6800000000000000000000018426"
private const val DISCOVERY_V2 = "5a5a011178007a8000000000000000000000000060ca0000000e0000000000000000000001000000c08651cb1b88a167bdcf7d37534ef81312d39429bf9b2673f200b635fae369a560fa9655eab8344be22b1e3b024ef5dfd392dc3db64dbffb6a66fb9cd5ec87a78000cd9043833b9f76991e8af29f3496"
private const val DISCOVERY_V3 = "837000c8200f00005a5a0111b8007a800000000061433702060817143daa00000086000000000000000001800000000041c7129527bc03ee009284a90c2fbd2f179764ac35b55e7fb0e4ab0de9298fa1a5ca328046c603fb1ab60079d550d03546b605180127fdb5bb33a105f5206b5f008bffba2bae272aa0c96d56b45c4afa33f826a0a4215d1dd87956a267d2dbd34bdfb3e16e33d88768cc4c3d0658937d0bb19369bf0317b24d3a4de9e6a13106f7ceb5acc6651ce53d684a32ce34dc3a4fbe0d4139de99cc88a0285e14657045"

class MideaProtocolTest {

    @Test
    fun `crc8 table matches msmart's published table`() {
        // First entries of msmart crc8.py _CRC8_854_TABLE.
        assertEquals(listOf(0x00, 0x5E, 0xBC, 0xE2, 0x61, 0x3F, 0xDD, 0x83), (0 until 8).map { MideaCrc8.tableEntry(it) })
        assertEquals(listOf(0xC2, 0x9C, 0x7E, 0x20), (8 until 12).map { MideaCrc8.tableEntry(it) })
    }

    @Test
    fun `captured V2 packet decodes to the expected frame`() {
        assertArrayEquals(h(V2_FRAME), MideaV2Packet.decode(h(V2_PACKET)))
    }

    @Test
    fun `a tampered V2 packet is refused`() {
        val p = h(V2_PACKET).also { it[45] = (it[45] + 1).toByte() }
        try { MideaV2Packet.decode(p); fail() } catch (_: MideaProtocolException) {}
    }

    @Test
    fun `V2 packet round trip`() {
        val frame = MideaAcMessages.getState()
        assertArrayEquals(frame, MideaV2Packet.decode(MideaV2Packet.encode(123456789L, frame)))
    }

    @Test
    fun `captured V3 packet decrypts with its session key to the inner V2 packet and frame`() {
        val s = MideaV3Session().apply { sessionKey = h(V3_SESSION_KEY) }
        val d = s.decode(h(V3_PACKET)) as MideaV3Session.Decoded.Data
        assertArrayEquals(h(V3_FRAME), MideaV2Packet.decode(d.inner))
    }

    @Test
    fun `V3 encrypted request decodes back with the same session key`() {
        val s = MideaV3Session().apply { sessionKey = h(V3_SESSION_KEY) }
        val inner = MideaV2Packet.encode(42L, MideaAcMessages.getState())
        val request = s.encryptedRequest(inner)
        // A response is laid out the same way; flip the type nibble to read our own request back.
        val asResponse = request.copyOf().also { it[5] = ((it[5].toInt() and 0xF0) or MideaV3Session.TYPE_ENCRYPTED_RESPONSE).toByte() }
        val hashFixed = asResponse.copyOfRange(0, asResponse.size - 32) +
            MideaSecurity.sha256(asResponse.copyOfRange(0, 6) + MideaSecurity.cbc(javax.crypto.Cipher.DECRYPT_MODE, h(V3_SESSION_KEY), asResponse.copyOfRange(6, asResponse.size - 32)))
        assertArrayEquals(inner, (s.decode(hashFixed) as MideaV3Session.Decoded.Data).inner)
    }

    @Test
    fun `V3 handshake derives the session key and rejects a wrong cloud key`() {
        val key = ByteArray(32) { it.toByte() }
        val plain = ByteArray(32) { (it * 7).toByte() }
        // What a module sends back: AES-CBC(key, P) followed by SHA-256(P); the session key is P xor key.
        val reply = MideaSecurity.cbc(javax.crypto.Cipher.ENCRYPT_MODE, key, plain) + MideaSecurity.sha256(plain)
        val s = MideaV3Session()
        s.completeHandshake(reply, key)
        assertArrayEquals(ByteArray(32) { (plain[it].toInt() xor key[it].toInt()).toByte() }, s.sessionKey)
        try { MideaV3Session().completeHandshake(reply, ByteArray(32)); fail() } catch (_: MideaProtocolException) {}
    }

    @Test
    fun `captured discovery replies identify V2 and V3 air conditioners`() {
        val v2 = MideaDiscoveryCodec.parse(h(DISCOVERY_V2), "10.100.1.140")!!
        assertEquals(2, v2.version)
        assertEquals(15393162840672L, v2.deviceId)
        assertEquals(6444, v2.port)
        assertEquals("net_ac_F7B4", v2.ssid)
        assertEquals("000000P0000000Q1F0C9D153F7B40000", v2.serial)
        assertTrue(v2.isAirConditioner)
        assertEquals(ProtocolIds.MIDEA_V2, MideaDiscoveryCodec.toDiscovered(v2).protocolId)

        val v3 = MideaDiscoveryCodec.parse(h(DISCOVERY_V3), "10.100.1.239")!!
        assertEquals(3, v3.version)
        assertEquals(147334558165565L, v3.deviceId)
        assertEquals(6444, v3.port)
        assertTrue(v3.isAirConditioner)
        assertEquals(ProtocolIds.MIDEA_V3, MideaDiscoveryCodec.toDiscovered(v3).protocolId)
    }

    @Test
    fun `unrelated datagrams are not taken for a Midea unit`() {
        assertNull(MideaDiscoveryCodec.parse("hello".toByteArray(), "1.2.3.4"))
        assertNull(MideaDiscoveryCodec.parse(ByteArray(0), "1.2.3.4"))
    }
}

class MideaAcMessagesTest {

    @Test
    fun `captured V2 state reply`() {
        val s = MideaAcMessages.parseState(h("aa22ac00000000000303c0014566000000300010045eff00000000000000000069fdb9"))!!
        assertEquals(21.0, s.targetTemperature, 0.0)
        assertEquals(22.0, s.indoorTemperature!!, 0.0)
        assertNull(s.outdoorTemperature)
    }

    @Test
    fun `captured V3 state reply`() {
        val s = MideaAcMessages.parseState(h("aa23ac00000000000303c00145660000003c0010045c6b20000000000000000000020d79"))!!
        assertEquals(21.0, s.targetTemperature, 0.0)
        assertEquals(21.0, s.indoorTemperature!!, 0.0)
        assertEquals(28.5, s.outdoorTemperature!!, 0.0)
    }

    @Test
    fun `short reply whose body ends in a checksum instead of a CRC`() {
        val s = MideaAcMessages.parseState(h("aa1eac00000000000003c0004b1e7f7f000000000069630000000000000d33"))!!
        assertEquals(27.0, s.targetTemperature, 0.0)
        assertEquals(27.5, s.indoorTemperature!!, 0.0)
        assertEquals(24.5, s.outdoorTemperature!!, 0.0)
    }

    @Test
    fun `corrupt frames are refused`() {
        val bad = h("aa22ac00000000000303c0014566000000300010045eff00000000000000000069fdb9").also { it[it.size - 1] = (it.last() + 1).toByte() }
        try { MideaAcMessages.parseState(bad); fail() } catch (_: MideaProtocolException) {}
    }

    @Test
    fun `commands are well-formed frames with a valid body CRC`() {
        for (frame in listOf(MideaAcMessages.getState(), MideaAcMessages.setState(sample()))) {
            val body = MideaFrame.validate(frame, MideaFrame.TYPE_AC)
            assertEquals(MideaCrc8.calculate(body, 0, body.size - 1), body.last().toInt() and 0xFF)
        }
        assertEquals(0x41, MideaFrame.validate(MideaAcMessages.getState(), MideaFrame.TYPE_AC)[0].toInt())
    }

    @Test
    fun `a state write carries every current setting and only the requested change`() {
        val cur = sample()
        val next = MideaAcMessages.apply(cur, AcCommand.SetTargetTemperature(24.0))!!
        assertEquals(cur.copy(targetTemperature = 24.0), next)
        val body = MideaFrame.validate(MideaAcMessages.setState(next), MideaFrame.TYPE_AC)
        assertEquals(0x40, body[0].toInt())
        assertEquals(0x43, body[1].toInt() and 0xFF)                    // app control | beep | power on
        assertEquals((24 - 16) or (2 shl 5), body[2].toInt() and 0xFF) // 24 °C, cool
        assertEquals(102, body[3].toInt() and 0xFF)                     // fan auto
    }

    @Test
    fun `mapping to the unified model`() {
        assertEquals(AcMode.COOL, MideaAcMessages.modeOf(2))
        assertNull(MideaAcMessages.modeOf(6)) // smart dry has no unified equivalent: shown by code, not guessed
        assertEquals(FanSpeed.AUTO, MideaAcMessages.fanOf(102))
        assertNull(MideaAcMessages.fanOf(20))
        val st = MideaAcMessages.toStatus(sample().copy(swingMode = 0xC, eco = true), 0)
        assertTrue(st.verticalSwing)
        assertEquals(false, st.horizontalSwing)
        assertEquals(true, st.features[AcFeature.ENERGY_SAVING])
        assertFalse(st.features.containsKey(AcFeature.DISPLAY))
        assertFalse(MideaAcMessages.toStatus(sample().copy(fahrenheit = true), 0).writable)
        assertNull(MideaAcMessages.apply(sample(), AcCommand.StartSelfClean))
    }

    private fun sample() = MideaAcState(
        power = true, targetTemperature = 22.0, mode = 2, fanSpeed = 102, swingMode = 0, turbo = false, eco = false,
        sleep = false, fahrenheit = false, indoorTemperature = 25.0, outdoorTemperature = 30.0, displayOn = true,
        errorCode = 0, freezeProtection = false, purifier = false, followMe = false, auxHeat = false,
        independentAuxHeat = false, length = 0
    )
}

class MideaCredentialTest {
    @Test
    fun `credentials round trip and never print secrets`() {
        assertEquals(MideaCredential.None, MideaCredential.decode("v2"))
        val v3 = MideaCredential.V3(listOf(ByteArray(64) { 1 } to ByteArray(32) { 2 }))
        val back = MideaCredential.decode(v3.encode()) as MideaCredential.V3
        assertArrayEquals(ByteArray(64) { 1 }, back.candidates.single().first)
        assertFalse(v3.toString().contains("0101"))
        assertNull(MideaCredential.decode("v3:zz"))
        assertNull(MideaCredential.decode("v3:00:00")) // wrong sizes
    }

    @Test
    fun `udpids of the two byte orders differ and are 16 bytes`() {
        val le = MideaAccount.udpidHex(147334558165565L, bigEndian = false)
        val be = MideaAccount.udpidHex(147334558165565L, bigEndian = true)
        assertEquals(32, le.length)
        assertFalse(le == be)
    }
}
