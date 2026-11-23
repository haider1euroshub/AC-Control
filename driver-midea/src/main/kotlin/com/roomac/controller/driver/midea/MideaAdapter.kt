package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.AcProtocolAdapter
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.EvidenceLevel
import com.roomac.controller.protocol.ExternalProvisioning
import com.roomac.controller.protocol.ProtocolDescriptor
import com.roomac.controller.protocol.ProvisionResult

private const val LICENCE = "MIT (msmart-ng @dd09947), attributed in THIRD_PARTY_NOTICES.md"

/**
 * Controls offered for a Midea AC. The capability query (0xB5) is not implemented, so the list is the
 * common set and every control stays marked unverified until the AC confirms it by read-back. Heat
 * cannot be ruled out from the state reply. Temperatures stay in the 17–30 °C range of the primary
 * encoding.
 */
internal fun mideaCapabilities(status: AcStatus) = AcCapabilities(
    modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT),
    fanSpeeds = listOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
    minTemperatureC = 17.0,
    maxTemperatureC = 30.0,
    temperatureStepC = 1.0,
    verticalSwing = true,
    horizontalSwing = false,
    features = setOf(AcFeature.TURBO, AcFeature.SLEEP, AcFeature.ENERGY_SAVING),
    ecoLevels = emptyList(),
    selfClean = false,
    writable = status.writable
)

private val COMMON_LIMITATIONS = listOf(
    "Not yet tested on any real Midea unit.",
    "Capabilities are not read from the AC, so some offered controls may not exist on a given model.",
    "Units set to °F are shown read-only."
)

/** Midea V2 modules: fixed LAN cipher, no credential, no account. Pairing is automatic. */
class MideaV2Adapter(
    override val discovery: MideaDiscovery = MideaDiscovery(),
    override val client: MideaClient = MideaClient(discovery)
) : AcProtocolAdapter {
    override val descriptor = ProtocolDescriptor(
        id = ProtocolIds.MIDEA_V2,
        displayName = "Midea LAN protocol V2",
        defaultBrand = AcBrands.MIDEA.id,
        credentialMode = CredentialMode.AUTOMATIC,
        // RoomAC does not do Wi-Fi provisioning; msmart-ng lists these apps for setting units up.
        // Declared once here, so the "nothing found" help lists Midea once.
        externalProvisioning = ExternalProvisioning(
            appName = "NetHome Plus or MSmartHome", appPackage = null,
            step = "connect the AC to your home Wi-Fi", onlyForNewWifiSetup = true
        ),
        models = "Midea-module air conditioners (type 0xAC) answering discovery with a V2 (5A5A) reply.",
        evidence = EvidenceLevel.IMPLEMENTED_UNVERIFIED,
        limitations = COMMON_LIMITATIONS,
        licence = LICENCE
    )

    override fun capabilities(device: PairedDevice, status: AcStatus) = mideaCapabilities(status)

    /** Nothing to obtain: the cipher is fixed. The coordinator still proves it by reading the state. */
    override suspend fun provision(endpoint: AcEndpoint): ProvisionResult = ProvisionResult.Success(MideaCredential.None.encode())
}

/** Midea V3 modules: a token and key from the owner's NetHome Plus account unlock a per-session key. */
class MideaV3Adapter(
    override val discovery: MideaDiscovery = MideaDiscovery(versions = setOf(3), scans = false),
    override val client: MideaClient = MideaClient(discovery),
    private val account: CloudAccountProvider? = null
) : AcProtocolAdapter {
    override val descriptor = ProtocolDescriptor(
        id = ProtocolIds.MIDEA_V3,
        displayName = "Midea LAN protocol V3",
        defaultBrand = AcBrands.MIDEA.id,
        credentialMode = CredentialMode.ACCOUNT,
        accountProviderId = MideaAccount.PROVIDER_ID,
        models = "Midea-module air conditioners (type 0xAC) answering discovery with a V3 (8370) reply.",
        evidence = EvidenceLevel.IMPLEMENTED_UNVERIFIED,
        limitations = COMMON_LIMITATIONS + listOf(
            "The token comes from the NetHome Plus app's private API, which Midea could change or block.",
            "The AC must already be in the NetHome Plus account used to sign in."
        ),
        licence = LICENCE
    )

    override fun capabilities(device: PairedDevice, status: AcStatus) = mideaCapabilities(status)

    override suspend fun provision(endpoint: AcEndpoint): ProvisionResult =
        account?.fetchCredential(endpoint.deviceId) ?: ProvisionResult.Failure(AcError.AccountUnavailable("Midea"))
}
