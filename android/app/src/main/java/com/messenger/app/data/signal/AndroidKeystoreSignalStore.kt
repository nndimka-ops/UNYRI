package com.messenger.app.data.signal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * Keystore-backed Signal identity, prekey, ratchet and trust storage, in a
 * dedicated encrypted file separate from auth tokens. Logout synchronously
 * clears account keys after the session epoch is invalidated. The shared,
 * locked index retains decrypted names and byte lengths only, never values.
 */
class AndroidKeystoreSignalStore private constructor(
    private val prefs: SharedPreferences,
    private val index: KeyIndex,
) : SecureKeyValueStore {
    internal constructor(prefs: SharedPreferences) : this(prefs, indexFor(prefs))

    constructor(context: Context, prefsName: String = PREFS_FILE_NAME) : this(
        com.messenger.app.data.secure.WorkspaceSecrets.open(context, prefsName),
        // Logout also constructs a store. Share its index with the active store,
        // but keep inactive-account storage contexts and separate files isolated.
        indexFor(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)),
    )

    private class KeyIndex { var sizes: MutableMap<String, Int?>? = null }

    /**
     * Lock order (18 Nov ANR on the POCO): [accessLock] may be held while taking the
     * index lock, never the reverse, and the index lock is a LEAF — nothing inside it
     * calls back into app code. Reads ([getBytes], [getInt]) take no app lock at all,
     * as before: SignalNamespaceResolver reads under its own monitor while other
     * threads hold [accessLock] and call into the resolver.
     */
    private inline fun <T> indexed(block: (MutableMap<String, Int?>) -> T): T = synchronized(index) { block(sizes()) }

    /** One getAll per file/process, including all account namespaces in that file. Call under the index lock. */
    private fun sizes(): MutableMap<String, Int?> = index.sizes ?: prefs.all.mapValuesTo(linkedMapOf()) { (_, value) ->
        if (value is String) {
            // Values are NO_WRAP base64. No second plaintext allocation/decode.
            value.length / 4 * 3 - value.takeLast(2).count { it == '=' }
        } else null
    }.also { index.sizes = it }

    override fun <T> withAccess(block: () -> T): T = synchronized(accessLock, block)

    private fun write(changes: Map<String, Int?>, persist: () -> Unit) = indexed { names ->
        try {
            persist()
            names.putAll(changes)
        } catch (failure: Throwable) {
            // SharedPreferences can change memory even when commit fails. Drop
            // the cache rather than exposing stale names/sizes on the next access.
            index.sizes = null
            throw failure
        }
    }

    override fun commitReceive(bytes: Map<String, ByteArray?>, ints: Map<String, Int>) = withAccess { indexed { names ->
        try {
            val editor = prefs.edit()
            bytes.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, Base64.getEncoder().encodeToString(value))
            }
            ints.forEach { (key, value) -> editor.putInt(key, value) }
            check(editor.commit()) { "Could not persist receive transaction" }
            bytes.forEach { (key, value) -> if (value == null) names.remove(key) else names[key] = value.size }
            ints.keys.forEach { names[it] = null }
        } catch (failure: Throwable) {
            index.sizes = null
            throw failure
        }
    } }

    /** Logout invalidates the account epoch before this synchronous session wipe.
     * Installation trust is in the separate local_security_decisions file and
     * is erased only by explicit account-security cleanup, never by this call. */
    fun eraseAllAccountSecrets() = withAccess { synchronized(index) {
        try {
            check(prefs.edit().clear().commit()) { "Could not erase Signal account keys" }
            index.sizes = linkedMapOf()
        } catch (failure: Throwable) {
            index.sizes = null
            throw failure
        }
    } }

    override fun getBytes(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.getDecoder().decode(it) }

    /** Run only for the session restored from disk, before any new login. */
    fun migrateLegacy(owner: String): Unit = indexed { names ->
        if (owner.isBlank() || "identity_key_pair" !in names || names.keys.any { it.endsWith(":identity_key_pair") }) return@indexed
        val legacy = names.keys.filter { !it.contains(':') }
        val editor = prefs.edit()
        legacy.forEach { key ->
            if (names[key] != null) editor.putString("$owner:$key", prefs.getString(key, null))
            else editor.putInt("$owner:$key", prefs.getInt(key, 0))
            editor.remove(key)
        }
        try {
            check(editor.commit()) { "Could not migrate Signal keys" }
            legacy.forEach { key -> names["$owner:$key"] = names.remove(key) }
        } catch (failure: Throwable) {
            index.sizes = null
            throw failure
        }
    }

    override fun putBytes(key: String, value: ByteArray) = write(mapOf(key to value.size)) {
        prefs.edit().putString(key, Base64.getEncoder().encodeToString(value)).apply()
    }

    override fun putNamespaceBinding(key: String, value: ByteArray) = write(mapOf(key to value.size)) {
        check(prefs.edit().putString(key, Base64.getEncoder().encodeToString(value)).commit()) {
            "Could not persist Signal namespace binding"
        }
    }

    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun putInt(key: String, value: Int) = write(mapOf(key to null)) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun remove(key: String) = indexed { names ->
        try {
            prefs.edit().remove(key).apply()
            names.remove(key)
            Unit
        } catch (failure: Throwable) {
            index.sizes = null
            throw failure
        }
    }

    override fun contains(key: String): Boolean = indexed { key in it }

    override fun keysWithPrefix(prefix: String): Set<String> = indexed { names ->
        names.keys.filterTo(linkedSetOf()) { it.startsWith(prefix) }
    }

    override fun byteUsageWithPrefix(prefix: String): StoredByteUsage = indexed { names ->
        var entries = 0
        var bytes = 0L
        names.forEach { (key, size) -> if (key.startsWith(prefix) && size != null) { entries++; bytes += size } }
        StoredByteUsage(entries, bytes)
    }

    private companion object {
        const val PREFS_FILE_NAME = "messenger_signal_store"
        val accessLock = Any()
        // Android caches the underlying SharedPreferences by physical file.
        // Its identity also respects AccountWorkspaceContext's name remapping.
        val indexes = java.util.IdentityHashMap<SharedPreferences, KeyIndex>()
        fun indexFor(prefs: SharedPreferences): KeyIndex = synchronized(indexes) {
            indexes.getOrPut(prefs) { KeyIndex() }
        }
    }
}
