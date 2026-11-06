package com.messenger.app.data.local.chat

import androidx.room.*

/** Account-local durable composer/workspace state, cleared with the message DB. */
@Entity(tableName = "chat_workspace", primaryKeys = ["chatId", "name"])
data class ChatWorkspaceValue(val chatId: String, val name: String, val value: String)
/** Bounded local recovery snapshots; never used by chat/history UI. */
data class ChatWorkspaceRecoveryRow(val rowId: Long, val chatId: String, val name: String, val value: String)
data class ChatWorkspaceRecoveryLookup(val chatId: String, val name: String, val required: Boolean = true)
class ChatWorkspaceRecoveryConflict : IllegalStateException("Direct recovery snapshot changed")

@Dao
interface ChatWorkspaceDao {
    @Query("SELECT COALESCE(MAX(rowid),0) FROM chat_workspace WHERE name GLOB 'direct_transfer:*'")
    suspend fun directRecoveryUpperRowId(): Long
    @Query("SELECT rowid AS rowId, chatId, name, value FROM chat_workspace WHERE name GLOB 'direct_transfer:*' AND rowid>:afterRowId AND rowid<=:upperRowId ORDER BY rowid LIMIT 8")
    suspend fun directRecoveryPage(afterRowId: Long, upperRowId: Long): List<ChatWorkspaceRecoveryRow>

    /** Short transaction: no decoding, scanning loop, network or native work inside it. */
    @Transaction
    suspend fun checkpointDirectRecovery(value: ChatWorkspaceValue, expected: String?, page: List<ChatWorkspaceRecoveryRow>) {
        require(page.size <= 8)
        checkpointDirectRecoveryValues(value, expected, page.map { ChatWorkspaceValue(it.chatId, it.name, it.value) })
    }

    /** Bounded index-only reconciliation against exact unchanged canonical records. */
    @Transaction
    suspend fun checkpointDirectRecoveryValues(value: ChatWorkspaceValue, expected: String?, rows: List<ChatWorkspaceValue>) {
        require(rows.size <= 16)
        if (get(value.chatId, value.name) != expected) throw ChatWorkspaceRecoveryConflict()
        for (row in rows) if (get(row.chatId, row.name) != row.value) throw ChatWorkspaceRecoveryConflict()
        put(value)
    }

    /** Normal progress has recovery=null: exactly one canonical write and no index read. */
    @Transaction
    suspend fun putDirectTransferRecovery(value: ChatWorkspaceValue, recovery: ChatWorkspaceValue?, expected: String?) {
        if (recovery != null && get(recovery.chatId, recovery.name) != expected) throw ChatWorkspaceRecoveryConflict()
        put(value)
        if (recovery != null) put(recovery)
    }

    /** At most sixteen canonical jobs from one consistent SQLite snapshot. */
    @Transaction
    suspend fun directRecoveryRecords(indexChat: String, indexName: String, expected: String,
        lookups: List<ChatWorkspaceRecoveryLookup>): List<ChatWorkspaceValue> {
        require(lookups.size <= 16)
        if (get(indexChat, indexName) != expected) throw ChatWorkspaceRecoveryConflict()
        return lookups.mapNotNull { lookup ->
            val raw = get(lookup.chatId, lookup.name)
            if (raw == null && lookup.required) throw IllegalStateException("Missing direct recovery record")
            raw?.let { ChatWorkspaceValue(lookup.chatId, lookup.name, it) }
        }
    }

    // Export streams small pages; UI/protocol bounds remain independent.
    @Query("SELECT * FROM chat_workspace WHERE name LIKE 'direct_transfer:%' ORDER BY rowid LIMIT 8 OFFSET :offset")
    suspend fun historyDirectTransferPage(offset: Int): List<ChatWorkspaceValue>
    @Query("SELECT * FROM chat_workspace WHERE name LIKE 'direct_transfer:%' ORDER BY rowid DESC LIMIT 1024")
    suspend fun allDirectTransfers(): List<ChatWorkspaceValue>
    @Query("SELECT * FROM chat_workspace WHERE name LIKE 'direct_transfer:%' ORDER BY rowid DESC LIMIT 1024")
    fun observeAllDirectTransfers(): kotlinx.coroutines.flow.Flow<List<ChatWorkspaceValue>>
    @Query("SELECT * FROM (SELECT * FROM chat_workspace WHERE chatId=:chatId AND name LIKE 'direct_transfer:%' ORDER BY rowid DESC LIMIT 1024) UNION ALL SELECT * FROM (SELECT * FROM chat_workspace WHERE chatId=:chatId AND name LIKE 'direct_history:%' ORDER BY rowid DESC LIMIT 1024)")
    fun directTransfers(chatId: String): kotlinx.coroutines.flow.Flow<List<ChatWorkspaceValue>>
    @Query("SELECT * FROM chat_workspace WHERE chatId=:chatId AND name LIKE 'conference_declined:%'")
    fun conferenceDecisions(chatId: String): kotlinx.coroutines.flow.Flow<List<ChatWorkspaceValue>>
    @Query("SELECT * FROM chat_workspace WHERE name IN ('pending_preference:pin', 'pending_preference:mute', 'pending_preference:archive')")
    suspend fun pendingPreferences(): List<ChatWorkspaceValue>
    @Transaction
    suspend fun queueArchives(values: List<ChatWorkspaceValue>, snapshot: ChatWorkspaceValue) {
        values.forEach { put(it) }
        put(snapshot)
    }
    @Query("UPDATE chats SET isMuted=:enabled, mutedUntil=:until WHERE chatId=:chat")
    suspend fun projectMute(chat: String, enabled: Boolean, until: String?)
    @Transaction
    suspend fun queueMute(value: ChatWorkspaceValue, enabled: Boolean, until: String?) {
        put(value)
        projectMute(value.chatId, enabled, until)
    }

    @Query("DELETE FROM chat_workspace WHERE name IN ('call_activity_at','call_unread')")
    suspend fun clearCallActivity()
    @androidx.room.Transaction
    suspend fun replaceCallActivity(values: List<ChatWorkspaceValue>) { clearCallActivity(); values.forEach { put(it) } }

    @Query("SELECT * FROM chat_workspace WHERE chatId = :chatId AND name LIKE 'upload:%'")
    suspend fun mediaJobs(chatId: String): List<ChatWorkspaceValue>
    @Query("SELECT w.* FROM chat_workspace w JOIN messages m ON m.chatId=w.chatId AND w.name='presentation:' || m.clientMessageId WHERE w.chatId=:chatId AND m.hiddenLocally=0 AND m.isDeletedForEveryone=0")
    suspend fun mediaPresentations(chatId: String): List<ChatWorkspaceValue>
    @Query("SELECT * FROM chat_workspace WHERE name='personal_clear' OR (chatId='__server_history__' AND name='cleared_through_sequence') LIMIT 10002")
    suspend fun historyArchiveValues(): List<ChatWorkspaceValue>
    @Query("SELECT * FROM chat_workspace")
    suspend fun allValues(): List<ChatWorkspaceValue>
    @Query("DELETE FROM chat_workspace WHERE name LIKE 'upload:%' AND EXISTS (SELECT 1 FROM messages m WHERE m.chatId = chat_workspace.chatId AND chat_workspace.name = 'upload:' || m.clientMessageId AND (m.status IN ('sent','delivered','read') OR m.hiddenLocally = 1))")
    suspend fun removeCompletedMediaJobs()
    @Query("SELECT * FROM chat_workspace WHERE chatId = :chatId AND name LIKE 'schedule:%'")
    fun schedules(chatId: String): kotlinx.coroutines.flow.Flow<List<ChatWorkspaceValue>>
    @Query("SELECT value FROM chat_workspace WHERE chatId = :chatId AND name = :name")
    suspend fun get(chatId: String, name: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ChatWorkspaceValue)
    @Query("DELETE FROM chat_workspace WHERE chatId = :chatId AND name = :name")
    suspend fun remove(chatId: String, name: String)
}
