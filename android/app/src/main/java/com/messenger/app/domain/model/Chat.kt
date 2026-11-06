package com.messenger.app.domain.model

data class ChatSummary(
    val chatId: String,
    val type: String,
    val otherUser: PublicUser?,
    val createdAt: String,
    val lastMessageAt: String?,
    val isMuted: Boolean = false,
    val mutedUntil: String? = null,
    val isBlocked: Boolean = false,
    val blockedUserId: String? = null,
    val unreadCount: Int = 0,
    /** Receipt reconciliation: how far the other member has delivered/read
     *  messages *we* sent in this chat (0 = nothing yet). Recovers state a
     *  missed `receipt.updated` WS event would otherwise have carried. */
    val otherMemberDeliveredSequence: Long = 0,
    val otherMemberReadSequence: Long = 0,
    /** Last-message preview derived from the LOCAL message cache only (never
     *  from the backend, which exposes no preview text). Null when this chat
     *  has no cached displayable message yet - callers fall back to the
     *  peer's @username. Never raw JSON / object keys / crypto metadata. */
    val lastPreview: LastMessagePreview? = null,
) {
    fun suppressesNotifications(now: java.time.Instant = java.time.Instant.now()): Boolean =
        isBlocked || (isMuted && (mutedUntil == null || runCatching { java.time.Instant.parse(mutedUntil).isAfter(now) }.getOrDefault(true)))
}

/** A human-safe preview of a chat's latest message. Purely presentational -
 *  carries no protocol/crypto data. */
sealed interface LastMessagePreview {
    data class Text(val text: String) : LastMessagePreview
    data object Photo : LastMessagePreview
    data object Voice : LastMessagePreview
    data object Video : LastMessagePreview
    data class File(val filename: String?) : LastMessagePreview
    data object Deleted : LastMessagePreview
}

/** Pure, JVM-testable mapping from the raw latest-message row (as selected
 *  by [com.messenger.app.data.local.chat.ChatDao]) to a presentation preview.
 *  The raw `kind` values are the DB discriminator: "text" | "image" |
 *  "voice" | "file" | "deleted". Blank text yields null (fall back to
 *  @username) - it never leaks JSON/UUID/object-key content. */
object ChatPreviewFormatter {
    fun from(kind: String?, text: String?, filename: String?): LastMessagePreview? = when (kind) {
        null, "" -> null
        "deleted" -> LastMessagePreview.Deleted
        "image" -> LastMessagePreview.Photo
        "voice" -> LastMessagePreview.Voice
        "video" -> LastMessagePreview.Video
        "file" -> LastMessagePreview.File(filename?.takeIf { it.isNotBlank() })
        else -> text?.takeIf { it.isNotBlank() }?.let { LastMessagePreview.Text(com.messenger.app.conference.ConferenceInvitation.safePreview(it)) }
    }
}
