package com.roomac.controller.netscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomac.controller.devicecontrol.Command
import com.roomac.controller.devicecontrol.ControlResult
import com.roomac.controller.devicecontrol.Credentials
import com.roomac.controller.devicecontrol.Resolution
import com.roomac.controller.devicecontrol.defaultControllers
import com.roomac.controller.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for one device's control screen (feature flag DEVICE_CONTROL). */
data class DeviceControlUi(
    val checking: Boolean = false,
    val resolution: Resolution? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** RTSP address with the camera's credentials in it: shown to the player only, never logged. */
    val streamUri: String? = null,
    val image: ControlResult.Image? = null
)

class DeviceControlViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = defaultControllers()
    private val _ui = MutableStateFlow(DeviceControlUi())
    val ui: StateFlow<DeviceControlUi> = _ui.asStateFlow()
    private var device: NetworkDevice? = null
    private var job: Job? = null

    fun open(target: NetworkDevice, credentials: Credentials? = null) {
        if (job?.isActive == true) return
        if (credentials == null && device?.ip == target.ip && _ui.value.resolution != null) return
        closeConnection()
        device = target
        _ui.value = DeviceControlUi(checking = true)
        job = viewModelScope.launch {
            val r = registry.resolve(target, credentials)
            DiagnosticsLogger.i("DeviceControl", "${target.ip}: " + when (r) {
                is Resolution.Controllable -> "controllable via ${r.connection.protocol}"
                is Resolution.NeedsCredentials -> "${r.protocol} needs credentials"
                is Resolution.ViewOnly -> "view only (${r.reasons.joinToString("; ")})"
            })
            _ui.value = DeviceControlUi(resolution = r)
        }
    }

    fun perform(command: Command) {
        val c = (_ui.value.resolution as? Resolution.Controllable)?.connection ?: return
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val r = try { c.perform(command) } catch (e: Exception) { ControlResult.Failed("No answer from the device (${e.javaClass.simpleName})") }
            _ui.value = when (r) {
                ControlResult.Done -> _ui.value.copy(busy = false, message = "Sent")
                is ControlResult.Failed -> _ui.value.copy(busy = false, message = r.message)
                is ControlResult.Stream -> _ui.value.copy(busy = false, streamUri = r.uri)
                is ControlResult.Image -> _ui.value.copy(busy = false, image = r, message = "Scanned ${r.bytes.size / 1024} KB")
            }
        }
    }

    private fun closeConnection() {
        (_ui.value.resolution as? Resolution.Controllable)?.connection?.close()
    }

    override fun onCleared() = closeConnection()
}
