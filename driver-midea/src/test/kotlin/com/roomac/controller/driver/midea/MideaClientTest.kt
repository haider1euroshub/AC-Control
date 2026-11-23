package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.CommandOutcome
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.DeviceDiscovery
import com.roomac.controller.protocol.DiscoveredDevice
import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import javax.crypto.Cipher
import kotlin.concurrent.thread

/** A simulated Midea module on loopback: V2 (no credential) or V3 (token/key handshake). Test only. */
private class FakeMideaModule(private val v3Key: ByteArray? = null, var applyWrites: Boolean = true) {
    val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    var state = MideaAcState(
        power = false, targetTemperature = 24.0, mode = 2, fanSpeed = 102, swingMode = 0, turbo = false, eco = false,
        sleep = false, fahrenheit = false, indoorTemperature = 27.0, outdoorTemperature = 33.0, displayOn = true,
        errorCode = 0, freezeProtection = false, purifier = false, followMe = false, auxHeat = false,
        independentAuxHeat = false, length = 0
    )
    var handshakes = 0

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                try {
                    s.use { serve(it.getInputStream(), it.getOutputStream()) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun serve(input: InputStream, out: java.io.OutputStream) {
        var session: MideaV3Session? = null
        if (v3Key != null) {
            val hello = read(input)
            val token = hello.copyOfRange(8, hello.size)
            handshakes++
            if (token.size != 64) return
            val plain = ByteArray(32) { (it * 3 + 1).toByte() }
            val payload = byteArrayOf(0, 0) + MideaSecurity.cbc(Cipher.ENCRYPT_MODE, v3Key, plain) + MideaSecurity.sha256(plain)
            out.write(byteArrayOf(0x83.toByte(), 0x70, 0, 64, 0x20, MideaV3Session.TYPE_HANDSHAKE_RESPONSE.toByte()) + payload)
            session = MideaV3Session().apply { sessionKey = ByteArray(32) { (plain[it].toInt() xor v3Key[it].toInt()).toByte() } }
        }
        val raw = read(input)
        val inner = session?.let { (it.decode(raw) as MideaV3Session.Decoded.Data).inner } ?: raw
        val body = MideaFrame.validate(MideaV2Packet.decode(inner), MideaFrame.TYPE_AC)
        if ((body[0].toInt() and 0xFF) == 0x40 && applyWrites) state = parseWrite(body)
        val reply = MideaV2Packet.encode(1L, stateFrame(state))
        out.write(session?.encryptedRequest(reply) ?: reply)
        out.flush()
    }

    private fun parseWrite(b: ByteArray): MideaAcState {
        val p = b.map { it.toInt() and 0xFF }
        return state.copy(
            power = p[1] and 1 != 0, targetTemperature = (p[2] and 0xF) + 16.0, mode = (p[2] ushr 5) and 7,
            fanSpeed = p[3], swingMode = p[7] and 0xF, turbo = p[10] and 2 != 0, eco = p[9] and 0x80 != 0, sleep = p[10] and 1 != 0
        )
    }

    private fun read(input: InputStream): ByteArray {
        val head = ByteArray(6).also { var o = 0; while (o < 6) o += input.read(it, o, 6 - o) }
        val total = MideaV3Session.declaredLength(head) ?: MideaV2Packet.declaredLength(head)!!
        val rest = ByteArray(total - 6).also { var o = 0; while (o < it.size) o += input.read(it, o, it.size - o) }
        return head + rest
    }

    companion object {
        /** Builds a 0xC0 state reply in the layout msmart parses. */
        fun stateFrame(s: MideaAcState): ByteArray {
            val p = IntArray(22)
            p[0] = 0xC0
            p[1] = if (s.power) 1 else 0
            p[2] = ((s.targetTemperature.toInt() - 16) and 0xF) or (s.mode shl 5)
            p[3] = s.fanSpeed
            p[7] = s.swingMode
            p[8] = if (s.turbo) 0x20 else 0
            p[9] = if (s.eco) 0x10 else 0
            p[10] = (if (s.sleep) 1 else 0) or (if (s.turbo) 2 else 0)
            p[11] = ((s.indoorTemperature ?: 0.0) * 2 + 50).toInt()
            p[12] = ((s.outdoorTemperature ?: 0.0) * 2 + 50).toInt()
            val body = p.map { it.toByte() }.toByteArray()
            return MideaFrame.build(MideaFrame.TYPE_AC, MideaFrame.QUERY, body + MideaCrc8.calculate(body).toByte())
        }
    }

    fun close() = server.close()
}

private object NoDiscovery : DeviceDiscovery {
    override suspend fun query(host: String): DiscoveredDevice? = null
    override suspend fun scan(network: LocalNetwork, onFound: (DiscoveredDevice) -> Unit) = emptyList<DiscoveredDevice>()
}

class MideaClientTest {
    private val modules = mutableListOf<FakeMideaModule>()
    private fun module(key: ByteArray? = null) = FakeMideaModule(key).also { modules += it }
    private fun endpoint(m: FakeMideaModule) = AcEndpoint("127.0.0.1", m.server.localPort, "15393162840672")
    private val client = MideaClient(NoDiscovery, timeoutMs = 2000, settleMs = 10)

    @After fun tearDown() = modules.forEach { it.close() }

    @Test
    fun `V2 status read over a real socket`() = runBlocking {
        val m = module()
        val r = client.readStatus(endpoint(m), "v2", null) as ReadResult.Success
        assertEquals(24.0, r.status.targetTemperatureC, 0.0)
        assertEquals(27.0, r.status.indoorTemperatureC!!, 0.0)
    }

    @Test
    fun `a command is confirmed only after the state read back shows it`() = runBlocking {
        val m = module()
        val out = client.execute(endpoint(m), "v2", AcCommand.SetPower(true), null)
        assertTrue(out is CommandOutcome.Confirmed)
        assertTrue(m.state.power)
        assertEquals(24.0, m.state.targetTemperature, 0.0) // nothing else changed
    }

    @Test
    fun `a write the AC ignores is reported as not applied`() = runBlocking {
        val m = module().apply { applyWrites = false }
        val out = client.execute(endpoint(m), "v2", AcCommand.SetTargetTemperature(20.0), null)
        assertTrue(out is CommandOutcome.Rejected)
    }

    @Test
    fun `V3 handshake with the right token and key, then status`() = runBlocking {
        val key = ByteArray(32) { (it + 5).toByte() }
        val m = module(key)
        val cred = MideaCredential.V3(listOf(ByteArray(64) { 9 } to key)).encode()
        val r = client.readStatus(endpoint(m), cred, null)
        assertTrue(r is ReadResult.Success)
        assertEquals(1, m.handshakes)
    }

    @Test
    fun `V3 with a wrong key is a key error, after trying every candidate`() = runBlocking {
        val m = module(ByteArray(32) { 1 })
        val cred = MideaCredential.V3(listOf(ByteArray(64) to ByteArray(32) { 2 }, ByteArray(64) to ByteArray(32) { 3 })).encode()
        val r = client.readStatus(endpoint(m), cred, null) as ReadResult.Failure
        assertEquals(AcError.KeyInvalid, r.error)
        assertEquals(2, m.handshakes)
    }

    @Test
    fun `nothing listening is unreachable, and a missing credential is reported as such`() = runBlocking {
        val closed = ServerSocket(0).also { it.close() }.localPort
        val r = client.readStatus(AcEndpoint("127.0.0.1", closed, "1"), "v2", null) as ReadResult.Failure
        assertTrue(r.error is AcError.Unreachable)
        assertEquals(AcError.KeyMissing, (client.readStatus(AcEndpoint("127.0.0.1", closed, "1"), "garbage", null) as ReadResult.Failure).error)
    }
}
