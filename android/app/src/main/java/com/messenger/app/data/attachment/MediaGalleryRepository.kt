package com.messenger.app.data.attachment

import com.messenger.app.data.local.attachment.MediaDao
import com.messenger.app.data.local.attachment.MediaItemRow
import com.messenger.app.domain.model.MediaItem
import com.messenger.app.data.directtransfer.*
import java.io.File

/** Narrow read seam so [com.messenger.app.ui.media.MediaGalleryViewModel] can
 *  be unit-tested with an in-memory fake (M.2 BUG 3/4 regression tests). */
interface MediaGallerySource {
    suspend fun page(chatId: String, mediaType: String, beforeSequence: Long = Long.MAX_VALUE, limit: Int = 100): List<MediaItem>
    suspend fun directFile(chatId: String, id: String): File? = null
}

/** Local media gallery repository (M.19-M.29): reads the Room projection,
 *  never a backend media search, and never decodes/downloads blobs eagerly. */
class MediaGalleryRepository(
    private val dao: MediaDao,
    private val safeCachedPath: (String?) -> String? = { null },
    private val completedDirectFile: suspend (String, String) -> Pair<File, DirectFileOffer>? = { _, _ -> null },
) : MediaGallerySource {
    override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItem> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val count = limit.coerceIn(1, 100)
            val ordinary = dao.page(chatId, mediaType, beforeSequence, count).map { it.toDomain() }
            if (mediaType == "voice") return@withContext ordinary
            val direct = mutableListOf<MediaItem>()
            var cursor = beforeSequence
            // Page past incomplete/evicted jobs; they must neither appear nor hide earlier completed media.
            while (direct.size < count) {
                val rows = dao.directPage(chatId, cursor, count)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val record = DirectTransferCoordinator.decodeRecord(row.directRecord) ?: continue
                    if (row.directName != DirectTransferCoordinator.key(record.offer.transferId)) continue
                    if (!record.completed || record.state != "completed" || record.chatId != chatId || directMediaTab(record.offer.mimeType) != mediaType) continue
                    val local = completedDirectFile(chatId, record.offer.transferId)?.takeIf { it.second == record.offer }?.first
                    val item = completedDirectMediaItem(record, row.message, local) ?: continue
                    if (item.mediaType == mediaType) direct += item
                }
                val next = rows.last().message.sequence ?: break
                if (next >= cursor) break
                cursor = next
                if (rows.size < count) break
            }
            (ordinary + direct).distinctBy { it.attachmentId }.sortedByDescending { it.sequence }.take(count)
        }

    override suspend fun directFile(chatId: String, id: String): File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        completedDirectFile(chatId, id)?.first
    }

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
        localPath = safeCachedPath(localPath),
        senderId = senderId,
        sequence = sequence,
        createdAt = createdAt,
    )
}
