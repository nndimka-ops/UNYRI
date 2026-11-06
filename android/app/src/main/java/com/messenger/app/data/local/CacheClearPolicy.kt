package com.messenger.app.data.local

/**
 * Pure cache-clear policy (Phase 9.1). Decides which attachment local
 * plaintext files may be deleted by "Clear Media Cache".
 *
 * Invariant: an outgoing attachment whose upload has NOT completed still
 * needs its local plaintext as the retry/re-send source — clearing it would
 * silently strand the pending message. Everything else (uploaded by us, or
 * received) is re-downloadable from the server, so its plaintext is a safe
 * cache to drop.
 */
object CacheClearPolicy {

    /** Outgoing rows whose plaintext file is a protected retry source. */
    fun isProtectedUploadSource(uploadState: String): Boolean =
        uploadState !in setOf("sent", "uploaded", "received")
}
