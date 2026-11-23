package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NET = LocalNetwork("192.168.1.10", 24)

class RecordingSink : ObservationSink {
    val observations = mutableListOf<Observation>()
    val notes = mutableListOf<String>()
    @Synchronized override fun observe(observation: Observation) { observations += observation }
    @Synchronized override fun note(message: String) { notes += message }
}

class MdnsDiscoveryTest {

    private class FakeBrowser(private val services: List<MdnsService>) : MdnsBrowser {
        var requested: List<String> = emptyList()
        override suspend fun browse(serviceTypes: List<String>, durationMs: Long, onResolved: (MdnsService) -> Unit) {
            requested = serviceTypes
            services.forEach(onResolved)
        }
    }

    @Test fun resolvedServicesBecomeObservations() = runTest {
        val browser = FakeBrowser(
            listOf(
                MdnsService("Chromecast-1f2e3d", "_googlecast._tcp.", "192.168.1.40", 8009, mapOf("fn" to "Living Room TV", "md" to "Chromecast", "ca" to "4101")),
                MdnsService("HP OfficeJet 8020", "_ipp._tcp.local.", "192.168.1.41", 631, mapOf("ty" to "HP OfficeJet Pro 8020"), hostname = "HP1A2B3C.local."),
                MdnsService("v6-only", "_airplay._tcp", "fe80::1", 7000)
            )
        )
        val sink = RecordingSink()
        MdnsDiscovery(browser).discover(NET, sink)

        assertTrue("_googlecast._tcp" in browser.requested && "_ipp._tcp" in browser.requested)
        assertEquals(2, sink.observations.size) // IPv6-only service skipped
        val cast = sink.observations[0]
        assertEquals("Living Room TV", cast.name)
        assertEquals("Chromecast", cast.model)
        assertEquals(setOf("_googlecast._tcp"), cast.mdnsServices.keys)
        val printer = sink.observations[1]
        assertEquals("HP1A2B3C", printer.hostname)
        assertEquals("HP OfficeJet Pro 8020", printer.model)
        assertEquals(setOf("_ipp._tcp"), printer.mdnsServices.keys)
    }

    @Test fun workstationMacSuffixDropped() {
        val o = MdnsDiscovery.toObservation(MdnsService("nas [00:11:32:aa:bb:cc]", "_workstation._tcp", "192.168.1.5", 9))
        assertEquals("nas", o?.name)
    }

    @Test fun typeNormalisation() {
        assertEquals("_ipp._tcp", MdnsDiscovery.normalizeType("_ipp._tcp."))
        assertEquals("_ipp._tcp", MdnsDiscovery.normalizeType("._IPP._tcp.local."))
    }
}

class SsdpDiscoveryTest {

    private val gatewayReply = (
        "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=120\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
            "USN: uuid:abcd::urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
            "SERVER: Linux/3.14 UPnP/1.0 miniupnpd/2.1\r\n" +
            "LOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n"
        ).toByteArray()

    private val tvReply = (
        "HTTP/1.1 200 OK\r\n" +
            "st: urn:dial-multiscreen-org:service:dial:1\r\n" +
            "usn: uuid:tv::urn:dial-multiscreen-org:service:dial:1\r\n" +
            "location: http://192.168.1.60:8008/ssdp/device-desc.xml\r\n\r\n"
        ).toByteArray()

    private val tvRoot = (
        "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nUSN: uuid:tv::upnp:rootdevice\r\n\r\n"
        ).toByteArray()

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
          <friendlyName>Home Router &amp; Wi-Fi</friendlyName>
          <manufacturer>ExampleNet</manufacturer>
          <modelName>XR-500</modelName>
        </device></root>
    """.trimIndent()

    private class FakeTransport(private val replies: List<Pair<String, ByteArray>>) : SsdpTransport {
        var sent: String? = null
        override suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) {
            sent = String(request)
            replies.forEach { onResponse(it.first, it.second) }
        }
    }

    private class FakeFetcher(private val docs: Map<String, String>) : DescriptionFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): String? { fetched += url; return docs[url] }
    }

    @Test fun repliesGroupedByIpAndDescribed() = runTest {
        val transport = FakeTransport(listOf("192.168.1.1" to gatewayReply, "192.168.1.60" to tvReply, "192.168.1.60" to tvRoot))
        val fetcher = FakeFetcher(mapOf("http://192.168.1.1:5000/rootDesc.xml" to description))
        val sink = RecordingSink()
        SsdpDiscovery(transport, fetcher).discover(NET, sink)

        assertTrue(transport.sent!!.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("ST: ssdp:all" in transport.sent!!)
        val byIp = sink.observations.associateBy { it.ip }
        assertEquals(2, byIp.size)
        val router = byIp.getValue("192.168.1.1")
        assertEquals("Home Router & Wi-Fi", router.name)
        assertEquals("ExampleNet", router.manufacturer)
        assertEquals("XR-500", router.model)
        assertTrue(router.ssdpTypes.any { "InternetGatewayDevice" in it })
        assertEquals("Linux/3.14 UPnP/1.0 miniupnpd/2.1", router.ssdpServer)
        val tv = byIp.getValue("192.168.1.60")
        assertEquals(setOf("urn:dial-multiscreen-org:service:dial:1"), tv.ssdpTypes) // rootdevice/uuid dropped
        assertNull(tv.name) // description not served by the fake: nothing invented
    }

    @Test fun locationPointingElsewhereIsNotFetched() = runTest {
        val spoofed = String(gatewayReply).replace("http://192.168.1.1:5000", "http://203.0.113.9").toByteArray()
        val fetcher = FakeFetcher(emptyMap())
        SsdpDiscovery(FakeTransport(listOf("192.168.1.1" to spoofed)), fetcher).discover(NET, RecordingSink())
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test fun parserIgnoresMSearchEchoAndGarbage() {
        assertNull(SsdpParser.parse(SsdpParser.mSearch()))
        assertNull(SsdpParser.parse("hello".toByteArray()))
        assertEquals("upnp:rootdevice", SsdpParser.parse("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n".toByteArray())?.st)
    }

    @Test fun usnType() {
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", SsdpParser.usnType("uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertNull(SsdpParser.usnType("uuid:x"))
    }
}

class SubnetSweepTest {

    private class FakeProber(private val hosts: Map<String, Map<Int, PortState>>) : PortProber {
        override suspend fun probe(ip: String, port: Int, timeoutMs: Int) = hosts[ip]?.get(port) ?: PortState.NO_ANSWER
    }

    private class FakePinger(private val up: Set<String>) : Pinger {
        override suspend fun isReachable(ip: String, timeoutMs: Int) = ip in up
    }

    private class FakeResolver(private val names: Map<String, String>) : HostnameResolver {
        override suspend fun lookup(ip: String) = names[ip]
    }

    private val arpText = """
        IP address       HW type     Flags       HW address            Mask     Device
        192.168.1.1      0x1         0x2         50:c7:bf:11:22:33     *        wlan0
        192.168.1.77     0x1         0x2         b8:27:eb:44:55:66     *        wlan0
        192.168.1.99     0x1         0x0         00:00:00:00:00:00     *        wlan0
        10.9.9.9         0x1         0x2         aa:bb:cc:dd:ee:01     *        wlan0
    """.trimIndent()

    @Test fun findsLiveHostsPortsMacsAndNames() = runTest {
        val prober = FakeProber(
            mapOf(
                "192.168.1.1" to mapOf(80 to PortState.OPEN, 443 to PortState.OPEN),
                "192.168.1.30" to mapOf(9100 to PortState.OPEN, 80 to PortState.OPEN),
                "192.168.1.31" to mapOf(80 to PortState.CLOSED) // refused: alive, nothing open
            )
        )
        val sink = RecordingSink()
        SubnetSweep(prober, FakePinger(setOf("192.168.1.50")), { arpText }, FakeResolver(mapOf("192.168.1.30" to "printer.lan")))
            .discover(NET, sink)

        val sweep = sink.observations.filter { it.source == ScanSource.SWEEP && it.hostname == null }.associateBy { it.ip }
        assertEquals(setOf("192.168.1.1", "192.168.1.30", "192.168.1.31", "192.168.1.50"), sweep.keys)
        assertEquals(setOf(80, 9100), sweep.getValue("192.168.1.30").openPorts)
        assertEquals(emptySet<Int>(), sweep.getValue("192.168.1.31").openPorts)

        val arp = sink.observations.filter { it.source == ScanSource.ARP }.associate { it.ip to it.mac }
        // Incomplete entry and the off-subnet entry are not reported.
        assertEquals(mapOf("192.168.1.1" to "50:C7:BF:11:22:33", "192.168.1.77" to "B8:27:EB:44:55:66"), arp)
        assertEquals("printer.lan", sink.observations.single { it.hostname != null }.hostname)
        assertTrue(sink.notes.isEmpty())
    }

    @Test fun blockedArpDegradesWithNoteNotCrash() = runTest {
        val sink = RecordingSink()
        SubnetSweep(FakeProber(mapOf("192.168.1.2" to mapOf(443 to PortState.OPEN))), FakePinger(emptySet()), { null }, null)
            .discover(NET, sink)
        assertEquals(listOf("192.168.1.2"), sink.observations.map { it.ip })
        assertTrue(sink.observations.all { it.mac == null })
        assertEquals(1, sink.notes.size)
    }

    @Test fun ownAddressNotProbed() = runTest {
        val probed = mutableSetOf<String>()
        val prober = object : PortProber {
            override suspend fun probe(ip: String, port: Int, timeoutMs: Int): PortState {
                synchronized(probed) { probed += ip }
                return PortState.NO_ANSWER
            }
        }
        SubnetSweep(prober, FakePinger(emptySet()), { "" }, null).discover(NET, RecordingSink())
        assertEquals(253, probed.size) // .1-.254 minus the phone itself
        assertTrue("192.168.1.10" !in probed)
    }

    @Test fun arpParser() {
        assertEquals(2, ArpTable.parse(arpText).filterKeys { it.startsWith("192.") }.size)
        assertTrue(ArpTable.parse("").isEmpty())
    }
}
