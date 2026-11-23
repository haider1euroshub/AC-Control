package com.roomac.controller.driver.midea

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Midea LAN protocol (V2 and V3), as implemented by msmart-ng (github.com/mill1000/midea-msmart,
 * revision dd09947, MIT; see THIRD_PARTY_NOTICES.md). Kotlin adaptation of lan.py, frame.py and
 * crc8.py. Community-derived: Midea publishes no LAN protocol documentation.
 *
 *   frame      AA len type 00 00 00 00 00 ver ftype | body | checksum      (the appliance message)
 *   V2 packet  5A5A 0111 len(LE) 2000 msgid(4) time(8) devid(8 LE) 00*12 | AES-ECB(frame) | MD5 sign
 *   V3 packet  8370 size(BE) 20 pad<<4|type | id(2) + payload | (SHA-256)  — wraps a whole V2 packet
 */

class MideaProtocolException(message: String) : Exception(message)

/** Maxim/Dallas CRC-8 (reflected polynomial 0x8C), the "crc8_854" table msmart uses for message bodies. */
object MideaCrc8 {
    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }
        c
    }

    fun tableEntry(i: Int): Int = table[i]

    fun calculate(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) crc = table[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }
}

/** The fixed LAN cipher shared by every Midea module, plus the V3 per-session helpers. */
object MideaSecurity {
    /** Public protocol constant (msmart lan.py `SIGN_KEY`), the same in every module; protects nothing user-specific. */
    private val SIGN_KEY = "xhdiwjnchekd4d512chdjx5d8e4c394D2D7S".toByteArray(Charsets.US_ASCII)
    private val ENC_KEY: ByteArray = md5(SIGN_KEY)

    fun encryptEcb(data: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(ENC_KEY, "AES")); doFinal(data)
    }

    fun decryptEcb(data: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(ENC_KEY, "AES")); doFinal(data) }
    } catch (e: Exception) {
        throw MideaProtocolException("payload did not decrypt")
    }

    /** MD5 over the packet plus the sign key, appended to every V2 packet. */
    fun sign(data: ByteArray): ByteArray = md5(data + SIGN_KEY)

    fun cbc(mode: Int, key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16))); doFinal(data)
    }

    /** The id the cloud files a V3 token under: SHA-256 of the 6-byte device id, halves XORed. */
    fun udpid(deviceIdBytes: ByteArray): ByteArray {
        val h = sha256(deviceIdBytes)
        return ByteArray(16) { (h[it].toInt() xor h[it + 16].toInt()).toByte() }
    }

    fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

/** The appliance message inside every packet: 10-byte header, body, two's-complement checksum. */
object MideaFrame {
    const val HEADER_LENGTH = 10
    const val TYPE_AC = 0xAC
    const val TYPE_COMMERCIAL_AC = 0xCC
    const val CONTROL = 0x02
    const val QUERY = 0x03

    fun build(deviceType: Int, frameType: Int, body: ByteArray): ByteArray {
        val header = ByteArray(HEADER_LENGTH)
        header[0] = 0xAA.toByte()
        header[1] = (body.size + HEADER_LENGTH).toByte()
        header[2] = deviceType.toByte()
        header[9] = frameType.toByte()
        val frame = header + body
        return frame + checksum(frame, 1, frame.size).toByte()
    }

    fun checksum(data: ByteArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from until to) sum += data[i].toInt() and 0xFF
        return (sum.inv() + 1) and 0xFF
    }

    /** Checks length, checksum and device type; returns the body (without the frame checksum). */
    fun validate(frame: ByteArray, deviceType: Int): ByteArray {
        if (frame.size < HEADER_LENGTH + 1) throw MideaProtocolException("frame too short")
        if (checksum(frame, 1, frame.size - 1) != (frame.last().toInt() and 0xFF)) throw MideaProtocolException("frame checksum")
        if ((frame[2].toInt() and 0xFF) != deviceType) throw MideaProtocolException("frame device type")
        return frame.copyOfRange(HEADER_LENGTH, frame.size - 1)
    }
}

/** V2 packets (also the inner layer of V3). */
object MideaV2Packet {
    fun encode(deviceId: Long, frame: ByteArray, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): ByteArray {
        val encrypted = MideaSecurity.encryptEcb(frame)
        val length = 40 + encrypted.size + 16
        val header = ByteArray(40)
        header[0] = 0x5A; header[1] = 0x5A; header[2] = 0x01; header[3] = 0x11
        header[4] = (length and 0xFF).toByte(); header[5] = (length ushr 8 and 0xFF).toByte()
        header[6] = 0x20; header[7] = 0x00
        // 8..11 message id (zero), 12..19 timestamp, 20..27 device id LE, 28..39 zero
        val t = intArrayOf(now.nano / 10_000_000, now.second, now.minute, now.hour, now.dayOfMonth, now.monthValue, now.year % 100, now.year / 100)
        for (i in 0 until 8) header[12 + i] = t[i].toByte()
        for (i in 0 until 8) header[20 + i] = (deviceId ushr (8 * i) and 0xFF).toByte()
        val packet = header + encrypted
        return packet + MideaSecurity.sign(packet)
    }

    /** Returns the decrypted frame, after checking the length and the MD5 signature. */
    fun decode(data: ByteArray): ByteArray {
        if (data.size < 6 || data[0] != 0x5A.toByte() || data[1] != 0x5A.toByte()) throw MideaProtocolException("not a V2 packet")
        val length = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        if (length < 56 || data.size < length) throw MideaProtocolException("truncated packet")
        val packet = data.copyOfRange(0, length)
        val sign = packet.copyOfRange(length - 16, length)
        if (!MideaSecurity.sign(packet.copyOfRange(0, length - 16)).contentEquals(sign)) throw MideaProtocolException("packet signature")
        return MideaSecurity.decryptEcb(packet.copyOfRange(40, length - 16))
    }

    /** Total length declared by a V2 packet header, or null if [data] does not start one. */
    fun declaredLength(data: ByteArray): Int? =
        if (data.size >= 6 && data[0] == 0x5A.toByte() && data[1] == 0x5A.toByte()) (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) else null
}

/** V3 framing and the token/key handshake that derives a per-session key. */
class MideaV3Session(private val random: SecureRandom = SecureRandom()) {
    private var packetId = 0
    var sessionKey: ByteArray? = null
        internal set

    fun handshakeRequest(token: ByteArray): ByteArray {
        val payload = idBytes() + token
        return header(token.size, TYPE_HANDSHAKE_REQUEST) + payload
    }

    /**
     * Completes the handshake from the reply payload (64 bytes: 32 encrypted + SHA-256). Fails when the
     * cloud key does not belong to this device: the hash does not match.
     */
    fun completeHandshake(replyPayload: ByteArray, key: ByteArray) {
        if (replyPayload.size != 64) throw MideaProtocolException("handshake reply length ${replyPayload.size}")
        val decrypted = MideaSecurity.cbc(Cipher.DECRYPT_MODE, key, replyPayload.copyOfRange(0, 32))
        if (!MideaSecurity.sha256(decrypted).contentEquals(replyPayload.copyOfRange(32, 64))) throw MideaProtocolException("handshake hash")
        sessionKey = ByteArray(32) { (decrypted[it].toInt() xor key[it].toInt()).toByte() }
    }

    fun encryptedRequest(data: ByteArray): ByteArray {
        val key = sessionKey ?: throw MideaProtocolException("not authenticated")
        val remainder = (data.size + 2) % 16
        val pad = if (remainder != 0) 16 - remainder else 0
        val hdr = header(data.size + pad + 32, (pad shl 4) or TYPE_ENCRYPTED_REQUEST)
        val payload = idBytes() + data + ByteArray(pad).also(random::nextBytes)
        val hash = MideaSecurity.sha256(hdr + payload)
        return hdr + MideaSecurity.cbc(Cipher.ENCRYPT_MODE, key, payload) + hash
    }

    /** Decodes one complete V3 packet: the handshake payload, or a decrypted response (an inner V2 packet). */
    fun decode(packet: ByteArray): Decoded {
        if (packet.size < 8 || packet[0] != 0x83.toByte() || packet[1] != 0x70.toByte()) throw MideaProtocolException("not a V3 packet")
        if (packet[4] != 0x20.toByte()) throw MideaProtocolException("V3 magic byte")
        return when (packet[5].toInt() and 0x0F) {
            TYPE_HANDSHAKE_RESPONSE -> Decoded.Handshake(packet.copyOfRange(8, packet.size))
            // Requests have the same layout; accepted so a test module can read what the client sends.
            TYPE_ENCRYPTED_RESPONSE, TYPE_ENCRYPTED_REQUEST -> {
                val key = sessionKey ?: throw MideaProtocolException("not authenticated")
                val header = packet.copyOfRange(0, 6)
                val decrypted = MideaSecurity.cbc(Cipher.DECRYPT_MODE, key, packet.copyOfRange(6, packet.size - 32))
                if (!MideaSecurity.sha256(header + decrypted).contentEquals(packet.copyOfRange(packet.size - 32, packet.size))) {
                    throw MideaProtocolException("V3 response hash")
                }
                val pad = (packet[5].toInt() and 0xFF) ushr 4
                Decoded.Data(decrypted.copyOfRange(2, decrypted.size - pad))
            }
            TYPE_ERROR -> Decoded.Error
            else -> throw MideaProtocolException("V3 packet type")
        }
    }

    sealed class Decoded {
        class Handshake(val payload: ByteArray) : Decoded()
        class Data(val inner: ByteArray) : Decoded()
        object Error : Decoded()
    }

    private fun header(length: Int, typeByte: Int) =
        byteArrayOf(0x83.toByte(), 0x70, (length ushr 8 and 0xFF).toByte(), (length and 0xFF).toByte(), 0x20, typeByte.toByte())

    private fun idBytes(): ByteArray {
        val id = packetId
        packetId = (packetId + 1) and 0xFFF
        return byteArrayOf((id ushr 8 and 0xFF).toByte(), (id and 0xFF).toByte())
    }

    companion object {
        const val TYPE_HANDSHAKE_REQUEST = 0x0
        const val TYPE_HANDSHAKE_RESPONSE = 0x1
        const val TYPE_ENCRYPTED_RESPONSE = 0x3
        const val TYPE_ENCRYPTED_REQUEST = 0x6
        const val TYPE_ERROR = 0xF

        /** Total length of the V3 packet starting [data] (6-byte header + 2-byte id + declared size), or null. */
        fun declaredLength(data: ByteArray): Int? =
            if (data.size >= 4 && data[0] == 0x83.toByte() && data[1] == 0x70.toByte()) (((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) + 8 else null
    }
}
