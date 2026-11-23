package com.roomac.controller.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.roomac.controller.domain.model.PairedDevice
import com.roomac.controller.domain.model.ProtocolIds
import com.roomac.controller.protocol.DeviceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App appearance: follow the phone, or always light / dark. */
enum class ThemeMode(val label: String) { SYSTEM("Same as phone"), LIGHT("Light"), DARK("Dark") }

/** Minimal key-value persistence so the store can be tested off-device. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

interface StorageCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/** AES-256-GCM with a non-exportable key held in the Android Keystore. */
class AndroidKeyStoreCipher(private val keyAlias: String = "RoomAcLocalKeys") : StorageCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)
        require(combined.size > 12) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

/**
 * Saved air conditioners. Device records are plain JSON (they hold no secrets); each local key is
 * stored separately, encrypted with a Keystore key, and is deleted when its device is removed. If the
 * Keystore cannot decrypt a key (e.g. after a device restore), the key is treated as missing: the
 * app never falls back to an unencrypted copy.
 */
class PairedDeviceStore(
    private val kv: KeyValueStore,
    private val cipher: StorageCipher
) : DeviceStore {

    private val _devices = MutableStateFlow(loadDevices())
    override val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    private val _activeDeviceId = MutableStateFlow(kv.getString(KEY_ACTIVE)?.takeIf { id -> _devices.value.any { it.deviceId == id } }
        ?: _devices.value.firstOrNull()?.deviceId)
    override val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == kv.getString(KEY_THEME) } ?: ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _pollIntervalMs = MutableStateFlow(kv.getString(KEY_POLL)?.toLongOrNull() ?: 10_000L)
    val pollIntervalMs: StateFlow<Long> = _pollIntervalMs.asStateFlow()

    override suspend fun localKey(deviceId: String): String? = localKeySync(deviceId)

    fun localKeySync(deviceId: String): String? {
        val enc = kv.getString(keyFor(deviceId)) ?: return null
        return try { cipher.decrypt(enc) } catch (_: Exception) { null }
    }

    override suspend fun update(device: PairedDevice) = upsert(device)

    override suspend fun saveCredential(deviceId: String, credential: String?): Boolean = setLocalKey(deviceId, credential)

    /**
     * Adds or replaces a device. The same physical AC (same device ID) is never stored twice: adding
     * it again updates the existing record.
     */
    @Synchronized
    fun upsert(device: PairedDevice) {
        val id = device.deviceId.uppercase()
        val normalized = device.copy(deviceId = id, hasLocalKey = kv.getString(keyFor(id)) != null)
        val list = _devices.value.toMutableList()
        val i = list.indexOfFirst { it.deviceId == id }
        if (i >= 0) list[i] = normalized else list += normalized
        persist(list)
        if (_activeDeviceId.value == null) setActive(id)
    }

    /** Saves a key, or clears it with null. Returns false if the Keystore refused to encrypt it. */
    @Synchronized
    fun setLocalKey(deviceId: String, key: String?): Boolean {
        val id = deviceId.uppercase()
        val ok = if (key == null) {
            kv.remove(keyFor(id)); true
        } else {
            try { kv.putString(keyFor(id), cipher.encrypt(key)); true } catch (_: Exception) { false }
        }
        _devices.value.firstOrNull { it.deviceId == id }?.let { d ->
            persist(_devices.value.map { if (it.deviceId == id) d.copy(hasLocalKey = key != null && ok, keyVersion = 0) else it })
        }
        return ok
    }

    @Synchronized
    fun remove(deviceId: String) {
        val id = deviceId.uppercase()
        kv.remove(keyFor(id))
        val list = _devices.value.filterNot { it.deviceId == id }
        persist(list)
        if (_activeDeviceId.value == id) setActive(list.firstOrNull()?.deviceId)
    }

    fun setActive(deviceId: String?) {
        if (deviceId == null) kv.remove(KEY_ACTIVE) else kv.putString(KEY_ACTIVE, deviceId)
        _activeDeviceId.value = deviceId
    }

    fun setThemeMode(mode: ThemeMode) {
        kv.putString(KEY_THEME, mode.name)
        _themeMode.value = mode
    }

    fun setPollInterval(ms: Long) {
        kv.putString(KEY_POLL, ms.toString())
        _pollIntervalMs.value = ms
    }

    private fun persist(list: List<PairedDevice>) {
        kv.putString(KEY_DEVICES, encode(list))
        _devices.value = list
    }

    private fun loadDevices(): List<PairedDevice> = try {
        decode(kv.getString(KEY_DEVICES) ?: "[]").map { it.copy(hasLocalKey = kv.getString(keyFor(it.deviceId)) != null) }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val KEY_DEVICES = "devices_v2"
        private const val KEY_ACTIVE = "active_device"
        private const val KEY_POLL = "poll_interval_ms"
        private const val KEY_THEME = "theme_mode"
        private fun keyFor(deviceId: String) = "localkey_enc_$deviceId"

        fun create(context: Context): PairedDeviceStore = PairedDeviceStore(
            SharedPrefsStore(context.getSharedPreferences("room_ac_devices", Context.MODE_PRIVATE)),
            AndroidKeyStoreCipher()
        )

        fun encode(list: List<PairedDevice>): String = JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().apply {
                    put("deviceId", d.deviceId); put("name", d.name); put("room", d.room); put("uPlusId", d.uPlusId)
                    put("protocol", d.protocolId); put("brand", d.brand)
                    put("model", d.modelNumber); put("serial", d.serialNumber); put("productCode", d.productCode)
                    put("host", d.lastKnownHost); put("port", d.port); put("firmware", d.firmware)
                    put("keyVersion", d.keyVersion); put("addedAt", d.addedAtEpochMs)
                    put("verified", JSONArray(d.verifiedControls.sorted()))
                })
            }
        }.toString()

        fun decode(json: String): List<PairedDevice> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val verified = o.optJSONArray("verified")
                PairedDevice(
                    deviceId = o.getString("deviceId"), name = o.optString("name"), room = o.optString("room"), uPlusId = o.optString("uPlusId"),
                    // Records from 2.0 have no protocol field: they were all Haier uSS.
                    protocolId = o.optString("protocol").ifEmpty { ProtocolIds.HAIER_USS }, brand = o.optString("brand"),
                    modelNumber = o.optString("model"), serialNumber = o.optString("serial"),
                    productCode = o.optString("productCode"), lastKnownHost = o.optString("host"),
                    port = o.optInt("port", PairedDevice.DEFAULT_USS_PORT), firmware = o.optString("firmware"),
                    keyVersion = o.optInt("keyVersion", 0), addedAtEpochMs = o.optLong("addedAt", 0L),
                    verifiedControls = if (verified == null) emptySet() else (0 until verified.length()).map { verified.getString(it) }.toSet()
                )
            }.distinctBy { it.deviceId }
        }
    }
}
