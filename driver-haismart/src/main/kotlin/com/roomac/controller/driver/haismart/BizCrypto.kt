package com.roomac.controller.driver.haismart

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * uSS biz-data encryption. Adapted from haismart-local (MIT), see THIRD_PARTY_NOTICES.md.
 *
 * AES-128-CBC, IV = 16 zero bytes, key = MD5(localKey as ASCII). Plaintext layout:
 *   [0:2]   rawlen BE16 = 0x28 + datalen
 *   [2:6]   sn BE32
 *   [6:22]  MD5(pre4 || data)          integrity check, fails on a wrong or rotated key
 *   [22:38] 16 reserved bytes
 *   [38:42] pre4: first four digits of the 5-digit ASCII transport nonce
 *   [42:]   data, zero-padded to a 16-byte multiple
 * Outbound ciphertext is followed by the 5-digit ASCII nonce; the AC rejects an op without it.
 */

class BizIntegrityException(message: String) : Exception(message)

data class BizPayload(val sn: Long, val data: ByteArray)

class BizCrypto(localKey: String, private val random: SecureRandom = SecureRandom()) {

    private val aesKey = SecretKeySpec(md5(localKey.toByteArray(Charsets.US_ASCII)), "AES")

    fun encrypt(sn: Long, data: ByteArray, nonce5: ByteArray? = null): ByteArray {
        val nonce = nonce5?.also { require(it.size == 5) } ?: randomNonce()
        val pre4 = nonce.copyOfRange(0, 4)
        val rawlen = RAWLEN_BIAS + data.size
        val unpadded = ByteArray(42 + data.size)
        Bytes.putBe16(unpadded, 0, rawlen)
        Bytes.putBe32(unpadded, 2, sn and 0xFFFFFFFFL)
        md5(pre4 + data).copyInto(unpadded, 6)
        // [22:38] reserved zeros
        pre4.copyInto(unpadded, 38)
        data.copyInto(unpadded, 42)
        val padded = if (unpadded.size % 16 == 0) unpadded else unpadded.copyOf(unpadded.size + 16 - unpadded.size % 16)
        return cbc(Cipher.ENCRYPT_MODE, padded) + nonce
    }

    /**
     * Decrypts a biz payload as it arrived (trailing nonce included) and verifies its MD5. Throws
     * [BizIntegrityException] on a structural or integrity failure, which with a well-formed frame
     * means the key is wrong or has been rotated.
     */
    fun decrypt(payload: ByteArray): BizPayload {
        val n = payload.size / 16 * 16
        if (n < 48) throw BizIntegrityException("biz ciphertext too short (${payload.size} bytes)")
        val pt = cbc(Cipher.DECRYPT_MODE, payload.copyOfRange(0, n))
        val rawlen = Bytes.be16(pt, 0)
        val sn = Bytes.be32(pt, 2)
        val datalen = rawlen - RAWLEN_BIAS
        if (rawlen <= RAWLEN_BIAS || rawlen > minOf(payload.size - 2, 0xE028) || 0x2A + datalen > pt.size) {
            throw BizIntegrityException("bad rawlen $rawlen for a ${payload.size}-byte payload")
        }
        val check = md5(pt.copyOfRange(0x26, 0x26 + datalen + 4))
        if (!check.contentEquals(pt.copyOfRange(6, 22))) {
            throw BizIntegrityException("integrity check failed (wrong or rotated local key)")
        }
        return BizPayload(sn, EppFrame.destuff(pt.copyOfRange(0x2A, 0x2A + datalen)))
    }

    private fun cbc(mode: Int, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(mode, aesKey, IvParameterSpec(ByteArray(16)))
        return c.doFinal(data)
    }

    private fun randomNonce(): ByteArray =
        (10000 + random.nextInt(90000)).toString().toByteArray(Charsets.US_ASCII)

    companion object {
        private const val RAWLEN_BIAS = 0x28
        /** Shortest uSS payload that can carry biz data. */
        const val MIN_PAYLOAD = 48

        fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
    }
}
