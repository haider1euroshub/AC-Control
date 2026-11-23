package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.NetworkDevice
import kotlinx.coroutines.delay
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

/** WS-Security UsernameToken with PasswordDigest = Base64(SHA-1(nonce + created + password)), as ONVIF requires. */
object WsSecurity {
    fun digest(nonce: ByteArray, created: String, password: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce); sha1.update(created.toByteArray(Charsets.UTF_8)); sha1.update(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha1.digest())
    }

    fun header(username: String, password: String, nonce: ByteArray, created: String): String =
        "<wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
            "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\"><wsse:UsernameToken>" +
            "<wsse:Username>${xmlEscape(username)}</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">${digest(nonce, created, password)}</wsse:Password>" +
            "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">${Base64.getEncoder().encodeToString(nonce)}</wsse:Nonce>" +
            "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"

    fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * ONVIF cameras (Profile S): device service from WS-Discovery XAddrs; confirmed with the
 * unauthenticated GetSystemDateAndTime; then GetCapabilities, GetProfiles and GetStreamUri (RTSP) with
 * the camera's username/password. PTZ is offered only if the camera reports a PTZ service AND the
 * chosen profile has a PTZ configuration. Cameras without ONVIF are not controllable (cloud-only
 * cameras need their vendor app). NOT YET TESTED ON A REAL CAMERA.
 */
class OnvifController(
    private val http: LanHttp,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Instant = Instant::now,
    private val nudgeMs: Long = 400
) : DeviceController {

    override val protocol = "ONVIF"

    override fun appliesTo(device: NetworkDevice): Boolean = device.onvifXAddrs.isNotEmpty()

    override suspend fun connect(device: NetworkDevice, credentials: Credentials?): ConnectResult {
        val deviceUrl = device.onvifXAddrs.sorted().first()
        // 1. Confirm ONVIF (this call must be answered without authentication) and learn the camera's clock.
        val time = soap(deviceUrl, "<tds:GetSystemDateAndTime xmlns:tds=\"$TDS\"/>", null, 0)
        if (time.status != 200 || !time.text.contains("GetSystemDateAndTimeResponse")) return ConnectResult.Unavailable("$protocol: no answer from the device service (HTTP ${time.status})")
        val skewMs = cameraUtc(time.text)?.let { it.toEpochMilli() - clock().toEpochMilli() } ?: 0L
        if (credentials == null) return ConnectResult.NeedsCredentials(protocol, "This camera speaks ONVIF. Enter the camera's own username and password (set in its app or web page).")

        // 2. Services.
        val caps = soap(deviceUrl, "<tds:GetCapabilities xmlns:tds=\"$TDS\"><tds:Category>All</tds:Category></tds:GetCapabilities>", credentials, skewMs)
        if (caps.status == 400 || caps.status == 401 || caps.text.contains("NotAuthorized")) return ConnectResult.NeedsCredentials(protocol, "The camera refused that username or password.")
        if (caps.status != 200) return ConnectResult.Unavailable("$protocol: GetCapabilities HTTP ${caps.status}")
        val mediaUrl = sectionXAddr(caps.text, "Media")?.takeIf { sameHost(it, deviceUrl) } ?: return ConnectResult.Unavailable("$protocol: the camera reports no media service")
        val ptzUrl = sectionXAddr(caps.text, "PTZ")?.takeIf { sameHost(it, deviceUrl) }

        // 3. First media profile.
        val profiles = soap(mediaUrl, "<trt:GetProfiles xmlns:trt=\"$TRT\"/>", credentials, skewMs)
        if (profiles.status != 200) return if (profiles.status == 400 || profiles.status == 401) ConnectResult.NeedsCredentials(protocol, "The camera refused that username or password.")
            else ConnectResult.Unavailable("$protocol: GetProfiles HTTP ${profiles.status}")
        val profile = Regex("<(?:\\w+:)?Profiles[^>]*\\btoken=\"([^\"]+)\"[^>]*>(.*?)</(?:\\w+:)?Profiles>", RegexOption.DOT_MATCHES_ALL).find(profiles.text)
            ?: return ConnectResult.Unavailable("$protocol: the camera has no media profile")
        val token = profile.groupValues[1]
        val hasPtz = ptzUrl != null && Regex("<(?:\\w+:)?PTZConfiguration[\\s>]").containsMatchIn(profile.groupValues[2])
        return ConnectResult.Connected(Session(mediaUrl, ptzUrl.takeIf { hasPtz }, token, credentials, skewMs, device.displayName))
    }

    private inner class Session(
        private val mediaUrl: String, private val ptzUrl: String?, private val profile: String,
        private val credentials: Credentials, private val skewMs: Long, override val status: String?
    ) : Connection {
        override val protocol = this@OnvifController.protocol
        override val capabilities = Capabilities(stream = true, ptz = ptzUrl != null)

        override suspend fun perform(command: Command): ControlResult = when (command) {
            Command.StreamUri -> {
                val body = "<trt:GetStreamUri xmlns:trt=\"$TRT\" xmlns:tt=\"$TT\"><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                    "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>${WsSecurity.xmlEscape(profile)}</trt:ProfileToken></trt:GetStreamUri>"
                val r = soap(mediaUrl, body, credentials, skewMs)
                val uri = Regex("<(?:\\w+:)?Uri>\\s*([^<]+?)\\s*</(?:\\w+:)?Uri>").find(r.text)?.groupValues?.get(1)?.replace("&amp;", "&")
                if (r.status == 200 && uri != null && uri.startsWith("rtsp://")) ControlResult.Stream(withCredentials(uri, credentials))
                else ControlResult.Failed("The camera gave no RTSP address (HTTP ${r.status})")
            }
            is Command.Ptz -> {
                val url = ptzUrl ?: return ControlResult.Failed("This camera has no pan/tilt/zoom")
                val (x, y, z) = when (command.move) {
                    PtzMove.LEFT -> Triple(-0.5, 0.0, 0.0); PtzMove.RIGHT -> Triple(0.5, 0.0, 0.0)
                    PtzMove.UP -> Triple(0.0, 0.5, 0.0); PtzMove.DOWN -> Triple(0.0, -0.5, 0.0)
                    PtzMove.ZOOM_IN -> Triple(0.0, 0.0, 0.5); PtzMove.ZOOM_OUT -> Triple(0.0, 0.0, -0.5)
                }
                val p = WsSecurity.xmlEscape(profile)
                val move = soap(url, "<tptz:ContinuousMove xmlns:tptz=\"$TPTZ\" xmlns:tt=\"$TT\"><tptz:ProfileToken>$p</tptz:ProfileToken><tptz:Velocity>" +
                    "<tt:PanTilt x=\"$x\" y=\"$y\"/><tt:Zoom x=\"$z\"/></tptz:Velocity></tptz:ContinuousMove>", credentials, skewMs)
                if (move.status != 200) ControlResult.Failed("The camera refused to move (HTTP ${move.status})")
                else {
                    // A short nudge: move, then always stop.
                    delay(nudgeMs)
                    val stop = soap(url, "<tptz:Stop xmlns:tptz=\"$TPTZ\"><tptz:ProfileToken>$p</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>", credentials, skewMs)
                    if (stop.status == 200) ControlResult.Done else ControlResult.Failed("The camera did not confirm Stop (HTTP ${stop.status})")
                }
            }
            else -> ControlResult.Failed("Cameras support live view and pan/tilt/zoom only")
        }
    }

    private suspend fun soap(url: String, body: String, credentials: Credentials?, skewMs: Long): HttpReply {
        val security = credentials?.let {
            val nonce = ByteArray(16).also(random::nextBytes)
            val created = DateTimeFormatter.ISO_INSTANT.format(clock().plusMillis(skewMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            "<s:Header>" + WsSecurity.header(it.username, it.password, nonce, created) + "</s:Header>"
        } ?: ""
        val envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">$security<s:Body>$body</s:Body></s:Envelope>"
        return http.request("POST", url, mapOf("Content-Type" to "application/soap+xml; charset=utf-8"), envelope.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val TDS = "http://www.onvif.org/ver10/device/wsdl"
        const val TRT = "http://www.onvif.org/ver10/media/wsdl"
        const val TPTZ = "http://www.onvif.org/ver20/ptz/wsdl"
        const val TT = "http://www.onvif.org/ver10/schema"

        /** XAddr inside the <Media>/<PTZ> section of a GetCapabilities reply. */
        fun sectionXAddr(xml: String, section: String): String? =
            Regex("<(?:\\w+:)?$section>.*?<(?:\\w+:)?XAddr>\\s*([^<]+?)\\s*</(?:\\w+:)?XAddr>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        /** The camera's UTC clock from GetSystemDateAndTime (for the WS-Security Created time). */
        fun cameraUtc(xml: String): Instant? {
            val utc = Regex("<(?:\\w+:)?UTCDateTime>(.*?)</(?:\\w+:)?UTCDateTime>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
            fun n(tag: String) = Regex("<(?:\\w+:)?$tag>\\s*(\\d+)\\s*<").find(utc)?.groupValues?.get(1)?.toIntOrNull()
            return try {
                java.time.LocalDateTime.of(n("Year")!!, n("Month")!!, n("Day")!!, n("Hour")!!, n("Minute")!!, n("Second")!!).toInstant(java.time.ZoneOffset.UTC)
            } catch (_: Exception) { null }
        }

        private fun sameHost(a: String, b: String) = try { URI(a).host == URI(b).host } catch (_: Exception) { false }

        /** rtsp://host/... -> rtsp://user:pass@host/... so a player can open it (kept in memory only). */
        fun withCredentials(uri: String, c: Credentials): String {
            if (uri.substringAfter("rtsp://").substringBefore('/').contains('@')) return uri
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
            return "rtsp://${enc(c.username)}:${enc(c.password)}@" + uri.removePrefix("rtsp://")
        }
    }
}
