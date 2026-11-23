package com.roomac.controller.driver.haismart

import com.roomac.controller.protocol.ConnectionMethod
import com.roomac.controller.protocol.CredentialMode
import com.roomac.controller.protocol.EvidenceLevel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class HaierAdapterDescriptorTest {
    @Test
    fun `Haier is local control with an account-issued key, and only adding a new AC needs HaiSmart`() {
        val d = HaierUssAdapter().descriptor
        assertEquals(ConnectionMethod.LOCAL, d.connection)
        assertEquals(CredentialMode.ACCOUNT, d.credentialMode)
        val p = d.externalProvisioning!!
        assertEquals("HaiSmart", p.appName)
        assertNotNull(p.appPackage)
        assertFalse(p.onlyForNewWifiSetup)
        // Status reads and commands are not yet confirmed on the AC: must not claim hardware verification.
        assertEquals(EvidenceLevel.IMPLEMENTED_UNVERIFIED, d.evidence)
        assertTrue(d.limitations.isNotEmpty() && d.licence.isNotBlank())
    }
}
