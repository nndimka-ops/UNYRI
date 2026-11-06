package com.messenger.app.domain.model

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    /** Envelope arrived but could not be decrypted (tampered ciphertext,
     *  lost/desynced session, etc). Surfaced to the user rather than
     *  silently dropped or shown as if it were real content. */
    DECRYPT_FAILED,
    /** The sender's Signal identity key changed since it was pinned - the
     *  message is NOT decrypted and the peer is marked CHANGED until the
     *  user explicitly accepts the new key. Distinct from [DECRYPT_FAILED]
     *  so the UI can surface a security prompt rather than a generic error. */
    IDENTITY_CHANGED,
}

/** A resolved reply preview, computed once at reply-time and carried
 *  inside the reply message's own encrypted content (Этап I п.4) - never
 *  depends on the original message still being cached/decryptable. */
data class ReplyPreview(
    val targetServerId: String,
    val senderId: String,
    val contentType: String,
    val preview: String,
)

/** One active (emoji, count) group for a message's reaction row, plus
 *  whether the current user is the one who set it (Этап I п.21). */
data class ReactionSummary(val emoji: String, val count: Int, val reactedByMe: Boolean)

/** Device-local layout reservation. Never sent to the server or used for sync cursors. */
data class LocalMediaPresentation(val createdAt: String, val index: Int, val aspectRatio: Float?, val visual: Boolean)

data class Message(
    val clientMessageId: String,
    val serverId: String?,
    val chatId: String,
    val senderId: String,
    val text: String,
    val sequence: Long?,
    val createdAt: String,
    val status: MessageStatus,
    val isEdited: Boolean = false,
    val isDeletedForEveryone: Boolean = false,
    val replyPreview: ReplyPreview? = null,
    val forwarded: Boolean = false,
    val reactions: List<ReactionSummary> = emptyList(),
    val caption: String? = null,
    val albumId: String? = null,
    /** Local presentation only; populated before publishing a queued message snapshot. */
    val queuedAttachment: Attachment? = null,
    /** Safe local outbox reason only; never serialized into encrypted content. */
    val pendingReason: String? = null,
    val localMediaPresentation: LocalMediaPresentation? = null,
    val conferenceInvitation: com.messenger.app.conference.ConferenceInvitation? = null,
    val conferenceDeclined: Boolean = false,
    val directFile: com.messenger.app.data.directtransfer.DirectFilePresentation? = null,
)

/** Presentation only: never delete rows or advance the sync/remote read cursor.
 * Keep identity warnings and failures arriving after authentication visible. */
fun Message.isUnavailableHistory(cutoff: Long): Boolean =
    cutoff > 0 && sequence != null && sequence > 0 && sequence <= cutoff && status == MessageStatus.DECRYPT_FAILED

fun collapseUnavailableHistory(messages: List<Message>, cutoff: Long, collapseRecentFailures: Boolean = false): List<Message> {
    val recentMarker = messages.filter { it.status == MessageStatus.DECRYPT_FAILED && !it.isUnavailableHistory(cutoff) }.maxByOrNull { it.sequence ?: 0 }
    return messages.filter {
        if (it.isUnavailableHistory(cutoff)) false
        else !collapseRecentFailures || it.status != MessageStatus.DECRYPT_FAILED || it.clientMessageId == recentMarker?.clientMessageId
    }
}
