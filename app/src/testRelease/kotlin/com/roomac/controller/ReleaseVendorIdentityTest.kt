package com.roomac.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release builds must not carry the HaiSmart or NetHome Plus app identities unless the owner opted in
 * (`roomac.release.vendorAppIdentity=true` in local.properties). See app/build.gradle.kts.
 */
class ReleaseVendorIdentityTest {
    @Test
    fun releaseBuildOmitsVendorAppIdentities() {
        if (BuildConfig.VENDOR_IDENTITY_OPT_IN) return
        assertEquals("", BuildConfig.HAIER_APP_ID)
        assertEquals("", BuildConfig.HAIER_APP_KEY)
        assertEquals("", BuildConfig.MIDEA_APP_KEY)
        // In-app sign-up signs with the vendor identity, so it is forced off when that identity is omitted.
        assertEquals(false, BuildConfig.HAIER_IN_APP_SIGNUP)
    }
}
