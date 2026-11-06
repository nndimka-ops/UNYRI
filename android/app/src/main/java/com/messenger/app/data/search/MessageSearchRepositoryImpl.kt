package com.messenger.app.data.search

import androidx.sqlite.db.SimpleSQLiteQuery
import com.messenger.app.data.local.search.SearchDao
import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.repository.MessageSearchRepository

class MessageSearchRepositoryImpl(
    private val dao: SearchDao,
) : MessageSearchRepository {

    override suspend fun globalSearch(query: String, limit: Int): List<MessageSearchResult> {
        val match = SearchQueryBuilder.build(query) ?: return emptyList()
        val sql = SimpleSQLiteQuery(
            """
            SELECT client_message_id AS clientMessageId, chat_id AS chatId, sender_id AS senderId,
                   sequence AS sequence, created_at AS createdAt, text AS text
            FROM messages_fts
            WHERE messages_fts MATCH ?
            ORDER BY julianday(created_at) DESC, client_message_id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(match, limit),
        )
        return mapQuery { dao.globalSearch(sql) }
    }

    override suspend fun chatSearch(chatId: String, query: String, limit: Int): List<MessageSearchResult> {
        val match = SearchQueryBuilder.build(query) ?: return emptyList()
        val sql = SimpleSQLiteQuery(
            """
            SELECT client_message_id AS clientMessageId, chat_id AS chatId, sender_id AS senderId,
                   sequence AS sequence, created_at AS createdAt, text AS text
            FROM messages_fts
            WHERE messages_fts MATCH ? AND chat_id = ?
            ORDER BY julianday(created_at) DESC, client_message_id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(match, chatId, limit),
        )
        return mapQuery { dao.chatSearch(sql) }
    }

    // Report storage errors to the view model so Retry is available. A failed
    // database read is not evidence that the query has no matches.
    private inline fun mapQuery(block: () -> List<com.messenger.app.data.local.search.SearchResultRow>): List<MessageSearchResult> =
        block().map { row ->
            MessageSearchResult(
                messageId = row.clientMessageId,
                chatId = row.chatId,
                senderId = row.senderId,
                sequence = row.sequence,
                createdAt = row.createdAt,
                text = com.messenger.app.conference.ConferenceInvitation.safePreview(row.text),
            )
        }
}
