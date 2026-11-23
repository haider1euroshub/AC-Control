package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CommandOutcome

/** Where to reach one appliance, and the identity its protocol session is bound to. */
data class AcEndpoint(val host: String, val port: Int, val deviceId: String)

/** An appliance that answered local discovery. */
data class DiscoveredDevice(
    val deviceId: String,
    val host: String,
    val port: Int,
    /** Haier product profile id; empty for other protocols. */
    val uPlusId: String,
    val firmware: String,
    val sdkVersion: String,
    /** Raw cloud-state value the module reports; 1000 = connected to Haier cloud. */
    val cloudState: Int?,
    /** Adapter that found it (see ProtocolIds). */
    val protocolId: String,
    /** Brand the unit reports about itself, or the brand its protocol implies; "" when unknown. */
    val brand: String = "",
    /** Model string the unit reports, if any. */
    val model: String = "",
    /** Name the unit reports (often set in the manufacturer's app), if any. */
    val reportedName: String = ""
)

/** The phone's own IPv4 address on the Wi-Fi network, used to choose what to scan. */
data class LocalNetwork(val address: String, val prefixLength: Int)

interface DeviceDiscovery {
    /** Asks one address directly. Null when nothing answers. */
    suspend fun query(host: String): DiscoveredDevice?

    /** Searches the local subnet. [onFound] is called as each appliance answers. */
    suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit = {}): List<DiscoveredDevice>
}

sealed class ReadResult {
    data class Success(val status: AcStatus, val keyVersion: Int) : ReadResult()
    data class Failure(val error: AcError) : ReadResult()
}

/**
 * The local protocol client. Every call opens its own short session (the appliance caps sessions at
 * roughly 17 s and accepts only one at a time), so callers must serialise calls per appliance.
 */
interface LocalAcClient {
    /** Handshake only, no key needed: confirms the session is accepted and returns the key version. */
    suspend fun probeKeyVersion(endpoint: AcEndpoint): Result<Int>

    suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): ReadResult

    /**
     * Sends one change and verifies it by reading the appliance's reported state back. Never reports
     * success unless a status report shows the change.
     */
    suspend fun execute(
        endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?
    ): CommandOutcome
}
