package com.messenger.app.data.history

import java.security.MessageDigest

/** Errors reading protected storage are not equivalent to an absent key. Never
 * generate a replacement after a KeyStore/read error: that can orphan a backup. */
interface HistorySecretStorage {
    fun read(name: String): ByteArray?
    fun write(name: String, value: ByteArray)
    fun compareAndSet(name: String, expected: ByteArray?, value: ByteArray): Boolean = error("Atomic key replacement unavailable")
}

class HistoryRecoveryKeys(private val storage: HistorySecretStorage, server: String, owner: String) {
    /** Candidate must already decrypt the current confirmed backup. */
    fun replaceVerified(expected: ByteArray?, candidate: ByteArray) = synchronized(lock) {
        require(candidate.size == 32 && (expected == null || expected.size == 32))
        check(storage.compareAndSet(name, expected, candidate)) { "Saved key changed; verify again" }
    }
    private val name: String
    init {
        requireHistoryId(server); requireHistoryId(owner)
        name = "history-v1:$server:$owner"
    }
    fun load(): ByteArray? = synchronized(lock) {
        storage.read(name)?.also { check(it.size == 32) { "Recovery key storage is damaged" } }?.copyOf()
    }
    /** Call only after paper-key confirmation (new setup) or successful AEAD
     * verification (existing backup). This operation never rotates an old key. */
    fun saveConfirmed(key: ByteArray) = synchronized(lock) {
        require(key.size == 32)
        val existing = load()
        if (existing != null) {
            try { check(MessageDigest.isEqual(existing, key)) { "A different recovery key is already saved" } }
            finally { existing.fill(0) }
        } else {
            storage.write(name, key.copyOf())
            val persisted = load()
            try { check(persisted != null && MessageDigest.isEqual(persisted, key)) { "Could not save recovery key" } }
            finally { persisted?.fill(0) }
        }
    }
    private companion object { val lock = Any() }
}
