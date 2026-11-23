package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.DeviceType
import com.roomac.controller.netscan.NetworkDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuEcpTest {
    private val roku = NetworkDevice("192.168.1.50", ssdpTypes = setOf("roku:ecp"), type = DeviceType.MEDIA)
    private val base = "http://192.168.1.50:8060"

    private fun info(isTv: Boolean) = "<?xml version=\"1.0\"?><device-info><user-device-name>Living Room</user-device-name><is-tv>$isTv</is-tv></device-info>"

    @Test fun confirmsThenSendsKeys() = runTest {
        val http = FakeHttp().apply {
            on("GET", "$base/query/device-info") { FakeHttp.ok(info(isTv = false)) }
            routes += ({ r: FakeHttp.Req -> r.method == "POST" && r.url.startsWith("$base/keypress/") } to { _: FakeHttp.Req -> FakeHttp.ok("") })
        }
        val c = (RokuEcpController(http).connect(roku) as ConnectResult.Connected).connection
        assertEquals("Living Room", c.status)
        assertFalse("streaming sticks have no power-off", RemoteKey.POWER_OFF in c.capabilities.keys)
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.PLAY_PAUSE)))
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.VOLUME_UP)))
        assertEquals(listOf("$base/keypress/Play", "$base/keypress/VolumeUp"), http.requests.filter { it.method == "POST" }.map { it.url })
        assertTrue(c.perform(Command.Key(RemoteKey.POWER_OFF)) is ControlResult.Failed)
    }

    @Test fun rokuTvOffersPowerOff() = runTest {
        val http = FakeHttp().apply {
            on("GET", "$base/query/device-info") { FakeHttp.ok(info(isTv = true)) }
            on("POST", "$base/keypress/PowerOff") { FakeHttp.ok("") }
        }
        val c = (RokuEcpController(http).connect(roku) as ConnectResult.Connected).connection
        assertTrue(RemoteKey.POWER_OFF in c.capabilities.keys)
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.POWER_OFF)))
    }

    @Test fun restrictedRokuExplainsTheSetting() = runTest {
        val http = FakeHttp().apply { on("GET", "$base/query/device-info") { FakeHttp.status(403) } }
        val r = RokuEcpController(http).connect(roku) as ConnectResult.Unavailable
        assertTrue(r.reason.contains("Control by mobile apps"))
    }

    @Test fun nonRokuAnswerIsNotControllable() = runTest {
        val http = FakeHttp().apply { on("GET", "$base/query/device-info") { FakeHttp.ok("<html>router</html>") } }
        assertTrue(RokuEcpController(http).connect(roku) is ConnectResult.Unavailable)
    }
}

class UpnpAvTest {
    private val location = "http://192.168.1.60:1400/xml/device_description.xml"
    private val dev = NetworkDevice("192.168.1.60", ssdpTypes = setOf("urn:schemas-upnp-org:device:MediaRenderer:1"), ssdpLocation = location, type = DeviceType.MEDIA)
    private val avUrl = "http://192.168.1.60:1400/MediaRenderer/AVTransport/Control"
    private val rcUrl = "http://192.168.1.60:1400/MediaRenderer/RenderingControl/Control"

    private val description = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/MediaRenderer/RenderingControl/Control</controlURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/MediaRenderer/AVTransport/Control</controlURL></service>
          <service><serviceType>urn:evil:service:X:1</serviceType><controlURL>http://203.0.113.9/x</controlURL></service>
        </serviceList></device></root>
    """.trimIndent()

    private fun fake(volume: Int = 30) = FakeHttp().apply {
        on("GET", location) { FakeHttp.ok(description) }
        onBody(avUrl, "GetTransportInfo") { FakeHttp.ok("<CurrentTransportState>STOPPED</CurrentTransportState>") }
        onBody(rcUrl, "GetVolume") { FakeHttp.ok("<u:GetVolumeResponse><CurrentVolume>$volume</CurrentVolume></u:GetVolumeResponse>") }
        onBody(avUrl, "<u:Play ") { FakeHttp.ok("") }
        onBody(avUrl, "<u:Pause ") { FakeHttp.ok("") }
        onBody(rcUrl, "<u:SetVolume ") { FakeHttp.ok("") }
    }

    @Test fun parsesServicesAndDropsOffHostControlUrls() {
        val s = UpnpAvController.services(description, location)
        assertEquals(listOf(rcUrl, avUrl), s.map { it.controlUrl })
    }

    @Test fun confirmsBothServicesThenControls() = runTest {
        val http = fake()
        val c = (UpnpAvController(http).connect(dev) as ConnectResult.Connected).connection
        assertEquals(setOf(RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.STOP, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE), c.capabilities.keys)
        assertEquals("Volume 30", c.status)
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.PLAY)))
        val play = http.requests.last()
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", play.headers["SOAPACTION"])
        assertTrue(play.body.contains("<InstanceID>0</InstanceID><Speed>1</Speed>"))
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.VOLUME_UP)))
        assertTrue(http.requests.last().body.contains("<DesiredVolume>35</DesiredVolume>"))
        assertEquals("Volume 35", c.status)
        assertEquals(ControlResult.Done, c.perform(Command.SetVolume(150)))
        assertTrue("clamped", http.requests.last().body.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test fun rendererWithoutAnswersIsViewOnly() = runTest {
        val http = FakeHttp().apply { on("GET", location) { FakeHttp.ok(description) } } // services exist but never answer
        assertTrue(UpnpAvController(http).connect(dev) is ConnectResult.Unavailable)
    }

    @Test fun refusedActionReportsUpnpError() = runTest {
        val http = fake().apply { onBody(avUrl, "<u:Pause ") { HttpReply(500, emptyMap(), "<errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>".toByteArray()) } }
        val c = (UpnpAvController(http).connect(dev) as ConnectResult.Connected).connection
        val r = c.perform(Command.Key(RemoteKey.PAUSE)) as ControlResult.Failed
        assertTrue(r.message, r.message.contains("Transition not available"))
    }
}

class GoogleCastTest {

    /** Scripted Cast device: answers by request type, records what was sent. */
    private class FakeCast(var volumeControl: String = "attenuation", var withMedia: Boolean = true, var idle: Boolean = false) : CastChannel {
        val sent = mutableListOf<CastMessage>()
        private val inbox = ArrayDeque<CastMessage>()
        var level = 0.4

        private fun receiverStatus(id: Int): String {
            val app = if (idle) """{"appId":"E8C28D3C","displayName":"Backdrop","isIdleScreen":true,"sessionId":"s0","transportId":"t0","namespaces":[]}"""
            else """{"appId":"CC1AD845","displayName":"Default Media Receiver","sessionId":"s1","transportId":"t1","namespaces":[{"name":"urn:x-cast:com.google.cast.media"}${if (withMedia) "" else ""}]}"""
                .let { if (withMedia) it else it.replace("""{"name":"urn:x-cast:com.google.cast.media"}""", """{"name":"urn:x-cast:com.example.custom"}""") }
            return """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"applications":[$app],"volume":{"level":$level,"muted":false,"controlType":"$volumeControl"}}}"""
        }

        override suspend fun send(message: CastMessage) {
            sent += message
            val p = message.payload
            val id = Regex("\"requestId\":(\\d+)").find(p)?.groupValues?.get(1)?.toInt() ?: return
            // A heartbeat PING arrives before every reply; the controller must answer it and keep waiting.
            inbox += CastMessage("receiver-0", "sender-0", GoogleCastController.NS_HEARTBEAT, """{"type":"PING"}""")
            when {
                message.namespace == GoogleCastController.NS_RECEIVER && p.contains("SET_VOLUME") -> {
                    Regex("\"level\":([0-9.]+)").find(p)?.let { level = it.groupValues[1].toDouble() }
                    inbox += CastMessage("receiver-0", "sender-0", message.namespace, receiverStatus(id))
                }
                message.namespace == GoogleCastController.NS_RECEIVER -> inbox += CastMessage("receiver-0", "sender-0", message.namespace, receiverStatus(id))
                message.namespace == GoogleCastController.NS_MEDIA && p.contains("GET_STATUS") ->
                    inbox += CastMessage("t1", "sender-0", message.namespace, """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":7,"playerState":"PLAYING"}]}""")
                message.namespace == GoogleCastController.NS_MEDIA ->
                    inbox += CastMessage("t1", "sender-0", message.namespace, """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":7,"playerState":"PAUSED"}]}""")
            }
        }
        override suspend fun receive(timeoutMs: Long): CastMessage? = inbox.removeFirstOrNull()
        override fun close() {}
    }

    private val cast = NetworkDevice("192.168.1.40", mdnsServices = mapOf("_googlecast._tcp" to mapOf("fn" to "TV")), mdnsPorts = mapOf("_googlecast._tcp" to 8009), type = DeviceType.TV)

    @Test fun codecRoundTripAndFraming() {
        val m = CastMessage("sender-0", "receiver-0", GoogleCastController.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
        val framed = CastCodec.encode(m)
        val len = ((framed[0].toInt() and 0xFF) shl 24) or ((framed[1].toInt() and 0xFF) shl 16) or ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF)
        assertEquals(framed.size - 4, len)
        // protocol_version field 1 = 0, then source_id (field 2, wire type 2).
        assertEquals(0x08, framed[4].toInt()); assertEquals(0x00, framed[5].toInt()); assertEquals(0x12, framed[6].toInt())
        assertEquals(m, CastCodec.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test fun connectsAnswersPingsAndControlsVolumeAndMedia() = runTest {
        val fake = FakeCast()
        val c = (GoogleCastController({ _, _ -> fake }).connect(cast) as ConnectResult.Connected).connection
        assertEquals(setOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE, RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.STOP), c.capabilities.keys)
        assertTrue(c.status!!.contains("Default Media Receiver") && c.status!!.contains("40%"))
        assertTrue("PING answered", fake.sent.any { it.namespace == GoogleCastController.NS_HEARTBEAT && it.payload.contains("PONG") })
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.VOLUME_UP)))
        assertEquals(0.45, fake.level, 1e-9)
        assertEquals(ControlResult.Done, c.perform(Command.Key(RemoteKey.PAUSE)))
        val pause = fake.sent.last { it.namespace == GoogleCastController.NS_MEDIA } // PONGs may follow it
        assertEquals("t1", pause.destination)
        assertTrue(pause.payload.contains("\"type\":\"PAUSE\"") && pause.payload.contains("\"mediaSessionId\":7"))
    }

    @Test fun fixedVolumeAndNoMediaNamespaceHideThoseControls() = runTest {
        val fake = FakeCast(volumeControl = "fixed", withMedia = false)
        val c = (GoogleCastController({ _, _ -> fake }).connect(cast) as ConnectResult.Connected).connection
        assertEquals(setOf(RemoteKey.STOP), c.capabilities.keys)
        assertTrue(c.perform(Command.Key(RemoteKey.VOLUME_UP)) is ControlResult.Failed)
    }

    @Test fun idleCastOffersOnlyVolume() = runTest {
        val c = (GoogleCastController({ _, _ -> FakeCast(idle = true) }).connect(cast) as ConnectResult.Connected).connection
        assertEquals(setOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE), c.capabilities.keys)
    }

    @Test fun silentDeviceIsUnavailable() = runTest {
        val silent = object : CastChannel {
            override suspend fun send(message: CastMessage) {}
            override suspend fun receive(timeoutMs: Long): CastMessage? = null
            override fun close() {}
        }
        assertTrue(GoogleCastController({ _, _ -> silent }, replyTimeoutMs = 50).connect(cast) is ConnectResult.Unavailable)
    }
}
