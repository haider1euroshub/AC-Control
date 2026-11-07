package com.roomac.controller.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.DeviceConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StorageCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class AndroidKeyStoreCipher : StorageCipher {
    private val keyAlias = "RoomAcKeystoreKey"

    override fun encrypt(plainText: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + cipherText.size)
        iv.copyInto(combined, 0)
        cipherText.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)
        if (combined.size <= 12) return ""
        val iv = combined.copyOfRange(0, 12)
        val encryptedData = combined.copyOfRange(12, combined.size)

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(encryptedData)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }
}

class EncryptedConfigStorage(
    private val context: Context,
    private val prefsName: String = "room_ac_secure_prefs",
    private val cipher: StorageCipher = AndroidKeyStoreCipher()
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun loadConfiguration(): DeviceConfig {
        val protocolId = prefs.getString(KEY_PROTOCOL_ID, "") ?: ""
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val port = prefs.getInt(KEY_PORT, 56800)
        val macAddress = prefs.getString(KEY_MAC, "") ?: ""

        // Safe Decryption: On failure, fail closed and do NOT guess or expose plaintext
        val encryptedKey = prefs.getString(KEY_LOCAL_KEY_ENCRYPTED, "") ?: ""
        val localKeyHex = if (encryptedKey.isNotBlank()) {
            try {
                cipher.decrypt(encryptedKey)
            } catch (_: Exception) {
                // Key cannot be decrypted (e.g. keystore invalidated); fail closed
                ""
            }
        } else {
            ""
        }

        val hasCustomCaps = prefs.getBoolean(KEY_HAS_CUSTOM_CAPS, false)
        val customCaps = if (hasCustomCaps) {
            val minT = prefs.getFloat(KEY_CAP_MIN_TEMP, 16.0f).toDouble()
            val maxT = prefs.getFloat(KEY_CAP_MAX_TEMP, 30.0f).toDouble()
            val stepT = prefs.getFloat(KEY_CAP_STEP_TEMP, 1.0f).toDouble()
            val heating = prefs.getBoolean(KEY_CAP_HEATING, false)
            AcCapabilities(
                supportsHeating = heating,
                minTemperature = minT,
                maxTemperature = maxT,
                temperatureStep = stepT,
                temperatureUnit = TemperatureUnit.CELSIUS,
                source = CapabilitySource.ENTERED_BY_OWNER,
                areCapabilitiesKnown = true
            )
        } else {
            null
        }

        val hasBaseline = prefs.getBoolean(KEY_HAS_BASELINE, false)
        val baseline = if (hasBaseline) {
            OwnerAssertedBaseline(
                power = if (prefs.getBoolean(KEY_BASE_POWER, true)) PowerState.ON else PowerState.OFF,
                mode = if (prefs.getString(KEY_BASE_MODE, "COOL") == "HEAT") AcMode.HEAT else AcMode.COOL,
                targetTemperature = prefs.getFloat(KEY_BASE_TEMP, 24.0f).toDouble(),
                isEnabled = true
            )
        } else {
            null
        }

        return DeviceConfig(
            protocolId = protocolId,
            host = host,
            port = port,
            macAddress = macAddress,
            localKeyHex = localKeyHex,
            customCapabilities = customCaps,
            ownerBaseline = baseline
        )
    }

    fun saveConfiguration(config: DeviceConfig) {
        val editor = prefs.edit()
        editor.putString(KEY_PROTOCOL_ID, config.protocolId)
        editor.putString(KEY_HOST, config.host)
        editor.putInt(KEY_PORT, config.port)
        editor.putString(KEY_MAC, config.macAddress)

        if (config.localKeyHex.isNotBlank()) {
            try {
                val enc = cipher.encrypt(config.localKeyHex)
                editor.putString(KEY_LOCAL_KEY_ENCRYPTED, enc)
            } catch (_: Exception) {
                // Fail closed: do not write unencrypted key to SharedPreferences
                editor.remove(KEY_LOCAL_KEY_ENCRYPTED)
            }
        } else {
            editor.remove(KEY_LOCAL_KEY_ENCRYPTED)
        }

        val caps = config.customCapabilities
        if (caps != null) {
            editor.putBoolean(KEY_HAS_CUSTOM_CAPS, true)
            editor.putFloat(KEY_CAP_MIN_TEMP, caps.minTemperature.toFloat())
            editor.putFloat(KEY_CAP_MAX_TEMP, caps.maxTemperature.toFloat())
            editor.putFloat(KEY_CAP_STEP_TEMP, caps.temperatureStep.toFloat())
            editor.putBoolean(KEY_CAP_HEATING, caps.supportsHeating)
        } else {
            editor.putBoolean(KEY_HAS_CUSTOM_CAPS, false)
        }

        val baseline = config.ownerBaseline
        if (baseline != null && baseline.isEnabled) {
            editor.putBoolean(KEY_HAS_BASELINE, true)
            editor.putBoolean(KEY_BASE_POWER, baseline.power == PowerState.ON)
            editor.putString(KEY_BASE_MODE, baseline.mode.name)
            editor.putFloat(KEY_BASE_TEMP, baseline.targetTemperature.toFloat())
        } else {
            editor.putBoolean(KEY_HAS_BASELINE, false)
        }

        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PROTOCOL_ID = "pref_protocol_id"
        private const val KEY_HOST = "pref_host"
        private const val KEY_PORT = "pref_port"
        private const val KEY_MAC = "pref_mac"
        private const val KEY_LOCAL_KEY_ENCRYPTED = "pref_local_key_enc"
        private const val KEY_HAS_CUSTOM_CAPS = "pref_has_custom_caps"
        private const val KEY_CAP_MIN_TEMP = "pref_cap_min_temp"
        private const val KEY_CAP_MAX_TEMP = "pref_cap_max_temp"
        private const val KEY_CAP_STEP_TEMP = "pref_cap_step_temp"
        private const val KEY_CAP_HEATING = "pref_cap_heating"
        private const val KEY_HAS_BASELINE = "pref_has_baseline"
        private const val KEY_BASE_POWER = "pref_base_power"
        private const val KEY_BASE_MODE = "pref_base_mode"
        private const val KEY_BASE_TEMP = "pref_base_temp"
    }
}
