package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CapabilityResolver
import com.roomac.controller.domain.model.PairedDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** How an adapter obtains the credential its local protocol needs. */
enum class CredentialMode {
    /** The protocol needs no credential. */
    NONE,
    /** The app obtains it from the AC itself over the LAN (e.g. Gree bind); the user does nothing. */
    AUTOMATIC,
    /**
     * The manufacturer's cloud issues it to the owner's account. The user signs in once; the app then
     * fetches each AC's key from the account by itself (see [CloudAccountProvider]).
     */
    ACCOUNT
}

/** How RoomAC talks to the AC once it is set up. */
enum class ConnectionMethod {
    /** Directly over the home Wi-Fi; works without internet once the credential is stored. */
    LOCAL,
    /** Through the manufacturer's cloud; needs internet for every command. (No adapter uses this yet.) */
    CLOUD
}

/**
 * A setup step only the manufacturer's own app can do, stated so RoomAC can explain it instead of
 * failing silently. Only steps verified for the protocol belong here; routine control never does.
 */
data class ExternalProvisioning(
    /** Name of the manufacturer's app, e.g. "HaiSmart". */
    val appName: String,
    /** Its Android package, for an "open the app" button; null when not verified. */
    val appPackage: String?,
    /** What has to be done there, in plain words, finishing a sentence like "In HaiSmart, …". */
    val step: String,
    /** True when the step is needed only for ACs that are not on the home Wi-Fi yet (otherwise: only for ACs not yet in an account). */
    val onlyForNewWifiSetup: Boolean
)

/**
 * How strongly an integration's claims are backed, using the labels of requirement.md §8.2. Only
 * [VERIFIED_ON_HARDWARE] may be presented as working; everything else is shown as not yet verified.
 */
enum class EvidenceLevel(val label: String) {
    VERIFIED_ON_HARDWARE("Verified on hardware"),
    AUTOMATED_TEST_ONLY("Automated test only"),
    IMPLEMENTED_UNVERIFIED("Implemented, unverified"),
    RESEARCH_ONLY("Research only"),
    UNSUPPORTED("Unsupported"),
    UNKNOWN("Unknown")
}

data class ProtocolDescriptor(
    /** Stable id stored with each device (see ProtocolIds). */
    val id: String,
    /** Plain-language name for diagnostics and documentation, never for the main screens. */
    val displayName: String,
    /** Brand assumed when the unit does not report one. */
    val defaultBrand: String,
    val credentialMode: CredentialMode,
    /** For [CredentialMode.ACCOUNT]: the [CloudAccountProvider.id] that issues the key. */
    val accountProviderId: String? = null,
    val connection: ConnectionMethod = ConnectionMethod.LOCAL,
    /** The unavoidable step in the manufacturer's app, if any. */
    val externalProvisioning: ExternalProvisioning? = null
,
    /** Models / protocol versions this integration targets, as evidence allows (never "all models"). */
    val models: String = "",
    /** Weakest evidence behind control of the declared models (see TEST_REPORT.md). */
    val evidence: EvidenceLevel = EvidenceLevel.UNKNOWN,
    /** Known limitations, in plain words. */
    val limitations: List<String> = emptyList(),
    /** Licence / distribution constraint of the protocol source, e.g. "MIT (haismart-local), attributed". */
    val licence: String = ""
)

sealed class ProvisionResult {
    data class Success(val credential: String) : ProvisionResult()
    data class Failure(val error: AcError) : ProvisionResult()
}

/**
 * One manufacturer/protocol integration. Everything brand-specific (discovery, authentication,
 * encryption, encoding, status parsing, capability rules) lives behind this interface, so the
 * coordinator, storage and UI work the same for every brand.
 */
interface AcProtocolAdapter {
    val descriptor: ProtocolDescriptor
    val discovery: DeviceDiscovery
    val client: LocalAcClient

    /** What may be offered for [device], from its own report. Null when nothing is controllable. */
    fun capabilities(device: PairedDevice, status: AcStatus): AcCapabilities?

    /** Checks a command before anything is sent. Null = may be sent. */
    fun validate(command: AcCommand, caps: AcCapabilities, status: AcStatus): AcError? =
        CapabilityResolver.validate(command, caps, status)

    /**
     * Obtains the credential without user input: from the AC itself (AUTOMATIC) or from the signed-in
     * manufacturer account (ACCOUNT). Must never return a credential for a different device ID.
     */
    suspend fun provision(endpoint: AcEndpoint): ProvisionResult =
        ProvisionResult.Failure(AcError.NotSupported("Automatic pairing"))
}

/** Maps protocol ids to adapters. Adding a brand integration = registering one more adapter here. */
class ProtocolRegistry(adapters: List<AcProtocolAdapter>) {
    private val byId = adapters.associateBy { it.descriptor.id }

    init {
        require(byId.size == adapters.size) { "duplicate protocol id" }
    }

    val all: List<AcProtocolAdapter> = adapters

    fun forId(id: String): AcProtocolAdapter? = byId[id]
}

/**
 * Runs every registered adapter's discovery at once and merges the results. The same physical AC is
 * listed once: devices are keyed by their device ID (the Wi-Fi module MAC), whichever protocol found
 * them.
 */
class NetworkDiscoveryManager(
    private val registry: ProtocolRegistry,
    private val log: (String) -> Unit = {},
    private val lan: LanAccess = LanAccess.Direct
) {

    suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit = {}): List<DiscoveredDevice> = lan.hold("discovery scan") {
        val seen = LinkedHashMap<String, DiscoveredDevice>()
        val lock = Any()
        val report: (DiscoveredDevice) -> Unit = { d ->
            val isNew = synchronized(lock) { seen.putIfAbsent(d.deviceId.uppercase(), d) == null }
            if (isNew) onFound(d)
        }
        coroutineScope {
            registry.all.map { adapter ->
                async {
                    try {
                        adapter.discovery.scan(network, report).forEach(report)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One brand's discovery failing must not hide the others.
                        log("${adapter.descriptor.id} scan failed: ${e.javaClass.simpleName}")
                    }
                }
            }.forEach { it.await() }
        }
        synchronized(lock) { seen.values.toList() }
    }

    /** Asks one address with every protocol; returns the first that answers. */
    suspend fun query(host: String): DiscoveredDevice? = lan.hold("discovery query") {
        var result: DiscoveredDevice? = null
        for (adapter in registry.all) {
            result = try { adapter.discovery.query(host) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (result != null) break
        }
        result
    }
}
