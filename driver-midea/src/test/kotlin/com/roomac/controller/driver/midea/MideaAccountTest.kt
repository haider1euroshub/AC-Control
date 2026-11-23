package com.roomac.controller.driver.midea

import com.roomac.controller.domain.model.AcError
import com.roomac.controller.protocol.AccountSessionStore
import com.roomac.controller.protocol.ProvisionResult
import com.roomac.controller.protocol.SignInResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

private val IDENTITY = MideaAppIdentity(appKey = "test-app-key") // synthetic; the real key lives in local.properties
private const val APPLIANCE = 147334558165565L

/** A simulated NetHome Plus service. Records every form it receives. */
private class FakeNetHome(var password: String = "pw") : MideaHttp {
    val forms = mutableListOf<Pair<String, Map<String, String>>>()
    var down = false
    var tokenFor: Set<String> = setOf(MideaAccount.udpidHex(APPLIANCE, false))

    override fun postForm(url: String, form: String): Pair<Int, String> {
        if (down) throw IOException("no route")
        val path = url.substringAfter(".com")
        val f = form.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        forms += path to f
        val signed = NetHomePlusClient.sign(path, f - "sign", IDENTITY.appKey)
        if (signed != f["sign"]) return 200 to """{"errorCode":"3301","msg":"sign illegal"}"""
        return when (path) {
            "/v1/user/login/id/get" -> 200 to """{"errorCode":"0","result":{"loginId":"LID"}}"""
            "/v1/user/login" ->
                if (f["password"] == NetHomePlusClient.passwordHash("LID", password, IDENTITY.appKey)) 200 to """{"errorCode":"0","result":{"sessionId":"S1"}}"""
                else 200 to """{"errorCode":"3106","msg":"password error"}"""
            "/v1/iot/secure/getToken" -> {
                if (f["sessionId"] != "S1") return 200 to """{"errorCode":"3106","msg":"session invalid"}"""
                val u = f.getValue("udpid")
                val list = if (u in tokenFor) """[{"udpId":"$u","token":"${"ab".repeat(64)}","key":"${"cd".repeat(32)}"}]""" else "[]"
                200 to """{"errorCode":"0","result":{"tokenlist":$list}}"""
            }
            else -> 404 to ""
        }
    }
}

private class Memory : AccountSessionStore {
    val map = HashMap<String, String>()
    override fun load(providerId: String) = map[providerId]
    override fun save(providerId: String, session: String?): Boolean { if (session == null) map.remove(providerId) else map[providerId] = session; return true }
}

class MideaAccountTest {
    private fun account(svc: FakeNetHome = FakeNetHome(), store: Memory = Memory()) = MideaAccount(IDENTITY, store, svc, Dispatchers.Unconfined)

    @Test
    fun `sign-in sends a hash, never the password, and requests are signed`() = runTest {
        val svc = FakeNetHome()
        assertEquals(SignInResult.Success, account(svc).signIn("me@example.com", "pw", "global", false))
        assertFalse(svc.forms.any { (_, f) -> f.values.any { it == "pw" } })
        assertEquals(listOf("/v1/user/login/id/get", "/v1/user/login"), svc.forms.map { it.first })
    }

    @Test
    fun `token and key are fetched for the device, asking both byte orders`() = runTest {
        val svc = FakeNetHome()
        val a = account(svc)
        a.signIn("me@example.com", "pw", "global", false)
        val cred = (a.fetchCredential(APPLIANCE.toString()) as ProvisionResult.Success).credential
        val v3 = MideaCredential.decode(cred) as MideaCredential.V3
        assertEquals(1, v3.candidates.size)
        assertEquals(2, svc.forms.count { it.first == "/v1/iot/secure/getToken" })
    }

    @Test
    fun `wrong password is refused and nothing is saved`() = runTest {
        val store = Memory()
        val r = account(FakeNetHome(password = "other"), store).signIn("me@example.com", "pw", "global", true)
        assertTrue((r as SignInResult.Failure).error is AcError.AccountRejected)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `an AC the account has no token for is reported as not in the account`() = runTest {
        val svc = FakeNetHome().apply { tokenFor = emptySet() }
        val a = account(svc)
        a.signIn("me@example.com", "pw", "global", false)
        assertTrue((a.fetchCredential(APPLIANCE.toString()) as ProvisionResult.Failure).error is AcError.DeviceNotInAccount)
    }

    @Test
    fun `after a restart, stay-signed-in signs in by itself, without it the user is asked`() = runTest {
        val store = Memory()
        account(store = store).signIn("me@example.com", "pw", "global", staySignedIn = true)
        assertTrue(account(store = store).fetchCredential(APPLIANCE.toString()) is ProvisionResult.Success)

        val store2 = Memory()
        account(store = store2).signIn("me@example.com", "pw", "global", staySignedIn = false)
        val again = account(store = store2)
        assertTrue((again.fetchCredential(APPLIANCE.toString()) as ProvisionResult.Failure).error is AcError.AccountRejected)
        assertTrue(again.accounts().single().needsSignIn)
    }

    @Test
    fun `a refused saved password is deleted, and no internet is not mistaken for a refusal`() = runTest {
        val store = Memory()
        account(store = store).signIn("me@example.com", "pw", "global", staySignedIn = true)
        assertTrue((account(FakeNetHome().apply { down = true }, store).fetchCredential(APPLIANCE.toString()) as ProvisionResult.Failure).error is AcError.CloudUnavailable)
        assertTrue(store.map.values.single().contains("\"pw\""))
        account(FakeNetHome(password = "changed"), store).fetchCredential(APPLIANCE.toString())
        assertFalse(store.map.values.single().contains("\"pw\""))
    }

    @Test
    fun `without an app identity Midea sign-in is unavailable and nothing is sent`() = runTest {
        val svc = FakeNetHome()
        val a = MideaAccount(MideaAppIdentity(appKey = ""), Memory(), svc, Dispatchers.Unconfined)
        assertFalse(a.available)
        assertTrue((a.signIn("x", "y", "global", false) as SignInResult.Failure).error is AcError.AccountUnavailable)
        assertTrue(svc.forms.isEmpty())
    }
}
