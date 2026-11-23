package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.DeviceType
import com.roomac.controller.netscan.NetworkDevice

/*
 * Device control (feature flag DEVICE_CONTROL, default off).
 *
 * Rule: a control is shown only after the device has answered the protocol that provides it
 * ([DeviceController.connect] confirms with a real request). Discovery hints alone never enable a
 * control. Everything here is UNIT-TESTED AGAINST FAKES ONLY: no adapter has been run against a real
 * TV, camera or scanner yet (see [DeviceController.hardwareVerified]).
 */

enum class RemoteKey(val label: String) {
    PLAY("Play"), PAUSE("Pause"), PLAY_PAUSE("Play/Pause"), STOP("Stop"),
    VOLUME_UP("Volume +"), VOLUME_DOWN("Volume −"), MUTE("Mute"), POWER_OFF("Power off"),
    HOME("Home"), BACK("Back"), UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"), SELECT("OK")
}

enum class PtzMove { UP, DOWN, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT }

sealed interface Command {
    data class Key(val key: RemoteKey) : Command
    data class SetVolume(val percent: Int) : Command
    data class Ptz(val move: PtzMove) : Command
    data object StreamUri : Command
    data class Scan(val color: Boolean = true, val dpi: Int = 300) : Command
}

sealed class ControlResult {
    data object Done : ControlResult()
    data class Failed(val message: String) : ControlResult()
    data class Stream(val uri: String) : ControlResult()
    data class Image(val bytes: ByteArray, val mimeType: String) : ControlResult()
}

data class Capabilities(
    val keys: Set<RemoteKey> = emptySet(),
    val volumeLevel: Boolean = false,
    val ptz: Boolean = false,
    val stream: Boolean = false,
    val scan: Boolean = false
) {
    val any: Boolean get() = keys.isNotEmpty() || volumeLevel || ptz || stream || scan
}

/** Username/password some devices need (ONVIF cameras). Held in memory only, never saved. */
data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username)"
}

/** A live, confirmed control channel to one device. */
interface Connection {
    val protocol: String
    val capabilities: Capabilities
    /** Short state the device reported (e.g. "Volume 30%", "Netflix"), if any. */
    val status: String?
    suspend fun perform(command: Command): ControlResult
    fun close() {}
}

sealed class ConnectResult {
    data class Connected(val connection: Connection) : ConnectResult()
    data class NeedsCredentials(val protocol: String, val reason: String) : ConnectResult()
    data class Unavailable(val reason: String) : ConnectResult()
}

/** One local-control protocol. Each is its own unit-tested adapter. */
interface DeviceController {
    val protocol: String
    /** False until someone has run this adapter against a real device and recorded it in docs/STATUS.md. */
    val hardwareVerified: Boolean get() = false
    /** Cheap check on discovery evidence: is it worth trying this protocol on [device]? */
    fun appliesTo(device: NetworkDevice): Boolean
    /** Confirms the protocol with a real request and returns what the device actually supports. */
    suspend fun connect(device: NetworkDevice, credentials: Credentials? = null): ConnectResult
}

/** What the UI shows for a device: controls, a credentials prompt, or "view only" with the reason. */
sealed class Resolution {
    data class Controllable(val connection: Connection, val hardwareVerified: Boolean) : Resolution()
    data class NeedsCredentials(val protocol: String, val reason: String) : Resolution()
    data class ViewOnly(val reasons: List<String>) : Resolution()
}

class ControllerRegistry(private val controllers: List<DeviceController>) {

    fun candidates(device: NetworkDevice): List<DeviceController> = controllers.filter { it.appliesTo(device) }

    suspend fun resolve(device: NetworkDevice, credentials: Credentials? = null): Resolution {
        val reasons = mutableListOf<String>()
        var needs: ConnectResult.NeedsCredentials? = null
        for (c in candidates(device)) {
            val r = try {
                c.connect(device, credentials)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectResult.Unavailable("${c.protocol}: no answer (${e.javaClass.simpleName})")
            }
            when (r) {
                is ConnectResult.Connected -> if (r.connection.capabilities.any) return Resolution.Controllable(r.connection, c.hardwareVerified)
                    else { r.connection.close(); reasons += "${c.protocol}: answered, but offers nothing RoomAC can control" }
                is ConnectResult.NeedsCredentials -> needs = needs ?: r
                is ConnectResult.Unavailable -> reasons += r.reason
            }
        }
        needs?.let { return Resolution.NeedsCredentials(it.protocol, it.reason) }
        if (reasons.isEmpty()) reasons += noProtocolReason(device)
        return Resolution.ViewOnly(reasons)
    }

    private fun noProtocolReason(device: NetworkDevice): String = when (device.type) {
        DeviceType.CAMERA ->
            "This camera offers no ONVIF on the network. It is probably cloud-only: use its vendor app."
        DeviceType.TV, DeviceType.MEDIA, DeviceType.SPEAKER ->
            "No supported local-control protocol was found (Google Cast, Roku, DLNA/UPnP)."
        DeviceType.PRINTER -> "No eSCL (AirScan/Mopria) scanner service was found on this device."
        else -> "View only: RoomAC knows no local-control protocol for this device."
    }
}
