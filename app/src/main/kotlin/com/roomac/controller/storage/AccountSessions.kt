package com.roomac.controller.storage

import android.content.Context
import com.roomac.controller.protocol.AccountSessionStore

/**
 * Manufacturer account sessions (refresh token and the terminal id it is bound to), encrypted with
 * the same non-exportable Keystore key as the AC keys. Unreadable data counts as signed out: there is
 * never a plain-text fallback.
 */
class EncryptedAccountSessions(
    private val kv: KeyValueStore,
    private val cipher: StorageCipher
) : AccountSessionStore {

    @Synchronized
    override fun load(providerId: String): String? {
        val enc = kv.getString(keyFor(providerId)) ?: return null
        return try { cipher.decrypt(enc) } catch (_: Exception) { null }
    }

    @Synchronized
    override fun save(providerId: String, session: String?): Boolean {
        if (session == null) {
            kv.remove(keyFor(providerId))
            return true
        }
        return try { kv.putString(keyFor(providerId), cipher.encrypt(session)); true } catch (_: Exception) { false }
    }

    companion object {
        private fun keyFor(providerId: String) = "account_enc_$providerId"

        fun create(context: Context) = EncryptedAccountSessions(
            SharedPrefsStore(context.getSharedPreferences("room_ac_accounts", Context.MODE_PRIVATE)),
            AndroidKeyStoreCipher()
        )
    }
}
