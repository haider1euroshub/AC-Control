package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.AccountFeatures
import com.roomac.controller.protocol.AccountRegion
import com.roomac.controller.protocol.AccountSessionStore
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.CloudDeviceInfo
import com.roomac.controller.protocol.LinkedAccount
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.SignInResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * The NetHome Plus account service (mapp.appsmb.com), used only to fetch the token and key a V3 Midea
 * module requires. Protocol facts from msmart-ng cloud.py (revision dd09947, MIT). This is the
 * NetHome Plus app's own private API, not a published Midea interface: it is a compatibility risk,
 * like the HaiSmart route. Only the user's own account is used; msmart's built-in shared accounts are
 * deliberately not carried over (requirement.md §3.3).
 */

/** The NetHome Plus app identity every request is signed with. Read from local.properties; blank = unavailable. */
data class MideaAppIdentity(val appId: String = "1017", val appKey: String) {
    val isConfigured: Boolean get() = appKey.isNotBlank()
    override fun toString() = "MideaAppIdentity(appKey=${if (appKey.isBlank()) "<unset>" else "<set>"})"
}

class MideaCloudException(val kind: Kind, message: String, val code: Int? = null) : Exception(message) {
    enum class Kind { UNREACHABLE, REJECTED, MALFORMED }
}

fun interface MideaHttp {
    /** POSTs a form body; returns (status, body). Throws IOException on transport failure. */
    fun postForm(url: String, form: String): Pair<Int, String>
}

object MideaUrlConnectionHttp : MideaHttp {
    override fun postForm(url: String, form: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return status to text
        } finally {
            c.disconnect()
        }
    }
}

/** Signed NetHome Plus requests (msmart `NetHomePlusCloud` and `BaseCloud`). */
class NetHomePlusClient(
    private val identity: MideaAppIdentity,
    private val http: MideaHttp = MideaUrlConnectionHttp,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
    random: SecureRandom = SecureRandom(),
    private val baseUrl: String = "https://mapp.appsmb.com"
) {
    private val terminalId = ByteArray(8).also(random::nextBytes).hex()
    var sessionId: String = ""

    fun login(account: String, password: String) {
        val loginId = call("/v1/user/login/id/get", mapOf("loginAccount" to account)).str("loginId")
            .ifEmpty { throw MideaCloudException(MideaCloudException.Kind.MALFORMED, "no loginId") }
        val result = call("/v1/user/login", mapOf("loginAccount" to account, "password" to passwordHash(loginId, password, identity.appKey)))
        sessionId = result.str("sessionId").ifEmpty { throw MideaCloudException(MideaCloudException.Kind.MALFORMED, "no sessionId") }
    }

    /** The token and key for [udpidHex], or null when the account has none for it. */
    fun token(udpidHex: String, applianceId: Long): Pair<String, String>? {
        val result = call("/v1/iot/secure/getToken", mapOf("udpid" to udpidHex, "applianceCodes" to applianceId.toString()))
        val list = result["tokenlist"] as? JsonArray ?: return null
        return list.mapNotNull { it as? JsonObject }.firstOrNull { it.str("udpId").equals(udpidHex, ignoreCase = true) }
            ?.let { it.str("token") to it.str("key") }
            ?.takeIf { (t, k) -> t.isNotEmpty() && k.isNotEmpty() }
    }

    private fun call(path: String, data: Map<String, String>): JsonObject {
        val body = sortedMapOf(
            "appId" to identity.appId, "src" to identity.appId, "format" to "2", "clientType" to "1",
            "language" to "en_US", "deviceId" to terminalId,
            "stamp" to clock().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
            "sessionId" to sessionId
        )
        body.putAll(data)
        body["sign"] = sign(path, body, identity.appKey)
        val form = body.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val (status, text) = try {
            http.postForm(baseUrl + path, form)
        } catch (e: IOException) {
            throw MideaCloudException(MideaCloudException.Kind.UNREACHABLE, "$path: ${e.javaClass.simpleName}")
        }
        if (status >= 500 || status == 408 || status == 429) throw MideaCloudException(MideaCloudException.Kind.UNREACHABLE, "$path: HTTP $status")
        val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
            throw MideaCloudException(if (status == 200) MideaCloudException.Kind.UNREACHABLE else MideaCloudException.Kind.REJECTED, "$path: HTTP $status, not JSON")
        }
        val code = json.str("errorCode").toIntOrNull()
        if (status != 200 || code == null || code != 0) {
            // Only the code and the service's message, never the reply (it may carry a session).
            throw MideaCloudException(MideaCloudException.Kind.REJECTED, "$path: HTTP $status errorCode ${code ?: "-"} ${json.str("msg")}".trim(), code)
        }
        return json["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        /** sha256(path + "k=v&…" sorted by key + appKey), as the NetHome Plus app signs requests. */
        fun sign(path: String, body: Map<String, String>, appKey: String): String =
            MideaSecurity.sha256((path + body.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=$v" } + appKey).toByteArray(Charsets.US_ASCII)).hex()

        /** sha256(loginId + sha256hex(password) + appKey), hex. The password itself is never sent. */
        fun passwordHash(loginId: String, password: String, appKey: String): String =
            MideaSecurity.sha256((loginId + MideaSecurity.sha256(password.toByteArray(Charsets.UTF_8)).hex() + appKey).toByteArray(Charsets.UTF_8)).hex()
    }
}

/**
 * The user's own NetHome Plus account(s), linked once inside RoomAC, from which V3 Midea modules get
 * their token and key. Session ids live in memory only. With "stay signed in" the password is kept
 * (Keystore-encrypted by the app's [AccountSessionStore]) so the app can sign in again by itself when
 * a key must be fetched later; otherwise the user is asked then.
 */
class MideaAccount(
    private val identity: MideaAppIdentity,
    private val sessions: AccountSessionStore,
    private val http: MideaHttp = MideaUrlConnectionHttp,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {}
) : CloudAccountProvider {

    override val id = PROVIDER_ID
    override val brandName = "Midea"
    override val serviceName = "NetHome Plus"
    override val serviceAppPackage = "com.midea.aircondition" // as listed by msmart-ng
    override val regions = listOf(AccountRegion("global", "Any country"))
    override val defaultRegion = "global"
    override val available: Boolean get() = identity.isConfigured
    override val features = AccountFeatures()
    override val noRegistrationHelp =
        "RoomAC cannot create NetHome Plus accounts. Use the account your AC was set up with in the NetHome Plus app. " +
            "Only newer Midea modules need an account at all; older ones connect without one."

    private data class Account(val id: String, val login: String, val password: String, val needsSignIn: Boolean)

    private val lock = Mutex()
    private var accounts: List<Account> = load()
    private val clients = HashMap<String, NetHomePlusClient>()

    override fun accounts() = accounts.map { LinkedAccount(it.id, maskLogin(it.login), it.needsSignIn, it.password.isNotEmpty()) }
    override fun accountFor(deviceId: String): LinkedAccount? = null
    override fun loginHint(accountId: String): String? = accounts.firstOrNull { it.id == accountId }?.login

    override suspend fun signIn(login: String, password: String, region: String, staySignedIn: Boolean): SignInResult = lock.withLock {
        if (!available) return SignInResult.Failure(AcError.AccountUnavailable(brandName))
        val user = login.trim()
        if (user.isEmpty() || password.isEmpty()) return SignInResult.Failure(AcError.AccountRejected(serviceName))
        val client = NetHomePlusClient(identity, http)
        try {
            withContext(io) { client.login(user, password) }
        } catch (e: MideaCloudException) {
            log("midea account: sign-in failed (${e.kind}: ${e.message})")
            return SignInResult.Failure(e.toAcError())
        }
        val id = "m-" + MideaSecurity.sha256(user.lowercase().toByteArray()).hex().take(16)
        val account = Account(id, user, if (staySignedIn) password else "", needsSignIn = false)
        val previous = accounts
        accounts = accounts.filterNot { it.id == id } + account
        if (!persist()) {
            accounts = previous
            return SignInResult.Failure(AcError.General("The phone's secure storage refused to save the sign-in."))
        }
        clients[id] = client
        log("midea account: signed in (${accounts.size} linked)")
        SignInResult.Success
    }

    override fun signOut(accountId: String) {
        accounts = accounts.filterNot { it.id == accountId }
        clients.remove(accountId)
        persist()
    }

    override suspend fun devices(): List<CloudDeviceInfo> = emptyList()

    override suspend fun fetchCredential(deviceId: String): ProvisionResult = lock.withLock {
        if (!available) return ProvisionResult.Failure(AcError.AccountUnavailable(brandName))
        if (accounts.isEmpty()) return ProvisionResult.Failure(AcError.AccountRequired(brandName, serviceName))
        val appliance = deviceId.toLongOrNull() ?: return ProvisionResult.Failure(AcError.ProtocolMismatch)
        var unreachable = false
        var refused = false
        for (account in accounts.toList()) {
            try {
                val client = clientFor(account) ?: run { refused = true; null } ?: continue
                val candidates = withContext(io) {
                    // The cloud files the token under the udpid of one byte order; both are asked, as msmart does.
                    listOf(false, true).mapNotNull { bigEndian -> client.token(udpidHex(appliance, bigEndian), appliance) }.distinct()
                }
                if (candidates.isNotEmpty()) {
                    log("midea account: token fetched (${candidates.size} candidate(s))")
                    return ProvisionResult.Success(MideaCredential.V3(candidates.map { (t, k) -> t.unhex() to k.unhex() }).encode())
                }
            } catch (e: MideaCloudException) {
                log("midea account: token fetch failed (${e.kind}: ${e.message})")
                if (e.kind == MideaCloudException.Kind.REJECTED) { clients.remove(account.id); refused = true } else unreachable = true
            }
        }
        ProvisionResult.Failure(
            when {
                unreachable -> AcError.CloudUnavailable(serviceName)
                refused -> AcError.AccountRejected(serviceName)
                else -> AcError.DeviceNotInAccount(serviceName)
            }
        )
    }

    /**
     * A signed-in client: the in-memory session, or a fresh sign-in with the saved password. Null when
     * the user must sign in. A network failure is thrown, so it is never mistaken for a refusal.
     */
    private suspend fun clientFor(account: Account): NetHomePlusClient? {
        clients[account.id]?.let { return it }
        if (account.password.isEmpty()) { mark(account, needsSignIn = true); return null }
        val client = NetHomePlusClient(identity, http)
        return try {
            withContext(io) { client.login(account.login, account.password) }
            clients[account.id] = client
            client
        } catch (e: MideaCloudException) {
            log("midea account: silent sign-in failed (${e.kind})")
            if (e.kind != MideaCloudException.Kind.REJECTED) throw e
            // A refused saved password is deleted at once and never retried.
            mark(account.copy(password = ""), needsSignIn = true)
            null
        }
    }

    private fun mark(account: Account, needsSignIn: Boolean) {
        accounts = accounts.map { if (it.id == account.id) account.copy(needsSignIn = needsSignIn) else it }
        persist()
    }

    private fun MideaCloudException.toAcError(): AcError = when (kind) {
        MideaCloudException.Kind.UNREACHABLE -> AcError.CloudUnavailable(serviceName)
        MideaCloudException.Kind.REJECTED -> AcError.AccountRejected(serviceName)
        MideaCloudException.Kind.MALFORMED -> AcError.General("The NetHome Plus service sent an answer the app could not read. Try again later.")
    }

    private fun persist(): Boolean = sessions.save(id, if (accounts.isEmpty()) null else buildJsonObject {
        put("v", 1)
        putJsonArray("accounts") {
            accounts.forEach { a -> add(buildJsonObject { put("id", a.id); put("login", a.login); put("password", a.password); put("needsSignIn", a.needsSignIn) }) }
        }
    }.toString())

    private fun load(): List<Account> = try {
        val root = sessions.load(PROVIDER_ID)?.let { Json.parseToJsonElement(it).jsonObject }
        (root?.get("accounts") as? JsonArray ?: JsonArray(emptyList())).map { el ->
            val o = el.jsonObject
            Account(o.str("id"), o.str("login"), o.str("password"), o["needsSignIn"]?.jsonPrimitive?.boolean ?: false)
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val PROVIDER_ID = "midea-nethome"

        fun udpidHex(applianceId: Long, bigEndian: Boolean): String {
            val bytes = ByteArray(6) { i -> (applianceId ushr (8 * i) and 0xFF).toByte() }
            return MideaSecurity.udpid(if (bigEndian) bytes.reversedArray() else bytes).hex()
        }

        fun maskLogin(login: String): String {
            val at = login.indexOf('@')
            return if (at > 2) login.take(2) + "•••" + login.substring(at) else "•••" + login.takeLast(4)
        }
    }
}

internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.let { if (it.isString || it.content != "null") it.content else "" } ?: ""
