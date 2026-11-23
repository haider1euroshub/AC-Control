package com.roomac.controller.driver.haismart

/*
 * EPP frames carried inside uSS biz data. Adapted from haismart-local (MIT), see THIRD_PARTY_NOTICES.md.
 *
 *   FF FF | len | address(6) | frameType | command(2) | data | checksum
 *
 * len counts everything after itself including the checksum; checksum = sum(len..data) & 0xFF, where
 * each escaped 0xFF also counts its inserted 0x55. Inside a frame 0xFF travels as FF 55.
 */
object EppFrame {
    val HEAD = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

    const val TYPE_CONTROL = 0x01
    const val TYPE_REPLY = 0x02
    const val TYPE_REFUSED = 0x03
    const val TYPE_REPORT = 0x06

    val CMD_GET_ALL_PROPERTY = byteArrayOf(0x4D, 0x01)
    val CMD_GROUP_SET = byteArrayOf(0x60, 0x01)
    val RPT_ALARM = byteArrayOf(0x0F, 0x5A)
    val RPT_EXTENDED = byteArrayOf(0x7D, 0x01)
    val RPT_CHANGED_PARAMS = byteArrayOf(0x6C, 0x01)

    private const val SEPARATOR = 0xFF.toByte()
    private const val ESCAPE = 0x55.toByte()

    fun build(frameType: Int, command: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
        require(command.size == 2)
        val payload = ByteArray(6) + byteArrayOf(frameType.toByte()) + command + data
        val body = byteArrayOf((payload.size + 1).toByte()) + payload
        return HEAD + body + byteArrayOf(checksum(body).toByte())
    }

    /** Accumulate checksum over len..data (unescaped), counting the 0x55 each escaped 0xFF gains. */
    fun checksum(body: ByteArray): Int {
        var sum = 0
        for (b in body) {
            sum += b.toInt() and 0xFF
            if (b == SEPARATOR) sum += 0x55
        }
        return sum and 0xFF
    }

    fun getAllProperty(): ByteArray = build(TYPE_CONTROL, CMD_GET_ALL_PROPERTY)

    fun groupSet(words: ByteArray): ByteArray = build(TYPE_CONTROL, CMD_GROUP_SET, words)

    /** FF -> FF 55 escaping of a bare frame for transmission (leading separators preserved). */
    fun stuff(frame: ByteArray): ByteArray {
        if (frame.size < 2 || frame[0] != SEPARATOR || frame[1] != SEPARATOR) return frame
        val out = ArrayList<Byte>(frame.size + 4)
        out += SEPARATOR; out += SEPARATOR
        for (i in 2 until frame.size) {
            out += frame[i]
            if (frame[i] == SEPARATOR) out += ESCAPE
        }
        return out.toByteArray()
    }

    /** Undo FF 55 -> FF escaping inside the EPP frame of a decrypted blob. Safe on any input. */
    fun destuff(blob: ByteArray): ByteArray {
        val at = Bytes.indexOf(blob, HEAD)
        if (at < 0) return blob
        var hasEscape = false
        for (i in at + 2 until blob.size - 1) if (blob[i] == SEPARATOR && blob[i + 1] == ESCAPE) { hasEscape = true; break }
        if (!hasEscape) return blob
        val out = ArrayList<Byte>(blob.size)
        for (i in 0 until at + 2) out += blob[i]
        var i = at + 2
        while (i < blob.size) {
            val b = blob[i]
            out += b
            i++
            if (b == SEPARATOR && i < blob.size && blob[i] == ESCAPE) i++
        }
        return out.toByteArray()
    }

    fun frameType(blob: ByteArray): Int? {
        val at = Bytes.indexOf(blob, HEAD)
        return if (at < 0 || blob.size <= at + 9) null else blob[at + 9].toInt() and 0xFF
    }

    fun command(blob: ByteArray): ByteArray? {
        val at = Bytes.indexOf(blob, HEAD)
        return if (at < 0 || blob.size < at + 12) null else blob.copyOfRange(at + 10, at + 12)
    }

    /** The reason code of a refusal frame (type 03), or null if [blob] is not a refusal. */
    fun refusalCode(blob: ByteArray): Int? {
        if (frameType(blob) != TYPE_REFUSED) return null
        val at = Bytes.indexOf(blob, HEAD)
        return if (blob.size >= at + 12) Bytes.be16(blob, at + 10) else -1
    }
}

/** CAE envelopes around EPP frames. */
object CaeEnvelope {
    private const val TYPE_REQUEST = 0x2714L
    const val TYPE_REPORT = 0x2715

    /**
     * Outbound op envelope:
     *   00 00 27 14 | 36 zeros | deviceId ASCII padded to 32 | counter BE32 | len BE32 | escaped EPP frame
     */
    fun request(eppFrame: ByteArray, deviceId: String, counter: Long): ByteArray {
        val did = deviceId.toByteArray(Charsets.US_ASCII)
        require(did.size <= 32) { "deviceId too long" }
        val wire = EppFrame.stuff(eppFrame)
        val out = ByteArray(4 + 36 + 32 + 8 + wire.size)
        Bytes.putBe32(out, 0, TYPE_REQUEST)
        did.copyInto(out, 40)
        Bytes.putBe32(out, 72, counter)
        Bytes.putBe32(out, 76, wire.size.toLong())
        wire.copyInto(out, 80)
        return out
    }

    /** Inbound report blobs start 00 00 27 15. */
    fun isReport(blob: ByteArray): Boolean = blob.size >= 4 && Bytes.be16(blob, 2) == TYPE_REPORT
}
