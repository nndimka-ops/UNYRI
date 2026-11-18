package com.messenger.app.data.search

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.ensureActive
import com.messenger.app.data.local.search.SearchDao
import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.repository.MessageSearchRepository

class MessageSearchRepositoryImpl(
    private val dao: SearchDao,
    private val resolveOffers: suspend (String, Set<String>) -> Map<String, String> = { _, _ -> emptyMap() },
) : MessageSearchRepository {

    override suspend fun globalSearch(query: String, limit: Int): List<MessageSearchResult> = search(null, query, limit)
    override suspend fun chatSearch(chatId: String, query: String, limit: Int): List<MessageSearchResult> = search(chatId, query, limit)
    private suspend fun search(chatId: String?, query: String, limit: Int): List<MessageSearchResult> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val term = query.trim()
            if (term.isEmpty()) return@withContext emptyList()
            val found = mutableListOf<MessageSearchResult>()
            val cap = limit.coerceIn(1, 100)
            var offset = 0
            // Bounded pages avoid a full-history allocation. Kotlin matching supports Unicode
            // case folding (SQLite lower/NOCASE only handles ASCII on Android).
            while (found.size < cap) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val sql = SimpleSQLiteQuery("""
                    SELECT m.clientMessageId, m.chatId, m.senderId, m.sequence, m.createdAt, m.text, m.caption,
                        a.filename, a.mediaType, a.mimeType, a.thumbnailBase64
                    FROM messages m LEFT JOIN attachments a ON a.clientMessageId = m.clientMessageId
                    WHERE m.kind = 'content' AND m.hiddenLocally = 0 AND m.isDeletedForEveryone = 0
                        AND m.status NOT IN ('decrypt_failed', 'identity_changed')
                        AND (? IS NULL OR m.chatId = ?)
                    ORDER BY julianday(m.createdAt) DESC, m.clientMessageId DESC LIMIT 256 OFFSET ?
                """.trimIndent(), arrayOf(chatId, chatId, offset))
                val page = dao.globalSearch(sql)
                val offers = mutableMapOf<String, String>()
                for ((chat, rows) in page.filter { com.messenger.app.domain.model.FilePreviewText.isDirectFallback(it.text) }.groupBy { it.chatId }) {
                    offers += resolveOffers(chat, rows.map { it.clientMessageId }.toSet())
                }
                found += page.mapNotNull { row ->
                    val file = com.messenger.app.domain.model.FilePreviewText.direct(row.text, offers[row.clientMessageId])
                        ?: com.messenger.app.domain.model.FilePreviewText.preview(row.text)
                    val searchable = file?.let { listOfNotNull(it.filename, it.caption).joinToString("\n\n") } ?: row.text
                    if (!searchable.contains(term, true) && row.caption?.contains(term, true) != true && row.filename?.contains(term, true) != true) return@mapNotNull null
                    val snippet = file?.projected() ?: if (row.caption?.contains(term, true) == true) row.caption
                        else if (row.text.contains(term, true)) row.text else row.filename.orEmpty()
                    toResult(row, snippet.orEmpty(), file != null)
                }.take(cap - found.size)
                if (page.size < 256) break
                offset += page.size
            }
            found
        }

    private fun toResult(row: com.messenger.app.data.local.search.SearchResultRow, text: String, file: Boolean) = MessageSearchResult(
        messageId = row.clientMessageId, chatId = row.chatId, senderId = row.senderId, sequence = row.sequence,
        createdAt = row.createdAt, text = com.messenger.app.conference.ConferenceInvitation.safePreview(text),
        mediaType = if (file) "file" else row.mediaType,
        mimeType = row.mimeType, thumbnailBase64 = row.thumbnailBase64,
    )
}
