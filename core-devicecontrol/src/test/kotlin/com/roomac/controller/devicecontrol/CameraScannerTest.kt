package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.DeviceType
import com.roomac.controller.netscan.NetworkDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant

class OnvifTest {
    private val deviceUrl = "http://192.168.1.80/onvif/device_service"
    private val mediaUrl = "http://192.168.1.80/onvif/media_service"
    private val ptzUrl = "http://192.168.1.80/onvif/ptz_service"
    private val cam = NetworkDevice("192.168.1.80", onvifXAddrs = setOf(deviceUrl), type = DeviceType.CAMERA, name = "Front Door")
    private val creds = Credentials("admin", "secret")

    private val time = "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Time><tt:Hour>10</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>30</tt:Second></tt:Time>" +
        "<tt:Date><tt:Year>2026</tt:Year><tt:Month>11</tt:Month><tt:Day>19</tt:Day></tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"

    private fun caps(withPtz: Boolean) = "<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Device><tt:XAddr>$deviceUrl</tt:XAddr></tt:Device>" +
        "<tt:Media><tt:XAddr>$mediaUrl</tt:XAddr></tt:Media>" + (if (withPtz) "<tt:PTZ><tt:XAddr>$ptzUrl</tt:XAddr></tt:PTZ>" else "") + "</tds:Capabilities></tds:GetCapabilitiesResponse>"

    private fun profiles(withPtz: Boolean) = "<trt:GetProfilesResponse><trt:Profiles fixed=\"true\" token=\"Profile_1\"><tt:Name>main</tt:Name>" +
        (if (withPtz) "<tt:PTZConfiguration token=\"PTZ_1\"/>" else "") + "</trt:Profiles></trt:GetProfilesResponse>"

    private fun fake(withPtz: Boolean = true, acceptPassword: String = "secret") = FakeHttp().apply {
        val authOk = { r: FakeHttp.Req -> r.body.contains("<wsse:Username>admin</wsse:Username>") && checkDigest(r.body, acceptPassword) }
        onBody(deviceUrl, "GetSystemDateAndTime") { FakeHttp.ok(time) }
        onBody(deviceUrl, "GetCapabilities") { if (authOk(it)) FakeHttp.ok(caps(withPtz)) else HttpReply(400, emptyMap(), "<s:Fault>ter:NotAuthorized</s:Fault>".toByteArray()) }
        onBody(mediaUrl, "GetProfiles") { if (authOk(it)) FakeHttp.ok(profiles(withPtz)) else FakeHttp.status(401) }
        onBody(mediaUrl, "GetStreamUri") { FakeHttp.ok("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://192.168.1.80:554/Streaming/Channels/101?transportmode=unicast&amp;profile=Profile_1</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>") }
        onBody(ptzUrl, "ContinuousMove") { FakeHttp.ok("<tptz:ContinuousMoveResponse/>") }
        onBody(ptzUrl, "<tptz:Stop") { FakeHttp.ok("<tptz:StopResponse/>") }
    }

    /** The fake camera verifies the WS-Security digest like a real one would. */
    private fun checkDigest(body: String, password: String): Boolean {
        val nonce = Regex("<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>").find(body)?.groupValues?.get(1) ?: return false
        val created = Regex("<wsu:Created>([^<]+)</wsu:Created>").find(body)?.groupValues?.get(1) ?: return false
        val digest = Regex("<wsse:Password[^>]*>([^<]+)</wsse:Password>").find(body)?.groupValues?.get(1) ?: return false
        return digest == WsSecurity.digest(java.util.Base64.getDecoder().decode(nonce), created, password)
    }

    // Phone clock 5 minutes behind the camera: Created must follow the camera's clock.
    private fun controller(http: LanHttp) = OnvifController(http, SecureRandom(), { Instant.parse("2026-11-19T09:55:30Z") }, nudgeMs = 1)

    @Test fun digestMatchesIndependentVector() {
        // Computed independently with .NET SHA1 over bytes 00..0F + "2026-11-19T10:00:00Z" + "secret".
        assertEquals("DwUKikpgZ0Itt+pr/WHgELQ7lsM=", WsSecurity.digest(ByteArray(16) { it.toByte() }, "2026-11-19T10:00:00Z", "secret"))
    }

    @Test fun asksForCredentialsAfterConfirmingOnvif() = runTest {
        val r = controller(fake()).connect(cam, null)
        assertTrue(r is ConnectResult.NeedsCredentials)
    }

    @Test fun wrongPasswordAsksAgain() = runTest {
        val r = controller(fake()).connect(cam, Credentials("admin", "nope"))
        assertTrue(r is ConnectResult.NeedsCredentials)
    }

    @Test fun streamUriWithCredentialsAndPtzNudge() = runTest {
        val http = fake()
        val c = (controller(http).connect(cam, creds) as ConnectResult.Connected).connection
        assertTrue(c.capabilities.stream && c.capabilities.ptz)
        val created = Regex("<wsu:Created>([^<]+)</wsu:Created>").find(http.requests.last { it.body.contains("GetProfiles") }.body)!!.groupValues[1]
        assertEquals("camera clock used, not the phone's", "2026-11-19T10:00:30Z", created)
        val stream = c.perform(Command.StreamUri) as ControlResult.Stream
        assertEquals("rtsp://admin:secret@192.168.1.80:554/Streaming/Channels/101?transportmode=unicast&profile=Profile_1", stream.uri)
        assertEquals(ControlResult.Done, c.perform(Command.Ptz(PtzMove.LEFT)))
        val moves = http.requests.filter { it.url == ptzUrl }
        assertTrue(moves[0].body.contains("<tt:PanTilt x=\"-0.5\" y=\"0.0\"/>"))
        assertTrue("always followed by Stop", moves[1].body.contains("<tptz:Stop"))
    }

    @Test fun ptzOnlyWhenTheProfileHasIt() = runTest {
        val c = (controller(fake(withPtz = false)).connect(cam, creds) as ConnectResult.Connected).connection
        assertTrue(c.capabilities.stream)
        assertFalse(c.capabilities.ptz)
        assertTrue(c.perform(Command.Ptz(PtzMove.UP)) is ControlResult.Failed)
    }

    @Test fun nonOnvifAnswerIsUnavailable() = runTest {
        val http = FakeHttp().apply { onBody(deviceUrl, "GetSystemDateAndTime") { FakeHttp.ok("<html/>") } }
        assertTrue(controller(http).connect(cam, creds) is ConnectResult.Unavailable)
    }

    @Test fun helpers() {
        assertEquals(Instant.parse("2026-11-19T10:00:30Z"), OnvifController.cameraUtc(time))
        assertNull(OnvifController.cameraUtc("<x/>"))
        assertEquals("rtsp://a%40b:p%20w@h/s", OnvifController.withCredentials("rtsp://h/s", Credentials("a@b", "p w")))
        assertEquals("rtsp://u:p@h/s", OnvifController.withCredentials("rtsp://u:p@h/s", creds))
    }
}

class EsclTest {
    private val scanner = NetworkDevice(
        "192.168.1.41", mdnsServices = mapOf("_uscan._tcp" to mapOf("rs" to "eSCL")), mdnsPorts = mapOf("_uscan._tcp" to 8080),
        type = DeviceType.PRINTER, model = "OfficeJet"
    )
    private val base = "http://192.168.1.41:8080/eSCL"
    private val capabilities = """
        <scan:ScannerCapabilities xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
          <pwg:Version>2.63</pwg:Version>
          <scan:Platen><scan:PlatenInputCaps>
            <scan:MaxWidth>2550</scan:MaxWidth><scan:MaxHeight>3508</scan:MaxHeight>
            <scan:SettingProfiles><scan:SettingProfile>
              <scan:ColorModes><scan:ColorMode>Grayscale8</scan:ColorMode><scan:ColorMode>RGB24</scan:ColorMode></scan:ColorModes>
              <scan:DocumentFormats><pwg:DocumentFormat>application/pdf</pwg:DocumentFormat><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat></scan:DocumentFormats>
              <scan:SupportedResolutions><scan:DiscreteResolutions>
                <scan:DiscreteResolution><scan:XResolution>200</scan:XResolution><scan:YResolution>200</scan:YResolution></scan:DiscreteResolution>
                <scan:DiscreteResolution><scan:XResolution>600</scan:XResolution><scan:YResolution>600</scan:YResolution></scan:DiscreteResolution>
              </scan:DiscreteResolutions></scan:SupportedResolutions>
            </scan:SettingProfile></scan:SettingProfiles>
          </scan:PlatenInputCaps></scan:Platen>
        </scan:ScannerCapabilities>
    """.trimIndent()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Test fun scansAfterBusyPollsAndReturnsTheImage() = runTest {
        var polls = 0
        val http = FakeHttp().apply {
            on("GET", "$base/ScannerCapabilities") { FakeHttp.ok(capabilities) }
            on("POST", "$base/ScanJobs") { FakeHttp.status(201, mapOf("location" to "/eSCL/ScanJobs/42")) }
            on("GET", "http://192.168.1.41:8080/eSCL/ScanJobs/42/NextDocument") { if (polls++ < 2) FakeHttp.status(503) else HttpReply(200, mapOf("content-type" to "image/jpeg"), jpeg) }
        }
        val c = (EsclController(http, pollMs = 1).connect(scanner) as ConnectResult.Connected).connection
        assertTrue(c.capabilities.scan)
        val img = c.perform(Command.Scan(color = true, dpi = 300)) as ControlResult.Image
        assertArrayEquals(jpeg, img.bytes)
        assertEquals("image/jpeg", img.mimeType)
        val job = http.requests.first { it.method == "POST" }.body
        assertTrue(job.contains("<pwg:Version>2.63</pwg:Version>"))
        assertTrue("whole platen", job.contains("<pwg:Width>2550</pwg:Width>") && job.contains("<pwg:Height>3508</pwg:Height>"))
        assertTrue("closest supported dpi", job.contains("<scan:XResolution>200</scan:XResolution>"))
        assertTrue(job.contains("<scan:ColorMode>RGB24</scan:ColorMode>") && job.contains("image/jpeg"))
        assertEquals(3, polls)
    }

    @Test fun busyScannerAndForeignJobLocationAreRefused() = runTest {
        val busy = FakeHttp().apply {
            on("GET", "$base/ScannerCapabilities") { FakeHttp.ok(capabilities) }
            on("POST", "$base/ScanJobs") { FakeHttp.status(503) }
        }
        val c = (EsclController(busy).connect(scanner) as ConnectResult.Connected).connection
        assertTrue((c.perform(Command.Scan()) as ControlResult.Failed).message.contains("busy"))

        val foreign = FakeHttp().apply {
            on("GET", "$base/ScannerCapabilities") { FakeHttp.ok(capabilities) }
            on("POST", "$base/ScanJobs") { FakeHttp.status(201, mapOf("location" to "http://203.0.113.9/job")) }
        }
        val c2 = (EsclController(foreign).connect(scanner) as ConnectResult.Connected).connection
        assertTrue(c2.perform(Command.Scan()) is ControlResult.Failed)
        assertTrue(foreign.requests.none { it.url.contains("203.0.113.9") })
    }

    @Test fun baseUrlUsesTlsVariantAndResourcePath() {
        val secure = scanner.copy(mdnsServices = mapOf("_uscans._tcp" to mapOf("rs" to "/escl/")), mdnsPorts = mapOf("_uscans._tcp" to 443))
        assertEquals("https://192.168.1.41:443/escl", EsclController.baseUrl(secure))
        assertEquals(base, EsclController.baseUrl(scanner))
        assertNull(EsclController.baseUrl(scanner.copy(mdnsPorts = emptyMap())))
    }

    @Test fun noPlatenMeansNotControllable() = runTest {
        val http = FakeHttp().apply { on("GET", "$base/ScannerCapabilities") { FakeHttp.ok("<scan:ScannerCapabilities><scan:Adf/></scan:ScannerCapabilities>") } }
        assertTrue(EsclController(http).connect(scanner) is ConnectResult.Unavailable)
    }
}

class RegistryAndHttpTest {

    @Test fun cloudOnlyCameraIsViewOnlyWithVendorAppAdvice() = runTest {
        val r = defaultControllers(FakeHttp()).resolve(NetworkDevice("192.168.1.90", type = DeviceType.CAMERA)) as Resolution.ViewOnly
        assertTrue(r.reasons.single().contains("vendor app"))
    }

    @Test fun samsungAndLgTvsSayNotImplemented() = runTest {
        val samsung = defaultControllers(FakeHttp()).resolve(NetworkDevice("192.168.1.91", vendor = "Samsung", type = DeviceType.TV)) as Resolution.ViewOnly
        assertTrue(samsung.reasons.any { it.contains("not implemented") })
        val lg = defaultControllers(FakeHttp()).resolve(NetworkDevice("192.168.1.92", vendor = "LG", type = DeviceType.TV)) as Resolution.ViewOnly
        assertTrue(lg.reasons.any { it.contains("not implemented") })
    }

    @Test fun firstConfirmedProtocolWinsAndNothingIsHardwareVerifiedYet() = runTest {
        val roku = NetworkDevice("192.168.1.50", ssdpTypes = setOf("roku:ecp"), type = DeviceType.MEDIA)
        val http = FakeHttp().apply { on("GET", "http://192.168.1.50:8060/query/device-info") { FakeHttp.ok("<device-info><is-tv>false</is-tv></device-info>") } }
        val r = defaultControllers(http).resolve(roku) as Resolution.Controllable
        assertEquals("Roku ECP", r.connection.protocol)
        assertFalse(r.hardwareVerified)
    }

    @Test fun unknownDeviceIsViewOnly() = runTest {
        assertTrue(defaultControllers(FakeHttp()).resolve(NetworkDevice("192.168.1.99")) is Resolution.ViewOnly)
    }

    @Test fun throwingAdapterBecomesAReasonNotACrash() = runTest {
        val boom = object : LanHttp { override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply = throw java.io.IOException("refused") }
        val r = defaultControllers(boom).resolve(NetworkDevice("192.168.1.50", ssdpTypes = setOf("roku:ecp"))) as Resolution.ViewOnly
        assertTrue(r.reasons.first().contains("Roku ECP"))
    }

    @Test fun lanTransportsRefuseNonPrivateAddresses() = runTest {
        listOf("192.168.1.5", "10.0.0.2", "172.16.0.1", "172.31.255.254").forEach { assertTrue(it, LanTls.isPrivateLan(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.168.1", "fe80::1", null).forEach { assertFalse("$it", LanTls.isPrivateLan(it)) }
        val e = runCatching { RawSocketHttp().request("GET", "http://8.8.8.8/") }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("non-LAN") == true)
    }

    @Test fun httpParserHandlesChunkedAndContentLength() {
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/xml\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n".toByteArray()
        val r = RawSocketHttp.parse(chunked)
        assertEquals(200, r.status); assertEquals("hello world", r.text); assertEquals("text/xml", r.header("Content-Type"))
        val sized = RawSocketHttp.parse("HTTP/1.1 201 Created\r\nLocation: /eSCL/ScanJobs/1\r\nContent-Length: 2\r\n\r\nokEXTRA".toByteArray())
        assertEquals(201, sized.status); assertEquals("ok", sized.text); assertEquals("/eSCL/ScanJobs/1", sized.header("location"))
    }
}
