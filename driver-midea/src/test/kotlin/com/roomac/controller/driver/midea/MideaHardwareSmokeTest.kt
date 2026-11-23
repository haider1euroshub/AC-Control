package com.roomac.controller.driver.midea

import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against real Midea units only when asked to (PowerShell):
 *
 *   $env:MIDEA_SCAN_FROM="192.168.18.139/24"; .\gradlew.bat :driver-midea:test --tests '*MideaHardwareSmokeTest*' -i
 *
 * Read-only: scans, and reads the state of every V2 unit found (V2 needs no credential). V3 units are
 * only listed; reading them needs the owner's NetHome Plus sign-in in the app. Sends no command.
 */
class MideaHardwareSmokeTest {
    private val lan = System.getenv("MIDEA_SCAN_FROM")

    @Test
    fun `scan and read every Midea V2 unit on the network`() = runBlocking {
        assumeTrue(lan != null)
        val (address, prefix) = lan!!.split("/").let { it[0] to it[1].toInt() }
        val started = System.currentTimeMillis()
        val discovery = MideaDiscovery(log = { println(it) })
        val found = discovery.scan(LocalNetwork(address, prefix))
        println("Midea scan took ${System.currentTimeMillis() - started} ms, found ${found.size}: " +
            found.map { "${it.deviceId}@${it.host}:${it.port} ${it.protocolId}" })
        val client = MideaClient(discovery)
        found.filter { it.sdkVersion == "V2" }.forEach { d ->
            val r = client.readStatus(AcEndpoint(d.host, d.port, d.deviceId), MideaCredential.None.encode(), null)
            println("${d.deviceId} status: ${if (r is ReadResult.Success) "ok, power=${r.status.power} mode=${r.status.mode} target=${r.status.targetTemperatureC}" else r}")
        }
    }
}
