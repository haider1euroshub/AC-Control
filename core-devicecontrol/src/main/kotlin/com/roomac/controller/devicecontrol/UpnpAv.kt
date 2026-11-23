package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.NetworkDevice
import java.net.URI

/**
 * DLNA / UPnP AV (UPnP Forum AVTransport + RenderingControl, SOAP over HTTP).
 * Reads the device description from the SSDP LOCATION, finds the two services' control URLs, confirms
 * with GetTransportInfo / GetVolume, then offers Play / Pause / Stop and volume. UPnP AV defines no power
 * control, so none is offered. NOT YET TESTED ON A REAL RENDERER.
 */
class UpnpAvController(private val http: LanHttp) : DeviceController {

    override val protocol = "DLNA / UPnP AV"

    override fun appliesTo(device: NetworkDevice): Boolean =
        device.ssdpLocation != null && device.ssdpTypes.any { "mediarenderer" in it.lowercase() || "avtransport" in it.lowercase() || "renderingcontrol" in it.lowercase() }

    data class Service(val type: String, val controlUrl: String)

    override suspend fun connect(device: NetworkDevice, credentials: Credentials?): ConnectResult {
        val location = device.ssdpLocation ?: return ConnectResult.Unavailable("$protocol: no description URL")
        val desc = http.request("GET", location)
        if (desc.status != 200) return ConnectResult.Unavailable("$protocol: description HTTP ${desc.status}")
        val services = services(desc.text, location)
        val av = services.firstOrNull { it.type.contains(":service:AVTransport:") }
        val rc = services.firstOrNull { it.type.contains(":service:RenderingControl:") }
        // Confirm each service with a read-only call before offering its controls.
        val avOk = av != null && soap(av, "GetTransportInfo", "<InstanceID>0</InstanceID>").status == 200
        val volume = rc?.let { s ->
            val r = soap(s, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
            if (r.status == 200) value(r.text, "CurrentVolume")?.toIntOrNull() else null
        }
        val keys = buildSet {
            if (avOk) addAll(listOf(RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.STOP))
            if (volume != null) addAll(listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE))
        }
        if (keys.isEmpty()) return ConnectResult.Unavailable("$protocol: the renderer did not answer AVTransport or RenderingControl")
        return ConnectResult.Connected(Session(av.takeIf { avOk }, rc.takeIf { volume != null }, volume))
    }

    private inner class Session(private val av: Service?, private val rc: Service?, private var volume: Int?) : Connection {
        override val protocol = this@UpnpAvController.protocol
        private var muted = false
        override val capabilities = Capabilities(
            keys = buildSet {
                if (av != null) addAll(listOf(RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.STOP))
                if (rc != null) addAll(listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE))
            },
            volumeLevel = rc != null
        )
        override val status: String? get() = volume?.let { "Volume $it" }

        override suspend fun perform(command: Command): ControlResult {
            val reply = when (command) {
                is Command.Key -> when (command.key) {
                    RemoteKey.PLAY -> av?.let { soap(it, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
                    RemoteKey.PAUSE -> av?.let { soap(it, "Pause", "<InstanceID>0</InstanceID>") }
                    RemoteKey.STOP -> av?.let { soap(it, "Stop", "<InstanceID>0</InstanceID>") }
                    RemoteKey.VOLUME_UP -> rc?.let { setVolume(it, (volume ?: 0) + 5) }
                    RemoteKey.VOLUME_DOWN -> rc?.let { setVolume(it, (volume ?: 0) - 5) }
                    RemoteKey.MUTE -> rc?.let { s -> soap(s, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (muted) 0 else 1}</DesiredMute>").also { if (it.status == 200) muted = !muted } }
                    else -> null
                }
                is Command.SetVolume -> rc?.let { setVolume(it, command.percent) }
                else -> null
            } ?: return ControlResult.Failed("Not supported by this renderer")
            return if (reply.status == 200) ControlResult.Done else ControlResult.Failed("Renderer refused (HTTP ${reply.status}${fault(reply.text)?.let { ", $it" } ?: ""})")
        }

        private suspend fun setVolume(s: Service, target: Int): HttpReply {
            val v = target.coerceIn(0, 100)
            return soap(s, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>").also { if (it.status == 200) volume = v }
        }
    }

    private suspend fun soap(service: Service, action: String, args: String): HttpReply {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${service.type}\">$args</u:$action></s:Body></s:Envelope>"
        return http.request(
            "POST", service.controlUrl,
            mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"${service.type}#$action\""),
            body.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        /** Every <service> in the description (root and embedded devices), with absolute control URLs. */
        fun services(xml: String, location: String): List<Service> {
            val base = Regex("<URLBase>\\s*([^<]+?)\\s*</URLBase>").find(xml)?.groupValues?.get(1) ?: location
            return Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val block = m.groupValues[1]
                val type = value(block, "serviceType") ?: return@mapNotNull null
                val control = value(block, "controlURL") ?: return@mapNotNull null
                val resolved = try { URI(base).resolve(control).toString() } catch (_: Exception) { return@mapNotNull null }
                // Control URLs must stay on the device that described them.
                if (URI(resolved).host != URI(location).host) null else Service(type, resolved)
            }.toList()
        }

        fun value(xml: String, tag: String): String? =
            Regex("<(?:\\w+:)?$tag>\\s*([^<]*?)\\s*</(?:\\w+:)?$tag>").find(xml)?.groupValues?.get(1)

        private fun fault(xml: String): String? = value(xml, "errorDescription") ?: value(xml, "errorCode")?.let { "UPnP error $it" }
    }
}
