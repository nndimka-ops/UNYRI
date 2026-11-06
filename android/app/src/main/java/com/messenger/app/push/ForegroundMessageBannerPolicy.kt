package com.messenger.app.push

import com.messenger.app.domain.model.ChatPreviewFormatter
import com.messenger.app.domain.model.LastMessagePreview

/** Pure decision policy for the foreground message banner (Runtime Correction D).
 *  No Android/UI types — JVM-testable. This is the inverse of
 *  [MessagePushHandlerImpl.shouldNotify]: a banner shows exactly when a system
 *  notification is suppressed because the app is foreground and a DIFFERENT
 *  chat received the message. */
object ForegroundMessageBannerPolicy {

    /** Auto-dismiss after ~3.5s (spec: "restrained period"). */
    const val AUTO_DISMISS_MS = 4000L

    fun shouldShow(
        isForeground: Boolean,
        activeChatId: String?,
        eventChatId: String,
        isOwnMessage: Boolean,
        activeCall: Boolean,
    ): Boolean = isForeground && !activeCall && !isOwnMessage && eventChatId != activeChatId

    /** Maps raw local-message state to a typed preview, reusing the SAME
     *  [ChatPreviewFormatter] the chat list uses. Returns null when no safe,
     *  human-readable preview exists (never raw JSON / object keys / UUIDs),
     *  in which case the caller must SKIP the banner rather than show debug
     *  data. */
    fun previewFor(
        kind: String,
        status: String,
        isDeleted: Boolean,
        text: String?,
        mediaType: String?,
        filename: String?,
        mimeType: String? = null,
    ): LastMessagePreview? {
        // Lifecycle events (edit/delete/reaction) and undecodable rows have
        // no safe preview.
        if (kind != "content") return null
        if (status == "decrypt_failed" || status == "identity_changed") return null
        val discriminator = when {
            isDeleted -> "deleted"
            mimeType?.startsWith("video/", ignoreCase = true) == true -> "video"
            mediaType == "image" -> "image"
            mediaType == "voice" -> "voice"
            mediaType == "file" -> "file"
            else -> "text"
        }
        return ChatPreviewFormatter.from(discriminator, text, filename)
    }
}
