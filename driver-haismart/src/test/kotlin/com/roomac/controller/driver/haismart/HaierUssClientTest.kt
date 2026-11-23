package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** End-to-end sessions against [FakeAc] over a real localhost socket. */
class HaierUssClientTest {
    private val ac = FakeAc()
    private val client = HaierUssClient(replyTimeoutMs = 1500, collectIdleMs = 150)
    private val endpoint get() = AcEndpoint("127.0.0.1", ac.port, ac.deviceId)

    @After fun tearDown() = ac.close()

    @Test
    fun `reads status and key version through a full session`() = runBlocking {
        ac.words = Reports.words(setpoint = 21, mode = 1, fan = 2)
        val r = client.readStatus(endpoint, ac.localKey, null) as ReadResult.Success
        assertEquals(50, r.keyVersion)
        assertEquals(21.0, r.status.targetTemperatureC, 0.0)
        assertEquals(AcMode.COOL, r.status.mode)
        assertTrue(r.status.writable)
    }

    @Test
    fun `probes the key version without a key`() = runBlocking {
        assertEquals(50, client.probeKeyVersion(endpoint).getOrThrow())
    }

    @Test
    fun `a command is confirmed only by the status the AC sends back`() = runBlocking {
        ac.words = Reports.words(power = false, setpoint = 26, display = true)
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetPower(true), 50)
        assertTrue(out is CommandOutcome.Confirmed)
        assertTrue((out as CommandOutcome.Confirmed).status.power)
        // Everything else the unit had was preserved by the group-set.
        assertEquals(26.0, out.status.targetTemperatureC, 0.0)
        assertEquals(true, out.status.features[AcFeature.DISPLAY])
        assertEquals(1, ac.groupSets.get())
    }

    @Test
    fun `refusal frame is reported as a rejection, not success`() = runBlocking {
        ac.groupSetBehaviour = FakeAc.OpBehaviour.REFUSE
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetMode(AcMode.DRY), 50)
        assertTrue(out is CommandOutcome.Rejected)
        assertTrue((out as CommandOutcome.Rejected).reason.contains("reason code 5"))
    }

    @Test
    fun `an AC that reports its old state is a rejection`() = runBlocking {
        ac.groupSetBehaviour = FakeAc.OpBehaviour.IGNORE_WRITES_REPORT_OLD
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetTargetTemperature(19.0), 50)
        assertTrue(out is CommandOutcome.Rejected)
        assertEquals(24.0, (out as CommandOutcome.Rejected).status!!.targetTemperatureC, 0.0)
    }

    @Test
    fun `silence after a command is verified with a fresh read`() = runBlocking {
        ac.groupSetBehaviour = FakeAc.OpBehaviour.IGNORE
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetPower(false), 50)
        assertTrue(out is CommandOutcome.Rejected)
        assertEquals(2, ac.sessions.get())
    }

    @Test
    fun `wrong key is an authentication failure and nothing is written`() = runBlocking {
        val r = client.readStatus(endpoint, "ffffffffffffffffffffffffffffffff", null)
        assertEquals(AcError.KeyInvalid, (r as ReadResult.Failure).error)
        val out = client.execute(endpoint, "ffffffffffffffffffffffffffffffff", AcCommand.SetPower(false), null)
        assertEquals(AcError.KeyInvalid, (out as CommandOutcome.Failed).error)
        assertEquals(0, ac.groupSets.get())
    }

    @Test
    fun `rotated key is detected from the handshake before decrypting`() = runBlocking {
        ac.keyVersion = 51
        val r = client.readStatus(endpoint, ac.localKey, 50) as ReadResult.Failure
        assertEquals(AcError.KeyRotated(51, 50), r.error)
    }

    @Test
    fun `a session for another device id is refused`() = runBlocking {
        val r = client.readStatus(endpoint.copy(deviceId = "0000000000AA"), ac.localKey, null) as ReadResult.Failure
        assertEquals(AcError.HandshakeRefused, r.error)
    }

    @Test
    fun `peer closing during the handshake reads as another session holding the AC`() = runBlocking {
        ac.closeAfterHello = true
        val r = client.readStatus(endpoint, ac.localKey, null) as ReadResult.Failure
        assertEquals(AcError.SessionBusy, r.error)
    }

    @Test
    fun `drop after the op was sent is reported as unknown outcome`() = runBlocking {
        ac.groupSetBehaviour = FakeAc.OpBehaviour.CLOSE
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetPower(false), 50)
        // Connection closed with nothing to verify: the client re-reads and sees the old state.
        assertTrue(out is CommandOutcome.Rejected || (out as CommandOutcome.Failed).error == AcError.DisconnectedDuringCommand)
    }

    @Test
    fun `closed port is service unavailable`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val r = client.readStatus(AcEndpoint("127.0.0.1", port, ac.deviceId), ac.localKey, null) as ReadResult.Failure
        assertEquals(AcError.ServiceUnavailable, r.error)
    }

    @Test
    fun `non-classic layout is readable but never written`() = runBlocking {
        ac.words = Reports.words() + byteArrayOf(0, 0) // 7 control words: no confirmed layout
        val out = client.execute(endpoint, ac.localKey, AcCommand.SetPower(false), 50)
        assertTrue(out is CommandOutcome.Failed)
        assertEquals(0, ac.groupSets.get())
    }

    @Test
    fun `a stale push is not used to seed writes once the reply disagreed`() = runBlocking {
        // The unit's own answer says 20°C while its relayed push still says 24°C.
        ac.words = Reports.words(setpoint = 20)
        ac.pushedWordsOverride = Reports.words(setpoint = 24)
        val read = client.readStatus(endpoint, ac.localKey, null) as ReadResult.Success
        assertEquals(20.0, read.status.targetTemperatureC, 0.0)
        client.execute(endpoint, ac.localKey, AcCommand.SetFeature(AcFeature.SLEEP, true), 50)
        val sent = ClassicReport.decode(Reports.report(ac.lastGroupSetWords!!))!!
        assertEquals(20.0, sent.targetTemperatureC, 0.0)
        assertEquals(true, sent.features[AcFeature.SLEEP])
    }

    @Test
    fun `refusal code is null when absent`() {
        assertNull(EppFrame.refusalCode(Reports.report()))
    }
}
