package com.roomac.controller

import com.roomac.controller.permissions.CheckId
import com.roomac.controller.permissions.CheckStatus
import com.roomac.controller.permissions.PermissionState
import com.roomac.controller.permissions.Preflight
import com.roomac.controller.permissions.PreflightInputs
import com.roomac.controller.protocol.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {

    private val home = LocalNetwork("192.168.18.139", 24)

    private fun inputs(
        sdk: Int = 34, wifiEnabled: Boolean = true, onWifi: Boolean = true, local: LocalNetwork? = home,
        nearby: PermissionState = PermissionState.GRANTED, location: Boolean = true,
        btSupported: Boolean = true, btOn: Boolean = false, acHost: String? = null
    ) = PreflightInputs(sdk, wifiEnabled, onWifi, local, nearby, location, btSupported, btOn, acHost)

    @Test
    fun `everything required is green so the scan is ready, even with Bluetooth off`() {
        val r = Preflight.evaluate(inputs(btOn = false))
        assertTrue(r.ready)
        assertEquals(CheckStatus.OFF, r[CheckId.BLUETOOTH].status)
        assertFalse(r[CheckId.BLUETOOTH].required)
    }

    @Test
    fun `wifi off and wifi on but not joined are told apart, and both block the scan`() {
        val off = Preflight.evaluate(inputs(wifiEnabled = false, onWifi = false, local = null))
        assertEquals(CheckStatus.OFF, off[CheckId.WIFI].status)
        assertFalse(off.ready)
        val notJoined = Preflight.evaluate(inputs(onWifi = false, local = null))
        assertEquals(CheckStatus.NOT_CONNECTED, notJoined[CheckId.WIFI].status)
        assertFalse(notJoined.ready)
    }

    @Test
    fun `missing and permanently denied nearby permission block the scan`() {
        assertEquals(CheckStatus.NEEDS_PERMISSION, Preflight.evaluate(inputs(nearby = PermissionState.ASK))[CheckId.NEARBY].status)
        val denied = Preflight.evaluate(inputs(nearby = PermissionState.DENIED_PERMANENTLY))
        assertEquals(CheckStatus.DENIED_PERMANENTLY, denied[CheckId.NEARBY].status)
        assertFalse(denied.ready)
    }

    @Test
    fun `location services matter only below Android 13`() {
        assertEquals(CheckStatus.OFF, Preflight.evaluate(inputs(sdk = 32, location = false))[CheckId.NEARBY].status)
        assertEquals(CheckStatus.OFF, Preflight.evaluate(inputs(sdk = 29, location = false))[CheckId.NEARBY].status)
        assertEquals(CheckStatus.OK, Preflight.evaluate(inputs(sdk = 33, location = false))[CheckId.NEARBY].status)
    }

    @Test
    fun `permissions requested per Android version`() {
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), Preflight.nearbyPermissions(33))
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), Preflight.nearbyPermissions(34))
        // Android 12 refuses a fine request without coarse.
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), Preflight.nearbyPermissions(31))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), Preflight.nearbyPermissions(26))
        assertEquals("android.permission.BLUETOOTH_CONNECT", Preflight.bluetoothConnectPermission(31))
        assertNull(Preflight.bluetoothConnectPermission(30))
    }

    @Test
    fun `permanent denial is recognised only after the dialog has been shown once`() {
        assertEquals(PermissionState.GRANTED, Preflight.permissionState(granted = true, askedBefore = true, showRationale = false))
        // Never asked: no rationale yet, but the dialog will still show.
        assertEquals(PermissionState.ASK, Preflight.permissionState(granted = false, askedBefore = false, showRationale = false))
        // Refused once: Android offers a rationale and will show the dialog again.
        assertEquals(PermissionState.ASK, Preflight.permissionState(granted = false, askedBefore = true, showRationale = true))
        // Refused with "Don't ask again" (or twice on Android 11+).
        assertEquals(PermissionState.DENIED_PERMANENTLY, Preflight.permissionState(granted = false, askedBefore = true, showRationale = false))
    }

    @Test
    fun `same network check uses the phone's subnet and warns without blocking`() {
        assertEquals(CheckStatus.UNKNOWN, Preflight.evaluate(inputs(acHost = null))[CheckId.SAME_NETWORK].status)
        assertEquals(CheckStatus.OK, Preflight.evaluate(inputs(acHost = "192.168.18.68"))[CheckId.SAME_NETWORK].status)
        val other = Preflight.evaluate(inputs(acHost = "192.168.1.68"))
        assertEquals(CheckStatus.DIFFERENT_NETWORK, other[CheckId.SAME_NETWORK].status)
        assertTrue("a moved AC must not stop the search", other.ready)
    }

    @Test
    fun `subnet membership`() {
        assertTrue(Preflight.sameSubnet(home, "192.168.18.1"))
        assertTrue(Preflight.sameSubnet(home, "192.168.18.255"))
        assertFalse(Preflight.sameSubnet(home, "192.168.19.68"))
        assertTrue(Preflight.sameSubnet(LocalNetwork("10.0.1.7", 22), "10.0.3.200"))
        assertFalse(Preflight.sameSubnet(LocalNetwork("10.0.1.7", 22), "10.0.4.1"))
        assertFalse(Preflight.sameSubnet(home, "not-an-ip"))
    }

    // Regression (emulator, 2026-11-23): Wi-Fi switched off with the screen open was not noticed, and
    // switching it back on did not search again.
    @Test
    fun `a search starts the first time the checklist is ready and again after Wi-Fi comes back`() {
        assertTrue(Preflight.shouldAutoScan(previouslyReady = null, ready = true, scanning = false, onFindStep = true))
        assertFalse("not again while it stays ready", Preflight.shouldAutoScan(true, true, false, true))
        assertFalse("nothing while not ready", Preflight.shouldAutoScan(true, false, false, true))
        assertTrue("ready again after Wi-Fi came back", Preflight.shouldAutoScan(false, true, false, true))
        assertFalse("not on top of a running search", Preflight.shouldAutoScan(false, true, true, true))
        assertFalse("not after an AC was picked", Preflight.shouldAutoScan(false, true, false, false))
    }
}
