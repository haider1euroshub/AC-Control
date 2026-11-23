package com.roomac.controller.netscan

/**
 * Infers a [DeviceType] from what a device actually advertised, strongest evidence first: being the
 * gateway, mDNS service types, UPnP device types, its hostname, open ports, then its MAC vendor. Every
 * result carries the evidence it came from; with no evidence the answer is [DeviceType.UNKNOWN].
 */
object DeviceClassifier {

    data class Result(val type: DeviceType, val reason: String?)

    /** Well-known ports the sweep checks, and what an open one suggests. */
    object Ports {
        const val HTTP = 80
        const val HTTPS = 443
        const val RTSP = 554
        const val IPP = 631
        const val CAST = 8009
        const val JETDIRECT = 9100
        /** iOS "lockdown" service; open on iPhones and iPads. */
        const val IOS_LOCKDOWN = 62078
        val ALL = listOf(HTTP, HTTPS, RTSP, IPP, CAST, JETDIRECT, IOS_LOCKDOWN)
    }

    private val UNKNOWN = Result(DeviceType.UNKNOWN, null)

    /** mDNS service type to the type it implies on its own. Types needing their TXT record are handled in [fromMdns]. */
    private val SERVICE_TYPES = mapOf(
        "_ipp._tcp" to DeviceType.PRINTER,
        "_ipps._tcp" to DeviceType.PRINTER,
        "_printer._tcp" to DeviceType.PRINTER,
        "_pdl-datastream._tcp" to DeviceType.PRINTER,
        "_uscan._tcp" to DeviceType.PRINTER,
        "_uscans._tcp" to DeviceType.PRINTER,
        "_scanner._tcp" to DeviceType.PRINTER,
        "_rtsp._tcp" to DeviceType.CAMERA,
        "_androidtvremote2._tcp" to DeviceType.TV,
        "_amzn-wplay._tcp" to DeviceType.MEDIA,
        "_spotify-connect._tcp" to DeviceType.SPEAKER,
        "_sonos._tcp" to DeviceType.SPEAKER,
        "_raop._tcp" to DeviceType.SPEAKER,
        "_apple-mobdev2._tcp" to DeviceType.PHONE,
        "_smb._tcp" to DeviceType.COMPUTER,
        "_afpovertcp._tcp" to DeviceType.COMPUTER,
        "_workstation._tcp" to DeviceType.COMPUTER,
        "_hap._tcp" to DeviceType.IOT,
        "_matter._tcp" to DeviceType.IOT,
        "_hue._tcp" to DeviceType.IOT,
        "_esphomelib._tcp" to DeviceType.IOT,
        "_miio._udp" to DeviceType.IOT
    )

    /** Specific services win over generic ones when a device advertises several. */
    private val SERVICE_PRIORITY = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_hap._tcp", "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp",
        "_rtsp._tcp", "_androidtvremote2._tcp", "_amzn-wplay._tcp", "_sonos._tcp", "_spotify-connect._tcp", "_raop._tcp",
        "_apple-mobdev2._tcp", "_uscan._tcp", "_uscans._tcp", "_scanner._tcp", "_matter._tcp", "_hue._tcp", "_esphomelib._tcp", "_miio._udp",
        "_smb._tcp", "_afpovertcp._tcp", "_workstation._tcp"
    )

    fun classify(device: NetworkDevice, ouiVendor: OuiVendor?): Result {
        if (device.isGateway) return Result(DeviceType.ROUTER, "default gateway of this Wi-Fi")
        fromMdns(device.mdnsServices)?.let { return it }
        // Only devices that answered an ONVIF NetworkVideoTransmitter probe get XAddrs (cameras and video recorders).
        if (device.onvifXAddrs.isNotEmpty()) return Result(DeviceType.CAMERA, "answers ONVIF discovery as a video transmitter (camera or recorder)")
        fromSsdp(device.ssdpTypes, device.ssdpServer)?.let { return it }
        fromHostname(device.hostname ?: device.name)?.let { return it }
        fromPorts(device.openPorts)?.let { return it }
        ouiVendor?.typeHint?.let { return Result(it, "made by ${ouiVendor.name}") }
        return UNKNOWN
    }

    fun fromMdns(services: Map<String, Map<String, String>>): Result? {
        for (type in SERVICE_PRIORITY) {
            val txt = services[type] ?: continue
            val result = when (type) {
                "_googlecast._tcp" -> cast(txt)
                "_airplay._tcp" -> airplay(txt)
                "_hap._tcp" -> homeKit(txt)
                else -> SERVICE_TYPES[type]
            } ?: continue
            return Result(result, "advertises $type")
        }
        return null
    }

    /** Cast "ca" capability bitmask: bit 0 is video out. Google Home / Nest speakers have audio only. */
    private fun cast(txt: Map<String, String>): DeviceType {
        val ca = txt["ca"]?.toIntOrNull() ?: return DeviceType.MEDIA
        return if (ca and 1 != 0) DeviceType.TV else DeviceType.SPEAKER
    }

    /** AirPlay "model": AppleTVx,y, AudioAccessoryx,y (HomePod), Mac models; TVs from other makers advertise AirPlay too. */
    private fun airplay(txt: Map<String, String>): DeviceType? {
        val model = txt["model"].orEmpty()
        return when {
            model.startsWith("AppleTV") -> DeviceType.TV
            model.startsWith("AudioAccessory") -> DeviceType.SPEAKER
            model.startsWith("Mac") || model.startsWith("iMac") -> DeviceType.COMPUTER
            model.startsWith("iPhone") || model.startsWith("iPad") -> DeviceType.PHONE
            else -> null // unknown AirPlay receiver: let other evidence decide
        }
    }

    /** HomeKit accessory category ("ci"): 17 IP camera, 18 video doorbell, 31 television; anything else is a smart-home accessory. */
    private fun homeKit(txt: Map<String, String>): DeviceType = when (txt["ci"]?.toIntOrNull()) {
        17, 18 -> DeviceType.CAMERA
        31 -> DeviceType.TV
        else -> DeviceType.IOT
    }

    fun fromSsdp(types: Set<String>, server: String?): Result? {
        val lower = types.map { it.lowercase() }
        fun has(fragment: String) = lower.firstOrNull { fragment in it }
        has(":device:internetgatewaydevice:")?.let { return Result(DeviceType.ROUTER, "UPnP $it") }
        has(":device:printer:")?.let { return Result(DeviceType.PRINTER, "UPnP $it") }
        has("roku:ecp")?.let { return Result(DeviceType.MEDIA, "UPnP $it") }
        if (server?.contains("sonos", ignoreCase = true) == true) return Result(DeviceType.SPEAKER, "UPnP server \"$server\"")
        has("dial-multiscreen-org:service:dial")?.let { return Result(DeviceType.TV, "supports DIAL (cast-to-TV)") }
        has(":device:mediarenderer:")?.let { return Result(DeviceType.MEDIA, "UPnP $it") }
        return null
    }

    private val PHONE_NAMES = Regex("(^|[-_ ])(iphone|ipad|android|galaxy|pixel)([-_ ']|$)", RegexOption.IGNORE_CASE)
    private val COMPUTER_NAMES = Regex("^(desktop-|laptop-)|(macbook|imac|mac-?mini)", RegexOption.IGNORE_CASE)

    fun fromHostname(name: String?): Result? {
        if (name.isNullOrBlank()) return null
        if (PHONE_NAMES.containsMatchIn(name)) return Result(DeviceType.PHONE, "name \"$name\"")
        if (COMPUTER_NAMES.containsMatchIn(name)) return Result(DeviceType.COMPUTER, "name \"$name\"")
        return null
    }

    fun fromPorts(ports: Set<Int>): Result? = when {
        Ports.JETDIRECT in ports -> Result(DeviceType.PRINTER, "port 9100 (printing) open")
        Ports.IPP in ports -> Result(DeviceType.PRINTER, "port 631 (IPP printing) open")
        Ports.IOS_LOCKDOWN in ports -> Result(DeviceType.PHONE, "port 62078 (iOS) open")
        Ports.CAST in ports -> Result(DeviceType.MEDIA, "port 8009 (Cast) open")
        Ports.RTSP in ports -> Result(DeviceType.CAMERA, "port 554 (RTSP video) open")
        else -> null // 80/443 alone say nothing about what a device is
    }
}
