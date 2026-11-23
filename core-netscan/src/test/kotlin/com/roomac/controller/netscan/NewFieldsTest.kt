package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields device control needs (ports, LOCATION, ONVIF XAddrs) and the WS-Discovery source. */
class NewFieldsTest {

    private val net = LocalNetwork("192.168.1.10", 24)

    private val probeMatch = """
        <?xml version="1.0" encoding="UTF-8"?>
        <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
        <SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
          <d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>
          <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/Front%20Door onvif://www.onvif.org/hardware/CAM-100</d:Scopes>
          <d:XAddrs>http://192.168.1.80/onvif/device_service http://[fe80::1]/onvif/device_service</d:XAddrs>
        </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>
    """.trimIndent()

    private fun transport(vararg replies: Pair<String, ByteArray>) = object : SsdpTransport {
        var sent: String? = null
        override suspend fun search(request: ByteArray, timeoutMs: Long, onResponse: (String, ByteArray) -> Unit) {
            sent = String(request)
            replies.forEach { onResponse(it.first, it.second) }
        }
    }

    @Test fun wsDiscoveryProbeAndMatch() = runTest {
        val t = transport("192.168.1.80" to probeMatch.toByteArray(), "192.168.1.81" to OnvifDiscovery.probe())
        val sink = RecordingSink()
        OnvifDiscovery(t).discover(net, sink)
        assertTrue(t.sent!!.contains("<d:Types>dn:NetworkVideoTransmitter</d:Types>"))
        val o = sink.observations.single() // our own echoed Probe is not a match
        assertEquals("192.168.1.80", o.ip)
        assertEquals(setOf("http://192.168.1.80/onvif/device_service"), o.onvifXAddrs) // IPv6/other-host XAddr dropped
        assertEquals("Front Door", o.name)
        assertEquals("CAM-100", o.model)
    }

    @Test fun xaddrsPointingElsewhereAreIgnored() = runTest {
        val spoof = probeMatch.replace("http://192.168.1.80/", "http://203.0.113.5/").replace("http://[fe80::1]/onvif/device_service", "")
        val sink = RecordingSink()
        OnvifDiscovery(transport("192.168.1.80" to spoof.toByteArray())).discover(net, sink)
        assertTrue(sink.observations.isEmpty())
    }

    @Test fun onvifDeviceClassifiedAsCamera() {
        val r = DeviceClassifier.classify(NetworkDevice("192.168.1.80", onvifXAddrs = setOf("http://192.168.1.80/onvif/device_service")), null)
        assertEquals(DeviceType.CAMERA, r.type)
    }

    @Test fun mdnsPortAndSsdpLocationKept() = runTest {
        val o = MdnsDiscovery.toObservation(MdnsService("Scanner", "_uscan._tcp.", "192.168.1.41", 8080, mapOf("rs" to "eSCL")))!!
        assertEquals(mapOf("_uscan._tcp" to 8080), o.mdnsPorts)
        assertNull(MdnsDiscovery.toObservation(MdnsService("x", "_ipp._tcp", "192.168.1.41", 0))!!.mdnsPorts["_ipp._tcp"])

        val reply = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: http://192.168.1.70:49152/desc.xml\r\n\r\n".toByteArray()
        val sink = RecordingSink()
        SsdpDiscovery(transport("192.168.1.70" to reply), null).discover(net, sink)
        assertEquals("http://192.168.1.70:49152/desc.xml", sink.observations.single().ssdpLocation)
    }

    @Test fun aggregatorMergesNewFields() {
        val agg = DeviceAggregator(TEST_OUIS)
        agg.add(Observation("192.168.1.41", ScanSource.MDNS, mdnsPorts = mapOf("_uscan._tcp" to 8080)))
        agg.add(Observation("192.168.1.41", ScanSource.MDNS, mdnsPorts = mapOf("_ipp._tcp" to 631)))
        agg.add(Observation("192.168.1.41", ScanSource.SSDP, ssdpLocation = "http://192.168.1.41/d.xml"))
        agg.add(Observation("192.168.1.41", ScanSource.WSD, onvifXAddrs = setOf("http://192.168.1.41/onvif/device_service")))
        val d = agg.devices().single()
        assertEquals(mapOf("_uscan._tcp" to 8080, "_ipp._tcp" to 631), d.mdnsPorts)
        assertEquals("http://192.168.1.41/d.xml", d.ssdpLocation)
        assertEquals(1, d.onvifXAddrs.size)
    }
}
