package com.roomac.controller.driver.haismart

import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real appliance only when asked to:
 *
 *   AC_HOST=192.168.18.68 AC_DEVICE_ID=540853BD1D41 ./gradlew :driver-haismart:test --tests '*HardwareSmokeTest*'
 *
 * Read-only. Without AC_LOCAL_KEY it exercises only the key-free steps (discovery and the handshake);
 * with it, it also reads status. It never sends a control command.
 */
class HardwareSmokeTest {
    private val host = System.getenv("AC_HOST")
    private val deviceId = System.getenv("AC_DEVICE_ID")
    private val key = System.getenv("AC_LOCAL_KEY")
    private val lan = System.getenv("AC_SCAN_FROM") // e.g. "192.168.18.139/24"

    @Test
    fun `discovery identifies the appliance`() = runBlocking {
        assumeTrue(host != null)
        val info = UdiscoveryScanner().query(host!!)
        println("UDISCOVERY: $info")
        assertTrue("no UDISCOVERY reply from $host", info != null)
        if (deviceId != null) assertEquals(deviceId, info!!.deviceId)
    }

    @Test
    fun `subnet scan finds the appliance without its address`() = runBlocking {
        assumeTrue(lan != null && deviceId != null)
        val (ip, prefix) = lan!!.split('/')
        val started = System.currentTimeMillis()
        val all = UdiscoveryScanner().scan(LocalNetwork(ip, prefix.toInt()))
        println("scan took ${System.currentTimeMillis() - started} ms, found ${all.map { "${it.deviceId}@${it.host}" }}")
        assertTrue(all.any { it.deviceId == deviceId })
    }

    @Test
    fun `handshake is accepted for this device id and reports the key version`() = runBlocking {
        assumeTrue(host != null && deviceId != null)
        val version = HaierUssClient().probeKeyVersion(AcEndpoint(host!!, 56800, deviceId!!)).getOrThrow()
        println("localKey version: $version")
        assertTrue(version > 0)
    }

    @Test
    fun `status read with the local key`() = runBlocking {
        assumeTrue(host != null && deviceId != null && key != null)
        val r = HaierUssClient(log = { println(it) }).readStatus(AcEndpoint(host!!, 56800, deviceId!!), key!!, null)
        println("read: $r")
        assertTrue(r is ReadResult.Success)
    }
}
