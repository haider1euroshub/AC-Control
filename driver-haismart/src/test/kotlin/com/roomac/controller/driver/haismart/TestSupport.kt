package com.roomac.controller.driver.haismart

import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Builds decrypted status reports in the classic layout, the way the appliance lays them out. */
object Reports {
    // Bytes [0:78] of every report: CAE header + STD region, identical across models.
    val PREFIX: ByteArray = Bytes.fromHex("00002715000000004e56010000030200000401" + "00".repeat(59))

    /**
     * A control-word block (6 words) with the given settings. Word 1: setpoint-16 << 8 | vertical
     * vane; word 2: mode << 13 | fan << 8; word 3 flags; word 4 eco << 3 | horizontal; word 5 bit 4 self-clean.
     */
    fun words(
        power: Boolean = true, setpoint: Int = 24, vane: Int = 0, mode: Int = 1, fan: Int = 5,
        health: Boolean = false, turbo: Boolean = false, quiet: Boolean = false, sleep: Boolean = false,
        display: Boolean = true, horizontal: Int = 0, eco: Int = 0, selfClean: Boolean = false, wordCount: Int = 6
    ): ByteArray {
        val w = IntArray(wordCount)
        w[0] = ((setpoint - 16) shl 8) or vane
        w[1] = (mode shl 13) or (fan shl 8)
        w[2] = (if (power) 1 else 0) or (if (health) 2 else 0) or (if (turbo) 8 else 0) or
            (if (quiet) 16 else 0) or (if (sleep) 32 else 0) or (if (display) 0x200 else 0)
        w[3] = (eco shl 3) or horizontal
        if (wordCount >= 5) w[4] = if (selfClean) 0x10 else 0
        val out = ByteArray(wordCount * 2)
        for (i in w.indices) { out[2 * i] = (w[i] shr 8).toByte(); out[2 * i + 1] = w[i].toByte() }
        return out
    }

    /** A full report: [words] then indoor (x2), outdoor (+64), heat flag byte, error code, padding. */
    fun report(
        words: ByteArray = words(), indoorRaw: Int = 52, outdoorRaw: Int = 98, coolingOnly: Boolean = false,
        frameType: Int = EppFrame.TYPE_REPLY, command: ByteArray = byteArrayOf(0x6D, 0x01), errorCode: Int = 0
    ): ByteArray {
        val totalLen = 115 + words.size // classic: 127 for 6 words, 125 for 5
        val dataLen = totalLen - 80 - 13
        val data = ByteArray(dataLen)
        words.copyInto(data, 0)
        var i = words.size
        data[i++] = indoorRaw.toByte()
        data[i++] = 0
        data[i++] = outdoorRaw.toByte()
        data[i++] = if (coolingOnly) 0x80.toByte() else 0
        data[i] = errorCode.toByte()
        val frame = EppFrame.build(frameType, command, data)
        val len = ByteArray(2).also { Bytes.putBe16(it, 0, frame.size) }
        return PREFIX + len + frame
    }

    fun wrap(frame: ByteArray): ByteArray = PREFIX + ByteArray(2).also { Bytes.putBe16(it, 0, frame.size) } + frame
}

/**
 * A stand-in appliance on localhost that speaks the AC side of uSS: handshake, encrypted session
 * sequence base, a pushed status report, and answers to queries and group-sets.
 */
class FakeAc(
    val deviceId: String = "A1B2C3D4E5F6",
    val localKey: String = "00112233445566778899aabbccddeeff",
    var keyVersion: Int = 50,
    @Volatile var words: ByteArray = Reports.words(),
    @Volatile var coolingOnly: Boolean = false
) : AutoCloseable {
    enum class OpBehaviour { APPLY, REFUSE, IGNORE, IGNORE_WRITES_REPORT_OLD, CLOSE }

    @Volatile var helloStatus = 1L
    @Volatile var pushStatus = true
    @Volatile var closeAfterHello = false
    @Volatile var groupSetBehaviour = OpBehaviour.APPLY
    @Volatile var reportLengthWords = 6
    val sessions = AtomicInteger()
    val groupSets = AtomicInteger()
    @Volatile var lastGroupSetWords: ByteArray? = null
    /** When set, the pushed report (type 06) carries these words instead of the live ones: a lagging relay. */
    @Volatile var pushedWordsOverride: ByteArray? = null

    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private val crypto = BizCrypto(localKey)
    private val seqBase = 0x00001234L

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                sessions.incrementAndGet()
                try { serve(s) } catch (_: Exception) { } finally { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        s.soTimeout = 8000
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val hello = UssCodec.decode(readMessage(input))
        val did = String(hello.payload, Charsets.US_ASCII).trimEnd('\u0000')
        val status = if (did == deviceId) helloStatus else 0L
        val respPayload = Bytes.be32Bytes(status) + Bytes.be32Bytes(keyVersion.toLong())
        out.write(UssCodec.encode(UssCodec.INFO_HELLO_RESP, 1, respPayload, session = 0x76DF))
        if (closeAfterHello) return
        val done = UssCodec.decode(readMessage(input))
        check(done.infoType == UssCodec.INFO_HELLO_DONE)
        // Real units send this encrypted with flag 0.
        out.write(UssCodec.encode(UssCodec.INFO_HELLO_DONE_RESP, 2, crypto.encrypt(0, Bytes.be32Bytes(seqBase) + ByteArray(8)), session = 0x76DF))
        if (pushStatus) out.write(statusMessage(EppFrame.TYPE_REPORT))
        out.flush()
        while (true) {
            val msg = try { UssCodec.decode(readMessage(input)) } catch (_: EOFException) { return }
            if (msg.infoType != UssCodec.INFO_OP) continue
            val biz = try { crypto.decrypt(msg.payload) } catch (_: BizIntegrityException) { continue }
            if (biz.sn != seqBase) continue // wrong sequence: the AC discards silently
            val env = biz.data
            check(Bytes.be32(env, 0) == 0x2714L)
            val frame = EppFrame.destuff(env.copyOfRange(80, env.size))
            val cmd = frame.copyOfRange(10, 12)
            when {
                cmd.contentEquals(EppFrame.CMD_GET_ALL_PROPERTY) -> out.write(statusMessage(EppFrame.TYPE_REPLY))
                cmd.contentEquals(EppFrame.CMD_GROUP_SET) -> {
                    groupSets.incrementAndGet()
                    val newWords = frame.copyOfRange(12, frame.size - 1)
                    lastGroupSetWords = newWords
                    when (groupSetBehaviour) {
                        OpBehaviour.APPLY -> { words = newWords; out.write(statusMessage(EppFrame.TYPE_REPLY)) }
                        OpBehaviour.REFUSE -> out.write(bizMessage(Reports.wrap(EppFrame.build(EppFrame.TYPE_REFUSED, byteArrayOf(0, 5)))))
                        OpBehaviour.IGNORE -> {}
                        OpBehaviour.IGNORE_WRITES_REPORT_OLD -> out.write(statusMessage(EppFrame.TYPE_REPLY))
                        OpBehaviour.CLOSE -> return
                    }
                }
            }
            out.flush()
        }
    }

    private fun statusMessage(type: Int): ByteArray {
        val live = if (type == EppFrame.TYPE_REPORT) pushedWordsOverride ?: words else words
        val w = if (reportLengthWords == 5) live.copyOfRange(0, 10) else live
        return bizMessage(Reports.report(words = w, coolingOnly = coolingOnly, frameType = type))
    }

    private fun bizMessage(blob: ByteArray): ByteArray {
        // The AC escapes 0xFF inside the EPP frame on the wire.
        val at = Bytes.indexOf(blob, EppFrame.HEAD)
        val wire = blob.copyOfRange(0, at) + EppFrame.stuff(blob.copyOfRange(at, blob.size))
        return UssCodec.encode(UssCodec.INFO_OP, 0, crypto.encrypt(0, wire), flag = 1, session = 0x76DF)
    }

    private fun readMessage(input: InputStream): ByteArray {
        val head = readN(input, 6)
        val len = Bytes.be16(head, 4)
        return head + readN(input, len)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    override fun close() = server.close()
}
