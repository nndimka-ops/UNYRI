package com.messenger.app.data.signal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed [SecureKeyValueStore]. A dedicated
 * EncryptedSharedPreferences file, deliberately separate from
 * [com.messenger.app.data.secure.SecureTokenStore] - Signal Protocol key
 * material (identity keys, prekeys, session ratchet state) is a distinct
 * secret class from auth tokens, and keeping them in separate encrypted
 * files keeps their lifecycles explicit. Logout synchronously clears all
 * account keys alongside local history; reinstall/clear-data also creates
 * a new identity. No old ratchet or trust state is silently restored.
 */
class AndroidKeystoreSignalStore internal constructor(private val prefs: SharedPreferences) : SecureKeyValueStore {
    /** Logout invalidates the account epoch before this synchronous wipe. */
    override fun <T> withAccess(block: () -> T): T = synchronized(accessLock, block)
    fun eraseAllAccountSecrets() = withAccess {
        check(prefs.edit().clear().commit()) { "Could not erase Signal account keys" }
    }

    constructor(context: Context) : this(kotlin.run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    })

    override fun getBytes(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Run only for the session restored from disk, before any new login. */
    fun migrateLegacy(owner: String) {
        if (owner.isBlank() || !prefs.contains("identity_key_pair")) return
        if (prefs.all.keys.any { it.endsWith(":identity_key_pair") }) return
        val editor = prefs.edit()
        prefs.all.filterKeys { !it.contains(':') }.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString("$owner:$key", value)
                is Int -> editor.putInt("$owner:$key", value)
                else -> error("Unsupported legacy Signal value")
            }
            editor.remove(key)
        }
        check(editor.commit()) { "Could not migrate Signal keys" }
    }

    override fun putBytes(key: String, value: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    override fun putNamespaceBinding(key: String, value: ByteArray) {
        check(prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).commit()) {
            "Could not persist Signal namespace binding"
        }
    }

    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun keysWithPrefix(prefix: String): Set<String> =
        prefs.all.keys.filter { it.startsWith(prefix) }.toSet()

    private companion object {
        const val PREFS_FILE_NAME = "messenger_signal_store"
        val accessLock = Any()
    }
}
