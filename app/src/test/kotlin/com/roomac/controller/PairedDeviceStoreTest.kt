package com.roomac.controller

import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.storage.EncryptedAccountSessions
import com.roomac.controller.storage.KeyValueStore
import com.roomac.controller.storage.PairedDeviceStore
import com.roomac.controller.storage.StorageCipher
import com.roomac.controller.storage.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** Reversible stand-in for the Keystore cipher; proves keys are never stored as given. */
private class FakeCipher(var broken: Boolean = false) : StorageCipher {
    override fun encrypt(plainText: String): String { if (broken) error("keystore unavailable"); return "enc:" + plainText.reversed() }
    override fun decrypt(cipherText: String): String { if (broken) error("keystore unavailable"); return cipherText.removePrefix("enc:").reversed() }
}

class PairedDeviceStoreTest {
    private val key = "00112233445566778899aabbccddeeff"
    private fun dev(id: String, name: String = "Bedroom", host: String = "192.168.18.68") =
        PairedDevice(deviceId = id, name = name, lastKnownHost = host)

    @Test
    fun `devices, keys and settings survive a restart`() {
        val kv = MapStore()
        val a = PairedDeviceStore(kv, FakeCipher())
        a.upsert(dev("A1B2C3D4E5F6"))
        a.upsert(dev("0A0B0C0D0E0F", "Office"))
        a.setLocalKey("A1B2C3D4E5F6", key)
        a.setActive("0A0B0C0D0E0F")
        a.setPollInterval(20_000)

        val b = PairedDeviceStore(kv, FakeCipher())
        assertEquals(listOf("Bedroom", "Office"), b.devices.value.map { it.name })
        assertEquals("0A0B0C0D0E0F", b.activeDeviceId.value)
        assertEquals(key, b.localKeySync("A1B2C3D4E5F6"))
        assertTrue(b.devices.value.first().hasLocalKey)
        assertFalse(b.devices.value.last().hasLocalKey)
        assertEquals(20_000L, b.pollIntervalMs.value)
    }

    @Test
    fun `room and theme survive a restart`() {
        val kv = MapStore()
        PairedDeviceStore(kv, FakeCipher()).apply {
            upsert(dev("A1B2C3D4E5F6").copy(room = "Living room"))
            setThemeMode(ThemeMode.DARK)
        }
        val b = PairedDeviceStore(kv, FakeCipher())
        assertEquals("Living room", b.devices.value.single().room)
        assertEquals(ThemeMode.DARK, b.themeMode.value)
    }

    @Test
    fun `account sessions are stored encrypted, removed on sign-out, and never fall back to plain text`() {
        val kv = MapStore()
        val sessions = EncryptedAccountSessions(kv, FakeCipher())
        assertTrue(sessions.save("haier-sea", "{\"refresh\":\"RT-secret\"}"))
        assertTrue(kv.map.values.none { it.contains("RT-secret") })
        assertEquals("{\"refresh\":\"RT-secret\"}", EncryptedAccountSessions(kv, FakeCipher()).load("haier-sea"))
        assertNull("unreadable means signed out", EncryptedAccountSessions(kv, FakeCipher(broken = true)).load("haier-sea"))
        assertFalse("keystore failure is reported, not ignored", EncryptedAccountSessions(MapStore(), FakeCipher(broken = true)).save("haier-sea", "x"))
        sessions.save("haier-sea", null)
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `the key is never written in plain text`() {
        val kv = MapStore()
        PairedDeviceStore(kv, FakeCipher()).apply { upsert(dev("A1B2C3D4E5F6")); setLocalKey("A1B2C3D4E5F6", key) }
        assertTrue(kv.map.values.none { it.contains(key) })
    }

    @Test
    fun `the same physical AC is stored once, updated on re-pairing`() {
        val s = PairedDeviceStore(MapStore(), FakeCipher())
        s.upsert(dev("A1B2C3D4E5F6", host = "192.168.18.68"))
        s.upsert(dev("a1b2c3d4e5f6", name = "Lounge", host = "192.168.18.90"))
        assertEquals(1, s.devices.value.size)
        assertEquals("Lounge", s.devices.value[0].name)
        assertEquals("192.168.18.90", s.devices.value[0].lastKnownHost)
    }

    @Test
    fun `removing a device erases its key and moves the active selection`() {
        val kv = MapStore()
        val s = PairedDeviceStore(kv, FakeCipher())
        s.upsert(dev("A1B2C3D4E5F6")); s.upsert(dev("0A0B0C0D0E0F", "Office"))
        s.setLocalKey("A1B2C3D4E5F6", key)
        s.setActive("A1B2C3D4E5F6")
        s.remove("A1B2C3D4E5F6")
        assertNull(s.localKeySync("A1B2C3D4E5F6"))
        assertTrue(kv.map.keys.none { it.contains("A1B2C3D4E5F6") })
        assertEquals("0A0B0C0D0E0F", s.activeDeviceId.value)
    }

    @Test
    fun `a keystore failure never falls back to storing the key unencrypted`() {
        val kv = MapStore()
        val cipher = FakeCipher()
        val s = PairedDeviceStore(kv, cipher)
        s.upsert(dev("A1B2C3D4E5F6"))
        cipher.broken = true
        assertFalse(s.setLocalKey("A1B2C3D4E5F6", key))
        assertTrue(kv.map.values.none { it.contains(key) })
        assertNull(s.localKeySync("A1B2C3D4E5F6"))
    }

    @Test
    fun `a new key resets the verified key version`() {
        val s = PairedDeviceStore(MapStore(), FakeCipher())
        s.upsert(dev("A1B2C3D4E5F6").copy(keyVersion = 50))
        s.setLocalKey("A1B2C3D4E5F6", key)
        assertEquals(0, s.devices.value[0].keyVersion)
    }

    @Test
    fun `corrupt saved data starts empty instead of crashing`() {
        val kv = MapStore().apply { map["devices_v2"] = "{not json" }
        assertTrue(PairedDeviceStore(kv, FakeCipher()).devices.value.isEmpty())
    }
}

class ErrorMessagesTest {
    @Test
    fun `every error says what happened and what to do, without secrets`() {
        val errors = listOf(
            AcError.WifiOff, AcError.NotOnWifi, AcError.AcNotFound, AcError.Unreachable("192.168.18.68"),
            AcError.ServiceUnavailable, AcError.SessionBusy, AcError.HandshakeRefused, AcError.KeyMissing,
            AcError.KeyInvalid, AcError.KeyRotated(51, 50), AcError.UnsupportedLayout(129), AcError.ProtocolMismatch,
            AcError.Timeout, AcError.DisconnectedDuringCommand, AcError.CommandRefused(5), AcError.NotSupported("Heat mode"),
            AcError.InvalidValue("31°C is outside 16°C–30°C"), AcError.General("x"),
            AcError.AccountRequired("Haier", "HaiSmart"), AcError.AccountRejected("HaiSmart"), AcError.CloudUnavailable("HaiSmart"),
            AcError.DeviceNotInAccount("HaiSmart"), AcError.AccountUnavailable("Haier")
        )
        val hex32 = Regex("[0-9a-fA-F]{32}")
        errors.forEach {
            assertTrue(it.userMessage.isNotBlank())
            assertTrue("${it::class.simpleName} has no next step", it.nextStep.isNotBlank())
            assertFalse(hex32.containsMatchIn(it.userMessage + it.nextStep))
            assertFalse("${it::class.simpleName} shows an IP address", it.userMessage.contains("192.168"))
        }
    }
}

class DeviceMigrationTest {
    @Test
    fun `devices saved by 2_0 load as Haier uSS`() {
        val old = """[{"deviceId":"540853BD1D41","name":"Bedroom","uPlusId":"x","host":"192.168.18.68","port":56800}]"""
        val d = PairedDeviceStore.decode(old).single()
        assertEquals(ProtocolIds.HAIER_USS, d.protocolId)
        assertEquals("", d.brand)
    }

    @Test
    fun `protocol and brand survive a round trip`() {
        val d = PairedDevice(deviceId = "F4911E0A1B2C", name = "Lounge", protocolId = ProtocolIds.GREE_WIFI, brand = "gree", port = 7000)
        val back = PairedDeviceStore.decode(PairedDeviceStore.encode(listOf(d))).single()
        assertEquals(ProtocolIds.GREE_WIFI, back.protocolId)
        assertEquals("gree", back.brand)
        assertEquals(7000, back.port)
    }
}
