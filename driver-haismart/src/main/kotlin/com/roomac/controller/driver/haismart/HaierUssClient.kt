package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.LocalAcClient
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Local control of Haier appliances over the uSS protocol on TCP 56800.
 *
 * Session choreography (adapted from haismart-local, MIT, see THIRD_PARTY_NOTICES.md):
 *   HELLO(deviceId) -> HELLO_RESP(status=1, session, keyVersion) -> HELLO_DONE -> HELLO_DONE_RESP
 *   (encrypted body = op sequence base) -> AC pushes its status -> one encrypted op -> AC replies.
 *
 * The handshake is bound to the device ID (the module MAC): a unit refuses a session for any other
 * ID, which is how identity is verified before anything is sent. A wrong key fails the payload MD5,
 * so nothing is ever decoded from, or sent to, a unit whose key we do not hold.
 */
class HaierUssClient(
    private val socketFactory: () -> Socket = { Socket() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val connectTimeoutMs: Int = 3000,
    private val replyTimeoutMs: Long = 4000,
    private val collectIdleMs: Long = 600,
    private val clock: () -> Long = System::currentTimeMillis
) : LocalAcClient {

    /**
     * Per device: the control words of the last status the appliance itself answered with (frame type
     * 02), and whether the module's pushed report disagreed with it. The push is a relayed report and
     * can lag a change made from the remote; when it does, writes seed from the answer instead so a
     * stale setting is never written back.
     */
    private class SeedInfo(val replyWords: ByteArray, val pushStale: Boolean)

    private val seeds = ConcurrentHashMap<String, SeedInfo>()
    private var opCounter = 1L

    override suspend fun probeKeyVersion(endpoint: AcEndpoint): Result<Int> = withContext(ioDispatcher) {
        try {
            open(endpoint).use { conn ->
                conn.send(UssCodec.hello(endpoint.deviceId))
                val msg = conn.awaitMessage(null, replyTimeoutMs)
                if (msg.infoType != UssCodec.INFO_HELLO_RESP) throw SessionException(AcError.ProtocolMismatch)
                val resp = UssCodec.parseHelloResp(msg)
                if (resp.status != 1L) Result.failure(SessionException(AcError.HandshakeRefused))
                else Result.success(resp.localKeyVersion)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SessionException(mapError(e, endpoint)))
        }
    }

    override suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): ReadResult =
        withContext(ioDispatcher) {
            try {
                open(endpoint).use { conn ->
                    val session = handshake(conn, endpoint, localKey, expectedKeyVersion)
                    sendOp(conn, session, endpoint, EppFrame.getAllProperty())
                    // One collect gathers both the pushed report and the answer to the query.
                    val all = decryptAll(conn.collect(replyTimeoutMs, collectIdleMs, MAX_COLLECT_MS), session.crypto)
                    if (all.isEmpty()) return@use ReadResult.Failure(AcError.Timeout)
                    val reply = all.lastOrNull { isStatus(it) && EppFrame.frameType(it) == EppFrame.TYPE_REPLY }
                    val push = all.lastOrNull { isStatus(it) && EppFrame.frameType(it) != EppFrame.TYPE_REPLY }
                    val chosen = reply ?: push ?: return@use ReadResult.Failure(noStatusError(all))
                    rememberSeed(endpoint.deviceId, reply, push)
                    val status = ClassicReport.decode(chosen, clock())
                        ?: return@use ReadResult.Failure(AcError.ProtocolMismatch)
                    log("status read: ${chosen.size}-byte report, frame type ${EppFrame.frameType(chosen)}")
                    ReadResult.Success(status, session.keyVersion)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReadResult.Failure(mapError(e, endpoint))
            }
        }

    override suspend fun execute(
        endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?
    ): CommandOutcome = withContext(ioDispatcher) {
        var opSent = false
        val first: CommandOutcome? = try {
            open(endpoint).use { conn ->
                executeInSession(conn, endpoint, localKey, command, expectedKeyVersion) { opSent = true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GroupSetException) {
            CommandOutcome.Failed(AcError.InvalidValue(e.message ?: "value not allowed"))
        } catch (e: Exception) {
            // A drop after the op was written leaves the result unknown: say so rather than guess.
            if (opSent && e is IOException) CommandOutcome.Failed(AcError.DisconnectedDuringCommand)
            else CommandOutcome.Failed(mapError(e, endpoint))
        }
        if (first != null) return@withContext first

        // The op went out but no status came back on its connection: verify with a fresh read.
        log("no status on the command's connection; verifying with a fresh read")
        when (val read = readStatus(endpoint, localKey, expectedKeyVersion)) {
            is ReadResult.Success ->
                if (command.isReflectedIn(read.status)) CommandOutcome.Confirmed(read.status)
                else CommandOutcome.Rejected("The AC did not apply the change.", read.status)
            is ReadResult.Failure -> CommandOutcome.Failed(AcError.Timeout)
        }
    }

    /** Returns null when the op was sent but no status came back on this connection to verify it. */
    private fun executeInSession(
        conn: UssConnection, endpoint: AcEndpoint, localKey: String, command: AcCommand,
        expectedKeyVersion: Int?, onSent: () -> Unit
    ): CommandOutcome? {
        val session = handshake(conn, endpoint, localKey, expectedKeyVersion)
        // The AC pushes its current status right after the handshake; seed the group-set from it.
        val pushedBlobs = decryptAll(conn.collect(replyTimeoutMs, collectIdleMs, MAX_COLLECT_MS), session.crypto)
        val pushed = pushedBlobs.lastOrNull { ClassicReport.baseline(it) != null }
        val seedInfo = seeds[endpoint.deviceId]
        val baseline = when {
            seedInfo != null && seedInfo.pushStale -> seedInfo.replyWords
            pushed != null -> ClassicReport.baseline(pushed)!!
            seedInfo != null -> seedInfo.replyWords
            else -> {
                val anyStatus = pushedBlobs.firstOrNull { isStatus(it) }
                return if (anyStatus != null) CommandOutcome.Failed(AcError.UnsupportedLayout(anyStatus.size))
                else CommandOutcome.Failed(AcError.Timeout)
            }
        }
        val words = ClassicGroupSet.apply(baseline, command)
        sendOp(conn, session, endpoint, EppFrame.groupSet(words))
        onSent()
        log("sent ${command.description}")

        val replies = decryptAll(conn.collect(replyTimeoutMs, collectIdleMs, MAX_COLLECT_MS), session.crypto)
        val statuses = replies.filter { isStatus(it) }.mapNotNull { ClassicReport.decode(it, clock()) }
        val applied = statuses.lastOrNull { command.isReflectedIn(it) }
        if (applied != null) {
            // The op's reply is the appliance's own answer: the freshest seed there is.
            replies.lastOrNull { isStatus(it) && EppFrame.frameType(it) == EppFrame.TYPE_REPLY }
                ?.let { rememberSeed(endpoint.deviceId, it, null) }
            return CommandOutcome.Confirmed(applied)
        }
        val refusal = replies.firstNotNullOfOrNull { EppFrame.refusalCode(it) }
        if (refusal != null) return CommandOutcome.Rejected(AcError.CommandRefused(refusal).userMessage, statuses.lastOrNull())
        if (statuses.isNotEmpty()) return CommandOutcome.Rejected("The AC reported its state without the change.", statuses.last())
        return null
    }

    private class Session(val id: Int, val typeByte: Int, val seqBase: Long, val keyVersion: Int, val crypto: BizCrypto)

    private fun handshake(conn: UssConnection, endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): Session {
        conn.send(UssCodec.hello(endpoint.deviceId))
        val respMsg = conn.awaitMessage(null, replyTimeoutMs)
        if (respMsg.infoType != UssCodec.INFO_HELLO_RESP) throw SessionException(AcError.ProtocolMismatch)
        val resp = UssCodec.parseHelloResp(respMsg)
        if (resp.status != 1L) throw SessionException(AcError.HandshakeRefused)
        // Everything after HELLO_RESP is encrypted with the key version it states; check before decrypting.
        if (expectedKeyVersion != null && expectedKeyVersion > 0 && resp.localKeyVersion != 0 &&
            resp.localKeyVersion != expectedKeyVersion
        ) throw SessionException(AcError.KeyRotated(resp.localKeyVersion, expectedKeyVersion))

        val speak = UssCodec.negotiatedTypeByte(respMsg)
        conn.send(UssCodec.helloDone(resp.session, speak))
        val done = conn.awaitMessage(UssCodec.INFO_HELLO_DONE_RESP, replyTimeoutMs)
        val crypto = BizCrypto(localKey)
        // HELLO_DONE_RESP is encrypted even though its flag says plaintext; always decrypt it.
        val body = try {
            crypto.decrypt(done.payload).data
        } catch (_: BizIntegrityException) {
            throw SessionException(AcError.KeyInvalid)
        }
        if (body.size < 4) throw SessionException(AcError.ProtocolMismatch)
        log("session established (key version ${resp.localKeyVersion})")
        return Session(resp.session, speak, Bytes.be32(body, 0), resp.localKeyVersion, crypto)
    }

    private fun sendOp(conn: UssConnection, session: Session, endpoint: AcEndpoint, frame: ByteArray) {
        val counter = synchronized(this) { opCounter.also { opCounter += 2 } }
        val envelope = CaeEnvelope.request(frame, endpoint.deviceId, counter)
        val ciphertext = session.crypto.encrypt(session.seqBase, envelope)
        conn.send(
            UssCodec.encode(
                UssCodec.INFO_OP, 0, ciphertext, typeByte = session.typeByte,
                flag = UssCodec.FLAG_BIZ_ENCRYPTED, session = session.id
            )
        )
    }

    private fun decryptAll(messages: List<UssMessage>, crypto: BizCrypto): List<ByteArray> =
        messages.filter { it.payload.size >= BizCrypto.MIN_PAYLOAD }.mapNotNull {
            try { crypto.decrypt(it.payload).data } catch (_: BizIntegrityException) { null }
        }

    private fun isStatus(blob: ByteArray) = ClassicReport.classify(blob) == ClassicReport.Kind.STATUS

    private fun noStatusError(blobs: List<ByteArray>): AcError {
        val refusal = blobs.firstNotNullOfOrNull { EppFrame.refusalCode(it) }
        return if (refusal != null) AcError.CommandRefused(refusal) else AcError.ProtocolMismatch
    }

    private fun rememberSeed(deviceId: String, reply: ByteArray?, push: ByteArray?) {
        val replyWords = reply?.let { ClassicReport.baseline(it) } ?: return
        val pushWords = push?.let { ClassicReport.baseline(it) }
        val previous = seeds[deviceId]
        val stale = if (push == null) previous?.pushStale ?: false else pushWords != null && !pushWords.contentEquals(replyWords)
        seeds[deviceId] = SeedInfo(replyWords, stale)
    }

    private fun open(endpoint: AcEndpoint): UssConnection {
        val socket = socketFactory()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            // Connect-stage failures: refused means the port is closed; anything else means nothing
            // answered at that address (powered off, moved, or the network is down).
            throw SessionException(if (e is ConnectException) AcError.ServiceUnavailable else AcError.Unreachable(endpoint.host))
        }
        return UssConnection(socket)
    }

    /** Errors after the connection was established (connect-stage errors are mapped in [open]). */
    private fun mapError(e: Exception, endpoint: AcEndpoint): AcError = when (e) {
        is SessionException -> e.error
        is SocketTimeoutException -> AcError.Timeout
        // The AC closed or reset an established session: it accepts one local session at a time.
        is EOFException, is SocketException -> AcError.SessionBusy
        is UssFormatException -> AcError.ProtocolMismatch
        is IOException -> AcError.Unreachable(endpoint.host)
        else -> AcError.General(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private const val MAX_COLLECT_MS = 6000L
    }
}

class SessionException(val error: AcError) : Exception(error.userMessage)
