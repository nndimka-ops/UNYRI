package com.messenger.app.data.local.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions ORDER BY targetMessageId,userId LIMIT 50001")
    suspend fun historyArchiveRows(): List<ReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("SELECT lastReactionSequence FROM reactions WHERE targetMessageId = :targetMessageId AND userId = :userId")
    suspend fun currentSequence(targetMessageId: String, userId: String): Long?

    @Query("SELECT * FROM reactions WHERE targetMessageId = :targetMessageId AND userId = :userId")
    suspend fun find(targetMessageId: String, userId: String): ReactionEntity?

    /** Only rows with an ACTIVE (non-null) emoji - a "removed" row exists
     *  purely for sequence bookkeeping (see [ReactionEntity]'s doc comment)
     *  and must never render as a visible reaction. */
    @Query("SELECT * FROM reactions WHERE targetMessageId = :targetMessageId AND emoji IS NOT NULL ORDER BY lastReactionSequence, userId")
    fun observeActiveForMessage(targetMessageId: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE targetMessageId IN (:targetMessageIds) AND emoji IS NOT NULL ORDER BY targetMessageId, lastReactionSequence, userId")
    fun observeActiveForMessages(targetMessageIds: List<String>): Flow<List<ReactionEntity>>

    /** All active reactions across every message currently cached for this
     *  chat - one Flow the repository can group by target/emoji for the
     *  whole message list at once, rather than one Flow per bubble. */
    @Query(
        """
        SELECT r.* FROM reactions r JOIN messages m ON m.serverId = r.targetMessageId
        WHERE m.chatId = :chatId AND r.emoji IS NOT NULL
        ORDER BY r.targetMessageId, r.lastReactionSequence, r.userId
        """
    )
    fun observeActiveForChat(chatId: String): Flow<List<ReactionEntity>>

    /** Called when the target message is tombstoned - reactions to a
     *  deleted message are dropped entirely (Этап I п.40), no bookkeeping
     *  needed since a deleted message can never accept a new reaction
     *  again (enforced server-side too). */
    @Query("DELETE FROM reactions WHERE targetMessageId = :targetMessageId")
    suspend fun deleteAllForMessage(targetMessageId: String)
}
