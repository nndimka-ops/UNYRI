package com.messenger.app.data.local.attachment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE attachmentId = :attachmentId")
    suspend fun findByAttachmentId(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE clientMessageId = :clientMessageId ORDER BY rowid DESC LIMIT 1")
    suspend fun findByClientMessageId(clientMessageId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE chatId = :chatId")
    fun observeForChat(chatId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE localPath IS NOT NULL")
    suspend fun allWithLocalPath(): List<AttachmentEntity>

    @Query("UPDATE attachments SET uploadState = :state, progress = :progress WHERE attachmentId = :attachmentId")
    suspend fun updateUploadState(attachmentId: String, state: String, progress: Int)

    /** An acknowledged message owns no upload work. Never overwrite a concurrent
     * download's progress/file fields or resurrect an explicitly cancelled upload. */
    @Query("UPDATE attachments SET uploadState = 'sent' WHERE attachmentId = :attachmentId AND clientMessageId = :clientMessageId AND chatId = :chatId AND LOWER(uploadState) NOT IN ('sent', 'cancelled')")
    suspend fun markUploadSent(attachmentId: String, clientMessageId: String, chatId: String): Int

    @Query("UPDATE attachments SET downloadState = :state, progress = :progress WHERE attachmentId = :attachmentId")
    suspend fun updateDownloadState(attachmentId: String, state: String, progress: Int)

    @Query("UPDATE attachments SET downloadState = :state, localPath = :localPath, progress = 100 WHERE attachmentId = :attachmentId")
    suspend fun markDownloaded(attachmentId: String, state: String, localPath: String)

    /** Called when the owning message is tombstoned/hidden (Этап I п.14/47)
     *  - the caller deletes the actual cached plaintext file first, this
     *  just clears Room's reference to it so the UI stops offering
     *  Open/Download for a message that no longer exists. */
    @Query("UPDATE attachments SET downloadState = 'not_downloaded', localPath = NULL, progress = 0 WHERE attachmentId = :attachmentId")
    suspend fun clearLocalCache(attachmentId: String)
}
