package com.roomac.controller.protocol

import com.roomac.controller.domain.model.AcCapabilities
import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.domain.model.ModelProfile
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The connection states shown to the user. Discovery, identification, pairing and control are distinct. */
enum class ConnectionPhase(val label: String) {
    NO_DEVICE("No AC added"),
    CHECKING_NETWORK("Checking network"),
    WIFI_UNAVAILABLE("Wi-Fi unavailable"),
    SEARCHING("Searching for AC"),
    AC_FOUND("AC found"),
    AUTHENTICATING("Authenticating"),
    CONNECTED("Connected locally"),
    RECONNECTING("Reconnecting"),
    OFFLINE("AC offline / unreachable"),
    RENEWING_KEY("Refreshing AC connection…"),
    NEEDS_ACCOUNT("Sign-in needed"),
    AUTH_FAILED("Authentication failed"),
    UNSUPPORTED("Unsupported device or model"),
    ERROR("Connection error")
}

data class NetworkSnapshot(val wifiEnabled: Boolean, val onWifi: Boolean, val local: LocalNetwork?)

fun interface NetworkMonitor {
    fun snapshot(): NetworkSnapshot
}

/** Saved devices and their keys. Implemented by the app over Keystore-backed storage. */
interface DeviceStore {
    val devices: StateFlow<List<PairedDevice>>
    val activeDeviceId: StateFlow<String?>
    suspend fun localKey(deviceId: String): String?
    suspend fun update(device: PairedDevice)
    /** Stores (or with null, clears) a device's credential, encrypted. False if it could not be stored. */
    suspend fun saveCredential(deviceId: String, credential: String?): Boolean
}

data class DashboardState(
    val phase: ConnectionPhase = ConnectionPhase.CHECKING_NETWORK,
    val device: PairedDevice? = null,
    val host: String? = null,
    val status: AcStatus? = null,
    val capabilities: AcCapabilities? = null,
    val profile: ModelProfile? = null,
    val error: AcError? = null,
    val lastUpdateEpochMs: Long = 0L,
    val commandInFlight: AcCommand? = null
)

sealed class CommandFeedback {
    abstract val command: AcCommand
    data class Applied(override val command: AcCommand) : CommandFeedback()
    data class Rejected(override val command: AcCommand, val reason: String) : CommandFeedback()
    data class Failed(override val command: AcCommand, val error: AcError) : CommandFeedback()
}

class AcConnectionCoordinator(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val network: NetworkMonitor,
    private val registry: ProtocolRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val lan: LanAccess = LanAccess.Direct
) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val _feedback = MutableSharedFlow<CommandFeedback>(extraBufferCapacity = 8)
    val feedback: SharedFlow<CommandFeedback> = _feedback.asSharedFlow()

    /** One local session at a time: polls, commands and key checks all take this lock. */
    private val sessionLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile var pollIntervalMs: Long = DEFAULT_POLL_MS
        set(value) { field = value.coerceIn(MIN_POLL_MS, MAX_POLL_MS); wakeUp() }

    @Volatile private var foreground = true
    @Volatile private var connected = false
    @Volatile private var failures = 0
    /** Set once an automatic re-pair has been tried since the last successful read, so it is not repeated in a loop. */
    @Volatile private var reprovisioned = false
    /** The AC rejected its saved key: fetch a new one on the next connect, keeping the old until the new one is proven. */
    @Volatile private var renewKey = false
    private var currentDeviceId: String? = null
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch { runLoop() }
        // Any change to the saved devices (new key, new active device, removal) re-evaluates at once.
        scope.launch {
            store.activeDeviceId.collect { wakeUp() }
        }
        scope.launch {
            store.devices.map { list -> list.map { it.deviceId to it.hasLocalKey } }.distinctUntilChanged().collect { wakeUp() }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** "Scan again" / network changed / app resumed. */
    fun wakeUp() {
        wake.trySend(Unit)
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) wakeUp()
    }

    /** Forces a fresh discovery and handshake on the next cycle; a user's retry also re-allows one automatic key renewal. */
    fun reconnect() {
        connected = false
        failures = 0
        reprovisioned = false
        wakeUp()
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val wait = try {
                cycle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("cycle error: ${e.javaClass.simpleName}")
                _state.update { it.copy(phase = ConnectionPhase.ERROR, error = AcError.General("Unexpected error: ${e.javaClass.simpleName}")) }
                ERROR_RETRY_MS
            }
            if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** One step of the state machine. Returns how long to wait before the next one (null = until woken). */
    internal suspend fun cycle(): Long? {
        val device = activeDevice()
        if (device == null) {
            currentDeviceId = null
            connected = false
            _state.value = DashboardState(phase = ConnectionPhase.NO_DEVICE)
            return null
        }
        if (device.deviceId != currentDeviceId) {
            currentDeviceId = device.deviceId
            connected = false
            failures = 0
            // Renewal state belongs to one AC: never carry it to another.
            renewKey = false
            reprovisioned = false
            _state.value = DashboardState(phase = ConnectionPhase.CHECKING_NETWORK, device = device,
                host = device.lastKnownHost.ifEmpty { null }, profile = ModelProfiles.forUplusId(device.uPlusId))
        } else {
            _state.update { it.copy(device = device, profile = ModelProfiles.forUplusId(device.uPlusId)) }
        }
        if (!foreground) return null

        val adapter = registry.forId(device.protocolId)
        if (adapter == null) {
            connected = false
            setPhase(ConnectionPhase.UNSUPPORTED, AcError.NotSupported("This type of air conditioner"))
            return null
        }

        if (!connected) setPhase(ConnectionPhase.CHECKING_NETWORK)
        val net = network.snapshot()
        if (!net.wifiEnabled || !net.onWifi) {
            connected = false
            setPhase(ConnectionPhase.WIFI_UNAVAILABLE, if (!net.wifiEnabled) AcError.WifiOff else AcError.NotOnWifi)
            return NETWORK_RETRY_MS
        }
        val key = when (adapter.descriptor.credentialMode) {
            CredentialMode.NONE -> ""
            // Missing is fine: connect() provisions it (from the AC, or from the account) once the AC is found.
            CredentialMode.AUTOMATIC, CredentialMode.ACCOUNT -> store.localKey(device.deviceId)
        }
        return lan.hold("ac session") { sessionLock.withLock {
            val usable = key?.takeIf { it.isNotEmpty() || adapter.descriptor.credentialMode == CredentialMode.NONE }
            if (connected && usable != null) poll(adapter, device, usable) else connect(adapter, device, usable, net)
        } }
    }

    private suspend fun connect(adapter: AcProtocolAdapter, device: PairedDevice, savedKey: String?, net: NetworkSnapshot): Long? {
        connected = false
        setPhase(if (_state.value.status != null) ConnectionPhase.RECONNECTING else ConnectionPhase.SEARCHING)
        val host = locate(adapter, device, net)
        if (host == null) {
            failures++
            setPhase(ConnectionPhase.OFFLINE, if (device.lastKnownHost.isEmpty()) AcError.AcNotFound else AcError.Unreachable(device.lastKnownHost))
            return backoff()
        }
        _state.update { it.copy(phase = ConnectionPhase.AC_FOUND, host = host, error = null) }
        val renewing = renewKey && savedKey != null
        setPhase(if (renewing) ConnectionPhase.RENEWING_KEY else ConnectionPhase.AUTHENTICATING)
        if (savedKey != null && !renewKey) {
            return handleRead(adapter, device, host, adapter.client.readStatus(endpoint(device, host), savedKey, device.keyVersion.takeIf { it > 0 }))
        }
        val fresh = when (val p = adapter.provision(endpoint(device, host))) {
            is ProvisionResult.Success -> p.credential
            is ProvisionResult.Failure -> {
                // The old key (if any) is kept: it is only replaced once a new one is proven.
                return when (p.error) {
                    // Nothing improves without the user: wait until woken (signing in wakes the loop).
                    is AcError.AccountRequired, is AcError.AccountRejected -> { setPhase(ConnectionPhase.NEEDS_ACCOUNT, p.error); null }
                    // No internet: says nothing about the account or the key; retried with backoff, no prompt.
                    is AcError.CloudUnavailable -> { failures++; setPhase(ConnectionPhase.ERROR, p.error); backoff() }
                    else -> { failures++; setPhase(ConnectionPhase.AUTH_FAILED, p.error); backoff() }
                }
            }
        }
        // Prove the new key on the AC itself before it replaces anything. No version expectation: it is a new key.
        val read = adapter.client.readStatus(endpoint(device, host), fresh, null)
        if (read is ReadResult.Success) {
            if (!store.saveCredential(device.deviceId, fresh)) {
                setPhase(ConnectionPhase.ERROR, AcError.General("The phone's secure storage refused to save the AC's key."))
                return backoff()
            }
            log(if (renewing) "key renewed automatically and verified by the AC" else "credential provisioned automatically and verified by the AC")
            renewKey = false
        } else if ((read as ReadResult.Failure).error.let { it is AcError.KeyInvalid || it is AcError.KeyRotated }) {
            // A freshly issued key the AC does not accept: stop renewing until the user retries, so
            // later wake-ups (resume, network changes) do not keep asking the cloud for keys.
            reprovisioned = true
            renewKey = false
        }
        return handleRead(adapter, device, host, read)
    }

    private suspend fun poll(adapter: AcProtocolAdapter, device: PairedDevice, key: String): Long? {
        val host = _state.value.host ?: device.lastKnownHost
        return handleRead(adapter, device, host, adapter.client.readStatus(endpoint(device, host), key, device.keyVersion.takeIf { it > 0 }))
    }

    private suspend fun handleRead(adapter: AcProtocolAdapter, device: PairedDevice, host: String, read: ReadResult): Long? = when (read) {
        is ReadResult.Success -> {
            connected = true
            failures = 0
            reprovisioned = false
            if (device.keyVersion != read.keyVersion || device.lastKnownHost != host) {
                store.update(device.copy(keyVersion = read.keyVersion, lastKnownHost = host))
            }
            val profile = ModelProfiles.forUplusId(device.uPlusId)
            val caps = adapter.capabilities(device, read.status)
            _state.update {
                it.copy(
                    phase = if (read.status.writable) ConnectionPhase.CONNECTED else ConnectionPhase.UNSUPPORTED,
                    error = if (read.status.writable) null else AcError.UnsupportedLayout(read.status.reportLength),
                    host = host, status = read.status, capabilities = caps, profile = profile,
                    lastUpdateEpochMs = read.status.receivedAtEpochMs
                )
            }
            pollIntervalMs
        }
        is ReadResult.Failure -> {
            val err = read.error
            log("read failed: ${err.javaClass.simpleName}")
            when (err) {
                is AcError.KeyInvalid, is AcError.KeyRotated ->
                    if (adapter.descriptor.credentialMode != CredentialMode.NONE && !reprovisioned) {
                        // The AC itself says the key is wrong or has changed (never inferred from a network
                        // failure): fetch a fresh key (from the AC or the account), once per success.
                        log("saved key rejected by the AC (${err.javaClass.simpleName}); renewing automatically")
                        reprovisioned = true
                        renewKey = true
                        connected = false
                        setPhase(ConnectionPhase.RENEWING_KEY)
                        RECONNECT_DELAY_MS
                    } else {
                        connected = false
                        setPhase(ConnectionPhase.AUTH_FAILED, err)
                        null // nothing improves until the user changes the key or re-pairs
                    }
                is AcError.HandshakeRefused -> {
                    connected = false
                    setPhase(ConnectionPhase.AUTH_FAILED, err)
                    null
                }
                is AcError.ProtocolMismatch -> {
                    connected = false
                    failures++
                    setPhase(ConnectionPhase.UNSUPPORTED, err)
                    backoff()
                }
                is AcError.SessionBusy, is AcError.Timeout -> {
                    failures++
                    setPhase(ConnectionPhase.ERROR, err)
                    if (failures >= 3) connected = false
                    backoff()
                }
                else -> {
                    // Unreachable or refused: the address may have changed. Rediscover next cycle.
                    val wasConnected = connected
                    connected = false
                    failures++
                    setPhase(if (wasConnected) ConnectionPhase.RECONNECTING else ConnectionPhase.OFFLINE, err)
                    if (wasConnected) RECONNECT_DELAY_MS else backoff()
                }
            }
        }
    }

    /**
     * Finds the device's current address by its device ID. The saved address is tried first; if it
     * no longer answers as this device (DHCP moved it, or another device took the address) the subnet
     * is searched. A saved address that ignores discovery is still tried, because the protocol
     * handshake itself verifies the device ID.
     */
    private suspend fun locate(adapter: AcProtocolAdapter, device: PairedDevice, net: NetworkSnapshot): String? {
        val discovery = adapter.discovery
        val saved = device.lastKnownHost.ifEmpty { null }
        if (saved != null) {
            val info = discovery.query(saved)
            if (info != null && info.deviceId.equals(device.deviceId, ignoreCase = true)) return saved
        }
        val local = net.local
        if (local != null) {
            val found = discovery.scan(local).firstOrNull { it.deviceId.equals(device.deviceId, ignoreCase = true) }
            if (found != null) {
                if (found.host != device.lastKnownHost || found.uPlusId != device.uPlusId) {
                    log("device moved to ${found.host}")
                    store.update(device.copy(lastKnownHost = found.host, port = found.port,
                        uPlusId = found.uPlusId.ifEmpty { device.uPlusId }, firmware = found.firmware.ifEmpty { device.firmware }))
                }
                return found.host
            }
        }
        return saved
    }

    /**
     * Sends a command and reports the outcome the appliance confirmed. The dashboard only changes
     * when a status report read back from the AC shows the new state.
     */
    suspend fun execute(command: AcCommand): CommandOutcome {
        val snap = _state.value
        val device = snap.device ?: return fail(command, AcError.General("No AC is selected."))
        val status = snap.status ?: return fail(command, AcError.General("The AC's current state has not been read yet."))
        val caps = snap.capabilities ?: return fail(command, AcError.UnsupportedLayout(status.reportLength))
        if (!connected) return fail(command, AcError.General("Not connected to the AC yet. Wait for \"Connected locally\"."))
        val adapter = registry.forId(device.protocolId) ?: return fail(command, AcError.NotSupported("This type of air conditioner"))
        adapter.validate(command, caps, status)?.let { return fail(command, it) }
        val key = if (adapter.descriptor.credentialMode == CredentialMode.NONE) ""
        else store.localKey(device.deviceId) ?: return fail(command, AcError.KeyMissing)
        val host = snap.host ?: device.lastKnownHost

        _state.update { it.copy(commandInFlight = command) }
        val outcome = try {
            lan.hold("ac command") { sessionLock.withLock {
                // The selected AC may have changed while waiting for the lock: never send to another unit.
                if (_state.value.device?.deviceId != device.deviceId) CommandOutcome.Failed(AcError.General("The selected AC changed. Try again."))
                else adapter.client.execute(endpoint(device, host), key, command, device.keyVersion.takeIf { it > 0 })
            } }
        } finally {
            _state.update { it.copy(commandInFlight = null) }
        }
        when (outcome) {
            is CommandOutcome.Confirmed -> {
                applyStatus(adapter, device, outcome.status)
                recordVerified(device.deviceId, command)
                _feedback.tryEmit(CommandFeedback.Applied(command))
            }
            is CommandOutcome.Rejected -> {
                outcome.status?.let { applyStatus(adapter, device, it) }
                _feedback.tryEmit(CommandFeedback.Rejected(command, outcome.reason))
            }
            is CommandOutcome.Failed -> {
                when (outcome.error) {
                    is AcError.KeyInvalid, is AcError.KeyRotated ->
                        if (adapter.descriptor.credentialMode != CredentialMode.NONE && !reprovisioned) {
                            // The key changed while in use: renew it now rather than waiting for the next poll.
                            reprovisioned = true
                            renewKey = true
                            connected = false
                            setPhase(ConnectionPhase.RENEWING_KEY)
                        } else {
                            connected = false
                            setPhase(ConnectionPhase.AUTH_FAILED, outcome.error)
                        }
                    is AcError.HandshakeRefused -> {
                        connected = false
                        setPhase(ConnectionPhase.AUTH_FAILED, outcome.error)
                    }
                    is AcError.Unreachable, is AcError.ServiceUnavailable, is AcError.DisconnectedDuringCommand -> connected = false
                    else -> {}
                }
                _feedback.tryEmit(CommandFeedback.Failed(command, outcome.error))
            }
        }
        // Re-sync shortly after every command so related changes made by the AC itself show up.
        scope.launch {
            kotlinx.coroutines.delay(POST_COMMAND_REFRESH_MS)
            wakeUp()
        }
        return outcome
    }

    /**
     * Pairs automatically during setup: obtains the credential (from the AC or the signed-in account)
     * and proves it belongs to this AC by reading the AC's status with it over the LAN. Nothing is
     * stored here; the caller saves it with the device.
     */
    suspend fun autoPair(protocolId: String, endpoint: AcEndpoint): Pair<String, ReadResult> {
        val adapter = registry.forId(protocolId)
            ?: return "" to ReadResult.Failure(AcError.NotSupported("This type of air conditioner"))
        if (adapter.descriptor.credentialMode == CredentialMode.NONE) {
            return "" to lan.hold("ac pairing") { sessionLock.withLock { adapter.client.readStatus(endpoint, "", null) } }
        }
        // Provision outside the session lock: an account fetch is a cloud round trip and must not hold up polling.
        return when (val p = adapter.provision(endpoint)) {
            is ProvisionResult.Success -> p.credential to lan.hold("ac pairing") { sessionLock.withLock { adapter.client.readStatus(endpoint, p.credential, null) } }
            is ProvisionResult.Failure -> "" to ReadResult.Failure(p.error)
        }
    }

    private fun applyStatus(adapter: AcProtocolAdapter, device: PairedDevice, status: AcStatus) {
        _state.update {
            it.copy(
                status = status,
                capabilities = adapter.capabilities(device, status),
                lastUpdateEpochMs = status.receivedAtEpochMs
            )
        }
    }

    private suspend fun recordVerified(deviceId: String, command: AcCommand) {
        val device = store.devices.value.firstOrNull { it.deviceId == deviceId } ?: return
        val kind = PairedDevice.controlKind(command)
        if (kind !in device.verifiedControls) store.update(device.copy(verifiedControls = device.verifiedControls + kind))
    }

    private fun fail(command: AcCommand, error: AcError): CommandOutcome {
        _feedback.tryEmit(CommandFeedback.Failed(command, error))
        return CommandOutcome.Failed(error)
    }

    private fun activeDevice(): PairedDevice? {
        val id = store.activeDeviceId.value ?: return null
        return store.devices.value.firstOrNull { it.deviceId == id }
    }

    private fun setPhase(phase: ConnectionPhase, error: AcError? = null) {
        _state.update { it.copy(phase = phase, error = error) }
    }

    private fun backoff(): Long = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (failures - 1).coerceIn(0, 5))

    private fun endpoint(device: PairedDevice, host: String) = AcEndpoint(host, device.port, device.deviceId)

    companion object {
        const val DEFAULT_POLL_MS = 10_000L
        const val MIN_POLL_MS = 5_000L
        const val MAX_POLL_MS = 60_000L
        const val NETWORK_RETRY_MS = 5_000L
        const val RECONNECT_DELAY_MS = 1_000L
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val ERROR_RETRY_MS = 10_000L
        const val POST_COMMAND_REFRESH_MS = 2_000L
    }
}
