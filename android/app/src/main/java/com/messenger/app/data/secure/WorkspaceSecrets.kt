package com.messenger.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.messenger.app.data.auth.AccountWorkspaceContext
import com.messenger.app.data.auth.AccountWorkspaceLayout
import java.security.KeyStore

/** Account keys can be destroyed without invalidating another saved account or the logout journal. */
internal object WorkspaceSecrets {
    private const val SUFFIX = "-workspace-v1"
    private fun alias(key: String, history: Boolean = false) = "unyri.workspace.$key.${if (history) "history" else "session"}"

    @Synchronized
    fun open(context: Context, name: String, history: Boolean = false): SharedPreferences {
        // Peer pins and local blocks intentionally survive logout and belong to the installation.
        if (name == "local_security_decisions") return encrypted(context, name, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        val key = AccountWorkspaceContext.keyOf(context)
        val target = encrypted(context, name + SUFFIX, alias(key, history))
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            val old = encrypted(context, name, if (history) "coco_history_recovery_master" else MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            val edit = target.edit()
            old.all.forEach { (entry, value) -> if (!target.contains(entry)) when (value) {
                is String -> edit.putString(entry, value)
                is Boolean -> edit.putBoolean(entry, value)
                is Int -> edit.putInt(entry, value)
                is Long -> edit.putLong(entry, value)
                is Float -> edit.putFloat(entry, value)
                is Set<*> -> edit.putStringSet(entry, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit()) { "Could not migrate account secrets" }
            // Wrapped legacy keysets must be removed as well as their encrypted values.
            check(legacy.edit().clear().commit()) { "Could not retire legacy account secrets" }
            context.deleteSharedPreferences(name)
        }
        return target
    }

    private fun encrypted(context: Context, name: String, alias: String): SharedPreferences =
        EncryptedSharedPreferences.create(context, name,
            MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun eraseKeys(key: String) {
        require(AccountWorkspaceLayout.validKey(key))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (name in listOf(alias(key), alias(key, true))) if (store.containsAlias(name)) store.deleteEntry(name)
    }
}
