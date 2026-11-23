package com.roomac.controller.netscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private fun classify(d: NetworkDevice, oui: OuiVendor? = null) = DeviceClassifier.classify(d, oui)

    @Test fun gatewayIsRouter() {
        assertEquals(DeviceType.ROUTER, classify(NetworkDevice("192.168.1.1", isGateway = true)).type)
    }

    @Test fun noEvidenceIsUnknownWithoutReason() {
        val r = classify(NetworkDevice("192.168.1.50", openPorts = setOf(80, 443)))
        assertEquals(DeviceType.UNKNOWN, r.type)
        assertNull(r.reason)
    }

    @Test fun printerServices() {
        listOf("_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp").forEach {
            assertEquals(it, DeviceType.PRINTER, classify(NetworkDevice("10.0.0.5", mdnsServices = mapOf(it to emptyMap()))).type)
        }
    }

    @Test fun castCapabilityBitDecidesTvOrSpeaker() {
        fun cast(ca: String?) = classify(NetworkDevice("10.0.0.6", mdnsServices = mapOf("_googlecast._tcp" to listOfNotNull(ca?.let { "ca" to it }).toMap()))).type
        assertEquals(DeviceType.TV, cast("4101"))      // Chromecast: video out
        assertEquals(DeviceType.SPEAKER, cast("2052")) // Google Home: audio only
        assertEquals(DeviceType.MEDIA, cast(null))     // no capability info: don't guess
    }

    @Test fun airplayModel() {
        fun ap(model: String) = classify(NetworkDevice("10.0.0.7", mdnsServices = mapOf("_airplay._tcp" to mapOf("model" to model)))).type
        assertEquals(DeviceType.TV, ap("AppleTV6,2"))
        assertEquals(DeviceType.SPEAKER, ap("AudioAccessory5,1"))
        assertEquals(DeviceType.COMPUTER, ap("MacBookPro18,1"))
        assertEquals(DeviceType.UNKNOWN, ap("SomeVendorTV"))
    }

    @Test fun unknownAirplayFallsThroughToRaop() {
        val d = NetworkDevice("10.0.0.7", mdnsServices = mapOf("_airplay._tcp" to emptyMap(), "_raop._tcp" to emptyMap()))
        assertEquals(DeviceType.SPEAKER, classify(d).type)
    }

    @Test fun homeKitCategory() {
        fun hap(ci: String) = classify(NetworkDevice("10.0.0.8", mdnsServices = mapOf("_hap._tcp" to mapOf("ci" to ci)))).type
        assertEquals(DeviceType.CAMERA, hap("17"))
        assertEquals(DeviceType.TV, hap("31"))
        assertEquals(DeviceType.IOT, hap("5"))
    }

    @Test fun genericHttpServiceSaysNothing() {
        assertEquals(DeviceType.UNKNOWN, classify(NetworkDevice("10.0.0.9", mdnsServices = mapOf("_http._tcp" to emptyMap()))).type)
    }

    @Test fun ssdpTypes() {
        assertEquals(DeviceType.ROUTER, classify(NetworkDevice("10.0.0.1", ssdpTypes = setOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"))).type)
        assertEquals(DeviceType.TV, classify(NetworkDevice("10.0.0.2", ssdpTypes = setOf("urn:dial-multiscreen-org:service:dial:1"))).type)
        assertEquals(DeviceType.MEDIA, classify(NetworkDevice("10.0.0.3", ssdpTypes = setOf("urn:schemas-upnp-org:device:MediaRenderer:1"))).type)
        assertEquals(DeviceType.SPEAKER, classify(NetworkDevice("10.0.0.4", ssdpTypes = setOf("urn:schemas-upnp-org:device:MediaRenderer:1"), ssdpServer = "Linux UPnP/1.0 Sonos/70.3-35220")).type)
    }

    @Test fun hostnames() {
        assertEquals(DeviceType.PHONE, classify(NetworkDevice("10.0.0.20", hostname = "Johns-iPhone")).type)
        assertEquals(DeviceType.PHONE, classify(NetworkDevice("10.0.0.21", hostname = "android-5f2a9c1b")).type)
        assertEquals(DeviceType.COMPUTER, classify(NetworkDevice("10.0.0.22", hostname = "DESKTOP-AB12CD3")).type)
        // "pixel" inside another word is not a phone.
        assertEquals(DeviceType.UNKNOWN, classify(NetworkDevice("10.0.0.23", hostname = "pixelart-server")).type)
    }

    @Test fun ports() {
        assertEquals(DeviceType.PRINTER, classify(NetworkDevice("10.0.0.30", openPorts = setOf(80, 9100))).type)
        assertEquals(DeviceType.CAMERA, classify(NetworkDevice("10.0.0.31", openPorts = setOf(80, 554))).type)
        assertEquals(DeviceType.MEDIA, classify(NetworkDevice("10.0.0.32", openPorts = setOf(8009))).type)
        assertEquals(DeviceType.PHONE, classify(NetworkDevice("10.0.0.33", openPorts = setOf(62078))).type)
    }

    @Test fun mdnsBeatsPorts() {
        // A TV that also happens to expose RTSP is still a TV.
        val d = NetworkDevice("10.0.0.40", mdnsServices = mapOf("_androidtvremote2._tcp" to emptyMap()), openPorts = setOf(554))
        assertEquals(DeviceType.TV, classify(d).type)
    }

    @Test fun vendorHintOnlyWhenNothingElse() {
        val sonos = OuiVendor("Sonos", DeviceType.SPEAKER)
        assertEquals(DeviceType.SPEAKER, classify(NetworkDevice("10.0.0.50"), sonos).type)
        assertEquals(DeviceType.UNKNOWN, classify(NetworkDevice("10.0.0.51"), OuiVendor("Apple", null)).type)
        assertEquals(DeviceType.PRINTER, classify(NetworkDevice("10.0.0.52", openPorts = setOf(9100)), sonos).type)
    }

    @Test fun ouiLookup() {
        val t = TEST_OUIS
        assertEquals(4, t.size)
        assertEquals("TestPrinterCo", t.lookup("00-aa-01-12-34-56")?.name)
        assertEquals("TestSpeakerCo", t.lookup("00AA.0312.3456")?.name) // lower-case generated prefix normalised
        assertEquals(DeviceType.SPEAKER, t.lookup("00:AA:02:00:00:01")?.typeHint)
        assertNull(t.lookup("12:34:56:78:9A:BC"))
        // Randomised (locally administered) addresses never map to a vendor, even if the rest matches.
        assertNull(t.lookup("02:AA:01:12:34:56"))
        assertNull(t.lookup("not a mac"))
    }

    @Test fun bundledTableHasRegistryData() {
        // Rows confirmed in the source file the table was generated from (Wireshark manuf, IEEE data).
        val t = OuiTable.DEFAULT
        assertTrue("generated table should not be empty", t.size > 1000)
        assertEquals(OuiVendor("Raspberry Pi", null), t.lookup("B8:27:EB:01:02:03"))
        assertEquals(OuiVendor("Sonos", DeviceType.SPEAKER), t.lookup("00:0E:58:01:02:03"))
        assertEquals(OuiVendor("Espressif", DeviceType.IOT), t.lookup("18:FE:34:01:02:03"))
        assertEquals(OuiVendor("Brother", DeviceType.PRINTER), t.lookup("00:80:77:01:02:03"))
        assertEquals(OuiVendor("Signify (Philips Hue)", DeviceType.IOT), t.lookup("00:17:88:01:02:03"))
        assertEquals(OuiVendor("Hikvision", DeviceType.CAMERA), t.lookup("44:19:B6:01:02:03"))
        assertEquals(OuiVendor("Samsung", null), t.lookup("00:15:99:01:02:03"))
        // A registered company outside the selected brands stays unlabelled.
        assertNull(t.lookup("00:0C:B5:01:02:03"))
    }

    @Test fun bundledTableIsWellFormed() {
        // Holds for the empty (not yet generated) table and for any table the generator writes.
        OuiData.VENDORS.forEach { v ->
            val prefixes = OuiTable.parsePrefixes(v.prefixes)
            assertEquals(v.name, v.prefixes.split(' ').size, prefixes.size)
            prefixes.forEach { assertFalse("${v.name} $it is a locally administered prefix", Mac.isRandomized("$it:00:00:00")) }
        }
        assertEquals(OuiData.VENDORS.sumOf { OuiTable.parsePrefixes(it.prefixes).size }, OuiTable.DEFAULT.size)
        assertTrue(OuiData.SOURCE.isNotBlank())
    }

    @Test fun macHelpers() {
        assertEquals("AA:BB:CC:DD:EE:FF", Mac.normalize("aa-bb-cc-dd-ee-ff"))
        assertNull(Mac.normalize("00:00:00:00:00:00"))
        assertNull(Mac.normalize("FF:FF:FF:FF:FF:FF"))
        assertTrue(Mac.isRandomized("DA:A1:19:00:00:01"))
        assertFalse(Mac.isRandomized("00:AA:01:00:00:01"))
    }
}
