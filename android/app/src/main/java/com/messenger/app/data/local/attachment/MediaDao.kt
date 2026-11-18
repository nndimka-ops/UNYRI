package com.messenger.app.data.local.attachment

import androidx.room.Dao
import androidx.room.Query

/** Gallery queries build from the Room projection only (M.24) - never a
 *  backend media search. Tombstoned (`isDeletedForEveryone`) and locally
 *  hidden rows are excluded by the join, so a deleted message never lingers
 *  in the gallery. Keyset-paginated by descending message sequence. */
@Dao
interface MediaDao {

    @Query(MEDIA_GALLERY_PAGE_SQL)
    suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItemRow>

    @Query(DIRECT_GALLERY_PAGE_SQL)
    suspend fun directPage(chatId: String, beforeSequence: Long, limit: Int): List<DirectFileGalleryRow>
}

internal const val MEDIA_GALLERY_PAGE_SQL = """
        SELECT a.attachmentId AS attachmentId, a.clientMessageId AS clientMessageId, a.chatId AS chatId,
               a.mediaType AS mediaType, a.mimeType AS mimeType, a.filename AS filename, a.size AS size,
               a.durationMs AS durationMs, a.thumbnailBase64 AS thumbnailBase64, a.downloadState AS downloadState,
               a.localPath AS localPath, m.senderId AS senderId, m.sequence AS sequence, m.createdAt AS createdAt
        FROM attachments a
        JOIN messages m ON m.clientMessageId = a.clientMessageId
        WHERE a.chatId = :chatId AND (
          (:mediaType = 'image' AND (a.mimeType GLOB 'image/*' OR a.mimeType GLOB 'video/*')) OR
          (:mediaType != 'image' AND a.mediaType = :mediaType AND a.mimeType NOT GLOB 'image/*' AND a.mimeType NOT GLOB 'video/*'))
          AND m.kind = 'content' AND m.isDeletedForEveryone = 0 AND m.hiddenLocally = 0
          AND m.sequence < :beforeSequence
        ORDER BY m.sequence DESC
        LIMIT :limit
        """

/** Journals are written by the canonical Kotlin serializer. Avoid JSON1, absent on older Android SQLite.
 * The repository still validates the complete record, workspace key and every message identity. */
internal const val DIRECT_GALLERY_PAGE_SQL = """
        SELECT m.*, w.value AS directRecord, w.name AS directName
        FROM messages m JOIN chat_workspace w ON w.chatId = m.chatId AND w.name LIKE 'direct_transfer:%'
          AND m.clientMessageId = substr(w.value,
            instr(w.value, '"offer_client_message_id":"') + length('"offer_client_message_id":"'), 36)
        WHERE m.chatId = :chatId AND m.kind = 'content' AND m.isDeletedForEveryone = 0 AND m.hiddenLocally = 0
          AND m.sequence < :beforeSequence
        ORDER BY m.sequence DESC LIMIT :limit
    """
