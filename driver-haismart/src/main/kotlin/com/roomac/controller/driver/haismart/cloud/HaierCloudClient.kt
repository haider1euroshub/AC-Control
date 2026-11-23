package com.roomac.controller.driver.haismart.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Haier SE-Asia account service (the cloud behind the HaiSmart app), used only to sign in once and
 * fetch each AC's local key. Protocol facts come from haismart-local (MIT, see THIRD_PARTY_NOTICES.md),
 * which verified them against the live service:
 *
 *   sign-in       POST https://uhome-sea.haieriot.net/uplussea/accounts/v2/login
 *   token refresh POST https://<uhome>/uplussea/accounts/v1/user/refreshToken
 *   device list   GET  https://<uhome>/uplussea/devices/v2/user/devices
 *
 * Every request carries the "device-center" header envelope, signed with
 * SHA-256(path + body-without-whitespace + appId + appKey + timestamp).
 */

/**
 * The HaiSmart app's identity that Haier's service requires every request to be signed with. It is the
 * same for every install (not a user secret), but it is still kept out of source control: the build
 * reads it from the untracked local.properties (docs/BUILD_AND_INSTALL.md). Blank = Haier sign-in is
 * unavailable in this build.
 */
data class HaierAppIdentity(val appId: String, val appKey: String, val appVersion: String = "5.5.0") {
    val isConfigured: Boolean get() = appId.isNotBlank() && appKey.isNotBlank()
    override fun toString() = "HaierAppIdentity(appId=${if (appId.isBlank()) "<unset>" else "<set>"})"
}

/** The service hosts for one Haier data centre. India accounts (zone 91) live in the "-sea" centre, all others in "-sgp". */
data class HaierDomains(val uhome: String, val login: String, val gateway: String) {
    companion object {
        fun forZone(zone: String): HaierDomains =
            if (zone.trim().trimStart('+') == "91") HaierDomains("uhome-sea.haieriot.net", "uhome-sea.haieriot.net", "gw-sea.haieriot.net")
            else HaierDomains("uhome-sgp.haieriot.net", "uhome-sea.haieriot.net", "gw-sgp.haieriot.net")
    }
}

class HaierCloudException(
    val kind: Kind, message: String,
    /** The service's retCode, when it sent one. */ val code: String = "",
    /** The service's own retInfo text, when it sent one (never a reply body). */ val info: String = ""
) : Exception(message) {
    enum class Kind {
        /** No answer, DNS failure, timeout, HTTP 5xx/408/429: says nothing about the account. */
        UNREACHABLE,
        /** The service answered and refused (wrong password, expired or foreign token). */
        REJECTED,
        /**
         * The service refused this app's identity itself (HTTP 401/403 without a JSON body): every
         * signed request is turned away, whatever the account. Says nothing about the account, so it
         * must never cost the user a saved session or password. Seen with a wrong app key (probe of
         * 2026-11-14); would also be the symptom of Haier blocking the identity.
         */
        APP_REFUSED,
        /** An answer that could not be understood. */
        MALFORMED
    }
}

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String)
data class HttpResponse(val status: Int, val body: String)

/** Blocking HTTP, injectable so the client can be tested without a network. Throws IOException on transport failure. */
fun interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}

/** Default transport: HttpsURLConnection with the platform's certificate checks (works on Android and the JVM). */
object UrlConnectionTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(status, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Tokens issued at sign-in. [accessToken] lasts about a day. [refreshToken] is the durable credential:
 * reusable, but not eternal (it can expire, and a sign-in elsewhere can supersede it). [userId] is
 * the account's own id, when the service reports it.
 */
data class HaierTokens(val accessToken: String, val refreshToken: String, val userId: String = "")

data class HaierCloudDevice(
    val deviceId: String,
    val name: String,
    val model: String,
    val uplusId: String,
    val online: Boolean
)

object HaierSigning {
    private fun stripWhitespace(s: String) = s.replace(" ", "").replace("\t", "").replace("\r", "").replace("\n", "")

    fun payload(path: String, body: String, appId: String, appKey: String, timestamp: String): String =
        path + stripWhitespace(body) + appId + appKey.trim().replace("\"", "") + timestamp

    fun sign(path: String, body: String, appId: String, appKey: String, timestamp: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload(path, body, appId, appKey, timestamp).toByteArray(Charsets.UTF_8)).toHex()
}

/**
 * The sign-in password wrapping used by the HaiSmart app: a fresh 16-digit key `k` is both the AES-128
 * key and IV for AES-CBC/PKCS7 over the password, and `k` itself is RSA-PKCS1 encrypted with the
 * service's public key ("sesame"). The password never leaves the phone in plain text, even inside TLS.
 */
object HaierPasswordCipher {
    /** The service's published RSA-1024 public key (SubjectPublicKeyInfo). Public by design. */
    const val PUBLIC_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCpBUQQP/sCtV6UK4mDD6Qr3OKMuWhVuc7OXj+YHNyTGip0jXR1" +
        "PSMPshC6fql/GPMUMhZ2Ler/Yf5UihrgNkQsPK1ePu2lBntZp9B5tJza58fCKDtLeSvU6Z+VgFQsjJ9dcv0sw7P8" +
        "EH5HhvXTCcckpqCGMmIBiur59U06GJIQMQIDAQAB"

    fun randomKey(random: SecureRandom): String = (1..16).joinToString("") { random.nextInt(10).toString() }

    /** Returns (password field, sesame field). */
    fun encrypt(password: String, key: String, publicKeyB64: String = PUBLIC_KEY_B64): Pair<String, String> {
        require(key.length == 16) { "key must be 16 characters" }
        val kb = key.toByteArray(Charsets.US_ASCII)
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kb, "AES"), IvParameterSpec(kb))
        val pw = Base64.getEncoder().encodeToString(aes.doFinal(password.toByteArray(Charsets.UTF_8)))
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub)
        val sesame = Base64.getEncoder().encodeToString(rsa.doFinal(kb))
        return pw to sesame
    }
}

/**
 * Signed client for one account. [clientId] is the per-install terminal id chosen at sign-in; the
 * tokens the service issues are bound to it, so it is stored with the session and reused.
 */
class HaierCloudClient(
    private val identity: HaierAppIdentity,
    val clientId: String,
    val zone: String,
    private val transport: HttpTransport = UrlConnectionTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    val domains: HaierDomains = HaierDomains.forZone(zone)

    fun login(username: String, password: String): HaierTokens {
        val (pw, sesame) = HaierPasswordCipher.encrypt(password, HaierPasswordCipher.randomKey(random))
        val body = buildJsonObject { put("username", username); put("password", pw); put("sesame", sesame) }.toString()
        val reply = call("POST", domains.login, LOGIN_PATH, body, accessToken = "")
        val tokens = tokenInfo(reply)
        val refresh = tokens.str("refreshToken")
        val access = tokens.str("uhomeAccessToken").ifEmpty { tokens.str("accountToken") }.ifEmpty { tokens.str("accessToken") }
        if (refresh.isEmpty() || access.isEmpty()) throw HaierCloudException(HaierCloudException.Kind.MALFORMED, "sign-in returned no tokens")
        return HaierTokens(access, refresh, tokens.str("uhomeUserId").ifEmpty { tokens.str("uHomeUserId") })
    }

    /**
     * Mints a fresh access token from the refresh token. The service has so far returned the same
     * refresh token, but if it ever issues a new one, it is returned so the caller can keep it.
     */
    fun refresh(refreshToken: String): HaierTokens {
        val body = buildJsonObject { put("refreshToken", refreshToken) }.toString()
        val reply = call("POST", domains.uhome, REFRESH_PATH, body, accessToken = "")
        val tokens = tokenInfo(reply)
        val access = tokens.str("accountToken").ifEmpty { tokens.str("uhomeAccessToken") }.ifEmpty { tokens.str("accessToken") }
            .ifEmpty { throw HaierCloudException(HaierCloudException.Kind.MALFORMED, "token refresh returned no access token") }
        return HaierTokens(access, tokens.str("refreshToken").ifEmpty { refreshToken })
    }

    fun devices(accessToken: String): List<HaierCloudDevice> {
        val reply = call("GET", domains.uhome, DEVICE_LIST_PATH, "", accessToken)
        val infos = (reply["data"] as? JsonObject)?.get("deviceInfos") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return infos.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val base = o["baseInfo"] as? JsonObject ?: return@mapNotNull null
            val ext = o["extendedInfo"] as? JsonObject
            val id = base.str("deviceId").ifEmpty { return@mapNotNull null }
            HaierCloudDevice(
                deviceId = id,
                name = base.str("deviceName"),
                model = ext?.str("model") ?: "",
                uplusId = base.str("wifiType"),
                online = (base["isOnline"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false
            )
        }
    }

    // ---- Account creation (sign-up). Endpoints captured 2026-11-18, docs/HAIER_SIGNUP_BIND_ENDPOINTS.md. ----
    // The captured signup envelope adds these to the login header set. `langCode` is REQUIRED by the
    // email-verifications endpoint (it answers retCode 10001 "langCode cannot be empty" without it);
    // the camel and lower-case spellings are both sent, as the app does. accountToken is empty (pre-auth).
    private val signupHeaders = mapOf("identify" to "1", "accountToken" to "", "langCode" to "en", "langcode" to "en")

    /** True if [login] (an email or phone number) is free to register in this zone. */
    fun checkAvailability(login: String): Boolean {
        val body = buildJsonObject { put("phone", login); put("countryCode", "") }.toString()
        val reply = call("POST", domains.uhome, CHECK_PATH, body, accessToken = "", signupHeaders)
        return ((reply["data"] as? JsonObject)?.get("available") as? JsonPrimitive)?.booleanOrNull ?: false
    }

    /** Step 1: asks the service to email a verification code for a new-account registration. */
    fun sendVerificationCode(email: String, scenario: String = "registration") {
        val body = buildJsonObject { put("email", email); put("scenario", scenario) }.toString()
        call("POST", domains.uhome, SEND_CODE_PATH, body, accessToken = "", signupHeaders)
    }

    /** Step 2: submits the emailed [code]; returns the one-time token that authorises [register]. */
    fun verifyCode(email: String, code: String, scenario: String = "registration"): String {
        val body = buildJsonObject {
            put("email", email); put("verificationCode", code); put("countryCode", zone); put("scenario", scenario)
        }.toString()
        val reply = call("POST", domains.uhome, VERIFY_CODE_PATH, body, accessToken = "", signupHeaders)
        return (reply["data"] as? JsonObject)?.str("code").orEmpty()
            .ifEmpty { throw HaierCloudException(HaierCloudException.Kind.MALFORMED, "$VERIFY_CODE_PATH: no code in reply") }
    }

    /**
     * Step 3: creates the account. [verifyToken] comes from [verifyCode]; the password is wrapped by
     * [HaierPasswordCipher] exactly as at sign-in (same AES+RSA scheme, verified byte-for-byte).
     */
    fun register(email: String, password: String, verifyToken: String) {
        val (pw, sesame) = HaierPasswordCipher.encrypt(password, HaierPasswordCipher.randomKey(random))
        val body = buildJsonObject {
            put("email", email); put("password", pw); put("code", verifyToken); put("sesame", sesame)
        }.toString()
        val reply = call("POST", domains.uhome, REGISTER_PATH, body, accessToken = "", signupHeaders)
        val success = ((reply["data"] as? JsonObject)?.get("success") as? JsonPrimitive)?.booleanOrNull ?: false
        if (!success) throw HaierCloudException(HaierCloudException.Kind.MALFORMED, "$REGISTER_PATH: account not created")
    }

    /**
     * TODO(bind): adding (binding) a new AC to the account. The HaiSmart app does this after local
     * SoftAP provisioning, via a cloud call we have NOT captured: an emulator cannot reach a physical
     * AC, and no rooted phone was available (docs/HAIER_SIGNUP_BIND_ENDPOINTS.md, "AC binding").
     * [UdiscoveryScanner] already finds the AC on the LAN and reports its cloudState; the only missing
     * piece is this cloud bind request. No endpoint is invented here — until it is captured this throws,
     * and AccountFeatures.bindDevice stays false. When captured, add BIND_PATH and the request body here.
     *
     * STUB — fill in only from a real capture (docs/HAIER_SIGNUP_BIND_ENDPOINTS.md, "AC binding", step 6):
     *  1. companion object: `const val BIND_PATH = "<path from the capture>"` (next to DEVICE_LIST_PATH).
     *  2. Replace the `throw` below with
     *       `val body = buildJsonObject { <fields exactly as captured> }.toString()`
     *       `val reply = call("<METHOD>", domains.uhome /* or the captured host */, BIND_PATH, body, accessToken)`
     *     reusing call(): it signs the request and maps retCodes exactly like the other calls.
     *  3. Return whatever the reply proves (e.g. Unit, or the device id it confirms), and add a
     *     HaierCloudTest case built from the captured request/response.
     *  4. HaierAccount: set AccountFeatures(bindDevice = true) and call this from the pairing flow.
     */
    fun bindDevice(accessToken: String, deviceId: String): Nothing = throw HaierCloudException(
        HaierCloudException.Kind.REJECTED,
        // accessToken + deviceId are the two inputs every known Haier bind call needs; kept so the signature is ready.
        "device binding is not implemented (token:${accessToken.isNotEmpty()} device:${deviceId.isNotEmpty()}): the cloud bind endpoint has not been captured"
    )

    private fun headers(path: String, body: String, timestamp: String, accessToken: String, extra: Map<String, String> = emptyMap()) = mapOf(
        "appId" to identity.appId,
        "appVersion" to identity.appVersion,
        "apiVersion" to "v1",
        "clientId" to clientId,
        "sequenceId" to timestamp + "000010",
        "accessToken" to accessToken,
        "sign" to HaierSigning.sign(path, body, identity.appId, identity.appKey, timestamp),
        "timestamp" to timestamp,
        "language" to "en-us",
        "timezone" to "8", // the value the reference verified against the live service; not the user's zone
        "zoneInfo" to zone,
        "Content-Type" to "application/json;charset=UTF-8"
    ) + extra // sign covers only path+body+appId+appKey+timestamp, so extra headers never affect it

    private fun call(method: String, host: String, path: String, body: String, accessToken: String, extra: Map<String, String> = emptyMap()): JsonObject {
        val timestamp = clock().toString()
        val response = try {
            transport.send(HttpRequest(method, "https://$host$path", headers(path, body, timestamp, accessToken, extra), body))
        } catch (e: IOException) {
            throw HaierCloudException(HaierCloudException.Kind.UNREACHABLE, "$host: ${e.javaClass.simpleName}")
        }
        if (response.status >= 500 || response.status == 408 || response.status == 429) {
            throw HaierCloudException(HaierCloudException.Kind.UNREACHABLE, "$path: HTTP ${response.status}")
        }
        val json = try {
            Json.parseToJsonElement(response.body).jsonObject
        } catch (e: Exception) {
            // A captive portal answering with HTML lands here: treat it as "no internet", not as a refusal.
            val kind = when (response.status) {
                200 -> HaierCloudException.Kind.UNREACHABLE
                401, 403 -> HaierCloudException.Kind.APP_REFUSED
                else -> HaierCloudException.Kind.REJECTED
            }
            throw HaierCloudException(kind, "$path: HTTP ${response.status}, not JSON")
        }
        val code = json.str("retCode")
        if (response.status != 200 || (code.isNotEmpty() && code != SUCCESS)) {
            // Only the code and the service's own message: never echo the reply, which may carry tokens.
            throw HaierCloudException(HaierCloudException.Kind.REJECTED, "$path: HTTP ${response.status} retCode ${code.ifEmpty { "-" }} ${json.str("retInfo")}".trim(), code, json.str("retInfo"))
        }
        return json
    }

    private fun tokenInfo(reply: JsonObject): JsonObject {
        val data = reply["data"] as? JsonObject ?: return reply
        return data["tokenInfo"] as? JsonObject ?: data
    }

    companion object {
        const val LOGIN_PATH = "/uplussea/accounts/v2/login"
        const val REFRESH_PATH = "/uplussea/accounts/v1/user/refreshToken"
        const val DEVICE_LIST_PATH = "/uplussea/devices/v2/user/devices"
        // Account creation (sign-up), captured 2026-11-18. See docs/HAIER_SIGNUP_BIND_ENDPOINTS.md.
        const val CHECK_PATH = "/uplussea/accounts/v1/user/register/check"
        const val SEND_CODE_PATH = "/uplussea/accounts/v2/email-verifications"
        const val VERIFY_CODE_PATH = "/uplussea/accounts/v1/mail/code/verify"
        const val REGISTER_PATH = "/uplussea/accounts/v2/mail/register"
        const val SUCCESS = "00000"
        /** Login: no account with this email/phone in the requested region (also a right account in the wrong region). */
        const val ACCOUNT_NOT_IN_REGION = "30032"
        /** Sign-up: too many verification codes requested for this address ("send too often"). */
        const val CODE_SENT_TOO_OFTEN = "30035"

        /** A fresh per-install terminal id (32 upper-case hex characters). */
        fun newClientId(random: SecureRandom = SecureRandom()): String =
            ByteArray(16).also(random::nextBytes).toHex().uppercase()
    }
}

internal fun JsonObject.str(key: String): String = when (val v: JsonElement? = this[key]) {
    is JsonPrimitive -> if (v.isString || v.content != "null") v.content else ""
    else -> ""
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
