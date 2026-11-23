package com.roomac.controller.driver.gree

import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.EvidenceLevel
import com.roomac.controller.protocol.ExternalProvisioning
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.AcProtocolAdapter
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.LocalAcClient
import com.roomac.controller.protocol.ProtocolDescriptor
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Local control of Gree-protocol modules over UDP 7000 (protocol facts as documented by greeclimate; see THIRD_PARTY_NOTICES.md).
 *
 * Every request names the unit's MAC, and every reply is checked to come from the address asked and,
 * where the reply carries one, the same MAC. Commands are confirmed only by reading the status back:
 * the module's "res" reply means it received the command, not that the AC applied it.
 */
class GreeClient(
    private val transport: GreeTransport = UdpGreeTransport(),
    private val discovery: GreeDiscovery = GreeDiscovery(transport),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val replyTimeoutMs: Long = 2500,
    private val attempts: Int = 2,
    private val settleMs: Long = 700
) : LocalAcClient {

    /** Gree has no key versions; this just checks the unit answers discovery as this device. */
    override suspend fun probeKeyVersion(endpoint: AcEndpoint): Result<Int> {
        val info = discovery.query(endpoint.host)
        return if (info != null && info.deviceId.equals(endpoint.deviceId, ignoreCase = true)) Result.success(0)
        else Result.failure(IllegalStateException(AcError.Unreachable(endpoint.host).userMessage))
    }

    /** Obtains the per-device key from the AC itself (the same LAN bind the manufacturer's app uses). */
    suspend fun bind(endpoint: AcEndpoint): ProvisionResult {
        for (version in GreeCipherVersion.entries) {
            val generic = GreeCipher.generic(version)
            val raw = exchange(endpoint, GreeCodec.bindRequest(endpoint.deviceId, generic)) ?: continue
            val outer = GreeCodec.outer(raw) ?: continue
            val reply = try { GreeCodec.decrypt(outer, generic) } catch (_: Exception) { continue }
            if (reply.type != "bindok") continue
            if (!sameDevice(reply, endpoint)) return ProvisionResult.Failure(AcError.HandshakeRefused)
            val result = GreeCodec.resultCode(reply)
            val key = reply.pack.str("key")
            if ((result != null && result != 200) || key.toByteArray(Charsets.UTF_8).size !in setOf(16, 24, 32)) {
                return ProvisionResult.Failure(AcError.ProvisioningFailed("It answered without a usable key."))
            }
            log("bound to ${endpoint.deviceId} (cipher v${version.code})")
            return ProvisionResult.Success(GreeCredential(version, key).encode())
        }
        return ProvisionResult.Failure(AcError.ProvisioningFailed("It did not answer the pairing request."))
    }

    override suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): ReadResult {
        val cipher = cipherFor(localKey) ?: return ReadResult.Failure(AcError.KeyInvalid)
        return try {
            val raw = exchange(endpoint, GreeCodec.statusRequest(endpoint.deviceId, cipher))
                ?: return ReadResult.Failure(silenceError(endpoint))
            val outer = GreeCodec.outer(raw) ?: return ReadResult.Failure(AcError.ProtocolMismatch)
            val reply = try { GreeCodec.decrypt(outer, cipher) } catch (_: GreeCryptoException) { return ReadResult.Failure(AcError.KeyInvalid) }
            if (reply.type != "dat") return ReadResult.Failure(AcError.ProtocolMismatch)
            if (!sameDevice(reply, endpoint)) return ReadResult.Failure(AcError.HandshakeRefused)
            val status = GreeCodec.status(GreeCodec.properties(reply), clock())
                ?: return ReadResult.Failure(AcError.ProtocolMismatch)
            ReadResult.Success(status, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GreeFormatException) {
            ReadResult.Failure(AcError.ProtocolMismatch)
        } catch (e: Exception) {
            ReadResult.Failure(AcError.Unreachable(endpoint.host))
        }
    }

    override suspend fun execute(
        endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?
    ): CommandOutcome {
        val props = GreeCodec.propertiesFor(command) ?: return CommandOutcome.Failed(AcError.NotSupported(command.description))
        val cipher = cipherFor(localKey) ?: return CommandOutcome.Failed(AcError.KeyInvalid)
        val raw = try {
            exchange(endpoint, GreeCodec.commandRequest(endpoint.deviceId, cipher, props))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CommandOutcome.Failed(AcError.Unreachable(endpoint.host))
        }
        if (raw != null) {
            val reply = GreeCodec.outer(raw)?.let { try { GreeCodec.decrypt(it, cipher) } catch (_: Exception) { null } }
                ?: return CommandOutcome.Failed(AcError.KeyInvalid)
            val code = GreeCodec.resultCode(reply)
            if (reply.type == "res" && code != null && code != 200) {
                return CommandOutcome.Rejected(AcError.CommandRefused(code).userMessage, null)
            }
            log("sent ${command.description}; module acknowledged")
        } else {
            log("sent ${command.description}; no acknowledgement, verifying by status")
        }
        // The acknowledgement only says the module received it: confirm from the AC's own status.
        repeat(2) { attempt ->
            if (attempt > 0) delay(settleMs)
            when (val read = readStatus(endpoint, localKey, null)) {
                is ReadResult.Success -> if (command.isReflectedIn(read.status)) return CommandOutcome.Confirmed(read.status)
                    else if (attempt == 1) return CommandOutcome.Rejected("The AC did not apply the change.", read.status)
                is ReadResult.Failure -> return CommandOutcome.Failed(if (raw == null) AcError.Timeout else read.error)
            }
        }
        return CommandOutcome.Failed(AcError.Timeout)
    }

    /**
     * No reply to a status request: if the unit still answers discovery, it is reachable and is
     * ignoring us because the key is no longer valid (e.g. it was reset); otherwise it is unreachable.
     */
    private suspend fun silenceError(endpoint: AcEndpoint): AcError {
        val info = discovery.query(endpoint.host)
        return if (info != null && info.deviceId.equals(endpoint.deviceId, ignoreCase = true)) AcError.KeyInvalid
        else AcError.Unreachable(endpoint.host)
    }

    private suspend fun exchange(endpoint: AcEndpoint, payload: ByteArray): ByteArray? {
        repeat(attempts) {
            transport.request(endpoint.host, endpoint.port, payload, replyTimeoutMs)?.let { return it }
        }
        return null
    }

    private fun sameDevice(reply: GreeReply, endpoint: AcEndpoint): Boolean {
        val mac = reply.pack.str("mac").ifEmpty { reply.pack.str("cid") }
        return mac.isEmpty() || GreeCodec.canonicalMac(mac) == GreeCodec.canonicalMac(endpoint.deviceId)
    }

    private fun cipherFor(credential: String): GreeCipher? =
        GreeCredential.decode(credential)?.let { GreeCipher(it.version, it.key) }
}

/**
 * Gree and Gree-protocol OEM units. Pairing is AUTOMATIC: the app binds to the AC over the LAN and
 * stores the key it returns; the user never sees or types it.
 */
class GreeAdapter(
    transport: GreeTransport = UdpGreeTransport(),
    log: (String) -> Unit = {},
    override val discovery: GreeDiscovery = GreeDiscovery(transport, log = log),
    private val greeClient: GreeClient = GreeClient(transport, discovery, log = log)
) : AcProtocolAdapter {

    override val client: LocalAcClient get() = greeClient

    override val descriptor = ProtocolDescriptor(
        id = ProtocolIds.GREE_WIFI,
        displayName = "Gree Wi-Fi local protocol",
        defaultBrand = AcBrands.GREE.id,
        credentialMode = CredentialMode.AUTOMATIC,
        // The module must be on the home Wi-Fi before it can be found. RoomAC does not implement Wi-Fi
        // provisioning; binding and control need no app or account. Package not verified, so no button.
        externalProvisioning = ExternalProvisioning(
            appName = "GREE+", appPackage = null,
            step = "connect the AC to your home Wi-Fi",
            onlyForNewWifiSetup = true
        ),
        models = "Gree Wi-Fi modules that answer the LAN scan on UDP 7000 (v1 ECB or v2 GCM). Cloud-only modules are not found.",
        evidence = EvidenceLevel.IMPLEMENTED_UNVERIFIED,
        limitations = listOf(
            "Not yet tested on any real Gree unit.",
            "Heat availability and the room-temperature offset are inferred until confirmed on hardware."
        ),
        licence = "Independent implementation, no code copied. v1 facts (scan, port 7000, ECB generic key, property names) also confirmed against Apache-2.0 homebridge-gree-air-conditioner @72ecd0b; v2 (GCM) facts only from GPL-3.0 greeclimate @8ce5c9e. Review before distribution."
    )

    override suspend fun provision(endpoint: AcEndpoint): ProvisionResult = greeClient.bind(endpoint)

    /**
     * The protocol does not report a capability list, so controls are offered only for properties the
     * unit returned a value for. Heat cannot be ruled out from the status alone; it is offered and,
     * like every control, marked unverified until the AC has confirmed it by read-back.
     */
    override fun capabilities(device: PairedDevice, status: AcStatus): AcCapabilities = AcCapabilities(
        modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT),
        fanSpeeds = if (GreeProps.FAN in status.reportedProperties) listOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH) else emptyList(),
        minTemperatureC = 16.0,
        maxTemperatureC = 30.0,
        temperatureStepC = 1.0,
        verticalSwing = GreeProps.SWING_VERTICAL in status.reportedProperties,
        horizontalSwing = GreeProps.SWING_HORIZONTAL in status.reportedProperties,
        features = AcFeature.entries.filter { status.features.containsKey(it) }.toSet(),
        ecoLevels = emptyList(),
        selfClean = false,
        writable = true
    )
}
