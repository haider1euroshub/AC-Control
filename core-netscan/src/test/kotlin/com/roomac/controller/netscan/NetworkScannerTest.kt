package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkScannerTest {

    private val net = LocalNetwork("192.168.1.10", 24)

    private class FixedSource(override val source: ScanSource, private val items: List<Observation>, private val note: String? = null) : DiscoverySource {
        override suspend fun discover(network: LocalNetwork, sink: ObservationSink) {
            items.forEach(sink::observe)
            note?.let(sink::note)
        }
    }

    private class FailingSource : DiscoverySource {
        override val source = ScanSource.SSDP
        override suspend fun discover(network: LocalNetwork, sink: ObservationSink) = throw java.io.IOException("multicast blocked")
    }

    private class HangingSource : DiscoverySource {
        override val source = ScanSource.MDNS
        override suspend fun discover(network: LocalNetwork, sink: ObservationSink) = delay(Long.MAX_VALUE)
    }

    @Test fun mergesSourcesByIpAndClassifies() = runTest {
        val scanner = NetworkScanner(
            ouis = TEST_OUIS,
            sources = listOf(
                FixedSource(ScanSource.MDNS, listOf(Observation("192.168.1.41", ScanSource.MDNS, name = "Office printer", mdnsServices = mapOf("_ipp._tcp" to emptyMap())))),
                FixedSource(ScanSource.SWEEP, listOf(
                    Observation("192.168.1.41", ScanSource.SWEEP, openPorts = setOf(80, 9100)),
                    Observation("192.168.1.41", ScanSource.ARP, mac = "00:AA:01:01:02:03"),
                    Observation("192.168.1.1", ScanSource.SWEEP, openPorts = setOf(80)),
                    Observation("192.168.1.88", ScanSource.SWEEP)
                ))
            )
        )
        val state = scanner.scan(net, gateway = "192.168.1.1")

        assertFalse(state.running)
        assertEquals(listOf("192.168.1.1", "192.168.1.41", "192.168.1.88"), state.devices.map { it.ip })
        val printer = state.devices[1]
        assertEquals(DeviceType.PRINTER, printer.type)
        assertEquals("Office printer", printer.displayName)
        assertEquals("TestPrinterCo", printer.vendor)
        assertEquals("00:AA:01:01:02:03", printer.mac)
        assertEquals(setOf(80, 9100), printer.openPorts)
        assertEquals(setOf(ScanSource.MDNS, ScanSource.SWEEP, ScanSource.ARP), printer.sources)
        assertEquals(DeviceType.ROUTER, state.devices[0].type)
        // Seen, but nothing says what it is: honest unknown, no vendor invented.
        val unknown = state.devices[2]
        assertEquals(DeviceType.UNKNOWN, unknown.type)
        assertNull(unknown.vendor)
        assertEquals("Unknown device", unknown.displayName)
    }

    @Test fun sameMacOnTwoAddressesIsOneDevice() = runTest {
        val scanner = NetworkScanner(ouis = TEST_OUIS, sources = listOf(FixedSource(ScanSource.SWEEP, listOf(
            Observation("192.168.1.20", ScanSource.ARP, mac = "00:AA:04:00:00:01"),
            Observation("192.168.1.21", ScanSource.ARP, mac = "00-aa-04-00-00-01")
        ))))
        val devices = scanner.scan(net, null).devices
        assertEquals(1, devices.size)
        assertEquals(setOf("192.168.1.21"), devices[0].otherIps)
    }

    @Test fun randomisedMacGivesNoVendor() = runTest {
        val scanner = NetworkScanner(listOf(FixedSource(ScanSource.SWEEP, listOf(Observation("192.168.1.30", ScanSource.ARP, mac = "DA:A1:19:12:34:56")))))
        val d = scanner.scan(net, null).devices.single()
        assertTrue(d.randomizedMac)
        assertNull(d.vendor)
        assertEquals(DeviceType.UNKNOWN, d.type)
    }

    @Test fun failingAndHangingSourcesDoNotStopOthers() = runTest {
        val scanner = NetworkScanner(
            listOf(FailingSource(), HangingSource(), FixedSource(ScanSource.SWEEP, listOf(Observation("192.168.1.5", ScanSource.SWEEP)), note = "no MACs")),
            sourceTimeoutMs = 1_000
        )
        val state = scanner.scan(net, null)
        assertEquals(1, state.devices.size)
        assertTrue(state.sources[ScanSource.SSDP] is SourceOutcome.Failed)
        assertEquals(SourceOutcome.Failed("timed out"), state.sources[ScanSource.MDNS])
        assertEquals(SourceOutcome.Done(1), state.sources[ScanSource.SWEEP])
        assertEquals(listOf("no MACs"), state.notes)
    }

    @Test fun upnpManufacturerUsedOnlyWithoutOui() = runTest {
        val scanner = NetworkScanner(ouis = TEST_OUIS, sources = listOf(FixedSource(ScanSource.SSDP, listOf(
            Observation("192.168.1.60", ScanSource.SSDP, manufacturer = "ExampleTV Inc."),
            Observation("192.168.1.61", ScanSource.SSDP, manufacturer = "Claims Something Else"),
            Observation("192.168.1.61", ScanSource.ARP, mac = "00:AA:03:00:00:02")
        ))))
        val devices = scanner.scan(net, null).devices.associateBy { it.ip }
        assertEquals("ExampleTV Inc.", devices.getValue("192.168.1.60").vendor)
        assertEquals("TestSpeakerCo", devices.getValue("192.168.1.61").vendor)
    }
}
