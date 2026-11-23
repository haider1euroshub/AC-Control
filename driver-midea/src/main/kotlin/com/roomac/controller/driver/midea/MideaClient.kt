package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.DeviceDiscovery
import com.roomac.controller.protocol.LanAccess
import com.roomac.controller.protocol.LocalAcClient
import com.roomac.controller.protocol.ReadResult
import com.roomac.controller.protocol.socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The stored credential. V2 modules need none (the LAN cipher is fixed). V3 modules need the token
 * and key the Midea cloud issues for the device; the cloud may answer for both byte orders of the
 * device id, so up to two candidates are kept and the one the AC accepts is used.
 */
sealed class MideaCredential {
    object None : MideaCredential()
    data class V3(val candidates: List<Pair<ByteArray, ByteArray>>) : MideaCredential() {
        override fun toString() = "V3(${candidates.size} candidate(s))" // never the token or key
    }

    fun encode(): String = when (this) {
        None -> "v2"
        is V3 -> "v3:" + candidates.joinToString("|") { (t, k) -> t.hex() + ":" + k.hex() }
    }

    companion object {
        fun decode(s: String): MideaCredential? = when {
            s == "v2" -> None
            s.startsWith("v3:") -> runCatching {
                V3(s.removePrefix("v3:").split("|").map { c -> c.split(":").let { it[0].unhex() to it[1].unhex() } })
            }.getOrNull()?.takeIf { it.candidates.isNotEmpty() && it.candidates.all { (t, k) -> t.size == 64 && k.size == 32 } }
            else -> null
        }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Raised inside a session when the AC refuses the V3 token/key. */
private class MideaAuthException : Exception()

/**
 * Local control of Midea air conditioners over TCP (normally port 6444). Every call opens its own
 * short session, like the other adapters. Commands are a full state write built from a fresh state
 * read, and are confirmed only by reading the state back.
 */
class MideaClient(
    private val discovery: DeviceDiscovery,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Int = 3000,
    private val settleMs: Long = 700,
    private val lan: LanAccess = LanAccess.Direct,
    private val log: (String) -> Unit = {}
) : LocalAcClient {

    /** Midea has no key versions: confirms the unit still answers discovery as this device. */
    override suspend fun probeKeyVersion(endpoint: AcEndpoint): Result<Int> {
        val info = discovery.query(endpoint.host)
        return if (info != null && info.deviceId == endpoint.deviceId) Result.success(0)
        else Result.failure(IllegalStateException(AcError.Unreachable(endpoint.host).userMessage))
    }

    override suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): ReadResult =
        when (val r = exchange(endpoint, localKey, MideaAcMessages.getState())) {
            is Exchange.State -> ReadResult.Success(MideaAcMessages.toStatus(r.state, clock()), 0)
            is Exchange.Failed -> ReadResult.Failure(r.error)
        }

    override suspend fun execute(endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?): CommandOutcome {
        val current = when (val r = exchange(endpoint, localKey, MideaAcMessages.getState())) {
            is Exchange.State -> r.state
            is Exchange.Failed -> return CommandOutcome.Failed(r.error)
        }
        if (current.fahrenheit) return CommandOutcome.Failed(AcError.NotSupported("Changing settings on a unit set to °F"))
        val wanted = MideaAcMessages.apply(current, command) ?: return CommandOutcome.Failed(AcError.NotSupported(command.description))
        when (val sent = exchange(endpoint, localKey, MideaAcMessages.setState(wanted))) {
            is Exchange.Failed -> return CommandOutcome.Failed(sent.error)
            is Exchange.State -> log("sent ${command.description}; AC replied")
        }
        // The reply to a write is the module's echo; confirm from a fresh state read.
        repeat(2) { attempt ->
            if (attempt > 0) delay(settleMs)
            when (val read = readStatus(endpoint, localKey, null)) {
                is ReadResult.Success -> if (command.isReflectedIn(read.status)) return CommandOutcome.Confirmed(read.status)
                    else if (attempt == 1) return CommandOutcome.Rejected("The AC did not apply the change.", read.status)
                is ReadResult.Failure -> return CommandOutcome.Failed(read.error)
            }
        }
        return CommandOutcome.Failed(AcError.Timeout)
    }

    private sealed class Exchange {
        class State(val state: MideaAcState) : Exchange()
        class Failed(val error: AcError) : Exchange()
    }

    /** One session: (V3 handshake), send [frame], wait for a state reply. */
    private suspend fun exchange(endpoint: AcEndpoint, credential: String, frame: ByteArray): Exchange = withContext(io) {
        val cred = MideaCredential.decode(credential) ?: return@withContext Exchange.Failed(AcError.KeyMissing)
        val deviceId = endpoint.deviceId.toLongOrNull() ?: return@withContext Exchange.Failed(AcError.ProtocolMismatch)
        val packet = MideaV2Packet.encode(deviceId, frame)
        val attempts: List<Pair<ByteArray, ByteArray>?> = when (cred) {
            MideaCredential.None -> listOf(null)
            is MideaCredential.V3 -> cred.candidates
        }
        var authRefused = false
        for (candidate in attempts) {
            try {
                lan.socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()
                    val v3 = candidate?.let { (token, key) ->
                        MideaV3Session().also { s ->
                            out.write(s.handshakeRequest(token)); out.flush()
                            val reply = s.decode(readPacket(input))
                            if (reply !is MideaV3Session.Decoded.Handshake) throw MideaAuthException()
                            try { s.completeHandshake(reply.payload, key) } catch (_: MideaProtocolException) { throw MideaAuthException() }
                        }
                    }
                    out.write(v3?.encryptedRequest(packet) ?: packet); out.flush()
                    // The module may also push unsolicited reports; wait for the state reply.
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val raw = readPacket(input)
                        val inner = if (v3 == null) raw else when (val d = v3.decode(raw)) {
                            is MideaV3Session.Decoded.Data -> d.inner
                            MideaV3Session.Decoded.Error -> throw MideaAuthException()
                            else -> continue
                        }
                        val state = MideaAcMessages.parseState(MideaV2Packet.decode(inner)) ?: continue
                        return@withContext Exchange.State(state)
                    }
                    return@withContext Exchange.Failed(AcError.Timeout)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: MideaAuthException) {
                authRefused = true
                continue // try the other byte order's token, if any
            } catch (_: SocketTimeoutException) {
                return@withContext Exchange.Failed(AcError.Timeout)
            } catch (_: MideaProtocolException) {
                return@withContext Exchange.Failed(AcError.ProtocolMismatch)
            } catch (_: IOException) {
                return@withContext Exchange.Failed(AcError.Unreachable(endpoint.host))
            }
        }
        Exchange.Failed(if (authRefused) AcError.KeyInvalid else AcError.Unreachable(endpoint.host))
    }

    /** Reads exactly one V2 or V3 packet, using the length its header declares. */
    private fun readPacket(input: InputStream): ByteArray {
        val head = readFully(input, 6)
        val total = MideaV3Session.declaredLength(head) ?: MideaV2Packet.declaredLength(head)
            ?: throw MideaProtocolException("unknown packet start")
        if (total < 6 || total > 4096) throw MideaProtocolException("packet length")
        return head + readFully(input, total - 6)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("connection closed")
            off += r
        }
        return buf
    }
}
