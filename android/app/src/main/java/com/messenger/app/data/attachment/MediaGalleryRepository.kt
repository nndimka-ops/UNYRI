package com.messenger.app.data.attachment

import com.messenger.app.data.local.attachment.MediaDao
import com.messenger.app.data.local.attachment.MediaItemRow
import com.messenger.app.domain.model.MediaItem

/** Narrow read seam so [com.messenger.app.ui.media.MediaGalleryViewModel] can
 *  be unit-tested with an in-memory fake (M.2 BUG 3/4 regression tests). */
interface MediaGallerySource {
    suspend fun page(chatId: String, mediaType: String, beforeSequence: Long = Long.MAX_VALUE, limit: Int = 100): List<MediaItem>
}

/** Local media gallery repository (M.19-M.29): reads the Room projection,
 *  never a backend media search, and never decodes/downloads blobs eagerly. */
class MediaGalleryRepository(
    private val dao: MediaDao,
) : MediaGallerySource {
    override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItem> =
        dao.page(chatId, mediaType, beforeSequence, limit).map { it.toDomain() }

    private fun MediaItemRow.toDomain() = MediaItem(
        attachmentId = attachmentId,
        clientMessageId = clientMessageId,
        chatId = chatId,
        mediaType = mediaType,
        mimeType = mimeType,
        filename = filename,
        size = size,
        durationMs = durationMs,
        thumbnailBase64 = thumbnailBase64,
        downloadState = downloadState,
        localPath = localPath,
        senderId = senderId,
        sequence = sequence,
        createdAt = createdAt,
    )
}
