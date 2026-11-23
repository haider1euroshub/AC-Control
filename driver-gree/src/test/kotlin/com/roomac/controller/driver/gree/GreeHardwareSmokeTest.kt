package com.roomac.controller.driver.gree

import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against real Gree-protocol units only when asked to (PowerShell):
 *
 *   $env:GREE_SCAN_FROM="192.168.18.139/24"; .\gradlew.bat :driver-gree:test --tests '*GreeHardwareSmokeTest*' -i
 *
 * Read-only: scans the subnet, then for each unit found binds (which only returns the unit's key) and
 * reads its status. It never sends a control command and never prints the key.
 */
class GreeHardwareSmokeTest {
    private val lan = System.getenv("GREE_SCAN_FROM")

    @Test
    fun `scan, bind and read every Gree unit on the network`() = runBlocking {
        assumeTrue(lan != null)
        val (address, prefix) = lan!!.split("/").let { it[0] to it[1].toInt() }
        val started = System.currentTimeMillis()
        val found = GreeDiscovery(log = { println(it) }).scan(LocalNetwork(address, prefix))
        println("Gree scan took ${System.currentTimeMillis() - started} ms, found ${found.size}: " +
            found.map { "${it.deviceId}@${it.host} brand=${it.brand} model=${it.model} name='${it.reportedName}' fw=${it.firmware}" })
        val client = GreeClient()
        for (d in found) {
            val endpoint = AcEndpoint(d.host, d.port, d.deviceId)
            val bind = client.bind(endpoint)
            println("${d.deviceId} bind: ${if (bind is ProvisionResult.Success) "ok (${bind.credential.length} characters)" else (bind as ProvisionResult.Failure).error.userMessage}")
            if (bind is ProvisionResult.Success) {
                val read = client.readStatus(endpoint, bind.credential, null)
                println("${d.deviceId} status: ${if (read is ReadResult.Success) "ok, power=${read.status.power} mode=${read.status.mode} target=${read.status.targetTemperatureC} room=${read.status.indoorTemperatureC}" else read}")
                assertTrue(read is ReadResult.Success)
            }
        }
    }
}
