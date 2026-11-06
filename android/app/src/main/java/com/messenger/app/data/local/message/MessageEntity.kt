package com.messenger.app.data.local.message

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local cache of chat messages - never the full E2E ciphertext concept yet
 *  (plaintext `text` today), just enough to render history offline and
 *  reconcile optimistic sends. `clientMessageId` is the primary key (not the
 *  server id) because it exists from the moment a message is composed,
 *  before the server has assigned anything - reconciliation is then a
 *  same-row UPDATE (pending -> sent) instead of a delete+reinsert.
 *
 *  Uniqueness: clientMessageId (PK), serverId (once assigned - defense in
 *  depth against ever attaching one server message to two local rows), and
 *  (chatId, sequence) - SQLite doesn't treat multiple NULLs as duplicates
 *  for a UNIQUE index, so any number of still-pending (sequence = null)
 *  messages per chat are fine; only assigned sequences must be unique. */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["chatId", "sequence"], unique = true),
        Index(value = ["chatId"]),
        Index(value = ["targetMessageId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val clientMessageId: String,
    val serverId: String?,
    val chatId: String,
    val senderId: String,
    /** Effective (post-edit) text/placeholder - never the original
     *  pre-edit text (Этап I doesn't keep edit history, only the latest
     *  content, per the "edited" indicator spec). */
    val text: String,
    val sequence: Long?,
    val createdAt: String,
    /** pending | sent | delivered | read | failed */
    val status: String,
    /** Этап I: "content" (a real chat bubble) | "edit" | "delete" | "reaction"
     *  (lifecycle events - never rendered as their own bubble, only their
     *  side effect on the target row's projection fields below matters).
     *  Mirrors the server-visible `kind` field verbatim. */
    val kind: String = "content",
    /** A SERVER id (matches `MessageDto.id`/`serverId`, NOT clientMessageId).
     *  Dual-purpose by [kind]: reply-to reference when kind="content",
     *  lifecycle target when kind="edit"/"delete"/"reaction". */
    val targetMessageId: String? = null,
    /** Serialized [com.messenger.app.data.attachment.ReplySnapshotDto] -
     *  only set on kind="content" rows that are themselves a reply. */
    val replySnapshotJson: String? = null,
    val isEdited: Boolean = false,
    /** Guards against a stale/reordered edit event resurrecting older text
     *  (Этап I п.43) - an edit only applies if its sequence is higher. */
    val lastEditSequence: Long = 0,
    val isDeletedForEveryone: Boolean = false,
    /** Guards against a stale edit "un-deleting" a tombstoned message
     *  (Этап I п.44/41). */
    val deleteSequence: Long = 0,
    /** "Delete for me" - purely local, NEVER sent to the backend, survives
     *  restart/re-sync (Этап I п.15/45): a message hidden this way must
     *  never reappear even if `/sync`/history redelivers it. */
    val hiddenLocally: Boolean = false,
    val forwarded: Boolean = false,
    /** --- N.2R durable outbox (Bug 4) ---
     *  Retry bookkeeping for PENDING sends. [nextRetryAtEpochMs] = 0 means
     *  eligible now; transient failures schedule a future retry here. */
    val retryCount: Int = 0,
    val nextRetryAtEpochMs: Long = 0,
    val lastErrorClass: String? = null,
    val caption: String? = null,
    val albumId: String? = null,
    /** Text frozen under the current server idempotency key. Never rewritten
     * after an attempt may have reached the server. */
    val payloadText: String? = null,
    /** Latest edit that must be sent as an ordinary edit after serverId. */
    val pendingEditText: String? = null,
    val pendingEditRevision: Int = 0,
    /** True once a POST may have reached the server for this clientMessageId. */
    val sendAttempted: Boolean = false,
    /** Validated optional Signal content metadata; never used in list/search/notification text. */
    val conferenceInviteJson: String? = null,
)
