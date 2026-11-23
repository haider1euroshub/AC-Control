package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.NetworkDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String)

/**
 * The CASTV2 wire format: each message is a 4-byte big-endian length followed by a protobuf
 * `CastMessage` (1 protocol_version=0, 2 source_id, 3 destination_id, 4 namespace, 5 payload_type=0
 * STRING, 6 payload_utf8). Only the string-payload form is used.
 */
object CastCodec {
    fun encode(m: CastMessage): ByteArray {
        val body = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { body.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; body.write(x.toInt()) }
        fun string(field: Int, s: String) { val b = s.toByteArray(Charsets.UTF_8); varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); body.write(b) }
        varint((1 shl 3).toLong()); varint(0)          // protocol_version CASTV2_1_0
        string(2, m.source); string(3, m.destination); string(4, m.namespace)
        varint((5 shl 3).toLong()); varint(0)          // payload_type STRING
        string(6, m.payload)
        val bytes = body.toByteArray()
        return byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    /** Decodes one protobuf CastMessage body (without the length prefix). */
    fun decode(bytes: ByteArray): CastMessage {
        var pos = 0
        fun varint(): Long { var shift = 0; var r = 0L; while (true) { val b = bytes[pos++].toInt() and 0xFF; r = r or ((b and 0x7F).toLong() shl shift); if (b and 0x80 == 0) return r; shift += 7; if (shift > 63) throw IOException("bad varint") } }
        val strings = HashMap<Int, String>()
        while (pos < bytes.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> varint()
                2 -> { val len = varint().toInt(); if (len < 0 || pos + len > bytes.size) throw IOException("bad length"); strings[key ushr 3] = String(bytes, pos, len, Charsets.UTF_8); pos += len }
                else -> throw IOException("unsupported wire type ${key and 7}")
            }
        }
        return CastMessage(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6].orEmpty())
    }
}

/** A CASTV2 connection. Replaced by a fake in tests. */
interface CastChannel {
    suspend fun send(message: CastMessage)
    /** The next message, or null after [timeoutMs]. */
    suspend fun receive(timeoutMs: Long): CastMessage?
    fun close()
}

fun interface CastChannelFactory {
    suspend fun open(host: String, port: Int): CastChannel
}

/** TLS to the device's port 8009 (Cast devices present a device certificate, not verified here; see [LanTls]). */
class TlsCastChannelFactory(private val io: CoroutineDispatcher = Dispatchers.IO) : CastChannelFactory {
    override suspend fun open(host: String, port: Int): CastChannel = withContext(io) {
        val socket: SSLSocket = LanTls.socket(host, port, 5_000)
        val input = DataInputStream(socket.getInputStream())
        object : CastChannel {
            override suspend fun send(message: CastMessage) = withContext(io) {
                synchronized(socket) { socket.getOutputStream().apply { write(CastCodec.encode(message)); flush() } }
                Unit
            }
            override suspend fun receive(timeoutMs: Long): CastMessage? = withContext(io) {
                socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
                try {
                    val len = input.readInt()
                    if (len !in 0..(1 shl 20)) throw IOException("frame too large")
                    CastCodec.decode(ByteArray(len).also(input::readFully))
                } catch (_: SocketTimeoutException) { null }
            }
            override fun close() { try { socket.close() } catch (_: Exception) {} }
        }
    }
}

/**
 * Google Cast (CASTV2) receiver and media control: volume and mute for the device; stop for the
 * running app; play/pause/stop only when the running app supports the standard media namespace and
 * has a media session. Cast has no power control. NOT YET TESTED ON A REAL CAST DEVICE.
 */
class GoogleCastController(private val channels: CastChannelFactory, private val replyTimeoutMs: Long = 4_000) : DeviceController {

    override val protocol = "Google Cast"

    override fun appliesTo(device: NetworkDevice): Boolean = "_googlecast._tcp" in device.mdnsServices || PORT in device.openPorts

    override suspend fun connect(device: NetworkDevice, credentials: Credentials?): ConnectResult {
        val port = device.mdnsPorts["_googlecast._tcp"] ?: PORT
        val ch = channels.open(device.ip, port)
        try {
            val s = Session(ch)
            s.start()
            return ConnectResult.Connected(s)
        } catch (e: Exception) {
            ch.close()
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ConnectResult.Unavailable("$protocol: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private inner class Session(private val ch: CastChannel) : Connection {
        override val protocol = this@GoogleCastController.protocol
        private var requestId = 0
        private var level: Double? = null
        private var muted = false
        private var fixedVolume = false
        private var appName: String? = null
        private var sessionId: String? = null
        private var transportId: String? = null
        private var mediaSessionId: Int? = null
        private var idleApp = true

        override val capabilities: Capabilities get() = Capabilities(
            keys = buildSet {
                if (!fixedVolume && level != null) addAll(listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE))
                if (mediaSessionId != null) addAll(listOf(RemoteKey.PLAY, RemoteKey.PAUSE))
                if (sessionId != null && !idleApp) add(RemoteKey.STOP)
            },
            volumeLevel = !fixedVolume && level != null
        )
        override val status: String? get() = listOfNotNull(appName, level?.let { "Volume ${(it * 100).toInt()}%" + if (muted) " (muted)" else "" }).joinToString(" · ").ifEmpty { null }

        suspend fun start() {
            ch.send(CastMessage(SENDER, RECEIVER, NS_CONNECTION, """{"type":"CONNECT"}"""))
            readReceiverStatus(request(RECEIVER, NS_RECEIVER) { put("type", "GET_STATUS") } ?: throw IOException("no RECEIVER_STATUS"))
            refreshMedia()
        }

        private suspend fun refreshMedia() {
            mediaSessionId = null
            val t = transportId ?: return
            ch.send(CastMessage(SENDER, t, NS_CONNECTION, """{"type":"CONNECT"}"""))
            val reply = request(t, NS_MEDIA) { put("type", "GET_STATUS") } ?: return
            val status = reply["status"] as? JsonArray ?: return
            mediaSessionId = (status.firstOrNull() as? JsonObject)?.get("mediaSessionId")?.let { (it as? JsonPrimitive)?.intOrNull }
        }

        private fun readReceiverStatus(reply: JsonObject) {
            val status = reply["status"] as? JsonObject ?: throw IOException("RECEIVER_STATUS without status")
            (status["volume"] as? JsonObject)?.let { v ->
                level = (v["level"] as? JsonPrimitive)?.doubleOrNull
                muted = (v["muted"] as? JsonPrimitive)?.booleanOrNull ?: false
                fixedVolume = (v["controlType"] as? JsonPrimitive)?.content == "fixed"
            }
            val app = (status["applications"] as? JsonArray)?.firstOrNull() as? JsonObject
            appName = (app?.get("displayName") as? JsonPrimitive)?.content
            sessionId = (app?.get("sessionId") as? JsonPrimitive)?.content
            idleApp = app == null || (app["isIdleScreen"] as? JsonPrimitive)?.booleanOrNull == true
            val hasMedia = (app?.get("namespaces") as? JsonArray)?.any { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content == NS_MEDIA } == true
            transportId = if (hasMedia) (app?.get("transportId") as? JsonPrimitive)?.content else null
        }

        /** Sends a request with a fresh requestId and waits for the reply carrying the same id (answering PINGs meanwhile). */
        private suspend fun request(destination: String, namespace: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject? {
            val id = ++requestId
            val payload = buildJsonObject { build(); put("requestId", id) }
            ch.send(CastMessage(SENDER, destination, namespace, payload.toString()))
            val deadline = System.currentTimeMillis() + replyTimeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val m = ch.receive(left) ?: return null
                val o = try { Json.parseToJsonElement(m.payload).jsonObject } catch (_: Exception) { continue }
                if (m.namespace == NS_HEARTBEAT && (o["type"] as? JsonPrimitive)?.content == "PING") {
                    ch.send(CastMessage(SENDER, m.source, NS_HEARTBEAT, """{"type":"PONG"}""")); continue
                }
                if ((o["requestId"] as? JsonPrimitive)?.intOrNull == id) return o
            }
        }

        override suspend fun perform(command: Command): ControlResult {
            val key = (command as? Command.Key)?.key
            val reply: JsonObject? = when {
                command is Command.SetVolume && capabilities.volumeLevel -> setVolume(command.percent / 100.0)
                key == RemoteKey.VOLUME_UP && key in capabilities.keys -> setVolume((level ?: 0.0) + STEP)
                key == RemoteKey.VOLUME_DOWN && key in capabilities.keys -> setVolume((level ?: 0.0) - STEP)
                key == RemoteKey.MUTE && key in capabilities.keys -> request(RECEIVER, NS_RECEIVER) { put("type", "SET_VOLUME"); putJsonObject("volume") { put("muted", !muted) } }
                key == RemoteKey.STOP && key in capabilities.keys -> request(RECEIVER, NS_RECEIVER) { put("type", "STOP"); put("sessionId", sessionId) }
                (key == RemoteKey.PLAY || key == RemoteKey.PAUSE) && key in capabilities.keys ->
                    request(transportId!!, NS_MEDIA) { put("type", if (key == RemoteKey.PLAY) "PLAY" else "PAUSE"); put("mediaSessionId", mediaSessionId) }
                else -> return ControlResult.Failed("Not available on this Cast device right now")
            }
            reply ?: return ControlResult.Failed("The Cast device did not answer")
            when ((reply["type"] as? JsonPrimitive)?.content) {
                "RECEIVER_STATUS" -> { readReceiverStatus(reply); refreshMedia() }
                "INVALID_REQUEST", "LOAD_FAILED", "INVALID_PLAYER_STATE" -> return ControlResult.Failed("Cast refused: ${(reply["reason"] as? JsonPrimitive)?.content ?: reply["type"]}")
            }
            return ControlResult.Done
        }

        private suspend fun setVolume(target: Double): JsonObject? =
            request(RECEIVER, NS_RECEIVER) { put("type", "SET_VOLUME"); putJsonObject("volume") { put("level", target.coerceIn(0.0, 1.0)) } }

        override fun close() = ch.close()
    }

    companion object {
        const val PORT = 8009
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val STEP = 0.05
    }
}
