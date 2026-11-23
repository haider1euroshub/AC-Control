package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Edge cases for the classifier and the three discovery sources, all with fake network responses. */
class ClassifierEdgeCaseTest {

    private fun type(d: NetworkDevice) = DeviceClassifier.classify(d, null).type

    @Test fun gatewayWinsOverEverythingElse() {
        val d = NetworkDevice("192.168.1.1", isGateway = true, mdnsServices = mapOf("_ipp._tcp" to emptyMap()), openPorts = setOf(9100))
        assertEquals(DeviceType.ROUTER, type(d))
    }

    @Test fun moreServicesAndPorts() {
        assertEquals(DeviceType.CAMERA, type(NetworkDevice("a", mdnsServices = mapOf("_hap._tcp" to mapOf("ci" to "18")))))
        assertEquals(DeviceType.IOT, type(NetworkDevice("a", mdnsServices = mapOf("_hap._tcp" to emptyMap()))))
        assertEquals(DeviceType.PHONE, type(NetworkDevice("a", mdnsServices = mapOf("_apple-mobdev2._tcp" to emptyMap()))))
        assertEquals(DeviceType.TV, type(NetworkDevice("a", mdnsServices = mapOf("_androidtvremote2._tcp" to emptyMap()))))
        assertEquals(DeviceType.PRINTER, type(NetworkDevice("a", openPorts = setOf(631))))
        assertEquals(DeviceType.PRINTER, type(NetworkDevice("a", ssdpTypes = setOf("urn:schemas-upnp-org:device:Printer:1"))))
        assertEquals(DeviceType.MEDIA, type(NetworkDevice("a", ssdpTypes = setOf("roku:ecp"))))
    }

    @Test fun printerBeatsHttpWhenBothAdvertised() {
        val d = NetworkDevice("a", mdnsServices = mapOf("_http._tcp" to emptyMap(), "_ipp._tcp" to emptyMap()))
        assertEquals(DeviceType.PRINTER, type(d))
    }

    @Test fun dialBeatsGenericMediaRenderer() {
        val d = NetworkDevice("a", ssdpTypes = setOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:dial-multiscreen-org:service:dial:1"))
        assertEquals(DeviceType.TV, type(d))
    }

    @Test fun mdnsNameUsedForHostnameHintWhenNoHostname() {
        assertEquals(DeviceType.PHONE, type(NetworkDevice("a", name = "Sara's iPhone")))
    }

    @Test fun everyTypeCarriesAReasonAndUnknownNone() {
        val typed = DeviceClassifier.classify(NetworkDevice("a", openPorts = setOf(554)), null)
        assertTrue(typed.reason!!.contains("554"))
        assertNull(DeviceClassifier.classify(NetworkDevice("a"), null).reason)
    }
}

class MdnsEdgeCaseTest {

    @Test fun blankCastNameFallsBackToInstanceName() {
        val o = MdnsDiscovery.toObservation(MdnsService("Kitchen speaker", "_googlecast._tcp", "192.168.1.9", 8009, mapOf("fn" to " ")))
        assertEquals("Kitchen speaker", o?.name)
    }

    @Test fun missingOrInvalidHostIsSkipped() {
        assertNull(MdnsDiscovery.toObservation(MdnsService("x", "_ipp._tcp", null, 631)))
        assertNull(MdnsDiscovery.toObservation(MdnsService("x", "_ipp._tcp", "999.1.1.1", 631)))
    }

    @Test fun browserFailureSurfacesToScanner() = runTest {
        val browser = object : MdnsBrowser {
            override suspend fun browse(serviceTypes: List<String>, durationMs: Long, onResolved: (MdnsService) -> Unit) =
                throw IllegalStateException("NsdManager unavailable")
        }
        val state = NetworkScanner(listOf(MdnsDiscovery(browser)), ouis = TEST_OUIS).scan(LocalNetwork("192.168.1.10", 24), null)
        assertEquals(SourceOutcome.Failed("NsdManager unavailable"), state.sources[ScanSource.MDNS])
    }
}

class SsdpEdgeCaseTest {

    private val net = LocalNetwork("192.168.1.10", 24)
    private val reply = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: http://192.168.1.70:49152/desc.xml\r\n\r\n".toByteArray()

    private fun transport(vararg replies: Pair<String, ByteArray>) = object : SsdpTransport {
        override suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) =
            replies.forEach { onResponse(it.first, it.second) }
    }

    @Test fun nonIpv4ResponderIgnored() = runTest {
        val sink = RecordingSink()
        SsdpDiscovery(transport("fe80::1" to reply), null).discover(net, sink)
        assertTrue(sink.observations.isEmpty())
    }

    @Test fun descriptionFailureStillReportsDevice() = runTest {
        val failing = object : DescriptionFetcher { override suspend fun fetch(url: String): String? = null }
        val sink = RecordingSink()
        SsdpDiscovery(transport("192.168.1.70" to reply), failing).discover(net, sink)
        val o = sink.observations.single()
        assertNull(o.name)
        assertEquals(setOf("urn:schemas-upnp-org:device:MediaRenderer:1"), o.ssdpTypes)
    }

    @Test fun slowDescriptionTimesOut() = runTest {
        val slow = object : DescriptionFetcher { override suspend fun fetch(url: String): String? { delay(60_000); return "<friendlyName>late</friendlyName>" } }
        val sink = RecordingSink()
        SsdpDiscovery(transport("192.168.1.70" to reply), slow, fetchTimeoutMs = 1_000).discover(net, sink)
        assertNull(sink.observations.single().name)
    }

    @Test fun descriptionDeviceTypeAddedToTypes() = runTest {
        val fetcher = object : DescriptionFetcher {
            override suspend fun fetch(url: String) = "<root><device><deviceType>urn:schemas-upnp-org:device:Printer:1</deviceType></device></root>"
        }
        val sink = RecordingSink()
        val plain = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nLOCATION: http://192.168.1.70/d.xml\r\n\r\n".toByteArray()
        SsdpDiscovery(transport("192.168.1.70" to plain), fetcher).discover(net, sink)
        assertEquals(setOf("urn:schemas-upnp-org:device:Printer:1"), sink.observations.single().ssdpTypes)
    }

    @Test fun descriptionParserToleratesNamespacesAndMissingTags() {
        val d = SsdpParser.parseDescription("<u:device><u:friendlyName> Den TV </u:friendlyName></u:device>")
        assertEquals("Den TV", d.friendlyName)
        assertNull(d.manufacturer)
    }

    @Test fun onlyPlainHttpOnResponderIsFollowed() {
        assertEquals("http://192.168.1.70:80/d.xml", SsdpParser.safeLocation("http://192.168.1.70:80/d.xml", "192.168.1.70"))
        assertNull(SsdpParser.safeLocation("https://192.168.1.70/d.xml", "192.168.1.70"))
        assertNull(SsdpParser.safeLocation("file:///etc/passwd", "192.168.1.70"))
        assertNull(SsdpParser.safeLocation("not a url", "192.168.1.70"))
        assertNull(SsdpParser.safeLocation(null, "192.168.1.70"))
    }

    @Test fun httpBodyOnlyFor200() {
        assertEquals("<xml/>", SocketDescriptionFetcher.httpBody("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n<xml/>"))
        assertNull(SocketDescriptionFetcher.httpBody("HTTP/1.1 404 Not Found\r\n\r\nnope"))
        assertNull(SocketDescriptionFetcher.httpBody("HTTP/1.0 2000 Weird\r\n\r\nx"))
        assertNull(SocketDescriptionFetcher.httpBody("garbage"))
        assertNull(SocketDescriptionFetcher.httpBody("HTTP/1.1 200 OK\r\nno blank line"))
    }
}

class SweepEdgeCaseTest {

    private class CountingProber(private val delayMs: Long) : PortProber {
        val inFlight = AtomicInteger()
        val peakHosts = AtomicInteger()
        private val hosts = mutableMapOf<String, Int>()
        override suspend fun probe(ip: String, port: Int, timeoutMs: Int): PortState {
            synchronized(hosts) { hosts[ip] = (hosts[ip] ?: 0) + 1; peakHosts.set(maxOf(peakHosts.get(), hosts.size)) }
            delay(delayMs)
            synchronized(hosts) { val n = hosts.getValue(ip) - 1; if (n == 0) hosts.remove(ip) else hosts[ip] = n }
            return PortState.NO_ANSWER
        }
    }

    private val nobody = object : Pinger { override suspend fun isReachable(ip: String, timeoutMs: Int) = false }

    @Test fun hostConcurrencyIsBounded() = runTest {
        val prober = CountingProber(100)
        SubnetSweep(prober, nobody, { null }, null, concurrency = 8).discover(LocalNetwork("192.168.1.10", 24), RecordingSink())
        assertTrue("peak ${prober.peakHosts.get()}", prober.peakHosts.get() in 1..8)
    }

    @Test fun largeSubnetNarrowedToAboutA24() = runTest {
        val probed = mutableSetOf<String>()
        val prober = object : PortProber {
            override suspend fun probe(ip: String, port: Int, timeoutMs: Int): PortState { synchronized(probed) { probed += ip }; return PortState.NO_ANSWER }
        }
        SubnetSweep(prober, nobody, { null }, null).discover(LocalNetwork("10.20.30.40", 16), RecordingSink())
        assertTrue("probed ${probed.size}", probed.size <= 254)
        assertTrue(probed.all { it.startsWith("10.20.30.") })
    }

    @Test fun hostnameEqualToIpIsDropped() = runTest {
        val sink = RecordingSink()
        val up = object : Pinger { override suspend fun isReachable(ip: String, timeoutMs: Int) = ip == "192.168.1.20" }
        val echo = object : HostnameResolver { override suspend fun lookup(ip: String) = ip }
        SubnetSweep(object : PortProber { override suspend fun probe(ip: String, port: Int, timeoutMs: Int) = PortState.NO_ANSWER }, up, { null }, echo)
            .discover(LocalNetwork("192.168.1.10", 24), sink)
        assertTrue(sink.observations.none { it.hostname != null })
    }

    @Test fun arpParserIgnoresMalformedLines() {
        val text = "IP address HW type Flags HW address Mask Device\nbroken line\n192.168.1.5 0x1 0x2 zz:zz:zz:zz:zz:zz * wlan0\n192.168.1.6 0x1 0x6 00:aa:01:00:00:06 * wlan0"
        assertEquals(mapOf("192.168.1.6" to "00:AA:01:00:00:06"), ArpTable.parse(text))
    }
}

class AggregatorEdgeCaseTest {

    @Test fun firstNameWinsAndGatewayFlagged() {
        val agg = DeviceAggregator(TEST_OUIS, gateway = "192.168.1.1")
        agg.add(Observation("192.168.1.1", ScanSource.SSDP, name = "Router A"))
        agg.add(Observation("192.168.1.1", ScanSource.MDNS, name = "Router B", hostname = "router.lan"))
        val d = agg.devices().single()
        assertEquals("Router A", d.name)
        assertEquals("router.lan", d.hostname)
        assertTrue(d.isGateway)
        assertEquals(DeviceType.ROUTER, d.type)
    }

    @Test fun devicesSortedNumericallyNotAlphabetically() {
        val agg = DeviceAggregator(TEST_OUIS)
        listOf("192.168.1.100", "192.168.1.9", "192.168.1.20").forEach { agg.add(Observation(it, ScanSource.SWEEP)) }
        assertEquals(listOf("192.168.1.9", "192.168.1.20", "192.168.1.100"), agg.devices().map { it.ip })
    }
}
