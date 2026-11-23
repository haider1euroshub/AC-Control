package com.roomac.controller.driver.haismart

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection carrying uSS messages, with a reassembly buffer so messages split across reads
 * or batched into one read are both handled. Blocking; callers run it on an IO dispatcher.
 */
class UssConnection(private val socket: Socket) : AutoCloseable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private var buffer = ByteArray(0)
    private val chunk = ByteArray(4096)

    fun send(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /**
     * Reads until a message of [infoType] arrives (any message when null), discarding others that
     * precede it. Throws [SocketTimeoutException] at [timeoutMs] and [EOFException] if the peer closes.
     */
    fun awaitMessage(infoType: Int?, timeoutMs: Long): UssMessage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (true) {
                val len = UssCodec.completeLength(buffer, 0, buffer.size)
                if (len < 0) break
                val msg = UssCodec.decode(buffer.copyOfRange(0, len))
                buffer = buffer.copyOfRange(len, buffer.size)
                if (infoType == null || msg.infoType == infoType) return msg
            }
            if (buffer.size > MAX_BUFFER) throw IOException("the appliance sent too much without completing the exchange")
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("no reply within ${timeoutMs}ms")
            fill(remaining)
        }
    }

    /**
     * Collects messages until the line goes quiet: waits up to [firstByteTimeoutMs] for the first
     * bytes, then only [idleMs] for stragglers, never longer than [maxTotalMs] overall. The appliance
     * sends its whole burst at once and then holds the socket open and silent.
     */
    fun collect(firstByteTimeoutMs: Long, idleMs: Long, maxTotalMs: Long): List<UssMessage> {
        val start = System.currentTimeMillis()
        var gotBytes = buffer.isNotEmpty()
        while (buffer.size < MAX_BUFFER) {
            val elapsed = System.currentTimeMillis() - start
            val budget = maxTotalMs - elapsed
            if (budget <= 0) break
            val wait = minOf(budget, if (gotBytes) idleMs else firstByteTimeoutMs)
            try {
                fill(wait)
                gotBytes = true
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: EOFException) {
                break
            }
        }
        val messages = UssCodec.split(buffer).map { UssCodec.decode(it) }
        buffer = ByteArray(0)
        return messages
    }

    private fun fill(timeoutMs: Long) {
        socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val n = input.read(chunk)
        if (n < 0) throw EOFException("the appliance closed the connection")
        buffer += chunk.copyOf(n)
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        private const val MAX_BUFFER = 16384
    }
}
