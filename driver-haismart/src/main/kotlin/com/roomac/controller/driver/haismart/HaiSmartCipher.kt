package com.roomac.controller.driver.haismart

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HaiSmartCipher {

    // Default zero IV used in Haier U+ local framing if IV is not carried in the packet prefix
    val DEFAULT_IV = ByteArray(16) { 0x00 }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun encryptAes128Cbc(
        plainData: ByteArray,
        keyBytes: ByteArray,
        ivBytes: ByteArray = DEFAULT_IV
    ): ByteArray {
        require(keyBytes.size == 16) { "AES-128 requires exactly 16 key bytes, got ${keyBytes.size}" }
        require(ivBytes.size == 16) { "AES-128 CBC requires a 16-byte IV, got ${ivBytes.size}" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plainData)
    }

    fun decryptAes128Cbc(
        encryptedData: ByteArray,
        keyBytes: ByteArray,
        ivBytes: ByteArray = DEFAULT_IV
    ): ByteArray {
        require(keyBytes.size == 16) { "AES-128 requires exactly 16 key bytes, got ${keyBytes.size}" }
        require(ivBytes.size == 16) { "AES-128 CBC requires a 16-byte IV, got ${ivBytes.size}" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(encryptedData)
    }
}

