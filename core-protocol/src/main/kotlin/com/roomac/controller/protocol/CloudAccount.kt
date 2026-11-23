package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcError

/** A region the manufacturer's account service is split by (users pick their country, never a server). */
data class AccountRegion(val code: String, val label: String)

/** An appliance listed in the signed-in account, used to name the AC and to check it belongs to the user. */
data class CloudDeviceInfo(
    val deviceId: String,
    val name: String,
    val model: String,
    val online: Boolean
)

sealed class SignInResult {
    object Success : SignInResult()
    data class Failure(val error: AcError) : SignInResult()
}

/** Result of asking the service to send a new-account verification code to an email/phone. */
sealed class SendCodeResult {
    object Success : SendCodeResult()
    data class Failure(val error: AcError) : SendCodeResult()
}

/** Result of creating an account in-app. [Success] means the account was created and is now linked (signed in). */
sealed class RegisterResult {
    object Success : RegisterResult()
    data class Failure(val error: AcError) : RegisterResult()
}

/**
 * Account operations a manufacturer makes available to RoomAC, beyond signing in. Creating an account,
 * resetting a password and signing in are separate operations; one being possible says nothing about
 * the others. Each provider declares only what has been verified for it.
 */
data class AccountFeatures(
    /** A new account can be created inside RoomAC. */
    val register: Boolean = false,
    /** A forgotten password can be reset inside RoomAC. */
    val passwordReset: Boolean = false,
    /** A new AC can be added (bound) to the account inside RoomAC. */
    val bindDevice: Boolean = false,
    /**
     * The manufacturer's own account sign-up page, opened inside RoomAC in a browser tab, when one exists
     * AND an account made there is verified to work with the service this provider signs in to. Null
     * otherwise. A shop, warranty or website-member account is not enough.
     */
    val webRegistration: WebRegistration? = null
)

/**
 * An official registration page. Only [url] is opened, and only if it passes [TrustedUrl.isAllowed] for
 * [trustedHosts]. The page runs in the system browser engine (Custom Tab): RoomAC never sees what is
 * typed there, injects nothing, and reads no cookies or tokens from it. After registering, the user
 * signs in through the normal in-app form.
 */
data class WebRegistration(
    val url: String,
    /** Hosts the page may be on (exact host or a subdomain of one). */
    val trustedHosts: Set<String>,
    /** Where the evidence for this page is recorded, e.g. "docs/MANUFACTURER_CAPABILITIES.md#samsung". */
    val evidence: String
) {
    init {
        require(TrustedUrl.isAllowed(url, trustedHosts)) { "registration URL must be https on a trusted host" }
    }
}

/** Validation for any manufacturer page or redirect RoomAC opens or accepts. */
object TrustedUrl {
    /**
     * True only for an https URL with no user-info and no non-default port, whose host is one of [hosts]
     * or a subdomain of one. Look-alikes such as "haier.com.evil.example" or "evilhaier.com" are refused.
     */
    fun isAllowed(url: String, hosts: Set<String>): Boolean {
        val uri = try { java.net.URI(url.trim()) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return hosts.any { h -> val t = h.lowercase(); host == t || host.endsWith(".$t") }
    }
}

/** One linked manufacturer account, as shown to the user. */
data class LinkedAccount(
    val id: String,
    /** The email or phone number, partly masked. */
    val label: String,
    /** The saved sign-in stopped working and could not be renewed silently: the user must sign in again. */
    val needsSignIn: Boolean,
    /** The user chose "stay signed in": the app can sign in again by itself if the saved session expires. */
    val staysSignedIn: Boolean
)

/**
 * Manufacturer accounts the user links once, so the app can fetch the per-device keys the
 * manufacturer's cloud issues. Several accounts may be linked (ACs set up under different accounts).
 *
 * Renewal order, without asking the user: a cached access token; a new one minted from the saved
 * refresh token; and only if the service no longer accepts that, a fresh sign-in with the password,
 * if the user chose "stay signed in" (stored Keystore-encrypted by [AccountSessionStore]). Fetching a
 * key is the only cloud call made, and only when an AC is first connected or its key is rejected.
 */
interface CloudAccountProvider {
    /** Stable id, referenced by [ProtocolDescriptor.accountProviderId]. */
    val id: String
    /** Brand shown to the user, e.g. "Haier". */
    val brandName: String
    /** Name of the manufacturer's app the account belongs to, e.g. "HaiSmart". */
    val serviceName: String
    /** Android package of the manufacturer's app, for "open the app" fallbacks; null if none. */
    val serviceAppPackage: String?
    val regions: List<AccountRegion>
    val defaultRegion: String
    /** False when this build cannot sign in to the service (no app identity configured). */
    val available: Boolean

    /** What besides sign-in can be done inside RoomAC. Defaults to sign-in only. */
    val features: AccountFeatures get() = AccountFeatures()

    /**
     * Plain-language explanation shown when [AccountFeatures.register] is false: why RoomAC cannot create
     * the account and where the user can. Null = nothing to say.
     */
    val noRegistrationHelp: String? get() = null

    fun accounts(): List<LinkedAccount>

    /** True when at least one linked account can be used without the user signing in again. */
    fun isSignedIn(): Boolean = accounts().any { !it.needsSignIn }

    /** The account that issued [deviceId]'s key, once known. */
    fun accountFor(deviceId: String): LinkedAccount?

    /** The full email or phone number saved for [accountId], to pre-fill a repeated sign-in. */
    fun loginHint(accountId: String): String?

    /**
     * Links an account (or renews one already linked). With [staySignedIn] the password is kept,
     * Keystore-encrypted, solely so the app can sign in again by itself when the service ends the
     * saved session; otherwise it is used for this one request and forgotten.
     */
    suspend fun signIn(login: String, password: String, region: String, staySignedIn: Boolean): SignInResult

    /**
     * Step 1 of in-app account creation: asks the service to send a verification code to [email]. Only
     * meaningful when [AccountFeatures.register] is true; the default declares account creation unsupported.
     */
    suspend fun sendVerificationCode(email: String, region: String): SendCodeResult =
        SendCodeResult.Failure(AcError.NotSupported("Account creation"))

    /**
     * Step 2: creates the account with the emailed [code] and [password], then links it just as [signIn]
     * would (so keys can be fetched straight away). [staySignedIn] is handled exactly as in [signIn].
     * Only meaningful when [AccountFeatures.register] is true.
     */
    suspend fun register(email: String, code: String, password: String, region: String, staySignedIn: Boolean): RegisterResult =
        RegisterResult.Failure(AcError.NotSupported("Account creation"))

    /** Unlinks one account and deletes everything saved for it. Keys already fetched stay, so local control keeps working. */
    fun signOut(accountId: String)

    /**
     * Fetches the current credential for [deviceId], from whichever linked account owns it, renewing
     * that account's authorisation silently if needed. Fails with [AcError.AccountRequired] when no
     * account is linked, [AcError.AccountRejected] when the owning account needs the user to sign in
     * again, [AcError.DeviceNotInAccount] when no linked account owns the AC, and
     * [AcError.CloudUnavailable] when the service cannot be reached.
     */
    suspend fun fetchCredential(deviceId: String): ProvisionResult

    /** The linked accounts' appliances, for naming. Empty when none is usable or the service is unreachable. */
    suspend fun devices(): List<CloudDeviceInfo>
}

/** Encrypted persistence for account sessions. Implemented by the app over the Android Keystore. */
interface AccountSessionStore {
    fun load(providerId: String): String?
    /** Saves, or with null removes, a session. False if it could not be stored securely. */
    fun save(providerId: String, session: String?): Boolean
}

/** Device IDs are Wi-Fi MACs; the cloud and the LAN may spell them with different case or separators. */
fun normalizeDeviceId(id: String): String = id.filter { it.isLetterOrDigit() }.uppercase()
