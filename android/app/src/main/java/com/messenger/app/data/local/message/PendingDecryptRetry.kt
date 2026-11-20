package com.messenger.app.data.local.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingDecryptRetryCursor(val sequence: Long, val clientMessageId: String) {
    companion object {
        fun decode(raw: String?): PendingDecryptRetryCursor? = raw?.let {
            runCatching { Json.decodeFromString<PendingDecryptRetryCursor>(it) }.getOrNull()
        }
    }
}

/** Shared verbatim by Room and JVM SQLite contracts. Explicit retry queries do not filter. */
internal object PendingDecryptRetrySql {
    const val ELIGIBLE = "COALESCE(lastErrorClass,'') NOT IN ('permanent_duplicate','permanent_malformed_ciphertext','permanent_unsupported_type','DuplicateMessageException')"
    const val ALL = "SELECT * FROM pending_decrypts WHERE " + ELIGIBLE
    const val SENDER = ALL + " AND senderId = :senderId"
    const val ORDER = " ORDER BY sequence, clientMessageId LIMIT :limit"
    const val AFTER = " AND (sequence, clientMessageId) > (:sequence, :client)"
    const val THROUGH = " AND (sequence, clientMessageId) <= (:sequence, :client)"
    const val INDEX_ALL = "CREATE INDEX IF NOT EXISTS index_pending_decrypts_retry ON pending_decrypts(sequence,clientMessageId) WHERE " + ELIGIBLE
    const val INDEX_SENDER = "CREATE INDEX IF NOT EXISTS index_pending_decrypts_sender_retry ON pending_decrypts(senderId,sequence,clientMessageId) WHERE " + ELIGIBLE
    /** Room does not model these partial indexes; every migration drops them (MessengerDatabase). */
    val INDEX_NAMES = listOf("index_pending_decrypts_retry", "index_pending_decrypts_sender_retry")
}

internal fun nextDecryptRetryCount(count: Int): Int = if (count == Int.MAX_VALUE) count else count + 1
