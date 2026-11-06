package com.messenger.app.ui.chats

import com.messenger.app.domain.model.*

/** Presentation only: preserve the local preview until its replacement is ready. */
internal fun queuedMediaPresentation(message: Message, stored: Attachment?): Attachment? {
    val queued = message.queuedAttachment
    var result = stored ?: queued ?: return null
    if (stored != null && stored.uploadState != AttachmentUploadState.SENT && stored.localPath == null && queued?.localPath != null) {
        result = stored.copy(localPath = queued.localPath, downloadState = queued.downloadState,
            width = queued.width, height = queued.height)
    }
    val layout = message.localMediaPresentation
    if (layout?.visual == true) {
        // Reserve the original aspect even when transcoding rounds dimensions.
        val ratio = layout.aspectRatio ?: 1f
        result = result.copy(width = (ratio.coerceIn(.2f, 5f) * 10_000).toInt(), height = 10_000)
    }
    // A restored/multi-device row may retain its old transport state even
    // though the message is already confirmed. Upload controls cannot own an ACK.
    if (result.clientMessageId == message.clientMessageId && !message.serverId.isNullOrBlank() &&
        (message.sequence ?: 0L) > 0L && !message.isDeletedForEveryone &&
        message.status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ) &&
        result.uploadState != AttachmentUploadState.CANCELLED) {
        // Progress belongs to downloads too. Preserve it and all cached metadata.
        result = result.copy(uploadState = AttachmentUploadState.SENT)
    }
    return result
}
