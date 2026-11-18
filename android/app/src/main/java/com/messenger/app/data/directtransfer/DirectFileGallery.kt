package com.messenger.app.data.directtransfer

import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.domain.model.MediaItem
import java.io.File

internal fun directMediaTab(mime: String): String = if (mime.startsWith("image/") || mime.startsWith("video/")) "image" else "file"

/** Only a completed, identity-bound local journal plus its checked original enters the gallery. */
internal fun completedDirectMediaItem(record: DirectTransferRecord, message: MessageEntity, checkedFile: File?): MediaItem? {
    val offer = record.offer
    if (!record.completed || record.state != "completed" || record.role !in setOf("sender", "receiver") ||
        message.kind != "content" || message.hiddenLocally || message.isDeletedForEveryone || message.forwarded ||
        message.chatId != record.chatId || message.clientMessageId != offer.offerClientMessageId || message.senderId != offer.senderUserId ||
        message.sequence == null || checkedFile == null || !checkedFile.isFile || checkedFile.length() != offer.size) return null
    return MediaItem("direct:" + offer.transferId, message.clientMessageId, message.chatId, directMediaTab(offer.mimeType),
        offer.mimeType, offer.filename, offer.size, offer.safeMediaPreview?.durationMs, null, "downloaded", checkedFile.path,
        message.senderId, message.sequence, message.createdAt, directTransferId = offer.transferId)
}
