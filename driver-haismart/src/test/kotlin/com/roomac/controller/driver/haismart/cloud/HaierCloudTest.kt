package com.roomac.controller.driver.haismart.cloud

import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.AccountSessionStore
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.RegisterResult
import com.roomac.controller.protocol.SendCodeResult
import com.roomac.controller.protocol.SignInResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val IDENTITY = HaierAppIdentity("MB-TEST-0001", "test-app-key")

/** Expected values below were computed with sha256sum / md5sum / openssl, not with the code under test. */
class HaierCryptoTest {

    @Test
    fun `request signature matches an independently computed SHA-256`() {
        val sign = HaierSigning.sign("/uplussea/devices/v2/user/devices", "{\"a\": 1,\n \"b\": \"x y\"}", "MB-TEST-0001", " \"KEY1234567\" ", "1700000000000")
        assertEquals("40903622fc94b7edd93cb9e13a2b8a3e9c98180b16d181166eb669aae2d8c74b", sign)
    }

    @Test
    fun `password is AES-CBC with the 16-digit key as key and IV, and the key is RSA-wrapped`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(pair.public.encoded)
        val (pw, sesame) = HaierPasswordCipher.encrypt("S3cret pass!", "1234567890123456", pub)
        assertEquals("eWXc4m7WdsgwVbkI2ELOBw==", pw) // openssl enc -aes-128-cbc -K/-iv hex("1234567890123456")
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
        assertEquals("1234567890123456", String(rsa.doFinal(Base64.getDecoder().decode(sesame)), Charsets.US_ASCII))
    }

    @Test
    fun `the published service key loads and wraps a fresh random key each time`() {
        val random = SecureRandom()
        val k1 = HaierPasswordCipher.randomKey(random)
        assertTrue(k1.matches(Regex("[0-9]{16}")))
        val (pw1, s1) = HaierPasswordCipher.encrypt("x", k1)
        val (pw2, s2) = HaierPasswordCipher.encrypt("x", "0000000000000000")
        assertTrue(pw1 != pw2 && s1 != s2)
    }

    @Test
    fun `gateway credentials match independently computed values`() {
        assertEquals("397a1e2e07cbf0391eaa95067dfba026", HaierGatewayAuth.clientId("A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4"))
        assertEquals("0375c3cc7818f865fdd5d6156dd700bd", HaierGatewayAuth.password("12345678"))
        assertTrue(HaierGatewayAuth.usernameBody(SecureRandom()).matches(Regex("[0-9]{8}")))
    }

    @Test
    fun `key request and reply round trip, unrelated messages are ignored`() {
        val req = Json.parseToJsonElement(HaierKeyMessages.request("540853BD1D41", "tok", "42")).jsonObject
        assertEquals("devLocalkey", req.str("type"))
        val inner = Json.parseToJsonElement(String(Base64.getDecoder().decode(req.str("data")))).jsonObject
        assertEquals("540853BD1D41", inner.str("dev"))
        assertEquals("42", inner.str("sn"))
        assertEquals("tok", req["tokens"].toString().trim('[', ']', '"'))
        assertNotNull(HaierKeyMessages.parse(reply("42", 0, "k").toByteArray()))
        assertNull(HaierKeyMessages.parse("{\"type\":\"other\"}".toByteArray()))
        assertNull(HaierKeyMessages.parse("not json".toByteArray()))
    }

    @Test
    fun `data centre follows the account region`() {
        assertEquals("gw-sea.haieriot.net", HaierDomains.forZone("91").gateway)
        assertEquals("gw-sgp.haieriot.net", HaierDomains.forZone("92").gateway)
        assertEquals("uhome-sgp.haieriot.net", HaierDomains.forZone("92").uhome)
        assertEquals("uhome-sea.haieriot.net", HaierDomains.forZone("92").login)
    }

    @Test
    fun `login label is masked and the identity never prints its values`() {
        assertEquals("so•••@example.com", HaierAccount.maskLogin("someone@example.com"))
        assertEquals("•••••••4567", HaierAccount.maskLogin("03001234567"))
        assertFalse(IDENTITY.toString().contains("test-app-key"))
    }
}

internal fun reply(sn: String, errNo: Int, key: String?, vers: Int = 50): String {
    val inner = "{\"sn\":\"$sn\",\"errNo\":$errNo" + (if (key != null) ",\"vers\":$vers,\"key\":\"$key\"" else "") + "}"
    return "{\"type\":\"devLocalkey\",\"data\":\"${Base64.getEncoder().encodeToString(inner.toByteArray())}\"}"
}

/** Routes requests by path; records what was sent. Two accounts are known: a@x (owns AC 540853BD1D41) and b@x (owns AABBCC001122). */
private class FakeHaierService : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var loginReply: (HttpRequest) -> HttpResponse = { req ->
        if (req.body.contains("\"b@x\"")) tokens("ACCESS-B", "REFRESH-B", "user-b") else tokens("ACCESS1", "REFRESH1", "user-a")
    }
    var refreshReply: (HttpRequest) -> HttpResponse = { req ->
        if (req.body.contains("REFRESH-B")) tokens("ACCESS-B2", "REFRESH-B") else tokens("ACCESS2", "REFRESH1")
    }
    var devicesReply: (HttpRequest) -> HttpResponse = { req ->
        if (req.headers["accessToken"]!!.startsWith("ACCESS-B")) list("AA:BB:CC:00:11:22" to "Office AC")
        else list("54:08:53:bd:1d:41" to "Bedroom AC")
    }
    // Sign-up (account creation) routes.
    var checkReply: (HttpRequest) -> HttpResponse = { ok("{\"retCode\":\"00000\",\"data\":{\"available\":true}}") }
    var sendCodeReply: (HttpRequest) -> HttpResponse = { ok("{\"retCode\":\"00000\",\"data\":{\"delay\":\"60\"}}") }
    var verifyReply: (HttpRequest) -> HttpResponse = { ok("{\"retCode\":\"00000\",\"data\":{\"code\":\"TOKEN123\"}}") }
    var registerReply: (HttpRequest) -> HttpResponse = { ok("{\"retCode\":\"00000\",\"data\":{\"success\":true}}") }

    override fun send(request: HttpRequest): HttpResponse {
        requests += request
        return when {
            request.url.endsWith(HaierCloudClient.LOGIN_PATH) -> loginReply(request)
            request.url.endsWith(HaierCloudClient.REFRESH_PATH) -> refreshReply(request)
            request.url.endsWith(HaierCloudClient.DEVICE_LIST_PATH) -> devicesReply(request)
            request.url.endsWith(HaierCloudClient.CHECK_PATH) -> checkReply(request)
            request.url.endsWith(HaierCloudClient.SEND_CODE_PATH) -> sendCodeReply(request)
            request.url.endsWith(HaierCloudClient.VERIFY_CODE_PATH) -> verifyReply(request)
            request.url.endsWith(HaierCloudClient.REGISTER_PATH) -> registerReply(request)
            else -> HttpResponse(404, "")
        }
    }

    fun paths() = requests.map { it.url.substringAfter(".net") }

    companion object {
        fun ok(body: String) = HttpResponse(200, body)
        fun tokens(access: String, refresh: String, user: String = "") =
            ok("{\"retCode\":\"00000\",\"data\":{\"tokenInfo\":{\"uhomeAccessToken\":\"$access\",\"accountToken\":\"$access\",\"refreshToken\":\"$refresh\",\"uhomeUserId\":\"$user\"}}}")
        fun list(vararg devices: Pair<String, String>) = ok(
            "{\"retCode\":\"00000\",\"data\":{\"deviceInfos\":[" + devices.joinToString(",") { (id, name) ->
                "{\"baseInfo\":{\"deviceId\":\"$id\",\"deviceName\":\"$name\",\"wifiType\":\"201c\",\"isOnline\":true},\"extendedInfo\":{\"model\":\"HSU-20HFTEX\"}}"
            } + "]}}"
        )
    }
}

/** Issues a key only for devices the presented token's account owns. */
private class FakeGateway : MqttConnector {
    var connects = 0
    var lastToken: String? = null
    var lastDevice: String? = null
    var lastHost: String? = null
    var unreachable = false
    /** Token prefix -> device IDs (normalised) its account may get keys for. More specific prefixes first. */
    var owned: Map<String, Set<String>> = linkedMapOf("ACCESS-B" to setOf("AABBCC001122"), "ACCESS" to setOf("540853BD1D41"))
    var answer: (sn: String, dev: String, token: String) -> String? = { sn, dev, token ->
        val mine = owned.entries.firstOrNull { token.startsWith(it.key) }?.value ?: emptySet()
        if (normalize(dev) in mine) reply(sn, 0, "key-for-${normalize(dev)}") else reply(sn, 3, null)
    }

    override fun connect(host: String, port: Int, clientId: String, username: String, password: String): MqttLink {
        if (unreachable) throw IOException("connect timed out")
        connects++
        lastHost = host
        assertEquals(HaierGatewayAuth.password(username.removePrefix("01")), password)
        return object : MqttLink {
            val inbox = mutableListOf<Pair<String, ByteArray>>()
            override fun subscribe(topic: String) = assertEquals("Client/$clientId/Business/Down", topic)
            override fun publish(topic: String, payload: String) {
                assertEquals("Client/$clientId/Business/Up", topic)
                val o = Json.parseToJsonElement(payload).jsonObject
                val token = o["tokens"].toString().trim('[', ']', '"')
                lastToken = token
                val inner = Json.parseToJsonElement(String(Base64.getDecoder().decode(o.str("data")))).jsonObject
                lastDevice = inner.str("dev")
                // an unrelated push first, then the answer
                inbox += topic to reply("999", 0, "not-for-us").toByteArray()
                answer(inner.str("sn"), inner.str("dev"), token)?.let { inbox += topic to it.toByteArray() }
            }
            override fun poll(timeoutMs: Int) = inbox.toList().also { inbox.clear() }
            override fun close() {}
        }
    }

    companion object {
        fun normalize(id: String) = id.filter { it.isLetterOrDigit() }.uppercase()
    }
}

private class MemorySessions : AccountSessionStore {
    val map = mutableMapOf<String, String>()
    override fun load(providerId: String) = map[providerId]
    override fun save(providerId: String, session: String?): Boolean { if (session == null) map.remove(providerId) else map[providerId] = session; return true }
}

class HaierCloudClientTest {

    @Test
    fun `login signs the request and returns both tokens`() {
        val svc = FakeHaierService()
        val client = HaierCloudClient(IDENTITY, "CLIENT", "92", svc, clock = { 1700000000000 })
        val tokens = client.login("me@example.com", "pw")
        assertEquals(HaierTokens("ACCESS1", "REFRESH1", "user-a"), tokens)
        val req = svc.requests.single()
        assertEquals("https://uhome-sea.haieriot.net/uplussea/accounts/v2/login", req.url)
        assertEquals(HaierSigning.sign(HaierCloudClient.LOGIN_PATH, req.body, IDENTITY.appId, IDENTITY.appKey, "1700000000000"), req.headers["sign"])
        assertEquals("92", req.headers["zoneInfo"])
        assertEquals("CLIENT", req.headers["clientId"])
        assertFalse("password never sent in plain text", req.body.contains("\"pw\""))
    }

    @Test
    fun `failures are classified so the user gets the right message`() {
        fun kind(reply: () -> HttpResponse): HaierCloudException.Kind {
            val svc = FakeHaierService().apply { loginReply = { reply() } }
            return try { HaierCloudClient(IDENTITY, "C", "92", svc).login("a", "b"); fail(); throw IllegalStateException() } catch (e: HaierCloudException) { e.kind }
        }
        assertEquals(HaierCloudException.Kind.REJECTED, kind { HttpResponse(200, "{\"retCode\":\"30032\",\"retInfo\":\"Account is not registered\"}") })
        assertEquals(HaierCloudException.Kind.REJECTED, kind { HttpResponse(401, "{\"retCode\":\"21016\"}") })
        assertEquals(HaierCloudException.Kind.UNREACHABLE, kind { HttpResponse(503, "") })
        assertEquals(HaierCloudException.Kind.UNREACHABLE, kind { HttpResponse(200, "<html>captive portal</html>") })
        assertEquals(HaierCloudException.Kind.UNREACHABLE, kind { throw IOException("no route") })
        assertEquals(HaierCloudException.Kind.MALFORMED, kind { HttpResponse(200, "{\"retCode\":\"00000\",\"data\":{}}") })
        // As observed live: a wrong app key is answered with HTTP 403 and no JSON.
        assertEquals(HaierCloudException.Kind.APP_REFUSED, kind { HttpResponse(403, "") })
        assertEquals(HaierCloudException.Kind.APP_REFUSED, kind { HttpResponse(401, "<html></html>") })
    }

    @Test
    fun `the service's retCode is kept for diagnosis`() {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"30032\",\"retInfo\":\"Account is not registered\"}") } }
        try { HaierCloudClient(IDENTITY, "C", "92", svc).login("a", "b"); fail() } catch (e: HaierCloudException) {
            assertEquals(HaierCloudClient.ACCOUNT_NOT_IN_REGION, e.code)
            assertTrue(e.message!!.contains("Account is not registered"))
        }
    }

    @Test
    fun `error messages never contain the reply body`() {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"1\",\"retInfo\":\"x\",\"data\":{\"refreshToken\":\"SECRET\"}}") } }
        try { HaierCloudClient(IDENTITY, "C", "92", svc).login("a", "b"); fail() } catch (e: HaierCloudException) { assertFalse(e.message!!.contains("SECRET")) }
    }

    @Test
    fun `device list is parsed from the account`() {
        val d = HaierCloudClient(IDENTITY, "C", "92", FakeHaierService()).devices("ACCESS1").single()
        assertEquals("54:08:53:bd:1d:41", d.deviceId)
        assertEquals("Bedroom AC", d.name)
        assertEquals("HSU-20HFTEX", d.model)
        assertTrue(d.online)
    }

    @Test
    fun `sign-up sends a code, verifies to a one-time token, and registers without the password in clear`() {
        val svc = FakeHaierService()
        val client = HaierCloudClient(IDENTITY, "CLIENT", "65", svc, clock = { 1700000000000 })
        assertTrue(client.checkAvailability("new@example.com"))
        client.sendVerificationCode("new@example.com")
        val token = client.verifyCode("new@example.com", "123456")
        assertEquals("TOKEN123", token)
        client.register("new@example.com", "S3cret pass!", token)

        assertEquals(
            listOf(HaierCloudClient.CHECK_PATH, HaierCloudClient.SEND_CODE_PATH, HaierCloudClient.VERIFY_CODE_PATH, HaierCloudClient.REGISTER_PATH),
            svc.paths()
        )
        val verify = svc.requests.first { it.url.endsWith(HaierCloudClient.VERIFY_CODE_PATH) }
        assertTrue(verify.body.contains("\"verificationCode\":\"123456\""))
        assertTrue("the zone is sent as the countryCode", verify.body.contains("\"countryCode\":\"65\""))
        val reg = svc.requests.first { it.url.endsWith(HaierCloudClient.REGISTER_PATH) }
        assertTrue("the one-time token is forwarded", reg.body.contains("\"code\":\"TOKEN123\""))
        assertTrue(reg.body.contains("\"sesame\":"))
        assertFalse("password never sent in clear", reg.body.contains("S3cret pass!"))
        // The signature covers path+body+appId+appKey+timestamp only, so the extra sign-up headers never change it.
        assertEquals(HaierSigning.sign(HaierCloudClient.REGISTER_PATH, reg.body, IDENTITY.appId, IDENTITY.appKey, "1700000000000"), reg.headers["sign"])
        assertEquals("1", reg.headers["identify"])
        assertEquals("65", reg.headers["zoneInfo"])
        // langCode is required by the sign-up endpoints (live retCode 10001 "langCode cannot be empty" without it).
        val send = svc.requests.first { it.url.endsWith(HaierCloudClient.SEND_CODE_PATH) }
        assertEquals("en", send.headers["langCode"])
        assertEquals("en", reg.headers["langCode"])
    }

    @Test
    fun `a taken address is reported, and a verify or register with no success is malformed`() {
        val taken = FakeHaierService().apply { checkReply = { HttpResponse(200, "{\"retCode\":\"00000\",\"data\":{\"available\":false}}") } }
        assertFalse(HaierCloudClient(IDENTITY, "C", "65", taken).checkAvailability("x@y.z"))

        val noToken = FakeHaierService().apply { verifyReply = { HttpResponse(200, "{\"retCode\":\"00000\",\"data\":{}}") } }
        try { HaierCloudClient(IDENTITY, "C", "65", noToken).verifyCode("x@y.z", "000000"); fail() }
        catch (e: HaierCloudException) { assertEquals(HaierCloudException.Kind.MALFORMED, e.kind) }

        val noSuccess = FakeHaierService().apply { registerReply = { HttpResponse(200, "{\"retCode\":\"00000\",\"data\":{\"success\":false}}") } }
        try { HaierCloudClient(IDENTITY, "C", "65", noSuccess).register("x@y.z", "pw", "TOKEN123"); fail() }
        catch (e: HaierCloudException) { assertEquals(HaierCloudException.Kind.MALFORMED, e.kind) }
    }

    @Test
    fun `a service refusal on the verify step is surfaced as a refusal`() {
        val svc = FakeHaierService().apply { verifyReply = { HttpResponse(200, "{\"retCode\":\"21016\",\"retInfo\":\"code error\"}") } }
        try { HaierCloudClient(IDENTITY, "C", "65", svc).verifyCode("x@y.z", "999999"); fail() }
        catch (e: HaierCloudException) { assertEquals(HaierCloudException.Kind.REJECTED, e.kind) }
    }

    @Test
    fun `the bind stub refuses without sending anything until the real call is captured`() {
        val svc = FakeHaierService()
        try { HaierCloudClient(IDENTITY, "C", "65", svc).bindDevice("token", "540853BD1D41"); fail() }
        catch (e: HaierCloudException) {
            assertEquals(HaierCloudException.Kind.REJECTED, e.kind)
            assertTrue(e.message!!.contains("not been captured"))
        }
        assertTrue("no request may be sent to an invented endpoint", svc.requests.isEmpty())
    }
}

class HaierAccountTest {
    private val ac = "540853BD1D41"
    private val office = "AABBCC001122"

    private fun account(svc: FakeHaierService = FakeHaierService(), gw: FakeGateway = FakeGateway(), store: MemorySessions = MemorySessions(), identity: HaierAppIdentity = IDENTITY, signup: Boolean = false) =
        HaierAccount(identity, store, svc, HaierKeyGateway(gw, timeoutMs = 300), Dispatchers.Unconfined, signupEnabled = signup)

    private fun ProvisionResult.error() = (this as ProvisionResult.Failure).error
    private fun ProvisionResult.key() = (this as ProvisionResult.Success).credential

    // ---- initial setup ----

    @Test
    fun `with the sign-up flag off, account creation, password reset and AC binding are unsupported with an explanation`() {
        val a = account()
        assertFalse(a.features.register)
        assertFalse(a.features.passwordReset)
        assertFalse(a.features.bindDevice)
        assertTrue(!a.noRegistrationHelp.isNullOrBlank())
    }

    @Test
    fun `with the sign-up flag off, creating an account is unsupported and nothing is sent`() = runTest {
        val svc = FakeHaierService()
        val a = account(svc) // signup flag defaults off
        assertTrue((a.sendVerificationCode("x@y.z", "65") as SendCodeResult.Failure).error is AcError.NotSupported)
        assertTrue((a.register("x@y.z", "123456", "pw-123", "65", false) as RegisterResult.Failure).error is AcError.NotSupported)
        assertTrue(svc.requests.isEmpty())
    }

    @Test
    fun `with the sign-up flag on, account creation is offered and password reset and binding stay off`() {
        val a = account(signup = true)
        assertTrue(a.features.register)
        assertFalse(a.features.passwordReset)
        assertFalse(a.features.bindDevice)
    }

    @Test
    fun `send code checks availability first and refuses a taken address without requesting a code`() = runTest {
        val taken = FakeHaierService().apply { checkReply = { HttpResponse(200, "{\"retCode\":\"00000\",\"data\":{\"available\":false}}") } }
        val r = account(taken, signup = true).sendVerificationCode("taken@example.com", "65")
        assertTrue(r is SendCodeResult.Failure)
        assertTrue(taken.paths().contains(HaierCloudClient.CHECK_PATH))
        assertFalse("no code is requested for a taken address", taken.paths().contains(HaierCloudClient.SEND_CODE_PATH))
    }

    @Test
    fun `send code rejects a malformed email without contacting Haier`() = runTest {
        val svc = FakeHaierService()
        listOf("not-an-email", "a@b", "two words@example.com").forEach { bad ->
            val error = (account(svc, signup = true).sendVerificationCode(bad, "65") as SendCodeResult.Failure).error
            assertTrue(error.userMessage, error.userMessage.contains("is not an email address"))
        }
        assertTrue(svc.requests.isEmpty())
    }

    @Test
    fun `send code requests a code when the address is free`() = runTest {
        val svc = FakeHaierService()
        assertTrue(account(svc, signup = true).sendVerificationCode("free@example.com", "65") is SendCodeResult.Success)
        assertEquals(listOf(HaierCloudClient.CHECK_PATH, HaierCloudClient.SEND_CODE_PATH), svc.paths())
    }

    @Test
    fun `creating an account verifies, registers, then signs in so the account is linked`() = runTest {
        val store = MemorySessions()
        val svc = FakeHaierService()
        val a = account(svc, store = store, signup = true)
        assertTrue(a.register("new@example.com", "123456", "S3cret pass!", "65", staySignedIn = false) is RegisterResult.Success)
        assertTrue(a.isSignedIn())
        assertEquals(
            listOf(HaierCloudClient.VERIFY_CODE_PATH, HaierCloudClient.REGISTER_PATH, HaierCloudClient.LOGIN_PATH),
            svc.paths()
        )
        assertFalse("the chosen password is never logged or stored without stay-signed-in", store.map[HaierAccount.PROVIDER_ID]!!.contains("S3cret pass!"))
    }

    @Test
    fun `a refused verification code fails account creation and links nothing`() = runTest {
        val store = MemorySessions()
        val svc = FakeHaierService().apply { verifyReply = { HttpResponse(200, "{\"retCode\":\"21016\",\"retInfo\":\"code error\"}") } }
        val a = account(svc, store = store, signup = true)
        val error = (a.register("new@example.com", "000000", "S3cret pass!", "65", false) as RegisterResult.Failure).error
        // The failed step and Haier's own reason are shown, never the generic sign-in refusal.
        assertFalse(error is AcError.AccountRejected)
        assertTrue(error.userMessage, error.userMessage.contains("did not accept the code") && error.userMessage.contains("code error") && error.userMessage.contains("21016"))
        assertFalse(a.isSignedIn())
        assertFalse(svc.paths().contains(HaierCloudClient.LOGIN_PATH))
        assertFalse("no account is linked", store.map.containsKey(HaierAccount.PROVIDER_ID))
    }

    @Test
    fun `a refused send-code quotes Haier's reason`() = runTest {
        val svc = FakeHaierService().apply { sendCodeReply = { HttpResponse(200, "{\"retCode\":\"10001\",\"retInfo\":\"langCode cannot be empty\"}") } }
        val error = (account(svc, signup = true).sendVerificationCode("new@example.com", "65") as SendCodeResult.Failure).error
        assertTrue(error.userMessage, error.userMessage.contains("did not send a verification code") && error.userMessage.contains("langCode cannot be empty") && error.userMessage.contains("10001"))
    }

    @Test
    fun `send too often (30035) says not to request another code`() = runTest {
        val svc = FakeHaierService().apply { sendCodeReply = { HttpResponse(200, "{\"retCode\":\"30035\",\"retInfo\":\"send too often\"}") } }
        val error = (account(svc, signup = true).sendVerificationCode("new@example.com", "65") as SendCodeResult.Failure).error
        assertTrue(error is AcError.TooManyCodes)
        assertTrue(error.nextStep.contains("newest code"))
    }

    @Test
    fun `a missing password at create-account is named, not sent or reported as a refusal`() = runTest {
        val svc = FakeHaierService()
        val error = (account(svc, signup = true).register("new@example.com", "123456", "", "65", false) as RegisterResult.Failure).error
        assertFalse(error is AcError.AccountRejected)
        assertTrue(error.userMessage.contains("password"))
        assertTrue("nothing is sent", svc.paths().isEmpty())
    }

    @Test
    fun `the signup terminal id survives the app process ending between send-code and create-account`() = runTest {
        val store = MemorySessions()
        val svc = FakeHaierService()
        account(svc, store = store, signup = true).sendVerificationCode("New@Example.com ", "65")
        // A new HaierAccount over the same store = RoomAC restarted after the phone ended its process.
        assertTrue(account(svc, store = store, signup = true).register("new@example.com", "123456", "S3cret pass!", "65", false) is RegisterResult.Success)
        val sendId = svc.requests.first { it.url.endsWith(HaierCloudClient.SEND_CODE_PATH) }.headers["clientId"]
        val verifyId = svc.requests.first { it.url.endsWith(HaierCloudClient.VERIFY_CODE_PATH) }.headers["clientId"]
        assertEquals(sendId, verifyId)
        assertFalse("the pending signup is cleared once the account exists", store.map.keys.any { it.endsWith(".signup") })
    }

    @Test
    fun `without a sign-in the key cannot be fetched and nothing is sent`() = runTest {
        val svc = FakeHaierService()
        assertTrue(account(svc).fetchCredential(ac).error() is AcError.AccountRequired)
        assertTrue(svc.requests.isEmpty())
    }

    @Test
    fun `wrong password or wrong country is a refused sign-in, and nothing is saved`() = runTest {
        val store = MemorySessions()
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"30032\"}") } }
        assertTrue((account(svc, store = store).signIn("a@b.c", "bad", "92", true) as SignInResult.Failure).error is AcError.AccountNotInRegion)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `key is fetched for the AC, spelled the way the account lists it, from the right data centre`() = runTest {
        val gw = FakeGateway()
        val a = account(gw = gw)
        a.signIn("a@x", "pw", "92", false)
        assertEquals("key-for-$ac", a.fetchCredential(ac).key())
        assertEquals("54:08:53:bd:1d:41", gw.lastDevice)
        assertEquals("gw-sgp.haieriot.net", gw.lastHost)
        assertEquals("u-user-a", a.accountFor(ac)!!.id)
    }

    @Test
    fun `an AC the account does not list is still asked for, so ID spelling or sharing cannot block it`() = runTest {
        val svc = FakeHaierService().apply { devicesReply = { FakeHaierService.list() } } // empty list
        val gw = FakeGateway()
        val a = account(svc, gw)
        a.signIn("a@x", "pw", "92", false)
        assertEquals("key-for-$ac", a.fetchCredential(ac).key())
        assertEquals(ac, gw.lastDevice)
    }

    @Test
    fun `an AC no linked account owns is reported as such, never given a key`() = runTest {
        val a = account()
        a.signIn("a@x", "pw", "92", false)
        assertTrue(a.fetchCredential("0011AABBCCDD").error() is AcError.DeviceNotInAccount)
        assertNull(a.accountFor("0011AABBCCDD"))
    }

    @Test
    fun `not-in-account is diagnosable from the log - device list, zone, verdict and gateway errNo, no secrets`() = runTest {
        val logs = mutableListOf<String>()
        val a = HaierAccount(IDENTITY, MemorySessions(), FakeHaierService(), HaierKeyGateway(FakeGateway(), timeoutMs = 300), Dispatchers.Unconfined, log = { logs += it })
        a.signIn("a@x", "pw", "92", false)
        assertTrue(a.fetchCredential("0011AABBCCDD").error() is AcError.DeviceNotInAccount)
        val text = logs.joinToString("\n")
        assertTrue(text, text.contains("zone 92") && text.contains("gw-sgp.haieriot.net"))
        assertTrue(text, text.contains("[$ac]") && text.contains("wanted 0011AABBCCDD -> NOT LISTED"))
        assertTrue(text, text.contains("errNo="))
        assertFalse("no token in the log", text.contains("ACCESS") || text.contains("REFRESH"))
    }

    @Test
    fun `a differently spelled device ID still matches the account's list and gets its key`() = runTest {
        val logs = mutableListOf<String>()
        val gw = FakeGateway()
        val a = HaierAccount(IDENTITY, MemorySessions(), FakeHaierService(), HaierKeyGateway(gw, timeoutMs = 300), Dispatchers.Unconfined, log = { logs += it })
        a.signIn("a@x", "pw", "92", false)
        val spelled = ac.lowercase().chunked(2).joinToString(":")
        assertEquals("key-for-$ac", a.fetchCredential(spelled).key())
        // The fake account lists the AC as "54:08:53:bd:1d:41": the gateway is asked with the account's own spelling.
        assertEquals("54:08:53:bd:1d:41", gw.lastDevice)
        assertTrue(logs.any { it.contains("wanted $ac -> LISTED") })
    }

    // ---- persistence and silent renewal ----

    @Test
    fun `without stay-signed-in only the refresh token is kept, never the password`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("someone@example.com", "hunter22", "92", staySignedIn = false)
        val saved = store.map[HaierAccount.PROVIDER_ID]!!
        assertTrue(saved.contains("REFRESH1"))
        assertFalse(saved.contains("hunter22"))
        val again = account(store = store)
        assertTrue("survives a restart", again.isSignedIn())
        assertEquals("so•••@example.com", again.accounts().single().label)
        assertFalse(again.accounts().single().staysSignedIn)
    }

    @Test
    fun `after a restart the access token is minted from the saved refresh token, no password used`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", false)
        val svc = FakeHaierService()
        val gw = FakeGateway()
        assertTrue(account(svc, gw, store).fetchCredential(ac) is ProvisionResult.Success)
        assertEquals(listOf(HaierCloudClient.REFRESH_PATH, HaierCloudClient.DEVICE_LIST_PATH), svc.paths())
        assertEquals("ACCESS2", gw.lastToken)
    }

    @Test
    fun `an expired access token is renewed silently and the call retried`() = runTest {
        val svc = FakeHaierService()
        val a = account(svc)
        a.signIn("a@x", "pw", "92", false)
        val okList = svc.devicesReply
        svc.devicesReply = { req -> if (req.headers["accessToken"] == "ACCESS1") HttpResponse(401, "{\"retCode\":\"21016\"}") else okList(req) }
        assertTrue(a.fetchCredential(ac) is ProvisionResult.Success)
        assertTrue(HaierCloudClient.REFRESH_PATH in svc.paths())
    }

    @Test
    fun `a rotated refresh token is saved`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", false)
        val svc = FakeHaierService().apply { refreshReply = { FakeHaierService.tokens("ACCESS2", "REFRESH-NEW") } }
        account(svc, store = store).fetchCredential(ac)
        assertTrue(store.map[HaierAccount.PROVIDER_ID]!!.contains("REFRESH-NEW"))
    }

    @Test
    fun `expired session with stay-signed-in signs in again by itself, no prompt`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", staySignedIn = true)
        val svc = FakeHaierService().apply { refreshReply = { HttpResponse(200, "{\"retCode\":\"30003\"}") } }
        val a = account(svc, store = store)
        assertEquals("key-for-$ac", a.fetchCredential(ac).key())
        assertEquals(listOf(HaierCloudClient.REFRESH_PATH, HaierCloudClient.LOGIN_PATH, HaierCloudClient.DEVICE_LIST_PATH), svc.paths())
        assertFalse(a.accounts().single().needsSignIn)
    }

    @Test
    fun `expired session without stay-signed-in asks once, keeping the account to pre-fill`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", staySignedIn = false)
        val svc = FakeHaierService().apply { refreshReply = { HttpResponse(200, "{\"retCode\":\"30003\"}") } }
        val a = account(svc, store = store)
        assertTrue(a.fetchCredential(ac).error() is AcError.AccountRejected)
        assertTrue(a.accounts().single().needsSignIn)
        assertEquals("a@x", a.loginHint(a.accounts().single().id))
        assertFalse(svc.paths().contains(HaierCloudClient.LOGIN_PATH))
        // Signing in again restores the same account, not a second one.
        a.signIn("a@x", "pw", "92", false)
        assertEquals(1, a.accounts().size)
        assertFalse(a.accounts().single().needsSignIn)
    }

    @Test
    fun `changed password stops using the saved one and asks, no retry loop`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "old-pw", "92", staySignedIn = true)
        val svc = FakeHaierService().apply {
            refreshReply = { HttpResponse(200, "{\"retCode\":\"30003\"}") }
            loginReply = { HttpResponse(200, "{\"retCode\":\"30032\"}") }
        }
        val a = account(svc, store = store)
        assertTrue(a.fetchCredential(ac).error() is AcError.AccountRejected)
        assertFalse("saved password dropped", store.map[HaierAccount.PROVIDER_ID]!!.contains("old-pw"))
        val before = svc.requests.size
        assertTrue(a.fetchCredential(ac).error() is AcError.AccountRejected)
        assertEquals("no further sign-in attempts until the user acts", before, svc.requests.size)
    }

    @Test
    fun `no internet is not mistaken for a bad account, and nothing is discarded`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", staySignedIn = true)
        val svc = FakeHaierService().apply { refreshReply = { throw IOException("no route") } }
        val a = account(svc, store = store)
        assertTrue(a.fetchCredential(ac).error() is AcError.CloudUnavailable)
        assertFalse(a.accounts().single().needsSignIn)
        assertTrue(store.map[HaierAccount.PROVIDER_ID]!!.contains("REFRESH1"))
        val gw = FakeGateway().apply { unreachable = true }
        assertTrue(account(gw = gw, store = store).fetchCredential(ac).error() is AcError.CloudUnavailable)
    }

    // ---- sign-in diagnosis (2026-11-14 probe: a wrong app key gets HTTP 403 with no JSON; a made-up account gets 30032) ----

    @Test
    fun `an account the service does not find in the chosen country is reported as such, not as a wrong password`() = runTest {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"30032\",\"retInfo\":\"Account is not registered\"}") } }
        val r = account(svc).signIn("a@b.c", "pw", "92", true) as SignInResult.Failure
        assertTrue(r.error is AcError.AccountNotInRegion)
    }

    @Test
    fun `the service refusing the app itself is not blamed on the account`() = runTest {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(403, "<html>Forbidden</html>") } }
        val r = account(svc).signIn("a@b.c", "pw", "92", true) as SignInResult.Failure
        assertTrue(r.error is AcError.ServiceRefusedApp)
    }

    @Test
    fun `an app refusal during renewal keeps the session and the saved password`() = runTest {
        val store = MemorySessions()
        account(store = store).signIn("a@x", "pw", "92", staySignedIn = true)
        val svc = FakeHaierService().apply {
            refreshReply = { HttpResponse(403, "") }
            loginReply = { HttpResponse(403, "") }
        }
        val a = account(svc, store = store)
        assertTrue(a.fetchCredential(ac).error() is AcError.ServiceRefusedApp)
        assertFalse(a.accounts().single().needsSignIn)
        assertTrue(store.map[HaierAccount.PROVIDER_ID]!!.contains("REFRESH1"))
        assertTrue("saved password kept", store.map[HaierAccount.PROVIDER_ID]!!.contains("pw"))
    }

    @Test
    fun `the logged sign-in failure carries the service code but never the password`() = runTest {
        val lines = mutableListOf<String>()
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"30032\",\"retInfo\":\"Account is not registered\"}") } }
        HaierAccount(IDENTITY, MemorySessions(), svc, HaierKeyGateway(FakeGateway(), timeoutMs = 300), Dispatchers.Unconfined, log = { lines += it })
            .signIn("a@b.c", "SuperSecret-123", "92", true)
        val failure = lines.single { "sign-in failed" in it }
        assertTrue(failure.contains("30032"))
        assertFalse(lines.any { "SuperSecret-123" in it })
    }

    @Test
    fun `a listed AC whose key the service will not issue is not reported as a sign-in problem`() = runTest {
        val gw = FakeGateway().apply { owned = emptyMap() }
        val a = account(gw = gw)
        a.signIn("a@x", "pw", "92", false)
        val error = a.fetchCredential(ac).error()
        assertTrue(error is AcError.KeyNotIssued)
        // The gateway's errNo and the failed step reach the user (and diagnostics).
        assertTrue(error.userMessage, error.userMessage.contains("Step: key fetch") && error.userMessage.contains("code 3"))
        assertTrue(a.isSignedIn())
    }

    // ---- every failure names its step and Haier's reason code (2026-11-23) ----

    @Test
    fun `a wrong-country sign-in names the service, the step and retCode 30032`() = runTest {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(200, "{\"retCode\":\"30032\",\"retInfo\":\"Account is not registered\"}") } }
        val e = (account(svc).signIn("a@b.c", "pw", "92", true) as SignInResult.Failure).error
        // Regression: the service name was missing from this message ("No  account with ...").
        assertEquals("No HaiSmart account with this email or phone number was found in the selected country. (Step: sign-in · code 30032)", e.userMessage)
        assertTrue(e.nextStep.contains("HaiSmart account was created"))
    }

    @Test
    fun `an app refusal message names the brand`() = runTest {
        val svc = FakeHaierService().apply { loginReply = { HttpResponse(403, "<html>Forbidden</html>") } }
        val e = (account(svc).signIn("a@b.c", "pw", "92", true) as SignInResult.Failure).error
        assertTrue(e.userMessage, e.userMessage.startsWith("Haier's online service"))
        assertTrue(e.userMessage.contains("Step: sign-in"))
    }

    @Test
    fun `an AC missing from the account is reported as a binding issue at the device-list step with the gateway errNo`() = runTest {
        val gw = FakeGateway().apply { owned = emptyMap() }
        val svc = FakeHaierService().apply { devicesReply = { FakeHaierService.list() } } // empty list
        val a = account(svc, gw)
        a.signIn("a@x", "pw", "92", false)
        val e = a.fetchCredential(ac).error()
        assertTrue(e is AcError.DeviceNotInAccount)
        assertTrue(e.userMessage, e.userMessage.contains("Step: device list") && e.userMessage.contains("code 3"))
        assertTrue(e.nextStep.contains("binding"))
    }

    @Test
    fun `an unreachable device list is reported at the device-list step, not as a sign-in failure`() = runTest {
        val svc = FakeHaierService()
        val a = account(svc)
        a.signIn("a@x", "pw", "92", false)
        svc.devicesReply = { HttpResponse(503, "") }
        val e = a.fetchCredential(ac).error()
        assertTrue(e is AcError.CloudUnavailable)
        assertTrue(e.userMessage, e.userMessage.contains("Step: device list"))
    }

    // ---- multiple ACs and accounts ----

    @Test
    fun `two accounts - each AC gets its own key from the account that owns it, and the link is remembered`() = runTest {
        val store = MemorySessions()
        val a = account(FakeHaierService(), FakeGateway(), store)
        a.signIn("a@x", "pw", "92", false)
        a.signIn("b@x", "pw", "92", false)
        assertEquals(2, a.accounts().size)
        assertEquals("key-for-$office", a.fetchCredential(office).key())
        assertEquals("key-for-$ac", a.fetchCredential(ac).key())
        assertEquals("u-user-b", a.accountFor(office)!!.id)
        assertEquals("u-user-a", a.accountFor(ac)!!.id)
        // After a restart the owning account is asked first: one key request, no detour through the other account.
        val gw2 = FakeGateway()
        account(FakeHaierService(), gw2, store).fetchCredential(office)
        assertEquals(1, gw2.connects)
    }

    @Test
    fun `signing out one account keeps the other`() = runTest {
        val store = MemorySessions()
        val a = account(store = store)
        a.signIn("a@x", "pw", "92", false)
        a.signIn("b@x", "pw", "92", false)
        a.fetchCredential(office)
        a.signOut("u-user-b")
        assertEquals(listOf("u-user-a"), a.accounts().map { it.id })
        assertNull(a.accountFor(office))
        assertFalse(store.map[HaierAccount.PROVIDER_ID]!!.contains("REFRESH-B"))
        a.signOut("u-user-a")
        assertTrue("last sign-out deletes everything", store.map.isEmpty())
    }

    @Test
    fun `a 2_2_0 single-account session is migrated and still works`() = runTest {
        val store = MemorySessions().apply {
            map[HaierAccount.PROVIDER_ID] = "{\"refresh\":\"REFRESH1\",\"terminal\":\"ABCDEF0123456789ABCDEF0123456789\",\"zone\":\"92\",\"label\":\"so•••@x\"}"
        }
        val a = account(store = store)
        assertTrue(a.isSignedIn())
        assertTrue(a.fetchCredential(ac) is ProvisionResult.Success)
    }

    @Test
    fun `a build without the app identity offers no sign-in and sends nothing`() = runTest {
        val svc = FakeHaierService()
        val a = account(svc, identity = HaierAppIdentity("", ""))
        assertFalse(a.available)
        assertTrue((a.signIn("a@b.c", "pw", "92", true) as SignInResult.Failure).error is AcError.AccountUnavailable)
        assertTrue(svc.requests.isEmpty())
    }
}

class MqttStreamLinkTest {

    private fun packet(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `remaining length encoding`() {
        assertEquals(listOf(0), MqttStreamLink.encodeLength(0).map { it.toInt() and 0xFF })
        assertEquals(listOf(127), MqttStreamLink.encodeLength(127).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x80, 0x01), MqttStreamLink.encodeLength(128).map { it.toInt() and 0xFF })
        assertEquals(listOf(0xFF, 0x7F), MqttStreamLink.encodeLength(16383).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x80, 0x80, 0x01), MqttStreamLink.encodeLength(16384).map { it.toInt() and 0xFF })
    }

    @Test
    fun `connect, subscribe with an early publish buffered, then poll`() {
        val topic = "Client/x/Business/Down".toByteArray()
        val payload = "hello".toByteArray()
        val publish = byteArrayOf(0x30, (2 + topic.size + payload.size).toByte(), 0, topic.size.toByte()) + topic + payload
        val server = packet(0x20, 2, 0, 0) + publish + packet(0x90, 3, 0, 1, 0)
        val out = ByteArrayOutputStream()
        val link = MqttStreamLink(ByteArrayInputStream(server), out, {}, {})
        link.connect("cid", "0112345678", "pw")
        val connect = out.toByteArray()
        assertEquals(0x10, connect[0].toInt())
        assertTrue(String(connect, Charsets.ISO_8859_1).contains("MQTT"))
        assertEquals(0xC2, connect[9].toInt() and 0xFF) // username + password + clean session
        link.subscribe("Client/x/Business/Down")
        val got = link.poll(10)
        assertEquals("Client/x/Business/Down", got.single().first)
        assertEquals("hello", String(got.single().second))
        try { link.poll(10); fail("closed stream must be reported") } catch (_: IOException) {}
    }

    @Test
    fun `a refused connection is an error, not a silent success`() {
        val link = MqttStreamLink(ByteArrayInputStream(packet(0x20, 2, 0, 5)), ByteArrayOutputStream(), {}, {})
        try { link.connect("c", "u", "p"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("5")) }
    }
}
