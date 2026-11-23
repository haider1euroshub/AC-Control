package com.roomac.controller.devicecontrol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpReply(val status: Int, /** Lower-case names. */ val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]
}

/** HTTP to a device on the LAN. Replaced by a fake in tests. */
interface LanHttp {
    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpReply
}

/**
 * Minimal HTTP/1.1 client over a plain socket (or TLS for https), one request per connection, with
 * chunked decoding and a size cap. Raw sockets keep the app's cleartext-HTTP policy closed for
 * everything else. Only private-LAN device URLs are passed in (callers take them from discovery).
 *
 * https: LAN devices (eSCL scanners, Cast) use self-signed certificates no CA vouches for, so the
 * certificate is NOT verified. That gives encryption against passive sniffing but no proof of
 * identity; it is used only for addresses discovery found on the phone's own network.
 */
class RawSocketHttp(
    private val timeoutMs: Int = 5_000,
    private val maxBytes: Int = 32 * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : LanHttp {

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply = withContext(io) {
        val uri = URI(url)
        if (!LanTls.isPrivateLan(uri.host)) throw IOException("refusing non-LAN address")
        val tls = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val socket = if (tls) LanTls.socket(uri.host, port, timeoutMs) else Socket().apply { connect(InetSocketAddress(uri.host, port), timeoutMs) }
        socket.use { s ->
            s.soTimeout = timeoutMs
            val head = StringBuilder("$method $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (body != null || method == "POST" || method == "PUT") head.append("Content-Length: ${body?.size ?: 0}\r\n")
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            body?.let(out::write)
            out.flush()
            val raw = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                raw.write(buf, 0, n)
                if (raw.size() > maxBytes) throw IOException("reply larger than ${maxBytes / 1024 / 1024} MB")
            }
            parse(raw.toByteArray())
        }
    }

    companion object {
        /** Parses a complete HTTP/1.x reply (status line, headers, body; chunked or not). */
        fun parse(raw: ByteArray): HttpReply {
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            if (split < 0) throw IOException("incomplete HTTP reply")
            val head = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val status = head.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
            val headers = head.drop(1).mapNotNull { l ->
                val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()
            var body = raw.copyOfRange(split + 4, raw.size)
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) body = dechunk(body)
            else headers["content-length"]?.toIntOrNull()?.let { if (it in 0..body.size) body = body.copyOf(it) }
            return HttpReply(status, headers, body)
        }

        fun dechunk(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                val lineEnd = indexOf(data, "\r\n".toByteArray(), pos)
                if (lineEnd < 0) throw IOException("bad chunk header")
                val size = String(data, pos, lineEnd - pos, Charsets.ISO_8859_1).substringBefore(';').trim().toInt(16)
                if (size == 0) break
                val start = lineEnd + 2
                if (start + size > data.size) throw IOException("truncated chunk")
                out.write(data, start, size)
                pos = start + size + 2
            }
            return out.toByteArray()
        }

        private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
            outer@ for (i in from..data.size - pattern.size) {
                for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
                return i
            }
            return -1
        }
    }
}

/** TLS sockets for LAN devices with self-signed certificates (see [RawSocketHttp] for why they are not verified). */
object LanTls {
    /** Only RFC 1918 IPv4 literals: these transports never reach the internet (and never resolve names). */
    fun isPrivateLan(host: String?): Boolean {
        val p = host?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 && it.all { b -> b in 0..255 } } ?: return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    private val context: SSLContext by lazy {
        val acceptAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(acceptAll), SecureRandom()) }
    }

    fun socket(host: String, port: Int, timeoutMs: Int): SSLSocket {
        if (!isPrivateLan(host)) throw IOException("refusing non-LAN address")
        val plain = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
        return (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
            soTimeout = timeoutMs
            startHandshake()
        }
    }
}
