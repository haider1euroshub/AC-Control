package com.roomac.controller.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.RoomAcApplication
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.domain.model.AcBrands
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.permissions.Preflight
import com.roomac.controller.permissions.PreflightReport
import com.roomac.controller.protocol.ExternalProvisioning
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.CloudAccountProvider
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.DiscoveredDevice
import com.roomac.controller.protocol.ReadResult
import com.roomac.controller.protocol.normalizeDeviceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingStep(val title: String) {
    FIND("1. Find the AC"),
    CONNECT("2. Connect"),
    NAME("3. Name and save")
}

/** Plain-language name for a found unit: the brand it reports, else the brand its protocol implies. */
fun DiscoveredDevice.brandName(): String =
    AcBrands.displayNameFor(brand) ?: AcBrands.forProtocol(protocolId).firstOrNull()?.displayName ?: "Air conditioner"

data class PairingUi(
    val step: PairingStep = PairingStep.FIND,
    val scanning: Boolean = false,
    val scanFinished: Boolean = false,
    val networkError: AcError? = null,
    val vpnActive: Boolean = false,
    val found: List<DiscoveredDevice> = emptyList(),
    val selected: DiscoveredDevice? = null,
    val existingIds: Set<String> = emptySet(),
    /** Set when the selected AC's key comes from a manufacturer account the user has not signed in to. */
    val signInProviderId: String? = null,
    /** The sign-in form is for linking another account, not the first one. */
    val addingAccount: Boolean = false,
    /** The in-app sign-up form is shown in place of the sign-in form. */
    val creatingAccount: Boolean = false,
    val connecting: Boolean = false,
    val verifiedStatus: AcStatus? = null,
    val connectError: AcError? = null,
    /** Credential obtained automatically; held only until the device is saved. */
    val credential: String? = null,
    val keyVersion: Int = 0,
    /** Model as reported by the AC or the manufacturer account. Never typed by the user. */
    val model: String = "",
    val name: String = "",
    val room: String = "",
    val saved: Boolean = false
)

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = RoomAcApplication.from(application)
    private val _ui = MutableStateFlow(PairingUi(existingIds = app.store.devices.value.map { it.deviceId }.toSet()))
    val ui: StateFlow<PairingUi> = _ui.asStateFlow()
    private var scanJob: Job? = null
    /** The last checklist verdict; null before the first. A search starts by itself on each not-ready → ready change. */
    private var lastReady: Boolean? = null

    /** Last known address of the selected AC, to check the phone is on the same network. */
    val savedAcHost: String?
        get() = app.store.activeDeviceId.value?.let { id -> app.store.devices.value.firstOrNull { it.deviceId == id } }?.lastKnownHost?.ifEmpty { null }

    /** Called with every checklist result: starts the search once everything required is ready. */
    fun onPreflight(report: PreflightReport) {
        val s = _ui.value
        val start = Preflight.shouldAutoScan(lastReady, report.ready, s.scanning, s.step == PairingStep.FIND)
        lastReady = report.ready
        if (start) scan()
    }

    fun scan() {
        scanJob?.cancel()
        val net = app.network.snapshot()
        val local = net.local
        if (!net.wifiEnabled || !net.onWifi || local == null) {
            _ui.update { it.copy(networkError = if (!net.wifiEnabled) AcError.WifiOff else AcError.NotOnWifi, scanning = false) }
            return
        }
        _ui.update { it.copy(networkError = null, scanning = true, scanFinished = false, found = emptyList(), vpnActive = app.network.vpnActive()) }
        scanJob = viewModelScope.launch {
            val results = app.discovery.scan(local) { d -> _ui.update { s -> s.copy(found = (s.found + d).distinctBy { it.deviceId.uppercase() }) } }.toMutableList()
            app.debugProbeHost?.let { host ->
                val direct = app.discovery.query(host)
                DiagnosticsLogger.i("Pairing", "debug direct query to $host: ${direct?.let { "${it.deviceId} (${it.protocolId})" } ?: "no answer"}")
                direct?.let { results += it }
            }
            DiagnosticsLogger.i("Pairing", "scan found ${results.size} appliance(s): ${results.groupingBy { it.protocolId }.eachCount()}")
            _ui.update { it.copy(scanning = false, scanFinished = true, found = (it.found + results).distinctBy { d -> d.deviceId.uppercase() }) }
        }
    }

    fun provider(id: String?): CloudAccountProvider? = id?.let { app.accounts[it] }

    /** The step [d]'s manufacturer only allows in its own app, if any. */
    fun provisioningFor(d: DiscoveredDevice): ExternalProvisioning? = app.registry.forId(d.protocolId)?.descriptor?.externalProvisioning

    /** Wi-Fi setup steps of every supported brand, for the "nothing found" help. Brand name to step. */
    fun wifiSetupSteps(): List<Pair<String, ExternalProvisioning>> = app.registry.all.mapNotNull { a ->
        a.descriptor.externalProvisioning?.takeIf { it.onlyForNewWifiSetup }?.let { (AcBrands.displayNameFor(a.descriptor.defaultBrand) ?: a.descriptor.defaultBrand) to it }
    }

    /**
     * The user came back to RoomAC (for example from a manufacturer's app after setting an AC up): if the
     * last search found nothing, search again by itself.
     */
    fun onReturn() {
        val s = _ui.value
        if (s.step == PairingStep.FIND && s.scanFinished && s.found.isEmpty()) scan()
    }

    /** The account provider that issues keys for [d]'s protocol, if any. */
    fun accountProviderFor(d: DiscoveredDevice): String? = app.accountFor(d.protocolId)?.id

    /** The user picked an AC: everything from here is automatic unless a one-time sign-in is needed. */
    fun select(d: DiscoveredDevice) {
        scanJob?.cancel()
        val existing = app.store.devices.value.firstOrNull { it.deviceId.equals(d.deviceId, ignoreCase = true) }
        _ui.update {
            it.copy(
                selected = d, step = PairingStep.CONNECT,
                model = existing?.modelNumber?.ifEmpty { null } ?: d.model.takeIf { m -> m.isNotEmpty() && !m.equals(d.brand, ignoreCase = true) } ?: "",
                room = existing?.room ?: "",
                name = existing?.name ?: d.reportedName.takeIf { n -> n.isNotBlank() && !n.equals(d.deviceId, ignoreCase = true) } ?: "",
                credential = null, verifiedStatus = null, connectError = null, signInProviderId = null
            )
        }
        connect()
    }

    /** Obtains the AC's key (from the AC, or from the signed-in account) and proves it by reading the AC's status. */
    fun connect() {
        val d = _ui.value.selected ?: return
        val adapter = app.registry.forId(d.protocolId)
        if (adapter?.descriptor?.credentialMode == CredentialMode.ACCOUNT) {
            val account = app.accountFor(d.protocolId)
            if (account == null || !account.isSignedIn()) {
                _ui.update { it.copy(signInProviderId = adapter.descriptor.accountProviderId, connecting = false, creatingAccount = false) }
                return
            }
        }
        _ui.update { it.copy(connecting = true, connectError = null, verifiedStatus = null, credential = null, signInProviderId = null, addingAccount = false, creatingAccount = false) }
        viewModelScope.launch {
            val (credential, read) = app.coordinator.autoPair(d.protocolId, AcEndpoint(d.host, d.port, d.deviceId))
            when (read) {
                is ReadResult.Success -> {
                    _ui.update { it.copy(connecting = false, verifiedStatus = read.status, credential = credential, keyVersion = read.keyVersion) }
                    fillFromAccount(d)
                }
                is ReadResult.Failure -> {
                    val needsSignIn = read.error is AcError.AccountRequired || read.error is AcError.AccountRejected
                    _ui.update {
                        it.copy(
                            connecting = false, connectError = read.error,
                            signInProviderId = if (needsSignIn) adapter?.descriptor?.accountProviderId else null
                        )
                    }
                }
            }
            // userMessage names the failed step and the service's reason code; it never holds a password, token or key.
            DiagnosticsLogger.i("Pairing", "connect ${d.deviceId}: ${if (read is ReadResult.Success) "ok" else (read as ReadResult.Failure).error.let { "${it.javaClass.simpleName}: ${it.userMessage}" }}")
        }
    }

    /** Uses the name and model the owner gave the AC in the manufacturer's app, when the account lists it. */
    private suspend fun fillFromAccount(d: DiscoveredDevice) {
        val account = app.accountFor(d.protocolId) ?: return
        val match = account.devices().firstOrNull { normalizeDeviceId(it.deviceId) == normalizeDeviceId(d.deviceId) } ?: return
        _ui.update {
            it.copy(
                name = it.name.ifEmpty { match.name },
                model = it.model.ifEmpty { match.model }
            )
        }
    }

    /** Shows the sign-in form for an additional account (the AC may belong to a different one). */
    fun signInAnotherAccount() {
        val d = _ui.value.selected ?: return
        _ui.update { it.copy(signInProviderId = app.registry.forId(d.protocolId)?.descriptor?.accountProviderId, addingAccount = true) }
    }

    /** Shows the sign-up form for a new account; once it is made (and signed in), [connect] carries on. */
    fun createAccount() = _ui.update { it.copy(creatingAccount = true) }
    fun cancelCreateAccount() = _ui.update { it.copy(creatingAccount = false) }

    fun toNameStep() = _ui.update { it.copy(step = PairingStep.NAME) }
    fun setName(v: String) = _ui.update { it.copy(name = v) }
    fun setRoom(v: String) = _ui.update { it.copy(room = v) }

    fun back(): Boolean {
        if (_ui.value.creatingAccount) { cancelCreateAccount(); return true }
        val s = _ui.value.step
        if (s == PairingStep.FIND) return false
        _ui.update { it.copy(step = PairingStep.entries[s.ordinal - 1]) }
        if (s == PairingStep.CONNECT) scan()
        return true
    }

    /** Saves the device with the key that was just proven against it. */
    fun save() {
        val s = _ui.value
        val d = s.selected ?: return
        val credential = s.credential ?: return
        val existing = app.store.devices.value.firstOrNull { it.deviceId.equals(d.deviceId, ignoreCase = true) }
        val device = PairedDevice(
            deviceId = d.deviceId,
            name = s.name.trim().ifEmpty { "${d.brandName()} AC" },
            room = s.room.trim(),
            protocolId = d.protocolId,
            brand = d.brand,
            uPlusId = d.uPlusId,
            modelNumber = ModelProfiles.normalizeModel(s.model),
            lastKnownHost = d.host,
            port = d.port,
            firmware = d.firmware,
            verifiedControls = existing?.verifiedControls ?: emptySet(),
            addedAtEpochMs = existing?.addedAtEpochMs ?: System.currentTimeMillis()
        )
        app.store.upsert(device)
        if (credential.isNotEmpty() && !app.store.setLocalKey(d.deviceId, credential)) {
            _ui.update { it.copy(connectError = AcError.General("The phone's secure storage refused to save the AC's key.")) }
            return
        }
        // setLocalKey resets the version; restore the one just verified.
        app.store.devices.value.firstOrNull { it.deviceId == d.deviceId.uppercase() }?.let { app.store.upsert(it.copy(keyVersion = s.keyVersion)) }
        app.store.setActive(d.deviceId.uppercase())
        app.coordinator.reconnect()
        DiagnosticsLogger.i("Pairing", "saved device ${d.deviceId} (${d.protocolId})")
        _ui.update { it.copy(saved = true) }
    }
}
