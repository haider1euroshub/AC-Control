package com.roomac.controller.driver.haismart.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/*
 * The per-device local key is delivered by Haier's MQTT 3.1.1 business gateway over TLS
 * (gw-sgp.haieriot.net:58702, or gw-sea for India accounts). Facts from haismart-local (MIT):
 *
 *   CONNECT  clientId = MD5(<terminal id> + "_" + "com.haier.uhome.uplus.seasia")
 *            username = "01" + 8 digits, password = hex(AES-128-CBC(MD5(8 digits), iv 0, BE16(9)+"haier_sdk", zero-padded))
 *   SUB      Client/<clientId>/Business/Down
 *   PUB      Client/<clientId>/Business/Up
 *            {"type":"devLocalkey","data":base64({"sn":"<n>","dev":"<deviceId>","flag":0}),"tokens":["<accessToken>"]}
 *   REPLY    {"type":"devLocalkey","data":base64({"sn":"<n>","errNo":0,"vers":<v>,"key":"<localKey>"})}
 *
 * The CONNECT pair carries no user secret; the account token in the request is what authorises it.
 */

data class HaierLocalKey(val key: String, val version: Int?)

class HaierGatewayException(
    val kind: HaierCloudException.Kind, message: String,
    /** The gateway's errNo, when it refused with one. */ val errNo: Int? = null
) : Exception(message)

object HaierGatewayAuth {
    const val PACKAGE = "com.haier.uhome.uplus.seasia"
    private val SALT = "haier_sdk".toByteArray(Charsets.US_ASCII)

    fun clientId(terminalId: String): String = md5Hex("${terminalId}_$PACKAGE")

    fun password(usernameBody: String): String {
        val block = ByteArray(16)
        block[0] = (SALT.size shr 8).toByte()
        block[1] = SALT.size.toByte()
        SALT.copyInto(block, 2)
        val aes = Cipher.getInstance("AES/CBC/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(md5(usernameBody), "AES"), IvParameterSpec(ByteArray(16)))
        return aes.doFinal(block).toHex()
    }

    fun usernameBody(random: SecureRandom): String = (1..8).joinToString("") { random.nextInt(10).toString() }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    private fun md5Hex(s: String) = md5(s).toHex()
}

object HaierKeyMessages {
    fun request(deviceId: String, accessToken: String, sn: String): String {
        val inner = buildJsonObject { put("sn", sn); put("dev", deviceId); put("flag", 0) }.toString()
        return buildJsonObject {
            put("type", "devLocalkey")
            put("data", Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8)))
            putJsonArray("tokens") { add(kotlinx.serialization.json.JsonPrimitive(accessToken)) }
        }.toString()
    }

    /** The inner reply object, or null if [payload] is not a local-key reply. */
    fun parse(payload: ByteArray): JsonObject? = try {
        val outer = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        if (outer.str("type") != "devLocalkey") null
        else Json.parseToJsonElement(Base64.getDecoder().decode(outer.str("data")).toString(Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        null
    }
}

/** The MQTT operations the key fetch needs, injectable for tests. */
interface MqttLink : AutoCloseable {
    /** Subscribes and waits for the SUBACK. */
    fun subscribe(topic: String)
    fun publish(topic: String, payload: String)
    /** PUBLISH messages received within [timeoutMs], as (topic, payload). Throws IOException when the link is gone. */
    fun poll(timeoutMs: Int): List<Pair<String, ByteArray>>
}

fun interface MqttConnector {
    fun connect(host: String, port: Int, clientId: String, username: String, password: String): MqttLink
}

class HaierKeyGateway(
    private val connector: MqttConnector = TlsMqttConnector,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 10_000
) {
    fun fetch(host: String, terminalId: String, accessToken: String, deviceId: String, port: Int = PORT): HaierLocalKey {
        val clientId = HaierGatewayAuth.clientId(terminalId)
        val body = HaierGatewayAuth.usernameBody(random)
        val link = try {
            connector.connect(host, port, clientId, "01$body", HaierGatewayAuth.password(body))
        } catch (e: IOException) {
            throw HaierGatewayException(HaierCloudException.Kind.UNREACHABLE, "gateway: ${e.message ?: e.javaClass.simpleName}")
        }
        link.use {
            val sn = (clock() % 1_000_000_000L).toString()
            try {
                it.subscribe("Client/$clientId/Business/Down")
                it.publish("Client/$clientId/Business/Up", HaierKeyMessages.request(deviceId, accessToken, sn))
                val deadline = clock() + timeoutMs
                while (clock() < deadline) {
                    for ((_, payload) in it.poll(500)) {
                        val inner = HaierKeyMessages.parse(payload) ?: continue
                        if (inner.str("sn") != sn) continue // another request's reply
                        // errNo first: a refusal carries no key, and its reason matters (token expired, not bound).
                        val err = inner["errNo"]?.jsonPrimitive?.intOrNull ?: 0
                        if (err != 0) throw HaierGatewayException(HaierCloudException.Kind.REJECTED, "gateway errNo=$err", errNo = err)
                        val key = inner.str("key")
                        if (key.isEmpty()) throw HaierGatewayException(HaierCloudException.Kind.MALFORMED, "gateway reply had no key")
                        return HaierLocalKey(key, inner["vers"]?.jsonPrimitive?.intOrNull)
                    }
                }
            } catch (e: IOException) {
                throw HaierGatewayException(HaierCloudException.Kind.UNREACHABLE, "gateway: ${e.message ?: e.javaClass.simpleName}")
            }
            throw HaierGatewayException(HaierCloudException.Kind.UNREACHABLE, "gateway: no reply within ${timeoutMs / 1000}s")
        }
    }

    companion object {
        const val PORT = 58702
    }
}

/**
 * Minimal MQTT 3.1.1 client over TLS, enough for one subscribe/publish/reply exchange. The server
 * certificate and host name are verified: this link carries the account token and the device key.
 */
object TlsMqttConnector : MqttConnector {
    override fun connect(host: String, port: Int, clientId: String, username: String, password: String): MqttLink {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.soTimeout = 10_000
            socket.startHandshake()
            val link = MqttStreamLink(socket.getInputStream(), socket.getOutputStream(), socket::close) { socket.soTimeout = it }
            link.connect(clientId, username, password)
            return link
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            throw e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }
}

/** MQTT 3.1.1 framing over a byte stream. Separate from the socket so it can be tested with pipes. */
class MqttStreamLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
    private val setReadTimeout: (Int) -> Unit
) : MqttLink {
    private var packetId = 0
    private val pending = ArrayList<Pair<String, ByteArray>>()

    fun connect(clientId: String, username: String, password: String) {
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 4) + "MQTT".toByteArray() + byteArrayOf(4, 0xC2.toByte(), 0, 60)) // v3.1.1, user+pass+clean, keepalive 60
        payload.write(field(clientId)); payload.write(field(username)); payload.write(field(password))
        send(0x10, payload.toByteArray())
        val (type, body) = readPacket() ?: throw IOException("gateway closed before CONNACK")
        if (type shr 4 != 2 || body.size < 2) throw IOException("expected CONNACK")
        when (val rc = body[1].toInt()) {
            0 -> {}
            3 -> throw IOException("gateway unavailable (CONNACK 3)")
            else -> throw IOException("gateway refused the connection (CONNACK $rc)")
        }
    }

    override fun subscribe(topic: String) {
        val id = ++packetId
        send(0x82, byteArrayOf((id shr 8).toByte(), id.toByte()) + field(topic) + byteArrayOf(0))
        // Wait for the SUBACK, or the reply to the publish that follows could arrive before the subscription exists.
        repeat(20) {
            val (type, body) = readPacket() ?: throw IOException("gateway closed before SUBACK")
            when (type shr 4) {
                9 -> if (body.size >= 2 && ((body[0].toInt() and 0xFF) shl 8 or (body[1].toInt() and 0xFF)) == id) return
                3 -> pending += decodePublish(type, body)
            }
        }
        throw IOException("no SUBACK")
    }

    override fun publish(topic: String, payload: String) = send(0x30, field(topic) + payload.toByteArray(Charsets.UTF_8))

    override fun poll(timeoutMs: Int): List<Pair<String, ByteArray>> {
        val out = ArrayList(pending).also { pending.clear() }
        // Only the wait for a packet to start is short; once one starts, read it whole so framing never splits.
        setReadTimeout(timeoutMs)
        val first = try { input.read() } catch (_: SocketTimeoutException) { return out }
        setReadTimeout(PACKET_TIMEOUT_MS)
        val (type, body) = readPacket(first) ?: if (out.isNotEmpty()) return out else throw IOException("gateway closed the connection")
        if (type shr 4 == 3) out += decodePublish(type, body)
        return out
    }

    override fun close() {
        try { output.write(byteArrayOf(0xE0.toByte(), 0)); output.flush() } catch (_: IOException) {}
        onClose()
    }

    private fun decodePublish(type: Int, body: ByteArray): Pair<String, ByteArray> {
        val topicLen = (body[0].toInt() and 0xFF) shl 8 or (body[1].toInt() and 0xFF)
        val topic = String(body, 2, topicLen, Charsets.UTF_8)
        val qos = (type shr 1) and 3
        val start = 2 + topicLen + if (qos > 0) 2 else 0
        return topic to body.copyOfRange(start, body.size)
    }

    private fun send(header: Int, body: ByteArray) {
        output.write(byteArrayOf(header.toByte()) + encodeLength(body.size) + body)
        output.flush()
    }

    /** One whole packet as (fixed header byte, body), or null at end of stream. */
    private fun readPacket(type: Int = input.read()): Pair<Int, ByteArray>? {
        if (type < 0) return null
        var length = 0
        var multiplier = 1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            length += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) break
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IOException("malformed length")
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return type to body
    }

    companion object {
        private const val PACKET_TIMEOUT_MS = 10_000

        fun encodeLength(n: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var x = n
            do {
                var d = x % 128
                x /= 128
                if (x > 0) d = d or 0x80
                out.write(d)
            } while (x > 0)
            return out.toByteArray()
        }

        private fun field(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
        }
    }
}
