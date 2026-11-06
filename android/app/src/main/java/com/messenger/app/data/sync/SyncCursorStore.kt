package com.messenger.app.data.sync

/**
 * The subset of [com.messenger.app.data.secure.SecureTokenStore] that the
 * sync layer depends on - kept as a narrow interface (same pattern as
 * `SignalKeyStore`'s `SecureKeyValueStore`) so [MessageIngestionPipeline]/
 * [GlobalSyncManager] are unit-testable with an in-memory fake instead of a
 * real Android-Keystore-backed store.
 */
interface SyncCursorStore {
    fun lastSyncedSequence(): Long
    fun saveLastSyncedSequence(sequence: Long)
}
