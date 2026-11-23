package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CapabilityResolver
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.PairedDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private const val ID = "A1B2C3D4E5F6"
private const val KEY = "00112233445566778899aabbccddeeff"

private fun status(power: Boolean = true, temp: Double = 24.0, writable: Boolean = true) = AcStatus(
    power = power, mode = AcMode.COOL, rawModeCode = 1, targetTemperatureC = temp, fanSpeed = FanSpeed.AUTO,
    rawFanCode = 5, verticalSwing = false, horizontalSwing = false, indoorTemperatureC = 27.0,
    outdoorTemperatureC = 33.0, features = AcFeature.entries.associateWith { false }, ecoLevel = EcoLevel.OFF,
    selfCleaning = false, heatCapable = true, errorCode = 0, writable = writable, reportLength = 127,
    receivedAtEpochMs = 1L
)

private class FakeStore(device: PairedDevice?, var key: String? = KEY) : DeviceStore {
    override val devices = MutableStateFlow(listOfNotNull(device))
    override val activeDeviceId: StateFlow<String?> = MutableStateFlow(device?.deviceId)
    override suspend fun localKey(deviceId: String) = key
    override suspend fun saveCredential(deviceId: String, credential: String?): Boolean { key = credential; return true }
    override suspend fun update(device: PairedDevice) {
        devices.value = devices.value.map { if (it.deviceId == device.deviceId) device else it }
    }
    val current get() = devices.value.first()
}

private class FakeDiscovery(var answers: Map<String, DiscoveredDevice> = emptyMap()) : DeviceDiscovery {
    var scans = 0
    override suspend fun query(host: String) = answers[host]
    override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> {
        scans++
        return answers.values.toList()
    }
}

private class FakeClient : LocalAcClient {
    var read: ReadResult = ReadResult.Success(status(), 50)
    var exec: (AcCommand) -> CommandOutcome = { CommandOutcome.Confirmed(status(power = (it as? AcCommand.SetPower)?.on ?: true)) }
    val concurrent = AtomicInteger()
    var maxConcurrent = 0
    val hosts = mutableListOf<String>()
    var execCalls = 0

    private suspend fun <T> track(block: suspend () -> T): T {
        maxConcurrent = maxOf(maxConcurrent, concurrent.incrementAndGet())
        try { delay(50); return block() } finally { concurrent.decrementAndGet() }
    }

    override suspend fun probeKeyVersion(endpoint: AcEndpoint) = Result.success(50)
    /** When set, the AC answers per key (e.g. accepts only the new one). */
    var readFor: ((String) -> ReadResult)? = null
    val keysUsed = mutableListOf<String>()
    override suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?) =
        track { hosts += endpoint.host; keysUsed += localKey; readFor?.invoke(localKey) ?: read }
    override suspend fun execute(endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?) =
        track { execCalls++; exec(command) }
}

private fun found(host: String) = DiscoveredDevice(ID, host, 56800, ModelProfiles.SHARED_WALL_SPLIT.uPlusId, "fw", "2.18", 1000, ProtocolIds.HAIER_USS)

/** A test adapter: Haier-like (key fetched from the manufacturer account) unless [mode] says otherwise. */
private class FakeAdapter(
    override val discovery: DeviceDiscovery, override val client: LocalAcClient,
    mode: CredentialMode = CredentialMode.ACCOUNT, id: String = ProtocolIds.HAIER_USS,
    var provisionResult: ProvisionResult = ProvisionResult.Success(KEY)
) : AcProtocolAdapter {
    var provisions = 0
    override val descriptor = ProtocolDescriptor(id, "fake", "haier", mode)
    override fun capabilities(device: PairedDevice, status: AcStatus) =
        CapabilityResolver.resolve(ModelProfiles.forUplusId(device.uPlusId), status)
    override suspend fun provision(endpoint: AcEndpoint): ProvisionResult { provisions++; return provisionResult }
}

class AcConnectionCoordinatorTest {

    private val wifi = NetworkSnapshot(wifiEnabled = true, onWifi = true, local = LocalNetwork("192.168.18.139", 24))
    private val device = PairedDevice(deviceId = ID, name = "Bedroom", uPlusId = ModelProfiles.SHARED_WALL_SPLIT.uPlusId,
        lastKnownHost = "192.168.18.68", hasLocalKey = true)

    private fun TestScope.coordinator(
        store: FakeStore = FakeStore(device), discovery: FakeDiscovery = FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))),
        client: FakeClient = FakeClient(), net: NetworkSnapshot = wifi
    ) = AcConnectionCoordinator(backgroundScope, store, { net }, ProtocolRegistry(listOf(FakeAdapter(discovery, client))), clock = { 1L })

    @Test
    fun `no saved device`() = runTest {
        val c = coordinator(store = FakeStore(null))
        assertNull(c.cycle())
        assertEquals(ConnectionPhase.NO_DEVICE, c.state.value.phase)
    }

    @Test
    fun `wifi off and not on wifi are distinguished`() = runTest {
        val off = coordinator(net = NetworkSnapshot(false, false, null))
        off.cycle()
        assertEquals(ConnectionPhase.WIFI_UNAVAILABLE, off.state.value.phase)
        assertEquals(AcError.WifiOff, off.state.value.error)
        val mobile = coordinator(net = NetworkSnapshot(true, false, null))
        mobile.cycle()
        assertEquals(AcError.NotOnWifi, mobile.state.value.error)
    }

    @Test
    fun `missing key without a sign-in waits for the user and never opens a session`() = runTest {
        val client = FakeClient()
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client,
            provisionResult = ProvisionResult.Failure(AcError.AccountRequired("Haier", "HaiSmart")))
        val c = AcConnectionCoordinator(backgroundScope, FakeStore(device, key = null), { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        assertNull(c.cycle())
        assertEquals(ConnectionPhase.NEEDS_ACCOUNT, c.state.value.phase)
        assertTrue(c.state.value.error is AcError.AccountRequired)
        assertTrue(client.hosts.isEmpty())
    }

    @Test
    fun `missing key is fetched from the account and proven by a local read`() = runTest {
        val store = FakeStore(device.copy(hasLocalKey = false), key = null)
        val client = FakeClient()
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client)
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle()
        assertEquals(1, adapter.provisions)
        assertEquals(KEY, store.key)
        assertEquals(listOf("192.168.18.68"), client.hosts)
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
    }

    @Test
    fun `account that no longer accepts the saved sign-in asks to sign in again`() = runTest {
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), FakeClient(),
            provisionResult = ProvisionResult.Failure(AcError.AccountRejected("HaiSmart")))
        val c = AcConnectionCoordinator(backgroundScope, FakeStore(device, key = null), { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        assertNull(c.cycle())
        assertEquals(ConnectionPhase.NEEDS_ACCOUNT, c.state.value.phase)
    }

    @Test
    fun `cloud outage while fetching the key is retried with backoff`() = runTest {
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), FakeClient(),
            provisionResult = ProvisionResult.Failure(AcError.CloudUnavailable("HaiSmart")))
        val c = AcConnectionCoordinator(backgroundScope, FakeStore(device, key = null), { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        val wait = c.cycle()
        assertTrue(wait != null && wait > 0)
        assertEquals("no internet is a connection problem, not an authentication failure", ConnectionPhase.ERROR, c.state.value.phase)
    }

    @Test
    fun `stored key is used without contacting the account (offline local control)`() = runTest {
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), FakeClient(),
            provisionResult = ProvisionResult.Failure(AcError.CloudUnavailable("HaiSmart")))
        val c = AcConnectionCoordinator(backgroundScope, FakeStore(device), { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle()
        assertEquals(0, adapter.provisions)
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
    }

    @Test
    fun `connects, records key version, then polls at the configured interval`() = runTest {
        val store = FakeStore(device)
        val c = coordinator(store = store)
        c.pollIntervalMs = 15_000
        assertEquals(15_000L, c.cycle())
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
        assertEquals(50, store.current.keyVersion)
        assertTrue(c.state.value.capabilities!!.modes.contains(AcMode.HEAT))
        assertEquals(15_000L, c.cycle()) // poll
    }

    @Test
    fun `rediscovers the AC after its address changes`() = runTest {
        val store = FakeStore(device)
        val discovery = FakeDiscovery(mapOf("192.168.18.90" to found("192.168.18.90")))
        val client = FakeClient()
        val c = coordinator(store = store, discovery = discovery, client = client)
        c.cycle()
        assertEquals(1, discovery.scans)
        assertEquals("192.168.18.90", store.current.lastKnownHost)
        assertEquals("192.168.18.90", client.hosts.last())
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
    }

    @Test
    fun `a different device at the saved address is not used`() = runTest {
        val impostor = found("192.168.18.68").copy(deviceId = "FFFFFFFFFFFF")
        val discovery = FakeDiscovery(mapOf("192.168.18.68" to impostor))
        val c = coordinator(discovery = discovery)
        c.cycle()
        assertEquals(1, discovery.scans) // the saved address answered as someone else, so search
    }

    @Test
    fun `not found with no saved address is offline with growing backoff`() = runTest {
        val c = coordinator(store = FakeStore(device.copy(lastKnownHost = "")), discovery = FakeDiscovery())
        val first = c.cycle()!!
        assertEquals(ConnectionPhase.OFFLINE, c.state.value.phase)
        assertEquals(AcError.AcNotFound, c.state.value.error)
        val second = c.cycle()!!
        assertTrue(second > first)
    }

    @Test
    fun `rejected account key is fetched again once, then reported and left for the user`() = runTest {
        val store = FakeStore(device, key = "stale")
        val client = FakeClient().apply { read = ReadResult.Failure(AcError.KeyInvalid) }
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client)
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle()
        assertEquals("the old key is kept until a new one is proven", "stale", store.key)
        assertEquals(ConnectionPhase.RENEWING_KEY, c.state.value.phase)
        assertNull(c.cycle())
        assertEquals(1, adapter.provisions)
        assertEquals(ConnectionPhase.AUTH_FAILED, c.state.value.phase)
    }

    @Test
    fun `losing the AC while connected reconnects through discovery`() = runTest {
        val client = FakeClient()
        val discovery = FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68")))
        val c = coordinator(client = client, discovery = discovery)
        c.cycle()
        client.read = ReadResult.Failure(AcError.Unreachable("192.168.18.68"))
        c.cycle()
        assertEquals(ConnectionPhase.RECONNECTING, c.state.value.phase)
        assertTrue("last good status stays visible", c.state.value.status != null)
        client.read = ReadResult.Success(status(), 50)
        c.cycle()
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
    }

    @Test
    fun `unrecognised layout shows status but is unsupported for control`() = runTest {
        val client = FakeClient().apply { read = ReadResult.Success(status(writable = false), 50) }
        val c = coordinator(client = client)
        c.cycle()
        assertEquals(ConnectionPhase.UNSUPPORTED, c.state.value.phase)
        assertTrue(c.execute(AcCommand.SetPower(false)) is CommandOutcome.Failed)
        assertEquals(0, client.execCalls)
    }

    @Test
    fun `confirmed command updates status from the read-back and records verification`() = runTest {
        val store = FakeStore(device)
        val c = coordinator(store = store)
        c.cycle()
        val out = c.execute(AcCommand.SetPower(false))
        assertTrue(out is CommandOutcome.Confirmed)
        assertFalse(c.state.value.status!!.power)
        assertTrue("power" in store.current.verifiedControls)
    }

    @Test
    fun `rejected command does not show the requested value`() = runTest {
        val client = FakeClient().apply { exec = { CommandOutcome.Rejected("refused", status(temp = 24.0)) } }
        val c = coordinator(client = client)
        c.cycle()
        assertTrue(c.execute(AcCommand.SetTargetTemperature(18.0)) is CommandOutcome.Rejected)
        assertEquals(24.0, c.state.value.status!!.targetTemperatureC, 0.0)
    }

    @Test
    fun `invalid values never reach the AC`() = runTest {
        val client = FakeClient()
        val c = coordinator(client = client)
        c.cycle()
        assertTrue(c.execute(AcCommand.SetTargetTemperature(35.0)) is CommandOutcome.Failed)
        assertEquals(0, client.execCalls)
    }

    @Test
    fun `commands before the first status read are refused`() = runTest {
        val client = FakeClient()
        val c = coordinator(client = client)
        assertTrue(c.execute(AcCommand.SetPower(true)) is CommandOutcome.Failed)
        assertEquals(0, client.execCalls)
    }

    @Test
    fun `polls and commands never open concurrent sessions`() = runTest {
        val client = FakeClient()
        val c = coordinator(client = client)
        c.cycle()
        val a = async { c.cycle() }
        val b = async { c.execute(AcCommand.SetPower(false)) }
        val d = async { c.cycle() }
        a.await(); b.await(); d.await()
        assertEquals(1, client.maxConcurrent)
    }

    // ---- Automatic provisioning (Gree-style adapters) ----

    private fun TestScope.autoCoordinator(store: FakeStore, adapter: FakeAdapter) =
        AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })

    private val autoDevice = device.copy(protocolId = "auto", hasLocalKey = false)

    @Test
    fun `automatic adapter pairs by itself when no key is stored`() = runTest {
        val store = FakeStore(autoDevice, key = null)
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), FakeClient(), CredentialMode.AUTOMATIC, "auto")
        val c = autoCoordinator(store, adapter)
        c.cycle()
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
        assertEquals(1, adapter.provisions)
        assertEquals(KEY, store.key)
    }

    @Test
    fun `automatic pairing failure is shown and retried with backoff`() = runTest {
        val store = FakeStore(autoDevice, key = null)
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), FakeClient(), CredentialMode.AUTOMATIC, "auto",
            provisionResult = ProvisionResult.Failure(AcError.ProvisioningFailed("No answer.")))
        val c = autoCoordinator(store, adapter)
        val wait = c.cycle()
        assertEquals(ConnectionPhase.AUTH_FAILED, c.state.value.phase)
        assertTrue(wait != null && wait > 0)
        assertNull(store.key)
    }

    // ---- Automatic key renewal ----

    private fun TestScope.renewalSetup(store: FakeStore, provision: ProvisionResult = ProvisionResult.Success("fresh")): Triple<AcConnectionCoordinator, FakeClient, FakeAdapter> {
        // The AC has changed its key: it accepts only "fresh" now.
        val client = FakeClient().apply { readFor = { k -> if (k == "fresh") ReadResult.Success(status(), 51) else ReadResult.Failure(AcError.KeyRotated(51, 50)) } }
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client, provisionResult = provision)
        return Triple(AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L }), client, adapter)
    }

    @Test
    fun `changed key is renewed silently, proven on the AC, then saved, and control resumes`() = runTest {
        val store = FakeStore(device.copy(keyVersion = 50), key = "old")
        val (c, client, adapter) = renewalSetup(store)
        c.cycle()
        assertEquals(ConnectionPhase.RENEWING_KEY, c.state.value.phase)
        assertNull("no prompt: renewal needs no user action", c.state.value.error)
        assertEquals("old", store.key)
        c.cycle()
        assertEquals(1, adapter.provisions)
        assertEquals("fresh", store.key)
        assertEquals(listOf("old", "fresh"), client.keysUsed)
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
        assertTrue(c.execute(AcCommand.SetPower(false)) is CommandOutcome.Confirmed)
    }

    @Test
    fun `network failures never trigger key renewal`() = runTest {
        val store = FakeStore(device, key = KEY)
        val client = FakeClient()
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client)
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle()
        for (err in listOf(AcError.Timeout, AcError.Unreachable("x"), AcError.SessionBusy, AcError.ServiceUnavailable)) {
            client.read = ReadResult.Failure(err)
            c.cycle(); c.cycle()
        }
        assertEquals(0, adapter.provisions)
        assertEquals(KEY, store.key)
    }

    @Test
    fun `no internet during renewal keeps the old key and retries with backoff, without asking to sign in`() = runTest {
        val store = FakeStore(device.copy(keyVersion = 50), key = "old")
        val (c, _, _) = renewalSetup(store, ProvisionResult.Failure(AcError.CloudUnavailable("HaiSmart")))
        c.cycle()
        val wait = c.cycle()
        assertTrue(wait != null && wait > 0)
        assertEquals(ConnectionPhase.ERROR, c.state.value.phase)
        assertTrue(c.state.value.error is AcError.CloudUnavailable)
        assertEquals("old", store.key)
    }

    @Test
    fun `renewal needing a new sign-in waits for the user instead of looping`() = runTest {
        val store = FakeStore(device.copy(keyVersion = 50), key = "old")
        val (c, _, adapter) = renewalSetup(store, ProvisionResult.Failure(AcError.AccountRejected("HaiSmart")))
        c.cycle()
        assertNull(c.cycle())
        assertEquals(ConnectionPhase.NEEDS_ACCOUNT, c.state.value.phase)
        assertEquals(1, adapter.provisions)
        // After signing in, the user's reconnect renews and connects.
        adapter.provisionResult = ProvisionResult.Success("fresh")
        c.reconnect()
        c.cycle()
        assertEquals("fresh", store.key)
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
    }

    @Test
    fun `a key change noticed by a command is renewed too`() = runTest {
        val store = FakeStore(device.copy(keyVersion = 50), key = "old")
        val client = FakeClient()
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client, provisionResult = ProvisionResult.Success("fresh"))
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle()
        client.exec = { CommandOutcome.Failed(AcError.KeyRotated(51, 50)) }
        c.execute(AcCommand.SetPower(false))
        assertEquals(ConnectionPhase.RENEWING_KEY, c.state.value.phase)
        c.cycle()
        assertEquals("fresh", store.key)
    }

    @Test
    fun `after a failed renewal the user's reconnect allows one more automatic attempt`() = runTest {
        val store = FakeStore(device, key = "old")
        val client = FakeClient().apply { read = ReadResult.Failure(AcError.KeyInvalid) }
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client)
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle(); c.cycle()
        assertEquals(ConnectionPhase.AUTH_FAILED, c.state.value.phase)
        assertNull("stops instead of looping", c.cycle())
        assertEquals(1, adapter.provisions)
        c.reconnect()
        c.cycle(); c.cycle()
        assertEquals(2, adapter.provisions)
    }

    @Test
    fun `renewing one AC never touches another AC's key`() = runTest {
        val other = device.copy(deviceId = "0A0B0C0D0E0F", name = "Office")
        val keys = mutableMapOf(ID to "old", other.deviceId to "office-key")
        val store = object : DeviceStore {
            override val devices = MutableStateFlow(listOf(device.copy(keyVersion = 50), other))
            override val activeDeviceId: StateFlow<String?> = MutableStateFlow(ID)
            override suspend fun localKey(deviceId: String) = keys[deviceId]
            override suspend fun saveCredential(deviceId: String, credential: String?): Boolean { if (credential == null) keys.remove(deviceId) else keys[deviceId] = credential; return true }
            override suspend fun update(device: PairedDevice) { devices.value = devices.value.map { if (it.deviceId == device.deviceId) device else it } }
        }
        val client = FakeClient().apply { readFor = { k -> if (k == "fresh") ReadResult.Success(status(), 51) else ReadResult.Failure(AcError.KeyRotated(51, 50)) } }
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client, provisionResult = ProvisionResult.Success("fresh"))
        val c = AcConnectionCoordinator(backgroundScope, store, { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L })
        c.cycle(); c.cycle()
        assertEquals("fresh", keys[ID])
        assertEquals("office-key", keys[other.deviceId])
    }

    @Test
    fun `rejected key is renewed automatically once, then reported`() = runTest {
        val store = FakeStore(autoDevice.copy(hasLocalKey = true), key = "stale")
        val client = FakeClient().apply { read = ReadResult.Failure(AcError.KeyInvalid) }
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client, CredentialMode.AUTOMATIC, "auto")
        val c = autoCoordinator(store, adapter)
        c.cycle()
        assertEquals("stale", store.key) // kept until a new key is proven
        c.cycle()
        assertEquals(1, adapter.provisions)
        // The fresh key is also rejected: stop and tell the user instead of looping.
        assertEquals(ConnectionPhase.AUTH_FAILED, c.state.value.phase)
    }

    @Test
    fun `device with an unknown protocol is unsupported, nothing is sent`() = runTest {
        val client = FakeClient()
        val c = coordinator(store = FakeStore(device.copy(protocolId = "nope")), client = client)
        assertNull(c.cycle())
        assertEquals(ConnectionPhase.UNSUPPORTED, c.state.value.phase)
        assertTrue(client.hosts.isEmpty())
    }
}

class NetworkDiscoveryManagerTest {
    @Test
    fun `merges brands and lists one physical AC once`() = runTest {
        val haier = FakeDiscovery(mapOf("10.0.0.2" to found("10.0.0.2")))
        val other = FakeDiscovery(mapOf(
            "10.0.0.2" to found("10.0.0.2").copy(protocolId = "auto"),
            "10.0.0.3" to found("10.0.0.3").copy(deviceId = "AABBCCDDEEFF", protocolId = "auto")
        ))
        val registry = ProtocolRegistry(listOf(FakeAdapter(haier, FakeClient()), FakeAdapter(other, FakeClient(), id = "auto")))
        val seen = mutableListOf<DiscoveredDevice>()
        val all = NetworkDiscoveryManager(registry).scan(LocalNetwork("10.0.0.1", 24)) { seen += it }
        assertEquals(2, all.size)
        assertEquals(all, seen)
    }

    @Test
    fun `one failing brand does not hide the others`() = runTest {
        val broken = object : DeviceDiscovery {
            override suspend fun query(host: String) = throw IllegalStateException("boom")
            override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> = throw IllegalStateException("boom")
        }
        val ok = FakeDiscovery(mapOf("10.0.0.2" to found("10.0.0.2")))
        val registry = ProtocolRegistry(listOf(FakeAdapter(broken, FakeClient(), id = "a"), FakeAdapter(ok, FakeClient(), id = "b")))
        assertEquals(1, NetworkDiscoveryManager(registry).scan(LocalNetwork("10.0.0.1", 24)).size)
        assertEquals(ID, NetworkDiscoveryManager(registry).query("10.0.0.2")?.deviceId)
    }
}

/** Records which LAN holds are active, so tests can check every network step runs inside one. */
private class RecordingLan : LanAccess {
    val active = AtomicInteger()
    val reasons = mutableListOf<String>()
    override suspend fun <T> hold(reason: String, block: suspend () -> T): T {
        reasons += reason
        active.incrementAndGet()
        try { return block() } finally { active.decrementAndGet() }
    }
}

/** A client that fails the test if a session runs without the LAN hold (multicast + Wi-Fi lock on Android). */
private class HoldCheckingClient(private val lan: RecordingLan) : LocalAcClient {
    var calls = 0
    override suspend fun probeKeyVersion(endpoint: AcEndpoint) = Result.success(50)
    override suspend fun readStatus(endpoint: AcEndpoint, localKey: String, expectedKeyVersion: Int?): ReadResult {
        calls++
        assertTrue("session ran without the LAN hold", lan.active.get() > 0)
        return ReadResult.Success(status(), 50)
    }
    override suspend fun execute(endpoint: AcEndpoint, localKey: String, command: AcCommand, expectedKeyVersion: Int?): CommandOutcome {
        calls++
        assertTrue("command ran without the LAN hold", lan.active.get() > 0)
        return CommandOutcome.Confirmed(status(power = false))
    }
}

class LanAccessTest {
    private val wifi = NetworkSnapshot(wifiEnabled = true, onWifi = true, local = LocalNetwork("192.168.18.139", 24))
    private val device = PairedDevice(deviceId = ID, name = "Bedroom", uPlusId = ModelProfiles.SHARED_WALL_SPLIT.uPlusId,
        lastKnownHost = "192.168.18.68", hasLocalKey = true)

    @Test
    fun `broadcasts go to the subnet broadcast address and the limited broadcast`() {
        assertEquals(listOf("192.168.18.255", "255.255.255.255"), BroadcastBursts.targets(LocalNetwork("192.168.18.139", 24)))
        assertEquals(listOf("10.0.3.255", "255.255.255.255"), BroadcastBursts.targets(LocalNetwork("10.0.1.7", 22)))
        // No directed broadcast for a /31 or /32 (point-to-point): only the limited one.
        assertEquals(listOf("255.255.255.255"), BroadcastBursts.targets(LocalNetwork("10.0.0.1", 32)))
    }

    @Test
    fun `discovery sends five bursts about 300 ms apart, then stops`() {
        val b = BroadcastBursts(start = 1000)
        val sentAt = (1000L..3000L step 50).filter { b.due(it) }
        assertEquals(listOf(1000L, 1300L, 1600L, 1900L, 2200L), sentAt)
        assertEquals(5, b.sent)
        assertNull(b.untilNext(3000))
    }

    @Test
    fun `the wait until the next burst never goes negative`() {
        val b = BroadcastBursts(start = 0)
        assertTrue(b.due(0))
        assertEquals(300L, b.untilNext(0))
        assertEquals(0L, b.untilNext(999))
    }

    @Test
    fun `the coordinator's connect, poll and command all run inside the LAN hold`() = runTest {
        val lan = RecordingLan()
        val client = HoldCheckingClient(lan)
        val adapter = FakeAdapter(FakeDiscovery(mapOf("192.168.18.68" to found("192.168.18.68"))), client)
        val c = AcConnectionCoordinator(backgroundScope, FakeStore(device), { wifi }, ProtocolRegistry(listOf(adapter)), clock = { 1L }, lan = lan)
        c.cycle() // connect
        assertEquals(ConnectionPhase.CONNECTED, c.state.value.phase)
        c.cycle() // poll
        c.execute(AcCommand.SetPower(false))
        assertEquals(3, client.calls)
        assertEquals(0, lan.active.get())
        assertTrue(lan.reasons.containsAll(listOf("ac session", "ac command")))
    }

    @Test
    fun `discovery scans and queries run inside the LAN hold, released afterwards`() = runTest {
        val lan = RecordingLan()
        val discovery = object : DeviceDiscovery {
            override suspend fun query(host: String): DiscoveredDevice? { assertTrue(lan.active.get() > 0); return found(host) }
            override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit): List<DiscoveredDevice> {
                assertTrue(lan.active.get() > 0)
                return listOf(found("192.168.18.68"))
            }
        }
        val manager = NetworkDiscoveryManager(ProtocolRegistry(listOf(FakeAdapter(discovery, FakeClient()))), lan = lan)
        assertEquals(1, manager.scan(LocalNetwork("192.168.18.139", 24)).size)
        assertEquals("192.168.18.68", manager.query("192.168.18.68")?.host)
        assertEquals(0, lan.active.get())
        assertEquals(listOf("discovery scan", "discovery query"), lan.reasons)
    }
}
