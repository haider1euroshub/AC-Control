package com.roomac.controller.driver.haismart.cloud

import com.roomac.controller.driver.haismart.HaierUssClient
import com.roomac.controller.protocol.AcEndpoint
import com.roomac.controller.protocol.AccountSessionStore
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.ReadResult
import com.roomac.controller.protocol.SignInResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against Haier's real service and a real AC only when asked to (PowerShell shown; the password
 * is read from the environment, never from a file or the command line history):
 *
 *   $env:HAISMART_USER="you@example.com"; $env:HAISMART_PASSWORD=Read-Host; $env:HAISMART_REGION="92"
 *   (the HaiSmart app identity is read from local.properties; HAIER_APP_ID/HAIER_APP_KEY override it)
 *   $env:AC_DEVICE_ID="540853BD1D41"; $env:AC_HOST="192.168.18.68"
 *   .\gradlew.bat :driver-haismart:test --tests '*HaierAccountSmokeTest*' -i
 *
 * Read-only: signs in, lists the account's devices, fetches the AC's key and (with AC_HOST) proves it
 * with a local status read. It never sends a control command and never prints the key or tokens.
 */
class HaierAccountSmokeTest {
    private val user = System.getenv("HAISMART_USER")
    private val password = System.getenv("HAISMART_PASSWORD")
    private val region = System.getenv("HAISMART_REGION") ?: "92"
    private val identity = configuredIdentity()
    private val deviceId = System.getenv("AC_DEVICE_ID")
    private val host = System.getenv("AC_HOST")

    private val memory = object : AccountSessionStore {
        var s: String? = null
        override fun load(providerId: String) = s
        override fun save(providerId: String, session: String?): Boolean { s = session; return true }
    }

    @Test
    fun `sign in, find the AC in the account, fetch its key and prove it locally`() = runBlocking {
        assumeTrue(user != null && password != null && identity.isConfigured && deviceId != null)
        println("app identity: from ${if (System.getenv("HAIER_APP_KEY").isNullOrBlank()) "local.properties" else "environment"}, configured=${identity.isConfigured}")
        val account = HaierAccount(identity, memory, log = { println(it) })
        val signIn = account.signIn(user!!, password!!, region, staySignedIn = false)
        println("sign-in: ${if (signIn is SignInResult.Failure) signIn.error.userMessage else "ok"}")
        assertTrue(signIn is SignInResult.Success)

        val devices = account.devices()
        println("account devices: ${devices.map { "${it.deviceId} '${it.name}' ${it.model} online=${it.online}" }}")

        val key = account.fetchCredential(deviceId!!)
        println("key fetch: ${if (key is ProvisionResult.Failure) key.error.userMessage else "ok (${(key as ProvisionResult.Success).credential.length} characters)"}")
        assertTrue(key is ProvisionResult.Success)

        if (host != null) {
            val read = HaierUssClient().readStatus(AcEndpoint(host, 56800, deviceId), (key as ProvisionResult.Success).credential, null)
            println("local read with fetched key: ${if (read is ReadResult.Success) "ok, power=${read.status.power} target=${read.status.targetTemperatureC}" else read}")
            assertTrue(read is ReadResult.Success)
        }
    }
}
