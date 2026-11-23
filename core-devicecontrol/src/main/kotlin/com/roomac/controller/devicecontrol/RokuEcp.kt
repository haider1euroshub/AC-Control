package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.NetworkDevice

/**
 * Roku External Control Protocol (Roku's published LAN REST API, port 8060).
 * Confirm: GET /query/device-info. Keys: POST /keypress/<Key>. PowerOff only on Roku TVs (is-tv=true).
 * Roku OS can restrict this under Settings → System → Advanced → Control by mobile apps; a 403 says so.
 * NOT YET TESTED ON A REAL ROKU.
 */
class RokuEcpController(private val http: LanHttp) : DeviceController {

    override val protocol = "Roku ECP"

    override fun appliesTo(device: NetworkDevice): Boolean =
        device.ssdpTypes.any { it.equals("roku:ecp", ignoreCase = true) } || device.vendor == "Roku" || 8060 in device.openPorts

    override suspend fun connect(device: NetworkDevice, credentials: Credentials?): ConnectResult {
        val base = "http://${device.ip}:$PORT"
        val reply = http.request("GET", "$base/query/device-info")
        if (reply.status == 403) return ConnectResult.Unavailable("Roku refused control: on the Roku, set Settings → System → Advanced system settings → Control by mobile apps to allow it.")
        if (reply.status != 200 || !reply.text.contains("<device-info")) return ConnectResult.Unavailable("$protocol: no device-info (HTTP ${reply.status})")
        val isTv = tag(reply.text, "is-tv")?.equals("true", ignoreCase = true) == true
        val name = tag(reply.text, "user-device-name") ?: tag(reply.text, "friendly-device-name")
        val keys = KEYS.keys.toMutableSet().apply { if (isTv) add(RemoteKey.POWER_OFF) }
        return ConnectResult.Connected(Session(base, keys, name))
    }

    private inner class Session(private val base: String, keys: Set<RemoteKey>, override val status: String?) : Connection {
        override val protocol = this@RokuEcpController.protocol
        override val capabilities = Capabilities(keys = keys)

        override suspend fun perform(command: Command): ControlResult {
            val key = (command as? Command.Key)?.key ?: return ControlResult.Failed("Roku supports remote keys only")
            val ecp = (if (key == RemoteKey.POWER_OFF) "PowerOff" else KEYS[key]) ?: return ControlResult.Failed("Roku has no ${key.label} key")
            if (key !in capabilities.keys) return ControlResult.Failed("${key.label} is not available on this Roku")
            val r = http.request("POST", "$base/keypress/$ecp")
            return when (r.status) {
                200 -> ControlResult.Done
                403 -> ControlResult.Failed("Roku refused the key: allow \"Control by mobile apps\" in the Roku's settings.")
                else -> ControlResult.Failed("Roku answered HTTP ${r.status}")
            }
        }
    }

    companion object {
        const val PORT = 8060
        /** RemoteKey -> ECP key name (Play toggles play/pause on Roku). */
        val KEYS = mapOf(
            RemoteKey.PLAY_PAUSE to "Play", RemoteKey.HOME to "Home", RemoteKey.BACK to "Back",
            RemoteKey.UP to "Up", RemoteKey.DOWN to "Down", RemoteKey.LEFT to "Left", RemoteKey.RIGHT to "Right",
            RemoteKey.SELECT to "Select", RemoteKey.VOLUME_UP to "VolumeUp", RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "VolumeMute"
        )

        internal fun tag(xml: String, name: String): String? =
            Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }
}
