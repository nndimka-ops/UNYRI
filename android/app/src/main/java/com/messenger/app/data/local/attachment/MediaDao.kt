package com.messenger.app.data.local.attachment

import androidx.room.Dao
import androidx.room.Query

/** Gallery queries build from the Room projection only (M.24) - never a
 *  backend media search. Tombstoned (`isDeletedForEveryone`) and locally
 *  hidden rows are excluded by the join, so a deleted message never lingers
 *  in the gallery. Keyset-paginated by descending message sequence. */
@Dao
interface MediaDao {

    @Query(
        """
        SELECT a.attachmentId AS attachmentId, a.clientMessageId AS clientMessageId, a.chatId AS chatId,
               a.mediaType AS mediaType, a.mimeType AS mimeType, a.filename AS filename, a.size AS size,
               a.durationMs AS durationMs, a.thumbnailBase64 AS thumbnailBase64, a.downloadState AS downloadState,
               a.localPath AS localPath, m.senderId AS senderId, m.sequence AS sequence, m.createdAt AS createdAt
        FROM attachments a
        JOIN messages m ON m.clientMessageId = a.clientMessageId
        WHERE a.chatId = :chatId AND a.mediaType = :mediaType
          AND m.kind = 'content' AND m.isDeletedForEveryone = 0 AND m.hiddenLocally = 0
          AND m.sequence < :beforeSequence
        ORDER BY m.sequence DESC
        LIMIT :limit
        """
    )
    suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItemRow>
}
