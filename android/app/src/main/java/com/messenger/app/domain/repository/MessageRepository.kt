package com.messenger.app.domain.repository

import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * Offline-first: [observeMessages] is a local Room Flow. Sending writes an
 * optimistic `pending` row immediately, then reconciles it in place once the
 * server responds. Postgres (via HTTP) is the source of truth - the
 * WebSocket is a realtime notification channel only, never assumed to be
 * complete or reliable on its own.
 */
data class FailedMessageAction(val clientMessageId: String, val kind: String, val reasonCode: String? = null)

/** One locally staged source; a batch is admitted atomically before any upload starts. */
data class QueuedMediaDraft(val id: String, val uri: String, val kind: String,
    val caption: String? = null, val duration: Long = 0, val albumId: String? = null)

interface MessageRepository {
    fun observeFailedActions(chatId: String): Flow<List<FailedMessageAction>> = kotlinx.coroutines.flow.flowOf(emptyList())
    val peerActivity: StateFlow<Map<String, Pair<String, Long>>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    fun sendActivity(chatId: String, activity: String) {}
    suspend fun locateMessage(chatId: String, id: String): String? = null
    suspend fun chatValue(chatId: String, name: String): String? = null
    suspend fun saveChatValue(chatId: String, name: String, value: String?) {}
    suspend fun discardMediaDrafts(paths: Set<String>) {}
    val managementRevision: StateFlow<Long> get() = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val socketState: StateFlow<SocketConnectionState>
    val syncPhase: StateFlow<com.messenger.app.data.sync.SyncPhase> get() = kotlinx.coroutines.flow.MutableStateFlow(com.messenger.app.data.sync.SyncPhase.IDLE)
    suspend fun measurePing(): Long? = null
    fun retryConnectionSync() {}

    fun observeMessages(chatId: String): Flow<List<Message>>

    /** Local attachment cache rows for this chat, keyed to messages via
     *  `Attachment.clientMessageId` - the UI joins these to `Message`s. */
    fun observeAttachments(chatId: String): Flow<List<Attachment>>

    suspend fun sendMessage(chatId: String, text: String): Result<Unit>
    /** Explicit share actions must remain bound to the account that chose the recipient. */
    suspend fun sendMessageForSession(chatId: String, text: String, expectedEpoch: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Session-bound text sending is unavailable"))
    suspend fun sendConferenceInvitation(chatId: String, invitation: com.messenger.app.conference.ConferenceInvitation, expectedEpoch: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Invitation sending unavailable"))
    suspend fun declineConferenceInvitation(clientMessageId: String, expectedEpoch: Int): Result<Unit> = Result.failure(UnsupportedOperationException("Invitation decision unavailable"))

    /** Sends a message whose content is an already-uploaded/completed
     *  attachment (see `AttachmentManager`) - the caller does the
     *  prepare/encrypt/upload/complete dance first and hands over only the
     *  resulting metadata, which gets wrapped in the versioned Signal
     *  content and sent exactly like a text message otherwise. */
    fun scheduledDates(chatId: String): kotlinx.coroutines.flow.Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun scheduleText(chatId: String, text: String, due: Long): Result<Unit> = Result.failure(IllegalStateException("Scheduling unavailable"))
    suspend fun queueMedia(chatId: String, id: String, uri: String, kind: String, caption: String? = null, duration: Long = 0, albumId: String? = null): Result<Unit> = Result.failure(IllegalStateException("Media queue unavailable"))
    suspend fun queueMediaBatch(chatId: String, media: List<QueuedMediaDraft>): Result<Unit> = Result.failure(IllegalStateException("Media batch queue unavailable"))
    suspend fun sendAttachmentMessage(chatId: String, clientMessageId: String, attachment: AttachmentMetadataDto, forwarded: Boolean = false, caption: String? = null, albumId: String? = null): Result<Unit>

    /** Re-sends a `failed` message reusing its original clientMessageId, so
     *  server-side idempotency makes this safe even if the earlier attempt
     *  actually succeeded despite a client-perceived failure. */
    suspend fun retry(clientMessageId: String): Result<Unit>

    /** Fetches the newest page of history and reconciles it into Room - used
     *  both for the initial chat open and after a reconnect. */
    suspend fun applyPersonalClear(chatId: String, sequence: Long): Boolean = false
    suspend fun applyDeletedChat(chatId: String): Boolean = false
    suspend fun syncLatest(chatId: String): Result<Unit>

    /** Fetches the next older page (keyset via the oldest cached sequence).
     *  Returns whether more history remains. */
    suspend fun loadOlderMessages(chatId: String): Result<Boolean>
    /** Exhaustive server traversal, including gaps between locally cached pages. */
    suspend fun prepareHistoryBackup(chatId: String): Result<Unit> = Result.failure(IllegalStateException("Complete history traversal unavailable"))

    suspend fun markRead(chatId: String, sequence: Long): Result<Unit>

    /** HTTP correctness-path fallback for the OTHER member's delivered/read
     *  watermark on OUR OWN sent messages - advances local message bubble
     *  statuses (forward-only) using [ChatSummary.otherMemberDeliveredSequence]/
     *  [ChatSummary.otherMemberReadSequence]. Recovers exactly what a missed
     *  `receipt.updated` WS event would otherwise have carried; call this
     *  whenever a fresh `ChatSummary` for this chat is fetched (e.g. on chat
     *  open). */
    suspend fun reconcileReceipts(chatId: String, otherMemberDeliveredSequence: Long, otherMemberReadSequence: Long)

    // --- Этап I: message lifecycle actions -----------------------------

    /** Reply - identical to [sendMessage] but with a self-contained
     *  preview snapshot of [replyToClientMessageId] embedded in the
     *  encrypted content (Этап I п.4) and the target referenced
     *  server-side for validation (never sent as plaintext). */
    suspend fun replyMessage(chatId: String, text: String, replyToClientMessageId: String): Result<Unit>

    /** Own text message only. Optimistic (shows [newText] immediately,
     *  rolled back on failure) - reconciled with the real server-assigned
     *  sequence on success, which is what makes ordering deterministic
     *  across devices (Этап I п.10/11/36). */
    suspend fun editMessage(clientMessageId: String, newText: String): Result<Unit>

    /** Delete for everyone - own messages only, shows a pending tombstone
     *  immediately, restored on failure (Этап I п.13/36). */
    suspend fun deleteForEveryone(clientMessageId: String): Result<Unit>

    /** Delete for me - purely local, never touches the network, survives
     *  restart/re-sync (Этап I п.15). */
    suspend fun deleteForMe(clientMessageId: String)

    /** Cancels only captured own unsent rows; delivered history is never deleted. */
    suspend fun cancelUnsentMessages(chatId: String, clientMessageIds: List<String>): Result<Unit> =
        Result.failure(IllegalStateException("Unsent cancellation unavailable"))

    /** Sets/replaces the CURRENT USER's own reaction (Этап I п.18: at most
     *  one active reaction per user per message - setting a new emoji
     *  implicitly replaces any previous one). */
    suspend fun setReaction(clientMessageId: String, emoji: String): Result<Unit>

    suspend fun removeReaction(clientMessageId: String): Result<Unit>

    /** Forwards a TEXT message's already-decrypted local content as a
     *  brand-new message/new client_message_id/new Signal encryption into
     *  [destinationChatId] (Этап I п.22/23) - never a backend-visible
     *  pointer to the original. Attachment forwarding goes through
     *  `AttachmentManager.forwardAttachment` + [sendAttachmentMessage]
     *  instead (needs the encrypt/upload pipeline, not just this call). */
    suspend fun forwardMessage(sourceClientMessageId: String, destinationChatId: String): Result<Unit>

    /** Saves a TEXT message's already-decrypted local content into Saved
     *  Messages as an INDEPENDENT new message (new client_message_id, new
     *  Signal encryption) - never a backend-visible pointer to the original
     *  (M.44/M.45/M.53). Unlike [forwardMessage] it is not marked
     *  "Forwarded". */
    suspend fun saveMessage(sourceClientMessageId: String, savedChatId: String): Result<Unit>
}

