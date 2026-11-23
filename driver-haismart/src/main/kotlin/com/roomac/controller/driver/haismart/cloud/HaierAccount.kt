package com.roomac.controller.driver.haismart.cloud

import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.AccountFeatures
import com.roomac.controller.protocol.AccountRegion
import com.roomac.controller.protocol.AccountSessionStore
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.CloudDeviceInfo
import com.roomac.controller.protocol.LinkedAccount
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.RegisterResult
import com.roomac.controller.protocol.SendCodeResult
import com.roomac.controller.protocol.SignInResult
import com.roomac.controller.protocol.normalizeDeviceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The owner's HaiSmart accounts (one or more). What is kept per account, all inside one blob that
 * [sessions] encrypts with the Android Keystore: the refresh token, the terminal id it is bound to,
 * the region, the email/phone, and the password only if the user chose "stay signed in".
 *
 * Renewal, never asking the user while any of these works:
 *  1. the access token held in memory (about a day);
 *  2. a new access token minted from the saved refresh token (durable, but it can expire or be
 *     superseded by a sign-in elsewhere);
 *  3. a fresh sign-in with the saved password, if the user allowed it.
 * Only when all of these are refused is the account marked "needs sign-in". A network failure never
 * counts as a refusal and never discards anything.
 *
 * A key is requested for the device ID found on the LAN. The service itself only issues keys for
 * devices the account may use, and the coordinator proves every key against the AC before saving it,
 * so a key can never be applied to another AC.
 */
class HaierAccount(
    private val identity: HaierAppIdentity,
    private val sessions: AccountSessionStore,
    private val transport: HttpTransport = UrlConnectionTransport,
    private val gateway: HaierKeyGateway = HaierKeyGateway(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {},
    /** In-app account creation. Behind a build flag (BuildConfig.HAIER_IN_APP_SIGNUP); off keeps the "create it in HaiSmart" help. */
    private val signupEnabled: Boolean = false
) : CloudAccountProvider {

    override val id = PROVIDER_ID
    override val brandName = "Haier"
    override val serviceName = "HaiSmart"
    override val serviceAppPackage = HaierGatewayAuth.PACKAGE
    override val regions = REGIONS
    override val defaultRegion = "92"
    override val available: Boolean get() = identity.isConfigured

    // Account creation IS now implemented: its five-call flow was captured against the live service on
    // 2026-11-18 (docs/HAIER_SIGNUP_BIND_ENDPOINTS.md) and reuses the verified sign-in crypto/signing. It
    // is behind a build flag (keep the "create it in HaiSmart" fallback visible until it is proven in the
    // field). Password reset and adding (binding) a new AC still have no captured interface, so both stay
    // false: binding needs the cloud bind call captured on a real phone (see the doc's "AC binding" TODO).
    override val features = AccountFeatures(register = signupEnabled, passwordReset = false, bindDevice = false)
    override val noRegistrationHelp =
        "Haier only lets you create a HaiSmart account or reset its password in the HaiSmart app. It offers no website or other way RoomAC could use. " +
            "If your AC is already set up with HaiSmart, you already have an account: sign in here with the same email or phone number. " +
            "Accounts made on the haier.com website belong to a separate system and are not known to work here."

    private data class Account(
        val id: String,
        val login: String,
        val zone: String,
        val terminalId: String,
        /** Empty once the service refused it and no silent sign-in was possible. */
        val refreshToken: String,
        /** Kept only with "stay signed in"; empty otherwise. */
        val password: String,
        val needsSignIn: Boolean
    ) {
        val label: String get() = maskLogin(login)
        override fun toString() = "Account($id)"
    }

    private sealed class Outcome {
        data class Key(val key: HaierLocalKey) : Outcome()
        /** Not in the account's list, and the key service refused it too ([errNo] from the gateway). */
        data class NotOwnedRefused(val errNo: Int?) : Outcome()
        /** The account lists the AC, but the service would not issue its key. */
        data class KeyRefused(val errNo: Int?) : Outcome()
    }

    private val lock = Mutex()
    /** The account step in progress (sign-in, device list, key fetch), for error messages. Guarded by [lock]. */
    private var step = "sign-in"
    @Volatile private var accounts: List<Account>
    /** Device ID (normalised) -> account id that last issued its key. */
    @Volatile private var deviceAccounts: Map<String, String>
    /** Access tokens, in memory only, by account id. */
    private val accessTokens = HashMap<String, String>()

    init {
        val (a, d) = load()
        accounts = a
        deviceAccounts = d
    }

    override fun accounts(): List<LinkedAccount> = accounts.map { it.toLinked() }

    override fun accountFor(deviceId: String): LinkedAccount? =
        deviceAccounts[normalizeDeviceId(deviceId)]?.let { id -> accounts.firstOrNull { it.id == id } }?.toLinked()

    override fun loginHint(accountId: String): String? = accounts.firstOrNull { it.id == accountId }?.login?.ifEmpty { null }

    private fun Account.toLinked() = LinkedAccount(id, label, needsSignIn, password.isNotEmpty())

    override suspend fun signIn(login: String, password: String, region: String, staySignedIn: Boolean): SignInResult = lock.withLock {
        if (!available) return SignInResult.Failure(AcError.AccountUnavailable(brandName))
        val user = login.trim()
        if (user.isEmpty() || password.isEmpty()) return SignInResult.Failure(AcError.AccountRejected(serviceName))
        val provisionalId = accountId("", user, region)
        // Re-signing in to an account already linked keeps its terminal id, so its tokens stay bound to one terminal.
        val existing = accounts.firstOrNull { it.id == provisionalId || (it.login.equals(user, ignoreCase = true) && it.zone == region) }
        val terminalId = existing?.terminalId ?: HaierCloudClient.newClientId(random)
        try {
            val tokens = withContext(io) { client(region, terminalId).login(user, password) }
            val id = accountId(tokens.userId, user, region)
            val account = Account(id, user, region, terminalId, tokens.refreshToken, if (staySignedIn) password else "", needsSignIn = false)
            val previous = accounts
            accounts = accounts.filterNot { it.id == id || it.id == existing?.id } + account
            if (!persist()) {
                accounts = previous
                return SignInResult.Failure(AcError.General("The phone's secure storage refused to save the sign-in."))
            }
            accessTokens[id] = tokens.accessToken
            log("haier account: signed in (${accounts.size} linked, stay signed in: $staySignedIn)")
            SignInResult.Success
        } catch (e: HaierCloudException) {
            // The message holds only the HTTP status and the service's retCode/retInfo, never request or reply bodies.
            log("haier account: sign-in failed (step sign-in, zone $region, ${e.kind}: ${e.message})")
            SignInResult.Failure(e.toAcError("sign-in"))
        }
    }

    /**
     * Terminal id of the in-progress signup, so the send-code and register calls share one (like the
     * app). Saved, not just held in memory: reading the code means leaving RoomAC for the mail app,
     * and a phone may end RoomAC's process meanwhile; a fresh id at "Create account" would not match
     * the one the code was sent to.
     */
    private fun signupTerminal(email: String): String {
        val key = email.trim().lowercase()
        sessions.load(SIGNUP_KEY)?.split('\t')?.takeIf { it.size == 2 && it[0] == key }?.let { return it[1] }
        return HaierCloudClient.newClientId(random).also { sessions.save(SIGNUP_KEY, "$key\t$it") }
    }

    override suspend fun sendVerificationCode(email: String, region: String): SendCodeResult {
        if (!available) return SendCodeResult.Failure(AcError.AccountUnavailable(brandName))
        if (!features.register) return SendCodeResult.Failure(AcError.NotSupported("Account creation"))
        val user = email.trim()
        if (user.isEmpty()) return SendCodeResult.Failure(AcError.General("Enter the email address for the new account."))
        // Sign-up is by email only (the captured flow has no phone variant); catch typos before Haier is asked.
        if (!EMAIL.matches(user)) return SendCodeResult.Failure(AcError.General("\"$user\" is not an email address. Enter it like name@example.com."))
        val cloud = client(region, signupTerminal(user))
        return try {
            // Fail fast if the address is taken, so the user is not left waiting for a code that cannot be used.
            if (!withContext(io) { cloud.checkAvailability(user) })
                return SendCodeResult.Failure(AcError.General("An account with this email already exists. Sign in with it instead."))
            withContext(io) { cloud.sendVerificationCode(user) }
            log("haier account: verification code sent")
            SendCodeResult.Success
        } catch (e: HaierCloudException) {
            log("haier account: send code failed (${e.kind}: ${e.message})")
            SendCodeResult.Failure(registerError(e, "did not send a verification code"))
        }
    }

    override suspend fun register(email: String, code: String, password: String, region: String, staySignedIn: Boolean): RegisterResult {
        if (!available) return RegisterResult.Failure(AcError.AccountUnavailable(brandName))
        if (!features.register) return RegisterResult.Failure(AcError.NotSupported("Account creation"))
        val user = email.trim()
        // Checked here too (the form also checks), so a missing field is named instead of reported as a refusal.
        if (user.isEmpty()) return RegisterResult.Failure(AcError.General("Enter the email address for the new account."))
        if (code.isBlank()) return RegisterResult.Failure(AcError.General("Enter the code from the email."))
        if (password.isEmpty()) return RegisterResult.Failure(AcError.General("Enter the password for the new account again."))
        val cloud = client(region, signupTerminal(user))
        val token = try {
            withContext(io) { cloud.verifyCode(user, code.trim()) }
        } catch (e: HaierCloudException) {
            log("haier account: verify code failed (${e.kind}: ${e.message})")
            return RegisterResult.Failure(registerError(e, "did not accept the code"))
        }
        try {
            withContext(io) { cloud.register(user, password, token) }
        } catch (e: HaierCloudException) {
            log("haier account: register failed (${e.kind}: ${e.message})")
            return RegisterResult.Failure(registerError(e, "did not create the account"))
        }
        sessions.save(SIGNUP_KEY, null)
        log("haier account: account created")
        // Created: sign in so the account is linked and keys can be fetched without a second prompt.
        return when (val r = signIn(user, password, region, staySignedIn)) {
            SignInResult.Success -> RegisterResult.Success
            // Say the account exists, so the user signs in rather than trying to create it again.
            is SignInResult.Failure -> RegisterResult.Failure(
                AcError.General("Your account was created, but signing in to it failed: ${r.error.userMessage} Sign in with it from Settings.")
            )
        }
    }

    /**
     * Signup errors name the failed step and quote Haier's own reason (retCode + retInfo), so a refusal
     * is never shown as the generic sign-in message.
     */
    private fun registerError(e: HaierCloudException, step: String): AcError = when (e.kind) {
        HaierCloudException.Kind.UNREACHABLE -> AcError.CloudUnavailable(serviceName)
        HaierCloudException.Kind.APP_REFUSED -> AcError.ServiceRefusedApp(brandName)
        HaierCloudException.Kind.MALFORMED -> AcError.General("Haier's service sent an answer the app could not read. Try again later.")
        HaierCloudException.Kind.REJECTED -> {
            // "send too often" (seen live 2026-11-19 after several code requests for one address).
            if (e.code == HaierCloudClient.CODE_SENT_TOO_OFTEN) AcError.TooManyCodes(brandName)
            else {
                val reason = listOf(e.info, e.code.takeIf { it.isNotEmpty() }?.let { "code $it" }).filterNot { it.isNullOrBlank() }.joinToString(", ")
                AcError.General("Haier $step" + (if (reason.isEmpty()) "." else ": $reason."))
            }
        }
    }

    override fun signOut(accountId: String) {
        accounts = accounts.filterNot { it.id == accountId }
        deviceAccounts = deviceAccounts.filterValues { it != accountId }
        synchronized(accessTokens) { accessTokens.remove(accountId) }
        persist()
        log("haier account: signed out (${accounts.size} still linked)")
    }

    override suspend fun devices(): List<CloudDeviceInfo> = lock.withLock {
        accounts.filterNot { it.needsSignIn }.flatMap { account ->
            try {
                withAccess(account) { client, token -> client.devices(token) }
                    .map { CloudDeviceInfo(it.deviceId, it.name, it.model, it.online) }
            } catch (_: HaierCloudException) {
                emptyList()
            }
        }
    }

    override suspend fun fetchCredential(deviceId: String): ProvisionResult = lock.withLock {
        if (!available) return ProvisionResult.Failure(AcError.AccountUnavailable(brandName))
        if (accounts.isEmpty()) return ProvisionResult.Failure(AcError.AccountRequired(brandName, serviceName))
        val wanted = normalizeDeviceId(deviceId)
        // The account that issued this AC's key before is asked first; the others only if it does not own it.
        val known = deviceAccounts[wanted]
        val order = accounts.sortedBy { if (it.id == known) 0 else 1 }
        var unreachable = false
        var refused = false
        var keyRefused = false
        var appRefused = false
        // The step and service code of the most recent failure, shown with the error.
        var failStep: String? = null
        var failCode: String? = null
        for (start in order) {
            val account = accounts.firstOrNull { it.id == start.id } ?: continue
            if (account.needsSignIn) { refused = true; continue }
            try {
                when (val o = fetchFrom(account, deviceId)) {
                    is Outcome.Key -> {
                        deviceAccounts = deviceAccounts + (wanted to account.id)
                        persist()
                        log("haier account: key fetched (version ${o.key.version ?: "?"})")
                        return ProvisionResult.Success(o.key.key)
                    }
                    is Outcome.KeyRefused -> {
                        log("haier account: service refused the key for a listed AC (errNo ${o.errNo ?: "?"})")
                        keyRefused = true; failCode = o.errNo?.toString()
                    }
                    is Outcome.NotOwnedRefused -> { log("haier account: this AC is not in one linked account (key gateway errNo ${o.errNo ?: "?"})"); failCode = o.errNo?.toString() }
                }
            } catch (e: HaierCloudException) {
                failStep = step; failCode = e.code.ifEmpty { null }
                log("haier account: key fetch failed (step $step, ${e.kind}: ${e.message})")
                when (e.kind) {
                    HaierCloudException.Kind.UNREACHABLE, HaierCloudException.Kind.MALFORMED -> unreachable = true
                    HaierCloudException.Kind.REJECTED -> refused = true
                    // The app, not the account, was refused: keep every saved session and password.
                    HaierCloudException.Kind.APP_REFUSED -> appRefused = true
                }
            } catch (e: HaierGatewayException) {
                failStep = "key fetch"; failCode = e.errNo?.toString()
                log("haier account: key gateway failed (step key fetch, ${e.kind}, errNo ${e.errNo ?: "-"})")
                if (e.kind == HaierCloudException.Kind.REJECTED) refused = true else unreachable = true
            }
        }
        // Most actionable first: a network problem is retried by itself; a refused account needs a sign-in.
        ProvisionResult.Failure(
            when {
                appRefused -> AcError.ServiceRefusedApp(brandName, failStep, failCode)
                unreachable -> AcError.CloudUnavailable(serviceName, failStep, failCode)
                refused -> AcError.AccountRejected(serviceName, failStep ?: "sign-in", failCode)
                keyRefused -> AcError.KeyNotIssued(serviceName, failCode)
                else -> AcError.DeviceNotInAccount(serviceName, "device list", failCode)
            }
        )
    }

    /**
     * Asks one account for the key. The account's device list is consulted for the ID's spelling, but
     * the request is made even when the AC is not listed there: the list can spell IDs differently or
     * omit shared devices, and the key service applies the account's real permissions itself.
     */
    private suspend fun fetchFrom(account: Account, deviceId: String): Outcome {
        step = "device list"
        val all = withAccess(account) { client, token -> client.devices(token) }
        step = "key fetch"
        val listed = all.firstOrNull { normalizeDeviceId(it.deviceId) == normalizeDeviceId(deviceId) }
        val target = listed?.deviceId ?: deviceId
        val domains = HaierDomains.forZone(account.zone)
        val gatewayHost = domains.gateway
        // Diagnostics for "AC not in account" reports: device IDs are not secret (the AC broadcasts its own),
        // and no token or key is logged. Decides between "not bound to this account" and "listed but refused".
        log(
            "haier account ${account.label}: zone ${account.zone} (login ${domains.login}, devices ${domains.uhome}, gateway $gatewayHost); " +
                "device list has ${all.size}: [${all.joinToString { normalizeDeviceId(it.deviceId) }}]; " +
                "wanted ${normalizeDeviceId(deviceId)} -> ${if (listed != null) "LISTED" else "NOT LISTED"}"
        )
        return try {
            Outcome.Key(withContext(io) { gateway.fetch(gatewayHost, account.terminalId, token(account.id)!!, target) })
        } catch (e: HaierGatewayException) {
            log("haier account: key gateway refused/failed (${e.kind}: ${e.message})")
            if (e.kind != HaierCloudException.Kind.REJECTED) throw e
            // The access token may have expired since the list call: renew once and ask again.
            synchronized(accessTokens) { accessTokens.remove(account.id) }
            val current = accounts.firstOrNull { it.id == account.id } ?: throw e
            ensureAccess(current)
            try {
                Outcome.Key(withContext(io) { gateway.fetch(gatewayHost, current.terminalId, token(current.id)!!, target) })
            } catch (again: HaierGatewayException) {
                log("haier account: key gateway refused again with a fresh token (${again.kind}: ${again.message})")
                // Refused with a fresh token: the account may not use this AC (or the service will not say why).
                if (again.kind != HaierCloudException.Kind.REJECTED) throw again
                if (listed == null) Outcome.NotOwnedRefused(again.errNo) else Outcome.KeyRefused(again.errNo)
            }
        }
    }

    /** Runs [block] with a valid access token, renewing it (silently) once if the service rejects the one held. */
    private suspend fun <T> withAccess(account: Account, block: (HaierCloudClient, String) -> T): T {
        val client = client(account.zone, account.terminalId)
        token(account.id)?.let { held ->
            try {
                return withContext(io) { block(client, held) }
            } catch (e: HaierCloudException) {
                if (e.kind != HaierCloudException.Kind.REJECTED) throw e
                synchronized(accessTokens) { accessTokens.remove(account.id) }
            }
        }
        ensureAccess(accounts.firstOrNull { it.id == account.id } ?: account)
        return withContext(io) { block(client, token(account.id)!!) }
    }

    /**
     * Obtains an access token without the user: from the refresh token, else by signing in again with
     * the saved password. Marks the account "needs sign-in" only when the service refuses both.
     */
    private suspend fun ensureAccess(account: Account) {
        if (token(account.id) != null) return
        val outer = step
        step = "sign-in"
        try { renewAccess(account) } finally { step = outer }
    }

    private suspend fun renewAccess(account: Account) {
        val client = client(account.zone, account.terminalId)
        if (account.refreshToken.isNotEmpty()) {
            try {
                val t = withContext(io) { client.refresh(account.refreshToken) }
                setToken(account.id, t.accessToken)
                if (t.refreshToken != account.refreshToken) replace(account.copy(refreshToken = t.refreshToken))
                return
            } catch (e: HaierCloudException) {
                if (e.kind != HaierCloudException.Kind.REJECTED) throw e
                log("haier account: saved session no longer accepted")
            }
        }
        if (account.password.isNotEmpty() && account.login.isNotEmpty()) {
            try {
                val t = withContext(io) { client.login(account.login, account.password) }
                setToken(account.id, t.accessToken)
                replace(account.copy(refreshToken = t.refreshToken, needsSignIn = false))
                log("haier account: signed in again automatically")
                return
            } catch (e: HaierCloudException) {
                if (e.kind != HaierCloudException.Kind.REJECTED) throw e
                // The password itself is refused (changed, or the account locked): stop using it.
                log("haier account: saved password no longer accepted")
            }
        }
        replace(account.copy(refreshToken = "", password = "", needsSignIn = true))
        throw HaierCloudException(HaierCloudException.Kind.REJECTED, "the account needs the user to sign in again")
    }

    private fun token(accountId: String): String? = synchronized(accessTokens) { accessTokens[accountId] }
    private fun setToken(accountId: String, token: String) = synchronized(accessTokens) { accessTokens[accountId] = token }

    private fun replace(updated: Account) {
        accounts = accounts.map { if (it.id == updated.id) updated else it }
        persist()
    }

    private fun client(zone: String, terminalId: String) = HaierCloudClient(identity, terminalId, zone, transport, random = random)

    private fun HaierCloudException.toAcError(step: String): AcError = when (kind) {
        HaierCloudException.Kind.UNREACHABLE -> AcError.CloudUnavailable(serviceName, step, code.ifEmpty { null })
        HaierCloudException.Kind.APP_REFUSED -> AcError.ServiceRefusedApp(brandName, step, code.ifEmpty { null })
        HaierCloudException.Kind.REJECTED ->
            if (code == HaierCloudClient.ACCOUNT_NOT_IN_REGION) AcError.AccountNotInRegion(serviceName, step, code)
            else AcError.AccountRejected(serviceName, step, code.ifEmpty { null })
        HaierCloudException.Kind.MALFORMED -> AcError.General("Haier's service sent an answer the app could not read. Try again later.")
    }

    private fun persist(): Boolean = sessions.save(id, if (accounts.isEmpty()) null else encode())

    private fun encode(): String = buildJsonObject {
        put("v", 2)
        putJsonArray("accounts") {
            accounts.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id); put("login", a.login); put("zone", a.zone); put("terminal", a.terminalId)
                    put("refresh", a.refreshToken); put("password", a.password); put("needsSignIn", a.needsSignIn)
                })
            }
        }
        putJsonObject("devices") { deviceAccounts.forEach { (d, a) -> put(d, a) } }
    }.toString()

    private fun load(): Pair<List<Account>, Map<String, String>> = try {
        val o = sessions.load(id)?.let { Json.parseToJsonElement(it).jsonObject } ?: return emptyList<Account>() to emptyMap()
        if (o["accounts"] == null) {
            // Version 2.2.0 kept a single account: refresh token, terminal, zone and a masked label only.
            val refresh = o.str("refresh")
            val terminal = o.str("terminal")
            if (refresh.isEmpty() || terminal.isEmpty()) emptyList<Account>() to emptyMap()
            else listOf(Account("t-" + terminal.take(12).lowercase(), "", o.str("zone").ifEmpty { "92" }, terminal, refresh, "", false)) to emptyMap()
        } else {
            val list = (o["accounts"] as JsonArray).mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                Account(
                    a.str("id"), a.str("login"), a.str("zone"), a.str("terminal"), a.str("refresh"), a.str("password"),
                    (a["needsSignIn"] as? JsonPrimitive)?.booleanOrNull ?: false
                ).takeIf { it.id.isNotEmpty() && it.terminalId.isNotEmpty() }
            }
            val devices = (o["devices"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }?.filterValues { it.isNotEmpty() } ?: emptyMap()
            list to devices
        }
    } catch (_: Exception) {
        emptyList<Account>() to emptyMap()
    }

    companion object {
        /** Deliberately loose: one @, no spaces, a dot in the domain. Haier does the real validation. */
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        const val PROVIDER_ID = "haier-sea"
        /** Session-store key of the pending signup's "email<TAB>terminal id". */
        private const val SIGNUP_KEY = "$PROVIDER_ID.signup"

        // Every country a HaiSmart account may have been registered under (dialling code = the zoneInfo
        // Haier expects). The account country can differ from where the AC is: Haier answers a right
        // account in the wrong country with retCode 30032. List from haismart-local countries.py (MIT).
        val REGIONS = listOf(
            AccountRegion("92", "Pakistan"),
            AccountRegion("355", "Albania"),
            AccountRegion("213", "Algeria"),
            AccountRegion("54", "Argentina"),
            AccountRegion("374", "Armenia"),
            AccountRegion("61", "Australia"),
            AccountRegion("43", "Austria"),
            AccountRegion("994", "Azerbaijan"),
            AccountRegion("973", "Bahrain"),
            AccountRegion("880", "Bangladesh"),
            AccountRegion("375", "Belarus"),
            AccountRegion("32", "Belgium"),
            AccountRegion("591", "Bolivia"),
            AccountRegion("387", "Bosnia and Herzegovina"),
            AccountRegion("55", "Brazil"),
            AccountRegion("673", "Brunei"),
            AccountRegion("359", "Bulgaria"),
            AccountRegion("855", "Cambodia"),
            AccountRegion("1", "Canada"),
            AccountRegion("56", "Chile"),
            AccountRegion("86", "China"),
            AccountRegion("57", "Colombia"),
            AccountRegion("506", "Costa Rica"),
            AccountRegion("385", "Croatia"),
            AccountRegion("357", "Cyprus"),
            AccountRegion("420", "Czechia"),
            AccountRegion("45", "Denmark"),
            AccountRegion("593", "Ecuador"),
            AccountRegion("20", "Egypt"),
            AccountRegion("372", "Estonia"),
            AccountRegion("358", "Finland"),
            AccountRegion("33", "France"),
            AccountRegion("995", "Georgia"),
            AccountRegion("49", "Germany"),
            AccountRegion("30", "Greece"),
            AccountRegion("502", "Guatemala"),
            AccountRegion("852", "Hong Kong"),
            AccountRegion("36", "Hungary"),
            AccountRegion("354", "Iceland"),
            AccountRegion("91", "India"),
            AccountRegion("62", "Indonesia"),
            AccountRegion("98", "Iran"),
            AccountRegion("964", "Iraq"),
            AccountRegion("353", "Ireland"),
            AccountRegion("972", "Israel"),
            AccountRegion("39", "Italy"),
            AccountRegion("81", "Japan"),
            AccountRegion("962", "Jordan"),
            AccountRegion("7", "Kazakhstan"),
            AccountRegion("254", "Kenya"),
            AccountRegion("965", "Kuwait"),
            AccountRegion("856", "Laos"),
            AccountRegion("371", "Latvia"),
            AccountRegion("961", "Lebanon"),
            AccountRegion("370", "Lithuania"),
            AccountRegion("352", "Luxembourg"),
            AccountRegion("60", "Malaysia"),
            AccountRegion("356", "Malta"),
            AccountRegion("52", "Mexico"),
            AccountRegion("373", "Moldova"),
            AccountRegion("976", "Mongolia"),
            AccountRegion("212", "Morocco"),
            AccountRegion("95", "Myanmar"),
            AccountRegion("977", "Nepal"),
            AccountRegion("31", "Netherlands"),
            AccountRegion("64", "New Zealand"),
            AccountRegion("234", "Nigeria"),
            AccountRegion("389", "North Macedonia"),
            AccountRegion("47", "Norway"),
            AccountRegion("968", "Oman"),
            AccountRegion("507", "Panama"),
            AccountRegion("595", "Paraguay"),
            AccountRegion("51", "Peru"),
            AccountRegion("63", "Philippines"),
            AccountRegion("48", "Poland"),
            AccountRegion("351", "Portugal"),
            AccountRegion("974", "Qatar"),
            AccountRegion("40", "Romania"),
            AccountRegion("7", "Russia"),
            AccountRegion("966", "Saudi Arabia"),
            AccountRegion("381", "Serbia"),
            AccountRegion("65", "Singapore"),
            AccountRegion("421", "Slovakia"),
            AccountRegion("386", "Slovenia"),
            AccountRegion("27", "South Africa"),
            AccountRegion("82", "South Korea"),
            AccountRegion("34", "Spain"),
            AccountRegion("94", "Sri Lanka"),
            AccountRegion("46", "Sweden"),
            AccountRegion("41", "Switzerland"),
            AccountRegion("886", "Taiwan"),
            AccountRegion("66", "Thailand"),
            AccountRegion("216", "Tunisia"),
            AccountRegion("90", "Turkey"),
            AccountRegion("380", "Ukraine"),
            AccountRegion("971", "United Arab Emirates"),
            AccountRegion("44", "United Kingdom"),
            AccountRegion("1", "United States"),
            AccountRegion("598", "Uruguay"),
            AccountRegion("998", "Uzbekistan"),
            AccountRegion("58", "Venezuela"),
            AccountRegion("84", "Vietnam")
        )

        /** Stable id for an account: the service's own user id when given, else derived from the login and region. */
        internal fun accountId(userId: String, login: String, zone: String): String =
            if (userId.isNotBlank()) "u-$userId"
            else "h-" + MessageDigest.getInstance("SHA-256").digest("${login.trim().lowercase()}|$zone".toByteArray()).toHex().take(16)

        /** "someone@example.com" -> "so•••@example.com"; "03001234567" -> "•••••••4567". */
        fun maskLogin(login: String): String {
            if (login.isEmpty()) return "your account"
            val at = login.indexOf('@')
            return if (at > 0) login.take(minOf(2, at)) + "•••" + login.substring(at)
            else "•".repeat((login.length - 4).coerceAtLeast(0)) + login.takeLast(4)
        }
    }
}
