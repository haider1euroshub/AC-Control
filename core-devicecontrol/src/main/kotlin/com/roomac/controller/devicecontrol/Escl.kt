package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.NetworkDevice
import kotlinx.coroutines.delay
import java.net.URI

/**
 * eSCL (AirScan / Mopria scanning) over HTTP(S), advertised as `_uscan._tcp` / `_uscans._tcp` with the
 * resource path in TXT "rs" (default "eSCL"). Confirm: GET ScannerCapabilities. Scan: POST ScanJobs
 * (whole platen, settings the scanner reported), then GET <job>/NextDocument until the page arrives.
 * NOT YET TESTED ON A REAL SCANNER.
 */
class EsclController(
    private val http: LanHttp,
    private val pollMs: Long = 1_000,
    private val maxWaitMs: Long = 120_000
) : DeviceController {

    override val protocol = "eSCL scanning"

    override fun appliesTo(device: NetworkDevice): Boolean = "_uscan._tcp" in device.mdnsServices || "_uscans._tcp" in device.mdnsServices

    data class ScannerCaps(
        val version: String, val maxWidth: Int, val maxHeight: Int,
        val colorModes: Set<String>, val resolutions: Set<Int>, val formats: Set<String>
    )

    override suspend fun connect(device: NetworkDevice, credentials: Credentials?): ConnectResult {
        val base = baseUrl(device) ?: return ConnectResult.Unavailable("$protocol: no scanner service port")
        val reply = http.request("GET", "$base/ScannerCapabilities")
        if (reply.status != 200 || !reply.text.contains("ScannerCapabilities")) return ConnectResult.Unavailable("$protocol: no capabilities (HTTP ${reply.status})")
        val caps = parseCapabilities(reply.text) ?: return ConnectResult.Unavailable("$protocol: the scanner reported no flatbed (platen) settings")
        return ConnectResult.Connected(Session(base, caps, device.model))
    }

    private inner class Session(private val base: String, private val caps: ScannerCaps, override val status: String?) : Connection {
        override val protocol = this@EsclController.protocol
        override val capabilities = Capabilities(scan = true)

        override suspend fun perform(command: Command): ControlResult {
            val scan = command as? Command.Scan ?: return ControlResult.Failed("Scanners support Scan only")
            val job = http.request("POST", "$base/ScanJobs", mapOf("Content-Type" to "text/xml"), settings(caps, scan).toByteArray(Charsets.UTF_8))
            when (job.status) {
                201 -> {}
                503 -> return ControlResult.Failed("The scanner is busy. Try again in a moment.")
                409 -> return ControlResult.Failed("The scanner refused the job (is a page on the glass and the lid closed?)")
                else -> return ControlResult.Failed("The scanner refused the job (HTTP ${job.status})")
            }
            val location = job.header("location") ?: return ControlResult.Failed("The scanner did not say where the scan is")
            val jobUrl = try { URI("$base/").resolve(location).toString().trimEnd('/') } catch (_: Exception) { return ControlResult.Failed("Bad job address from the scanner") }
            if (URI(jobUrl).host != URI(base).host) return ControlResult.Failed("The scanner pointed to another host; refused")
            var waited = 0L
            while (waited <= maxWaitMs) {
                val doc = http.request("GET", "$jobUrl/NextDocument")
                when (doc.status) {
                    200 -> return ControlResult.Image(doc.body, doc.header("content-type")?.substringBefore(';')?.trim() ?: "image/jpeg")
                    503 -> { delay(pollMs); waited += pollMs } // still scanning
                    404 -> return ControlResult.Failed("The scanner finished without a page")
                    else -> return ControlResult.Failed("The scanner answered HTTP ${doc.status}")
                }
            }
            return ControlResult.Failed("The scan did not finish within ${maxWaitMs / 1000} s")
        }
    }

    companion object {
        const val NS_SCAN = "http://schemas.hp.com/imaging/escl/2011/05/03"
        const val NS_PWG = "http://www.pwg.org/schemas/2010/12/sm"

        fun baseUrl(device: NetworkDevice): String? {
            val (type, tls) = if ("_uscans._tcp" in device.mdnsServices) "_uscans._tcp" to true else "_uscan._tcp" to false
            val port = device.mdnsPorts[type] ?: return null
            val rs = device.mdnsServices[type]?.get("rs")?.trim('/')?.ifEmpty { null } ?: "eSCL"
            return "${if (tls) "https" else "http"}://${device.ip}:$port/$rs"
        }

        private fun values(xml: String, tag: String): List<String> =
            Regex("<(?:\\w+:)?$tag>\\s*([^<]*?)\\s*</(?:\\w+:)?$tag>").findAll(xml).map { it.groupValues[1] }.toList()

        fun parseCapabilities(xml: String): ScannerCaps? {
            val platen = Regex("<(?:\\w+:)?Platen>(.*?)</(?:\\w+:)?Platen>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
            val width = values(platen, "MaxWidth").firstOrNull()?.toIntOrNull() ?: return null
            val height = values(platen, "MaxHeight").firstOrNull()?.toIntOrNull() ?: return null
            return ScannerCaps(
                version = values(xml, "Version").firstOrNull() ?: "2.0",
                maxWidth = width, maxHeight = height,
                colorModes = values(platen, "ColorMode").toSet(),
                resolutions = values(platen, "XResolution").mapNotNull { it.toIntOrNull() }.toSet(),
                formats = (values(platen, "DocumentFormatExt") + values(platen, "DocumentFormat")).toSet()
            )
        }

        /** Scan settings using only values the scanner reported (closest resolution, JPEG if offered). */
        fun settings(caps: ScannerCaps, scan: Command.Scan): String {
            val dpi = caps.resolutions.minByOrNull { kotlin.math.abs(it - scan.dpi) } ?: scan.dpi
            val mode = if (scan.color && "RGB24" in caps.colorModes) "RGB24" else if ("Grayscale8" in caps.colorModes) "Grayscale8" else caps.colorModes.firstOrNull() ?: "RGB24"
            val format = if ("image/jpeg" in caps.formats || caps.formats.isEmpty()) "image/jpeg" else if ("application/pdf" in caps.formats) "application/pdf" else caps.formats.first()
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<scan:ScanSettings xmlns:scan=\"$NS_SCAN\" xmlns:pwg=\"$NS_PWG\">" +
                "<pwg:Version>${caps.version}</pwg:Version>" +
                "<pwg:ScanRegions><pwg:ScanRegion><pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>" +
                "<pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset><pwg:Width>${caps.maxWidth}</pwg:Width><pwg:Height>${caps.maxHeight}</pwg:Height>" +
                "</pwg:ScanRegion></pwg:ScanRegions>" +
                "<pwg:InputSource>Platen</pwg:InputSource><scan:ColorMode>$mode</scan:ColorMode>" +
                "<scan:XResolution>$dpi</scan:XResolution><scan:YResolution>$dpi</scan:YResolution>" +
                "<pwg:DocumentFormat>$format</pwg:DocumentFormat><scan:DocumentFormatExt>$format</scan:DocumentFormatExt>" +
                "</scan:ScanSettings>"
        }
    }
}
