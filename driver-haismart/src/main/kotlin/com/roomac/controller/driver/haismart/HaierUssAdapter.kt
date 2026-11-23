package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CapabilityResolver
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.driver.haismart.cloud.HaierAccount
import com.roomac.controller.driver.haismart.cloud.HaierGatewayAuth
import com.roomac.controller.protocol.EvidenceLevel
import com.roomac.controller.protocol.ExternalProvisioning
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.AcProtocolAdapter
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.DeviceDiscovery
import com.roomac.controller.protocol.LocalAcClient
import com.roomac.controller.protocol.ProtocolDescriptor
import com.roomac.controller.protocol.ProvisionResult

/**
 * Haier (HaiSmart / U+ SE-Asia) appliances: UDISCOVERY on UDP 7083 and uSS on TCP 56800. The local
 * key is issued by Haier's cloud to the owner's account, so it is fetched from the signed-in
 * [account] (see docs/DISCOVERY_AND_AUTH.md).
 */
class HaierUssAdapter(
    override val discovery: DeviceDiscovery = UdiscoveryScanner(),
    override val client: LocalAcClient = HaierUssClient(),
    private val account: CloudAccountProvider? = null
) : AcProtocolAdapter {

    override val descriptor = ProtocolDescriptor(
        id = ProtocolIds.HAIER_USS,
        displayName = "Haier uSS (HaiSmart local protocol)",
        defaultBrand = AcBrands.HAIER.id,
        credentialMode = CredentialMode.ACCOUNT,
        accountProviderId = HaierAccount.PROVIDER_ID,
        // Adding a new AC to an account (Wi-Fi setup + binding) has no verified interface; see
        // docs/MANUFACTURER_CAPABILITIES.md. Everything after that runs in RoomAC.
        externalProvisioning = ExternalProvisioning(
            appName = "HaiSmart", appPackage = HaierGatewayAuth.PACKAGE,
            step = "add the AC to your account (this also connects it to your Wi-Fi)",
            onlyForNewWifiSetup = false
        ),
        models = "Haier wall splits with the uSS module and the classic 125/127-byte status layout (uPlusId 2008…0040, e.g. HSU-20HFTEX/013WUSDC(OW)-T3). Other layouts are read-only.",
        // Discovery and handshake pass on HSU-20HFTEX/013WUSDC(OW)-T3; status reads and commands have not.
        evidence = EvidenceLevel.IMPLEMENTED_UNVERIFIED,
        limitations = listOf(
            "The AC must already be in a HaiSmart account; adding it there is only possible in the HaiSmart app.",
            "Key retrieval uses the HaiSmart app's identity against an unpublished Haier API, which Haier could change or block.",
            "HaiSmart accounts created with Google or Facebook have no password and cannot sign in here."
        ),
        licence = "MIT (haismart-local), attributed in THIRD_PARTY_NOTICES.md"
    )

    override fun capabilities(device: PairedDevice, status: AcStatus): AcCapabilities? =
        CapabilityResolver.resolve(ModelProfiles.forUplusId(device.uPlusId), status)

    override suspend fun provision(endpoint: AcEndpoint): ProvisionResult =
        account?.fetchCredential(endpoint.deviceId) ?: ProvisionResult.Failure(AcError.AccountUnavailable("Haier"))
}
