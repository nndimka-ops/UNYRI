package com.messenger.app.data.signal

/**
 * Minimal key-value contract the [SignalKeyStore] is built on. Kept as an
 * interface (rather than talking to EncryptedSharedPreferences directly) so
 * the real Signal Protocol store/session logic can be exercised in fast JVM
 * unit tests (an in-memory fake) without needing Android Keystore/a device -
 * only the "is this actually encrypted at rest" concern is
 * Android-specific, and that's fully delegated to [AndroidKeystoreSignalStore]
 * alone, a thin, already-well-established pattern (see SecureTokenStore).
 */
interface SecureKeyValueStore {
    /** Atomic durable batch. Required by crash-safe receives; no partial fallback. */
    fun commitReceive(bytes: Map<String, ByteArray?>, ints: Map<String, Int>) {
        throw UnsupportedOperationException("Atomic receive persistence unavailable")
    }
    /** Short synchronous storage section, never held across network/suspension. */
    fun <T> withAccess(block: () -> T): T = block()
    fun getBytes(key: String): ByteArray?
    fun putBytes(key: String, value: ByteArray)
    /** A namespace choice must reach durable storage before crypto writes begin. */
    fun putNamespaceBinding(key: String, value: ByteArray) = putBytes(key, value)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    fun remove(key: String)
    fun contains(key: String): Boolean

    /** All currently-stored keys starting with [prefix] - used to enumerate
     *  one-time prekey ids and cached device-id mappings. */
    fun keysWithPrefix(prefix: String): Set<String>

    /** Byte-valued entries only. Encrypted stores override this with cached sizes. */
    fun byteUsageWithPrefix(prefix: String): StoredByteUsage {
        val keys = keysWithPrefix(prefix)
        return StoredByteUsage(keys.size, keys.sumOf { getBytes(it)?.size?.toLong() ?: 0 })
    }
}

data class StoredByteUsage(val entries: Int, val bytes: Long)
