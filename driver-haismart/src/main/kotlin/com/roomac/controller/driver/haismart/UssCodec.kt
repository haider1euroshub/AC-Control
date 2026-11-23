package com.roomac.controller.driver.haismart

/*
 * uSS local transport codec (TCP 56800).
 *
 * Adapted from haismart-local (https://github.com/enapt/haismart-local), MIT License,
 * Copyright (c) 2026 haismart-local contributors. See THIRD_PARTY_NOTICES.md.
 *
 * Message = 16-byte header + payload:
 *   [0:4]   info_code BE32 = 0xEA60 + info_type (hello 0, hello_resp 1, hello_done 2, done_resp 3, op 0x64)
 *   [4:6]   payload_len + 0x0A (BE16)
 *   [6]     protocol version byte (pro_ver 2 -> 0x01)
 *   [7]     flag (0 plaintext, 1 encrypted biz data)
 *   [8:12]  sn BE32
 *   [12:14] code2 BE16
 *   [14:16] session BE16 (assigned by the AC in HELLO_RESP)
 */

data class UssMessage(
    val infoType: Int,
    val typeByte: Int,
    val flag: Int,
    val sn: Long,
    val session: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is UssMessage && infoType == other.infoType && typeByte == other.typeByte && flag == other.flag &&
            sn == other.sn && session == other.session && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * infoType + payload.contentHashCode()
}

data class HelloResp(val session: Int, val status: Long, val localKeyVersion: Int)

class UssFormatException(message: String) : Exception(message)

object UssCodec {
    const val INFO_BASE = 0xEA60
    const val INFO_HELLO = 0
    const val INFO_HELLO_RESP = 1
    const val INFO_HELLO_DONE = 2
    const val INFO_HELLO_DONE_RESP = 3
    const val INFO_OP = 0x64

    const val TYPE_BYTE_V2 = 0x01
    const val FLAG_BIZ_ENCRYPTED = 1
    const val HEADER_LEN = 16
    private const val LENGTH_BIAS = 0x0A

    fun encode(
        infoType: Int, sn: Long, payload: ByteArray = ByteArray(0),
        typeByte: Int = TYPE_BYTE_V2, flag: Int = 0, session: Int = 0
    ): ByteArray {
        val out = ByteArray(HEADER_LEN + payload.size)
        Bytes.putBe32(out, 0, (INFO_BASE + infoType).toLong())
        Bytes.putBe16(out, 4, payload.size + LENGTH_BIAS)
        out[6] = typeByte.toByte()
        out[7] = flag.toByte()
        Bytes.putBe32(out, 8, sn and 0xFFFFFFFFL)
        Bytes.putBe16(out, 12, 0)
        Bytes.putBe16(out, 14, session and 0xFFFF)
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    fun decode(buf: ByteArray): UssMessage {
        if (buf.size < HEADER_LEN) throw UssFormatException("short uSS message (${buf.size} bytes)")
        val infoCode = Bytes.be32(buf, 0)
        val length = Bytes.be16(buf, 4)
        val end = minOf(6 + length, buf.size)
        return UssMessage(
            infoType = (infoCode - INFO_BASE).toInt(),
            typeByte = buf[6].toInt() and 0xFF,
            flag = buf[7].toInt() and 0xFF,
            sn = Bytes.be32(buf, 8),
            session = Bytes.be16(buf, 14),
            payload = if (end > HEADER_LEN) buf.copyOfRange(HEADER_LEN, end) else ByteArray(0)
        )
    }

    /**
     * Length of the first complete message in [buf] starting at [offset], or -1 if more bytes are
     * needed. A declared length below 0x0A cannot be a real message; the stream is then unusable.
     */
    fun completeLength(buf: ByteArray, offset: Int, available: Int): Int {
        if (available < 6) return -1
        val length = Bytes.be16(buf, offset + 4)
        if (length < LENGTH_BIAS) throw UssFormatException("declared uSS length $length is shorter than a header")
        val total = 6 + length
        return if (available >= total) total else -1
    }

    /** Splits a byte stream into whole messages; trailing partial bytes are ignored. */
    fun split(buf: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off + 6 <= buf.size) {
            val total = try {
                completeLength(buf, off, buf.size - off)
            } catch (_: UssFormatException) {
                break // desynchronised stream: cannot be resynced safely
            }
            if (total < 0) break
            out += buf.copyOfRange(off, off + total)
            off += total
        }
        return out
    }

    fun hello(deviceId: String, sn: Long = 1): ByteArray {
        val ascii = deviceId.toByteArray(Charsets.US_ASCII)
        require(ascii.size <= 32) { "deviceId longer than 32 bytes" }
        val payload = ByteArray(32)
        ascii.copyInto(payload)
        return encode(INFO_HELLO, sn, payload)
    }

    fun helloDone(session: Int, typeByte: Int, sn: Long = 2): ByteArray =
        encode(INFO_HELLO_DONE, sn, ByteArray(0), typeByte = typeByte, session = session)

    /** HELLO_RESP payload is status(BE32) || localkey_version(BE32). Status 1 means the session is accepted. */
    fun parseHelloResp(msg: UssMessage): HelloResp {
        if (msg.infoType != INFO_HELLO_RESP) throw UssFormatException("expected HELLO_RESP, got info type ${msg.infoType}")
        val p = msg.payload
        return if (p.size >= 8) {
            HelloResp(msg.session, Bytes.be32(p, 0), Bytes.be32(p, 4).toInt())
        } else {
            HelloResp(msg.session, 0, 0)
        }
    }

    /** The version byte to speak for the rest of a session: the appliance's own, unless it sent 0. */
    fun negotiatedTypeByte(resp: UssMessage): Int = if (resp.typeByte != 0) resp.typeByte else TYPE_BYTE_V2
}

internal object Bytes {
    fun be16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun be32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    fun putBe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }

    fun putBe32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    fun be32Bytes(v: Long): ByteArray = ByteArray(4).also { putBe32(it, 0, v) }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
