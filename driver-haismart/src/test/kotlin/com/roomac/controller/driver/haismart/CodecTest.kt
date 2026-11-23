package com.roomac.controller.driver.haismart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UssCodecTest {

    @Test
    fun `hello carries the device id padded to 32 bytes`() {
        val hello = UssCodec.hello("A1B2C3D4E5F6")
        assertEquals("0000ea60002a0100000000010000" + "0000", Bytes.hex(hello.copyOfRange(0, 16)))
        assertEquals(48, hello.size)
        assertEquals("A1B2C3D4E5F6", String(hello, 16, 12, Charsets.US_ASCII))
        assertTrue(hello.copyOfRange(28, 48).all { it == 0.toByte() })
    }

    @Test
    fun `parses the HELLO_RESP captured from the target AC`() {
        // Captured 2026-11-11 from the HSU-20HFTEX at 192.168.18.68: key-free, read-only handshake.
        val captured = Bytes.fromHex("0000ea610012010000000001000076df0000000100000032")
        val msg = UssCodec.decode(captured)
        assertEquals(UssCodec.INFO_HELLO_RESP, msg.infoType)
        val resp = UssCodec.parseHelloResp(msg)
        assertEquals(0x76df, resp.session)
        assertEquals(1L, resp.status)
        assertEquals(50, resp.localKeyVersion)
        assertEquals(0x01, UssCodec.negotiatedTypeByte(msg))
    }

    @Test
    fun `splits batched messages and keeps a trailing fragment out`() {
        val a = UssCodec.encode(UssCodec.INFO_OP, 0, ByteArray(48) { 1 }, flag = 1, session = 7)
        val b = UssCodec.encode(UssCodec.INFO_OP, 0, ByteArray(64) { 2 }, flag = 1, session = 7)
        val stream = a + b + b.copyOfRange(0, 10)
        val parts = UssCodec.split(stream)
        assertEquals(2, parts.size)
        assertArrayEquals(a, parts[0])
        assertArrayEquals(b, parts[1])
        assertEquals(64, UssCodec.decode(parts[1]).payload.size)
    }

    @Test
    fun `a declared length shorter than a header is rejected`() {
        val bad = Bytes.fromHex("0000ea640005010100000000000000000000")
        assertThrows(UssFormatException::class.java) { UssCodec.completeLength(bad, 0, bad.size) }
        assertTrue(UssCodec.split(bad).isEmpty())
    }
}

class EppFrameTest {

    @Test
    fun `getAllProperty matches the documented safe query frame`() {
        assertEquals("ffff0a000000000000014d0159", Bytes.hex(EppFrame.getAllProperty()))
    }

    @Test
    fun `extended status frames match both documented forms`() {
        assertEquals("ffff0a000000000000014dfe56", Bytes.hex(EppFrame.build(0x01, byteArrayOf(0x4D, 0xFE.toByte()))))
        assertEquals("ffff0a000000000000604dfeb5", Bytes.hex(EppFrame.build(0x60, byteArrayOf(0x4D, 0xFE.toByte()))))
    }

    @Test
    fun `refusal frame confirmed on hardware decodes its reason code`() {
        val refusal = Bytes.fromHex("ffff0a000000000000030000" + "0d")
        assertEquals("ffff0a0000000000000300000d", Bytes.hex(EppFrame.build(0x03, byteArrayOf(0, 0))))
        assertEquals(EppFrame.TYPE_REFUSED, EppFrame.frameType(refusal))
        assertEquals(0, EppFrame.refusalCode(refusal))
        assertNull(EppFrame.refusalCode(EppFrame.getAllProperty()))
    }

    @Test
    fun `manufacturer escape example unescapes and its checksum counts inserted bytes`() {
        val escaped = Bytes.fromHex("ffff0d41020304050602" + "6d0105ff55ff55" + "7f9f3c")
        val plain = EppFrame.destuff(escaped)
        assertEquals("ffff0d410203040506026d0105ffff7f9f3c", Bytes.hex(plain))
        val body = plain.copyOfRange(2, 2 + 13)
        assertEquals(0x7f, EppFrame.checksum(body))
    }

    @Test
    fun `stuffing round-trips a frame containing 0xFF`() {
        val frame = EppFrame.groupSet(byteArrayOf(0x0E, 0xFF.toByte(), 0x20, 0x05, 0, 1, 0, 0, 0, 0, 0, 0))
        val wire = EppFrame.stuff(frame)
        assertEquals(frame.size + 1, wire.size)
        assertArrayEquals(frame, EppFrame.destuff(wire))
    }

    @Test
    fun `op envelope places device id, counter and frame`() {
        val env = CaeEnvelope.request(EppFrame.getAllProperty(), "A1B2C3D4E5F6", 3)
        assertEquals("00002714", Bytes.hex(env.copyOfRange(0, 4)))
        assertTrue(env.copyOfRange(4, 40).all { it == 0.toByte() })
        assertEquals("A1B2C3D4E5F6", String(env, 40, 12, Charsets.US_ASCII))
        assertEquals(3L, Bytes.be32(env, 72))
        assertEquals(13L, Bytes.be32(env, 76))
        assertEquals("ffff0a000000000000014d0159", Bytes.hex(env.copyOfRange(80, env.size)))
    }
}

class BizCryptoTest {
    private val key = "0123456789abcdef0123456789abcdef"

    @Test
    fun `encrypt matches an independently computed vector`() {
        // Computed with .NET System.Security.Cryptography (AES-CBC, zero IV, MD5 key) from the layout
        // documented in PROTOCOL.md, independent of this implementation.
        val expected = "4c1e97b4095a4900fbf442937c9acafde609765bdf0d226cf8c81c4575dfaac5" +
            "3ae7f1b375d61810d3d67bd352105f7d9689a4309724c52a6ca04275db604f81" + "3132333435"
        val data = ByteArray(20) { it.toByte() }
        val out = BizCrypto(key).encrypt(0x01020304, data, "12345".toByteArray())
        assertEquals(expected, Bytes.hex(out))
    }

    @Test
    fun `decrypt recovers sn and data, ignoring the nonce trailer`() {
        val data = ByteArray(97) { (it * 7).toByte() }
        val ct = BizCrypto(key).encrypt(42, data)
        val back = BizCrypto(key).decrypt(ct)
        assertEquals(42L, back.sn)
        assertArrayEquals(data, back.data)
    }

    @Test
    fun `a wrong key fails the integrity check`() {
        val ct = BizCrypto(key).encrypt(1, ByteArray(30) { 9 })
        assertThrows(BizIntegrityException::class.java) { BizCrypto("ffffffffffffffffffffffffffffffff").decrypt(ct) }
    }

    @Test
    fun `key case matters because the key is hashed as text`() {
        val ct = BizCrypto(key).encrypt(1, ByteArray(30) { 9 })
        assertThrows(BizIntegrityException::class.java) { BizCrypto(key.uppercase()).decrypt(ct) }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val ct = BizCrypto(key).encrypt(1, ByteArray(30) { 9 })
        ct[20] = (ct[20].toInt() xor 1).toByte()
        assertThrows(BizIntegrityException::class.java) { BizCrypto(key).decrypt(ct) }
    }

    @Test
    fun `short payloads are rejected`() {
        assertThrows(BizIntegrityException::class.java) { BizCrypto(key).decrypt(ByteArray(32)) }
    }
}
