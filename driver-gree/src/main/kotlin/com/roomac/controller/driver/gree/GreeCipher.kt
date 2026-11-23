package com.roomac.controller.driver.gree

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Gree Wi-Fi module encryption. Independent Kotlin implementation of the publicly documented protocol
 * (protocol facts cross-checked against greeclimate, GPL-3.0; no code copied; see THIRD_PARTY_NOTICES.md).
 *
 * V1: AES-128-ECB, PKCS#7 padding. V2 (newer modules): AES-128-GCM with a fixed nonce and AAD; the
 * ciphertext travels in "pack" and the 16-byte tag in a separate "tag" field, both base64.
 *
 * The two "generic" keys are public protocol constants shared by every Gree module: they protect only
 * the scan reply and the bind exchange, which hands back the per-device key. They are not account
 * credentials or user secrets (see docs/SECURITY.md).
 */
enum class GreeCipherVersion(val code: Int) {
    V1_ECB(1), V2_GCM(2);

    companion object {
        fun fromCode(code: Int): GreeCipherVersion? = entries.firstOrNull { it.code == code }
    }
}

class GreeCryptoException(message: String) : Exception(message)

data class Sealed(val pack: String, val tag: String?)

class GreeCipher(val version: GreeCipherVersion, key: String) {
    private val keyBytes = key.toByteArray(Charsets.UTF_8)

    init {
        require(keyBytes.size in setOf(16, 24, 32)) { "Gree keys are 16, 24 or 32 bytes" }
    }

    fun seal(json: String): Sealed {
        val plain = json.toByteArray(Charsets.UTF_8)
        return when (version) {
            GreeCipherVersion.V1_ECB -> {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
                Sealed(b64(c.doFinal(plain)), null)
            }
            GreeCipherVersion.V2_GCM -> {
                val c = gcm(Cipher.ENCRYPT_MODE)
                val out = c.doFinal(plain)
                // Java appends the tag; the protocol sends it separately.
                Sealed(b64(out.copyOfRange(0, out.size - TAG_BYTES)), b64(out.copyOfRange(out.size - TAG_BYTES, out.size)))
            }
        }
    }

    /** Decrypts a pack. Throws [GreeCryptoException] when the key is wrong or the data is damaged. */
    fun open(pack: String, tag: String?): String {
        val data = try { Base64.getDecoder().decode(pack.trim()) } catch (e: IllegalArgumentException) { throw GreeCryptoException("pack is not base64") }
        val plain = try {
            when (version) {
                GreeCipherVersion.V1_ECB -> {
                    if (data.isEmpty() || data.size % 16 != 0) throw GreeCryptoException("bad block length")
                    val c = Cipher.getInstance("AES/ECB/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
                    c.doFinal(data)
                }
                GreeCipherVersion.V2_GCM -> {
                    val t = tag ?: throw GreeCryptoException("GCM reply without tag")
                    val tagBytes = try { Base64.getDecoder().decode(t.trim()) } catch (e: IllegalArgumentException) { throw GreeCryptoException("tag is not base64") }
                    gcm(Cipher.DECRYPT_MODE).doFinal(data + tagBytes)
                }
            }
        } catch (e: GreeCryptoException) {
            throw e
        } catch (e: Exception) {
            throw GreeCryptoException("decryption failed")
        }
        // Like the reference implementation, keep everything up to the last '}' (drops padding).
        val text = String(plain, Charsets.UTF_8)
        val end = text.lastIndexOf('}')
        if (!text.trimStart().startsWith("{") || end < 0) throw GreeCryptoException("not JSON: wrong key")
        return text.substring(0, end + 1)
    }

    private fun gcm(mode: Int): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BYTES * 8, GCM_NONCE))
        updateAAD(GCM_AAD)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val GENERIC_KEY_V1 = "a3K8Bx%2r8Y7#xDh"
        const val GENERIC_KEY_V2 = "{yxAHAY_Lm6pbC/<"
        private val GCM_NONCE = byteArrayOf(0x54, 0x40, 0x78, 0x44, 0x49, 0x67, 0x5a, 0x51, 0x6c, 0x5e, 0x63, 0x13)
        private val GCM_AAD = "qualcomm-test".toByteArray(Charsets.US_ASCII)
        private const val TAG_BYTES = 16

        fun generic(version: GreeCipherVersion) =
            GreeCipher(version, if (version == GreeCipherVersion.V1_ECB) GENERIC_KEY_V1 else GENERIC_KEY_V2)
    }
}

/**
 * The stored credential: the per-device key the AC handed out at bind time plus which cipher it
 * speaks, as "<version>:<key>". Kept in the Keystore-encrypted store like every other credential.
 */
data class GreeCredential(val version: GreeCipherVersion, val key: String) {
    fun encode(): String = "${version.code}:$key"

    companion object {
        fun decode(raw: String): GreeCredential? {
            val sep = raw.indexOf(':')
            if (sep != 1) return null
            val version = raw[0].digitToIntOrNull()?.let { GreeCipherVersion.fromCode(it) } ?: return null
            val key = raw.substring(2)
            return if (key.toByteArray(Charsets.UTF_8).size in setOf(16, 24, 32)) GreeCredential(version, key) else null
        }
    }
}
