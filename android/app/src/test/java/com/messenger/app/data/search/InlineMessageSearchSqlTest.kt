package com.messenger.app.data.search

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.messenger.app.data.local.search.SearchDao
import com.messenger.app.data.local.search.SearchResultRow
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import kotlin.test.*

/** Executes production search SQL with real SQLite, including visibility and filename joins. */
class InlineMessageSearchSqlTest {
    @Test fun `pending offers and completed direct files match filenames in global and chat search with localized highlights`() = runTest {
        Database().use { database ->
            val first = com.messenger.app.data.directtransfer.DirectPreviewFixture.offer()
            val second = first.copy(offerClientMessageId = "10000000-0000-4000-8000-000000000006")
            database.add(first.offerClientMessageId, "Direct file transfer")
            database.add(second.offerClientMessageId, "Передача файла напрямую\n\nПодпись Caption Підпис")
            database.add("completed", "[File] b023-report.pdf")
            database.add("ordinary", "b023 ordinary text")
            database.add("broken", "Direct file transfer")
            val metadata = mapOf(first.offerClientMessageId to first.stored(), second.offerClientMessageId to second.stored(), "broken" to "{invalid")
            val repository = MessageSearchRepositoryImpl(database.dao, resolveOffers = { _, clients -> metadata.filterKeys { it in clients } })
            for (hits in listOf(repository.globalSearch("b023"), repository.chatSearch("chat", "b023"))) {
                assertEquals(setOf(first.offerClientMessageId, second.offerClientMessageId, "completed", "ordinary"), hits.map { it.messageId }.toSet())
                for (hit in hits.filter { it.messageId != "ordinary" }) {
                    assertEquals("file", hit.mediaType)
                    assertTrue(com.messenger.app.ui.search.InlineCategory.FILES in com.messenger.app.ui.search.InlineSearchPolicy.categories(hit))
                    for (label in listOf("Файл", "File", "Файл")) {
                        val display = com.messenger.app.domain.model.FilePreviewText.display(hit.text, label)
                        assertTrue(display.startsWith("$label: b023-report.pdf"))
                        val highlighted = com.messenger.app.ui.search.auxiliarySearchHighlight(display, "b023")
                        val span = highlighted.spanStyles.single()
                        assertEquals("b023", highlighted.text.substring(span.start, span.end))
                    }
                }
            }
            assertEquals(second.offerClientMessageId, repository.globalSearch("підпис").single().messageId)
            assertTrue(repository.globalSearch("Direct file transfer").isEmpty())
            assertTrue(repository.globalSearch("Передача файла напрямую").isEmpty())
        }
    }
    @Test fun `direct-file stored text is classified as file without an attachment join`() = runTest {
        Database().use { database ->
            database.add("direct", "[File] b023-report.pdf")
            val repository = MessageSearchRepositoryImpl(database.dao)
            for (hit in listOf(repository.globalSearch("b023").single(), repository.chatSearch("chat", "b023").single())) {
                assertEquals("direct", hit.messageId)
                assertEquals("file", hit.mediaType)
                assertEquals("File: b023-report.pdf",
                    com.messenger.app.domain.model.FilePreviewText.display(hit.text, "File"))
            }
        }
    }

    private class Database : AutoCloseable {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        var queries = 0
        init {
            db.createStatement().use {
                it.execute("CREATE TABLE messages(clientMessageId TEXT PRIMARY KEY, chatId TEXT, senderId TEXT, sequence INTEGER, createdAt TEXT, text TEXT, caption TEXT, kind TEXT, hiddenLocally INTEGER, isDeletedForEveryone INTEGER, status TEXT)")
                it.execute("CREATE TABLE attachments(clientMessageId TEXT, filename TEXT, mediaType TEXT, mimeType TEXT, thumbnailBase64 TEXT)")
            }
        }
        fun add(id: String, text: String = "hello", status: String = "delivered", kind: String = "content", hidden: Int = 0, deleted: Int = 0, caption: String? = null, chat: String = "chat") {
            db.prepareStatement("INSERT INTO messages VALUES(?,?,?,1,?,?,?, ?,?,?,?)").use {
                listOf(id, chat, "peer", "2026-11-17T12:00:00Z", text, caption, kind, hidden, deleted, status).forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.executeUpdate()
            }
        }
        val dao = object : SearchDao {
            override suspend fun globalSearch(query: SupportSQLiteQuery): List<SearchResultRow> {
                queries++
                val arguments = mutableMapOf<Int, Any?>()
                query.bindTo(object : SupportSQLiteProgram {
                    override fun bindNull(index: Int) { arguments[index] = null }
                    override fun bindLong(index: Int, value: Long) { arguments[index] = value }
                    override fun bindDouble(index: Int, value: Double) { arguments[index] = value }
                    override fun bindString(index: Int, value: String) { arguments[index] = value }
                    override fun bindBlob(index: Int, value: ByteArray) { arguments[index] = value }
                    override fun clearBindings() { arguments.clear() }
                    override fun close() = Unit
                })
                return db.prepareStatement(query.sql).use { statement ->
                    arguments.forEach { (index, value) -> statement.setObject(index, value) }
                    statement.executeQuery().use { row -> buildList {
                        while (row.next()) add(SearchResultRow(row.getString("clientMessageId"), row.getString("chatId"), row.getString("senderId"), row.getLong("sequence"), row.getString("createdAt"), row.getString("text"),
                            caption = row.getString("caption"), filename = row.getString("filename"), mediaType = row.getString("mediaType"), mimeType = row.getString("mimeType"), thumbnailBase64 = row.getString("thumbnailBase64")))
                    } }
                }
            }
            override suspend fun chatSearch(query: SupportSQLiteQuery) = globalSearch(query)
        }
        override fun close() = db.close()
    }
    @Test fun `searches Unicode text caption and filenames without returning hidden deleted lifecycle or undecryptable rows`() = runTest {
        Database().use { database ->
            database.add("text", "ПРИВЕТ мир")
            database.add("caption", caption = "Привет из подписи")
            database.add("file")
            database.db.createStatement().use { it.execute("INSERT INTO attachments VALUES('file','ПРИВЕТ.pdf','file','application/pdf',NULL)") }
            database.add("hidden", "привет", hidden = 1); database.add("deleted", "привет", deleted = 1)
            database.add("edit", "привет", kind = "edit"); database.add("failed", "привет", status = "decrypt_failed")
            database.add("other", "Привет", chat = "different")
            val repository = MessageSearchRepositoryImpl(database.dao)
            assertEquals(setOf("text", "caption", "file", "other"), repository.globalSearch("привет").map { it.messageId }.toSet())
            val chat = repository.chatSearch("chat", "привет")
            assertEquals(3, chat.size)
            assertEquals("ПРИВЕТ.pdf", chat.first { it.messageId == "file" }.text)
            assertEquals("Привет из подписи", chat.first { it.messageId == "caption" }.text)
        }
    }
    @Test fun `empty query does no scan literal symbols stay literal and results are bounded newest first`() = runTest {
        Database().use { database ->
            val repository = MessageSearchRepositoryImpl(database.dao)
            assertTrue(repository.globalSearch("  ").isEmpty()); assertEquals(0, database.queries)
            repeat(310) { database.add("%03d".format(it)) }
            database.add("percent", "100%")
            assertEquals(listOf("percent"), repository.globalSearch("%").map { it.messageId })
            val hits = repository.globalSearch("hello", 150)
            assertEquals(100, hits.size); assertEquals("309", hits.first().messageId); assertEquals("210", hits.last().messageId)
        }
    }
}
