package com.messenger.app.data.local.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** Room emits only the complete album, never intermediate one/two/three-item layouts. */
    @androidx.room.Transaction
    suspend fun enqueueMediaBatch(rows: List<QueuedMediaRow>) {
        rows.forEach { row ->
            if (findByClientMessageId(row.message.clientMessageId) == null) {
                saveMediaJob(row.message.chatId, "presentation:" + row.message.clientMessageId, row.presentation)
                enqueueMedia(row.message, row.job)
            }
        }
    }
    @Query("SELECT * FROM messages WHERE chatId=:chatId AND kind IN ('edit','delete','reaction') AND status='failed' AND hiddenLocally=0 ORDER BY createdAt,rowid")
    fun observeFailedActions(chatId: String): Flow<List<MessageEntity>>

    @androidx.room.Transaction
    suspend fun commitLifecycle(event: MessageEntity, apply: suspend () -> Unit) {
        upsert(event)
        apply()
    }

    @Query("SELECT * FROM messages WHERE kind='content' AND sequence IS NOT NULL AND (sequence > :after OR (sequence=:after AND clientMessageId>:client)) ORDER BY sequence,clientMessageId LIMIT 32")
    suspend fun historyArchivePage(after: Long, client: String): List<MessageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE hiddenLocally=0 AND isDeletedForEveryone=0 AND (status IN ('pending','failed') OR (kind!='content' AND status NOT IN ('sent','delivered','read'))))")
    suspend fun historyImportBlocked(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE hiddenLocally=0 AND isDeletedForEveryone=0 AND kind!='content' AND status NOT IN ('sent','delivered','read'))")
    suspend fun historyRestoreBlocked(): Boolean
    @Query("SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId = '__server_history__' AND name = 'cleared_through_sequence'")
    suspend fun historyCutoff(): Long?
    @Query("DELETE FROM reactions WHERE targetMessageId IN (SELECT serverId FROM messages WHERE sequence IS NOT NULL AND sequence <= :before)")
    suspend fun clearHistoryReactions(before: Long)
    @Query("DELETE FROM attachments WHERE clientMessageId IN (SELECT clientMessageId FROM messages WHERE sequence IS NOT NULL AND sequence <= :before)")
    suspend fun clearHistoryAttachments(before: Long)
    @Query("DELETE FROM messages WHERE sequence IS NOT NULL AND sequence <= :before")
    suspend fun clearHistoryMessages(before: Long)
    @Query("UPDATE chats SET lastMessageAt = (SELECT MAX(createdAt) FROM messages WHERE chatId = chats.chatId AND kind = 'content' AND (sequence IS NULL OR sequence > :before)) WHERE chatId IN (SELECT chatId FROM messages WHERE sequence <= :before)")
    suspend fun clearHistoryPreviews(before: Long)
    @androidx.room.Transaction
    suspend fun applyHistoryClear(before: Long) {
        require(before >= 0)
        val cutoff = maxOf(before, historyCutoff() ?: 0)
        saveMediaJob("__server_history__", "cleared_through_sequence", cutoff.toString())
        clearHistoryReactions(cutoff)
        clearHistoryAttachments(cutoff)
        clearHistoryPreviews(cutoff)
        clearHistoryMessages(cutoff)
    }

    @Query("SELECT MIN(CAST(w.value AS INTEGER)) FROM chat_workspace w JOIN messages m ON w.chatId = m.chatId AND w.name = 'schedule:' || m.clientMessageId WHERE m.senderId = :owner AND m.status = 'pending' AND m.hiddenLocally = 0 AND CAST(w.value AS INTEGER) > :now")
    suspend fun nextScheduledAt(owner: String, now: Long): Long?
    @androidx.room.Transaction
    suspend fun enqueueScheduled(message: MessageEntity, due: Long) {
        saveMediaJob(message.chatId, "schedule:" + message.clientMessageId, due.toString())
        upsert(message)
    }
    @Query("INSERT OR REPLACE INTO chat_workspace(chatId,name,value) VALUES (:chatId,:name,:value)")
    suspend fun saveMediaJob(chatId: String, name: String, value: String)
    @androidx.room.Transaction
    suspend fun enqueueMedia(message: MessageEntity, job: String) {
        if (findByClientMessageId(message.clientMessageId) != null) return
        saveMediaJob(message.chatId, "upload:" + message.clientMessageId, job)
        upsert(message)
    }

    @Query("SELECT DISTINCT chatId FROM messages WHERE senderId != :ownerId AND kind = 'content'")
    suspend fun incomingChatIds(ownerId: String): List<String>

    /** Only readable content supplies a receipt target; old crypto failures must not block later messages. */
    @Query("""
        SELECT COALESCE(MAX(sequence), 0) FROM messages
        WHERE chatId = :chatId AND senderId != :ownerId AND kind = 'content' AND hiddenLocally = 0
          AND status IN ('sent', 'delivered', 'read') AND sequence <= :upperBound
    """)
    suspend fun receiptSequence(chatId: String, ownerId: String, upperBound: Long): Long

    /** REPLACE keyed by clientMessageId (the PK) is exactly how a pending
     *  optimistic row becomes sent/delivered in place, no duplicate row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    /** Sequenced messages first in order, still-pending/failed (sequence IS
     *  NULL) ones last (newest optimistic sends at the bottom, matching a
     *  chat UI's expectation regardless of server ack order). Failed
     *  messages stay visible (with a retry affordance) - a temporary network
     *  error must never silently delete a composed message. Lifecycle
     *  EVENT rows (kind != "content") never render as their own bubble -
     *  only their side effect on a target row's projection fields matters
     *  (Этап I). Locally-hidden ("delete for me") rows are excluded too -
     *  this exclusion must survive restart/re-sync (Этап I п.15/45). */
    @Query(
        """
        SELECT m.clientMessageId, m.serverId, m.chatId, m.senderId, m.text, m.sequence, m.createdAt, m.status, m.kind, m.targetMessageId, m.isEdited, m.lastEditSequence, m.isDeletedForEveryone, m.deleteSequence, m.hiddenLocally, m.forwarded, m.retryCount, m.nextRetryAtEpochMs, m.lastErrorClass, m.caption, m.albumId, m.payloadText, m.pendingEditText, m.pendingEditRevision, m.sendAttempted, m.conferenceInviteJson,
               CASE WHEN EXISTS (SELECT 1 FROM messages target WHERE target.serverId = m.targetMessageId
                   AND target.chatId = m.chatId AND (target.hiddenLocally = 1 OR target.isDeletedForEveryone = 1))
                   THEN NULL ELSE m.replySnapshotJson END AS replySnapshotJson
        FROM messages m WHERE m.chatId = :chatId AND (m.kind = 'content' OR (m.kind IN ('edit','delete','reaction') AND m.status = 'pending')) AND m.hiddenLocally = 0 AND m.isDeletedForEveryone = 0
        ORDER BY (sequence IS NULL), sequence ASC, createdAt ASC
        """
    )
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    /** Freeze the plaintext under the current idempotency key before the first
     *  POST. Later edits must never rewrite this value. */
    @Query(
        """
        UPDATE messages SET payloadText = COALESCE(payloadText, :fallbackText), sendAttempted = 1
        WHERE clientMessageId = :clientMessageId AND status = 'pending' AND kind = 'content' AND hiddenLocally = 0
        """
    )
    suspend fun markSendStarted(clientMessageId: String, fallbackText: String)

    /** Edit before the first attempt is just the message text. Returns 0 once
     *  the payload is frozen; the caller then stores a pending edit intent. */
    @Query(
        """
        UPDATE messages SET text = :text, payloadText = :text, pendingEditText = NULL, pendingEditRevision = 0
        WHERE clientMessageId = :clientMessageId AND serverId IS NULL AND sendAttempted = 0
          AND status IN ('pending','failed') AND kind = 'content' AND hiddenLocally = 0
        """
    )
    suspend fun updateTextBeforeSend(clientMessageId: String, text: String): Int

    /** Persist the latest edit while the original POST may already be in
     *  flight; the frozen payloadText is intentionally not touched. */
    @Query(
        """
        UPDATE messages SET text = :text, pendingEditText = :text, pendingEditRevision = :revision
        WHERE clientMessageId = :clientMessageId AND serverId IS NULL AND sendAttempted = 1
          AND status IN ('pending','failed') AND kind = 'content' AND hiddenLocally = 0
        """
    )
    suspend fun setPendingEdit(clientMessageId: String, text: String, revision: Int): Int

    @Query("UPDATE messages SET pendingEditText = NULL, pendingEditRevision = 0 WHERE clientMessageId = :clientMessageId")
    suspend fun clearPendingEdit(clientMessageId: String)

    /** Commit server coordinates and the deferred edit row atomically, then
     *  clear the intent. A failed edit insert rolls the sent row back. */
    @androidx.room.Transaction
    suspend fun commitSentAndPendingEdit(sent: MessageEntity, edit: MessageEntity?) {
        upsert(sent)
        if (edit != null) {
            upsert(edit)
            clearPendingEdit(sent.clientMessageId)
        }
    }

    @Query("SELECT * FROM messages WHERE clientMessageId = :clientMessageId")
    suspend fun findByClientMessageId(clientMessageId: String): MessageEntity?

    /** Reactive variant of [findByClientMessageId] for the foreground banner
     *  controller: emits the row once it has been persisted by the ingestion
     *  pipeline (so a banner is only built AFTER decrypt/persist, with no
     *  polling). Emits null until the row exists. */
    @Query("SELECT * FROM messages WHERE clientMessageId = :clientMessageId")
    fun observeByClientMessageId(clientMessageId: String): Flow<MessageEntity?>

    /** Looks up a message by its SERVER id - needed because lifecycle
     *  events (Этап I) address their target via the server-assigned id
     *  (`target_message_id`), not the locally-generated clientMessageId. */
    @Query("SELECT * FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND sequence = :sequence LIMIT 1")
    suspend fun findByChatSequence(chatId: String, sequence: Long): MessageEntity?

    /** Retain lifecycle payloads so history arriving after its edit/delete is projected correctly. */
    @Query("SELECT * FROM messages WHERE targetMessageId = :serverId AND kind IN ('edit', 'delete', 'reaction') AND status IN ('sent', 'delivered', 'read') ORDER BY sequence")
    suspend fun lifecycleEvents(serverId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE clientMessageId = :clientMessageId")
    suspend fun updateStatus(clientMessageId: String, status: String)

    /** N.2R outbox: all PENDING content messages the current account owns
     *  whose retry is due (nextRetryAtEpochMs == 0 or in the past), oldest
     *  first (stable creation order for the flush). */
    @Query(
        """
        SELECT m.* FROM messages m
        WHERE m.status = 'pending' AND m.kind IN ('content','edit','delete','reaction') AND m.senderId = :myUserId AND m.hiddenLocally = 0
          AND m.nextRetryAtEpochMs <= :nowEpochMs
          AND NOT EXISTS (SELECT 1 FROM chat_workspace scheduled WHERE scheduled.chatId = m.chatId AND scheduled.name = 'schedule:' || m.clientMessageId AND CAST(scheduled.value AS INTEGER) > :nowEpochMs)
          AND NOT EXISTS (SELECT 1 FROM messages older WHERE older.chatId = m.chatId
            AND older.senderId = :myUserId AND older.status = 'pending' AND older.kind IN ('content','edit','delete','reaction') AND older.hiddenLocally = 0
            AND ((m.kind = 'reaction' AND older.kind = 'reaction' AND older.targetMessageId = m.targetMessageId)
              OR (m.kind != 'reaction' AND older.kind != 'reaction'))
            AND older.nextRetryAtEpochMs > :nowEpochMs
            AND (NOT EXISTS (SELECT 1 FROM chat_workspace upload WHERE upload.chatId = older.chatId AND upload.name = 'upload:' || older.clientMessageId)
              OR EXISTS (SELECT 1 FROM chat_workspace upload WHERE upload.chatId = m.chatId AND upload.name = 'upload:' || m.clientMessageId))
            AND NOT EXISTS (SELECT 1 FROM chat_workspace scheduled WHERE scheduled.chatId = older.chatId AND scheduled.name = 'schedule:' || older.clientMessageId AND CAST(scheduled.value AS INTEGER) > :nowEpochMs)
            AND (older.createdAt < m.createdAt OR (older.createdAt = m.createdAt AND older.rowid < m.rowid)))
        ORDER BY m.createdAt ASC, m.rowid ASC
        """
    )
    suspend fun pendingEligible(myUserId: String, nowEpochMs: Long): List<MessageEntity>

    /** N.2R outbox: connectivity restored - make every PENDING content message
     *  of this account immediately eligible regardless of its stale backoff
     *  window, so a reconnect flush sends the whole queue in one pass. */
    @Query(
        """
        UPDATE messages SET nextRetryAtEpochMs = 0
        WHERE status = 'pending' AND kind IN ('content','edit','delete','reaction') AND senderId = :myUserId
        """
    )
    suspend fun resetPendingRetryWindows(myUserId: String)

    /** N.2R outbox: records a transient retry attempt (bumped count + next
     *  attempt time), leaving the message PENDING. */
    @Query(
        """
        UPDATE messages SET retryCount = :retryCount, nextRetryAtEpochMs = :nextRetryAtEpochMs, lastErrorClass = :lastErrorClass
        WHERE clientMessageId = :clientMessageId
        """
    )
    suspend fun scheduleRetry(clientMessageId: String, retryCount: Int, nextRetryAtEpochMs: Long, lastErrorClass: String?)

    /** N.2R outbox: manual retry resets a permanently-failed message back to
     *  PENDING with a fresh retry window (same clientMessageId - idempotent). */
    @Query(
        """
        UPDATE messages SET status = 'pending', retryCount = 0, nextRetryAtEpochMs = 0, lastErrorClass = NULL
        WHERE clientMessageId = :clientMessageId
        """
    )
    suspend fun resetToPending(clientMessageId: String)

    /** Applies an edit event's new text to its target, but ONLY if this is
     *  genuinely a newer edit than whatever was last applied
     *  (`:sequence > lastEditSequence`) AND the target isn't already
     *  tombstoned (Этап I п.41/43/44 - deterministic by global sequence,
     *  never by WS/sync arrival order; a delete "poisons" the message so
     *  no later-arriving-but-stale edit can ever resurrect it). Returns
     *  the number of rows actually updated (0 if the target doesn't exist
     *  locally yet, or the guard rejected it) so callers can tell whether
     *  the effect actually happened. */
    @Query(
        """
        UPDATE messages SET text = :newText, isEdited = 1, lastEditSequence = :sequence, conferenceInviteJson = NULL
        WHERE serverId = :targetServerId AND :sequence > lastEditSequence AND isDeletedForEveryone = 0
        """
    )
    suspend fun applyEdit(targetServerId: String, newText: String, sequence: Long): Int

    /** Tombstones the target for "delete for everyone" - guarded the same
     *  way as [applyEdit] (only a genuinely newer sequence takes effect;
     *  idempotent against a retried/duplicate delete event). Also clears
     *  the cached decrypted content reference (Этап I п.14) - `localPath`
     *  for an attachment message is cleaned up separately by the caller
     *  (needs to delete the actual file first). */
    @Query(
        """
        UPDATE messages SET isDeletedForEveryone = 1, deleteSequence = :sequence
        WHERE serverId = :targetServerId AND :sequence > deleteSequence
        """
    )
    suspend fun applyDelete(targetServerId: String, sequence: Long): Int

    /** "Delete for me" - local-only, never touches [deleteSequence]/global
     *  state (Этап I п.15). */
    @Query("UPDATE messages SET hiddenLocally = 1, status = CASE WHEN serverId IS NULL AND status = 'pending' THEN 'failed' ELSE status END WHERE clientMessageId = :clientMessageId")
    suspend fun hideLocally(clientMessageId: String)

    /** Atomic cancel guard: a late ACK or another account's row is never hidden. */
    @Query("UPDATE messages SET hiddenLocally=1,status='failed',pendingEditText=NULL,pendingEditRevision=0 WHERE clientMessageId=:clientMessageId AND chatId=:chatId AND senderId=:ownerId AND serverId IS NULL AND status IN ('pending','failed') AND hiddenLocally=0 AND isDeletedForEveryone=0 AND kind='content'")
    suspend fun hideUnsent(clientMessageId: String, chatId: String, ownerId: String): Int

    /** Sender-side OPTIMISTIC edit display (Этап I п.36) - keyed by the
     *  sender's own clientMessageId, unlike [applyEdit] (which is for
     *  applying a REMOTE event by serverId and is sequence-guarded). The
     *  sender reconciles with the real sequence via [applyEdit] once the
     *  send succeeds; on failure the caller rolls this back to the
     *  previous text using the same method. */
    @Query("UPDATE messages SET text = :newText, isEdited = 1 WHERE clientMessageId = :clientMessageId")
    suspend fun setOptimisticText(clientMessageId: String, newText: String)

    /** Sender-side optimistic tombstone/rollback (Этап I п.36) - keyed by
     *  clientMessageId, mirrors [setOptimisticText]'s rationale. */
    @Query("UPDATE messages SET isDeletedForEveryone = :deleted WHERE clientMessageId = :clientMessageId")
    suspend fun setOptimisticDeleted(clientMessageId: String, deleted: Boolean)

    /** Forward-only receipt propagation onto the SENDER's own message
     *  bubbles: only upgrades rows currently in one of [fromStatuses] (e.g.
     *  "sent" -> "delivered", or "sent"/"delivered" -> "read"), so a
     *  reordered/duplicate receipt update can never downgrade a status. */
    @Query(
        """
        UPDATE messages SET status = :status WHERE chatId = :chatId AND senderId = :myUserId
            AND sequence IS NOT NULL AND sequence <= :sequence AND status IN (:fromStatuses)
        """
    )
    suspend fun advanceStatusUpTo(chatId: String, myUserId: String, sequence: Long, status: String, fromStatuses: List<String>)

    @Query("SELECT MIN(sequence) FROM messages WHERE chatId = :chatId AND sequence IS NOT NULL")
    suspend fun minSequence(chatId: String): Long?

    // --- P1-4: durable decrypt-retry records -----------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDecrypt(pending: PendingDecryptEntity)

    @Query("DELETE FROM pending_decrypts WHERE clientMessageId = :clientMessageId")
    suspend fun deletePendingDecrypt(clientMessageId: String)

    @Query("SELECT * FROM pending_decrypts ORDER BY sequence ASC")
    suspend fun pendingDecryptsOrdered(): List<PendingDecryptEntity>

    @Query("SELECT * FROM pending_decrypts WHERE senderId = :senderId ORDER BY sequence ASC")
    suspend fun pendingDecryptsForSender(senderId: String): List<PendingDecryptEntity>

    @Query("UPDATE pending_decrypts SET retryCount = :retryCount, lastErrorClass = :lastErrorClass WHERE clientMessageId = :clientMessageId")
    suspend fun bumpPendingDecrypt(clientMessageId: String, retryCount: Int, lastErrorClass: String?)

    /** P1-4: writes the visible decrypt-failed placeholder AND the encrypted
     *  retry record in one transaction, so the durable sync cursor can only
     *  ever advance past a message that is EITHER fully decrypted OR durably
     *  retained for retry. */
    @androidx.room.Transaction
    suspend fun upsertDecryptFailed(message: MessageEntity, pending: PendingDecryptEntity) {
        upsert(message)
        insertPendingDecrypt(pending)
    }
}

data class QueuedMediaRow(val message: MessageEntity, val job: String, val presentation: String)
