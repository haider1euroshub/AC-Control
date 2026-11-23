package com.roomac.controller.driver.haismart

import com.roomac.controller.protocol.LanAccess
import com.roomac.controller.protocol.SubnetMath
import com.roomac.controller.protocol.LocalNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class UdiscoveryTest {

    /** The 309-byte reply the target AC sent on 2026-11-11, with its MAC and IP replaced. */
    private val capture: ByteArray by lazy {
        val hex = javaClass.classLoader!!.getResource("udiscovery_reply_anon.hex")!!.readText().trim()
        Bytes.fromHex(hex)
    }

    @Test
    fun `search request carries the two literals the device checks`() {
        val req = UdiscoveryCodec.searchRequest()
        assertEquals("Haier", String(req, 0, 5, Charsets.US_ASCII))
        assertEquals(0x6915L, Bytes.be32(req, 5))
        assertEquals(56L, Bytes.be32(req, 0x11))
        assertEquals("2.0.0", String(req, 0x15 + 0x10, 5, Charsets.US_ASCII))
        assertEquals("UDISCOVERY_SDK", String(req, 0x15 + 0x18, 14, Charsets.US_ASCII))
        assertEquals(0x15 + 56, req.size)
    }

    @Test
    fun `parses the captured reply from the target AC`() {
        assertEquals(309, capture.size)
        val d = UdiscoveryCodec.parse(capture, "10.0.0.1")!!
        assertEquals("A1B2C3D4E5F6", d.deviceId)
        assertEquals("192.168.10.77", d.host)
        assertEquals(56800, d.port)
        assertEquals("2008610800820324021200118012560000000000000000000000000000000040", d.uPlusId)
        assertEquals("e_4.3.00 / R_6.0.01", d.firmware)
        assertEquals("2.18", d.sdkVersion.trimEnd('\u0000'))
        assertEquals(1000, d.cloudState)
    }

    @Test
    fun `other datagrams are ignored`() {
        assertNull(UdiscoveryCodec.parse(UdiscoveryCodec.searchRequest(), "1.2.3.4"))
        assertNull(UdiscoveryCodec.parse(ByteArray(10), "1.2.3.4"))
        val wrongMagic = capture.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(UdiscoveryCodec.parse(wrongMagic, "1.2.3.4"))
    }

    @Test
    fun `unicast query gets the reply from a responder`() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            responder.receive(p)
            if (String(buf, 0, 5, Charsets.US_ASCII) == "Haier") {
                responder.send(DatagramPacket(capture, capture.size, p.socketAddress))
            }
        }
        val scanner = UdiscoveryScanner(udpPort = responder.localPort, queryTimeoutMs = 1500)
        val found = scanner.query("127.0.0.1")
        responder.close()
        assertEquals("A1B2C3D4E5F6", found!!.deviceId)
    }

    @Test
    fun `a lost first request is repeated and still finds the AC`() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            while (!responder.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { responder.receive(p) } catch (_: Exception) { break }
                // Drop the first request, as the network did on the real AC after a sweep.
                if (requests.incrementAndGet() == 1) continue
                responder.send(DatagramPacket(capture, capture.size, p.socketAddress))
            }
        }
        val scanner = UdiscoveryScanner(udpPort = responder.localPort, queryTimeoutMs = 1500)
        val found = scanner.query("127.0.0.1")
        responder.close()
        assertEquals("A1B2C3D4E5F6", found!!.deviceId)
        assertTrue(requests.get() >= 2)
    }

    @Test
    fun `query times out to null when nothing answers`() = runBlocking {
        val silent = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val scanner = UdiscoveryScanner(udpPort = silent.localPort, queryTimeoutMs = 300)
        assertNull(scanner.query("127.0.0.1"))
        silent.close()
    }

    @Test
    fun `every discovery socket is passed to the platform to be bound to the Wi-Fi network`() = runBlocking {
        val bound = mutableListOf<DatagramSocket>()
        val lan = object : LanAccess { override fun bind(socket: DatagramSocket) { bound += socket } }
        val silent = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val scanner = UdiscoveryScanner(udpPort = silent.localPort, queryTimeoutMs = 200, scanListenMs = 200, lan = lan)
        scanner.query("127.0.0.1")
        scanner.scan(LocalNetwork("127.0.0.10", 30))
        silent.close()
        assertEquals(2, bound.size)
        assertTrue("bound before use, closed after", bound.all { it.isClosed })
    }

    @Test
    fun `scan of an empty subnet finishes and finds nothing`() = runBlocking {
        val scanner = UdiscoveryScanner(udpPort = 47083, scanListenMs = 200)
        val found = scanner.scan(LocalNetwork("127.0.0.10", 30))
        assertTrue(found.isEmpty())
    }
}

class SubnetMathTest {
    @Test
    fun `hosts of a slash-24 exclude network, broadcast and self, nearest first`() {
        val hosts = SubnetMath.hosts("192.168.18.139", 24, limit = 1024)
        assertEquals(253, hosts.size)
        assertFalse("192.168.18.0" in hosts)
        assertFalse("192.168.18.255" in hosts)
        assertFalse("192.168.18.139" in hosts)
        assertEquals("192.168.18.138", hosts[0])
        assertTrue("192.168.18.68" in hosts)
    }

    @Test
    fun `large subnets are narrowed around the phone`() {
        val hosts = SubnetMath.hosts("10.20.30.40", 16, limit = 1024)
        assertTrue(hosts.size <= 1024)
        assertTrue("10.20.30.41" in hosts)
    }

    @Test
    fun `broadcast address and invalid input`() {
        assertEquals("192.168.18.255", SubnetMath.broadcastAddress("192.168.18.139", 24))
        assertTrue(SubnetMath.hosts("not-an-ip", 24, 1024).isEmpty())
        assertNull(SubnetMath.toInt("300.1.1.1"))
    }
}
