package com.messenger.app.data.local.chat

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "deleted_chats")
data class DeletedChat(@PrimaryKey val chatId: String)

@Dao
interface DeletedChatDao {
    @Query("SELECT COALESCE(MAX(sequence),0) FROM messages")
    suspend fun maximumKnownSequence(): Long
    @Query("UPDATE messages SET hiddenLocally=1, status=CASE WHEN status='pending' THEN 'failed' ELSE status END WHERE chatId=:id AND sequence IS NULL")
    suspend fun hideCurrentUnsent(id: String)
    @Query("DELETE FROM chat_workspace WHERE chatId=:id AND (name IN ('draft','mediaDrafts','mediaCaption','mediaAlbum','mediaImportPending','fileDraftUri','voiceDraft','cameraSendDraft','cameraCaptureIntent','opened_on_device','call_activity_at','call_unread') OR name LIKE 'upload:%' OR name LIKE 'schedule:%')")
    suspend fun clearCurrentComposer(id: String)
    @Query("SELECT chatId FROM deleted_chats LIMIT 10001")
    suspend fun historyArchiveIds(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM deleted_chats WHERE chatId = :chatId)")
    suspend fun contains(chatId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: DeletedChat)
    @Query("DELETE FROM reactions WHERE targetMessageId IN (SELECT serverId FROM messages WHERE chatId = :chatId)")
    suspend fun purgeReactions(chatId: String)
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun purgeMessages(chatId: String)
    @Query("DELETE FROM attachments WHERE chatId = :chatId")
    suspend fun purgeAttachments(chatId: String)
    @Query("DELETE FROM chat_workspace WHERE chatId = :chatId")
    suspend fun purgeWorkspace(chatId: String)
    @Query("DELETE FROM chat_members WHERE chatId = :chatId")
    suspend fun purgeMembers(chatId: String)
    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun purgeChat(chatId: String)
    @Query("DELETE FROM chat_participants WHERE userId NOT IN (SELECT userId FROM chat_members)")
    suspend fun purgeOrphanParticipants()

    @Query("SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId=:id AND name='personal_clear'")
    suspend fun personalCutoff(id: String): Long?
    @Query("INSERT INTO chat_workspace(chatId,name,value) VALUES(:id,'personal_clear',:cutoff) ON CONFLICT(chatId,name) DO UPDATE SET value=MAX(CAST(value AS INTEGER),CAST(excluded.value AS INTEGER))")
    suspend fun savePersonalCutoff(id: String, cutoff: String)
    @Query("DELETE FROM reactions WHERE targetMessageId IN (SELECT serverId FROM messages WHERE chatId=:id AND sequence<=:cutoff)")
    suspend fun clearPersonalReactions(id: String, cutoff: Long)
    @Query("DELETE FROM attachments WHERE clientMessageId IN (SELECT clientMessageId FROM messages WHERE chatId=:id AND sequence<=:cutoff)")
    suspend fun clearPersonalAttachments(id: String, cutoff: Long)
    @Query("DELETE FROM messages WHERE chatId=:id AND sequence<=:cutoff")
    suspend fun clearPersonalMessages(id: String, cutoff: Long)
    @Query("DELETE FROM pending_decrypts WHERE chatId=:id AND sequence<=:cutoff")
    suspend fun clearPersonalPending(id: String, cutoff: Long)
    @Transaction suspend fun clearPersonal(id: String, cutoff: Long) {
        require(cutoff >= 0)
        if (cutoff <= (personalCutoff(id) ?: -1)) return
        savePersonalCutoff(id, cutoff.toString())
        clearPersonalReactions(id, cutoff); clearPersonalAttachments(id, cutoff)
        clearPersonalMessages(id, cutoff); clearPersonalPending(id, cutoff)
    }
    @Transaction suspend fun tombstone(chatId: String) {
        insert(DeletedChat(chatId))
        purgeReactions(chatId)
        purgeMessages(chatId)
        purgeAttachments(chatId)
        purgeWorkspace(chatId)
        purgeMembers(chatId)
        purgeChat(chatId)
        purgeOrphanParticipants()
    }
}

/** Enforce permanent account-local deletion even against stale HTTP, WS and queued UI writes. */
internal fun installDeletedChatGuards(db: SupportSQLiteDatabase) {
    for (operation in listOf("INSERT", "UPDATE")) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS personal_clear_messages_$operation BEFORE $operation ON messages WHEN NEW.sequence IS NOT NULL AND NEW.sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId=NEW.chatId AND name='personal_clear') BEGIN SELECT RAISE(IGNORE); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS history_clear_messages_$operation BEFORE $operation ON messages WHEN NEW.sequence IS NOT NULL AND NEW.sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId='__server_history__' AND name='cleared_through_sequence') BEGIN SELECT RAISE(IGNORE); END")
    }
    for (table in listOf("chats", "messages", "attachments", "chat_workspace", "chat_members")) {
        for (operation in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS deleted_chat_${table}_${operation} BEFORE $operation ON $table WHEN EXISTS (SELECT 1 FROM deleted_chats WHERE chatId = NEW.chatId) BEGIN SELECT RAISE(IGNORE); END")
        }
    }
}
