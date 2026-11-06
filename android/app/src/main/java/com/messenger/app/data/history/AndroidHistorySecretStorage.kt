package com.messenger.app.data.history

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/** Dedicated OS-protected namespace; separate from auth and Signal state. */
class AndroidHistorySecretStorage internal constructor(private val prefs: SharedPreferences) : HistorySecretStorage {
    constructor(context: Context) : this(EncryptedSharedPreferences.create(
        context.applicationContext, "coco_history_recovery",
        MasterKey.Builder(context.applicationContext, "coco_history_recovery_master")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ))
    override fun read(name: String): ByteArray? = synchronized(persistenceLock) {
        // SharedPreferences updates its memory cache even when commit() fails.
        // Do not report that cache as a durably saved recovery key on retry.
        if (name in uncertainWrites) {
            val pending = prefs.getString(name, null)
            if (pending != null) check(prefs.edit().putString(name, pending).commit()) { "Could not persist recovery key" }
            uncertainWrites.remove(name)
        }
        prefs.getString(name, null)?.let { Base64.getDecoder().decode(it) }
    }
    fun eraseAllAccountSecrets() = synchronized(persistenceLock) {
        check(prefs.edit().clear().commit()) { "Could not erase history recovery keys" }
        uncertainWrites.clear()
    }
    override fun write(name: String, value: ByteArray) {
        try {
            synchronized(persistenceLock) {
                val edit = prefs.edit().putString(name, Base64.getEncoder().encodeToString(value))
                uncertainWrites.add(name)
                check(edit.commit()) {
                    "Could not save recovery key"
                }
                uncertainWrites.remove(name)
            }
        } finally { value.fill(0) }
    }
    override fun compareAndSet(name: String, expected: ByteArray?, value: ByteArray): Boolean = synchronized(persistenceLock) {
        require(value.size == 32)
        val current = read(name)
        try {
            if (current == null && expected != null || current != null && (expected == null || !java.security.MessageDigest.isEqual(current, expected))) return@synchronized false
            val previous = prefs.getString(name, null)
            uncertainWrites.add(name)
            if (!runCatching { prefs.edit().putString(name, Base64.getEncoder().encodeToString(value)).commit() }.getOrDefault(false)) {
                // commit(false) updates RAM. Restore the OLD value in RAM before
                // any reader may retry persistence, never adopt the candidate.
                val rollback = prefs.edit()
                if (previous == null) rollback.remove(name) else rollback.putString(name, previous)
                if (runCatching { rollback.commit() }.getOrDefault(false)) uncertainWrites.remove(name)
                error("Could not persist replacement key")
            }
            uncertainWrites.remove(name)
            true
        } finally { current?.fill(0) }
    }
    private companion object {
        val persistenceLock = Any()
        val uncertainWrites = mutableSetOf<String>()
    }
}
