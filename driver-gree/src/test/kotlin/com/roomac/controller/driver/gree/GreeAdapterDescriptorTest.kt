package com.roomac.controller.driver.gree

import com.roomac.controller.protocol.ConnectionMethod
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.EvidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GreeAdapterDescriptorTest {
    @Test
    fun `Gree is local control with automatic pairing, and GREE+ is needed only for first Wi-Fi setup`() {
        val d = GreeAdapter().descriptor
        assertEquals(ConnectionMethod.LOCAL, d.connection)
        assertEquals(CredentialMode.AUTOMATIC, d.credentialMode)
        assertNull(d.accountProviderId)
        val p = d.externalProvisioning!!
        assertTrue(p.onlyForNewWifiSetup)
        // The GREE+ package name is not verified, so no "open app" button is offered.
        assertNull(p.appPackage)
        assertEquals(EvidenceLevel.IMPLEMENTED_UNVERIFIED, d.evidence)
        assertTrue(d.licence.contains("GPL"))
    }
}
