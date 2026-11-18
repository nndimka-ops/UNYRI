package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.search.SearchQueryBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-SQLite instrumentation tests for the local FTS search (M.1 / B4):
 * exercises the actual FTS4 virtual table + triggers against Room, never a
 * JVM mock. Proves insert/edit/delete/hide/decrypt-failed projection, plus
 * Cyrillic / prefix / multi-term AND / chat-scoping / global scoping.
 */
@RunWith(AndroidJUnit4::class)
class SearchDaoInstrumentedTest {

    private lateinit var db: MessengerDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // In-memory DBs bypass MessengerDatabase.create(), so ensure the FTS
        // index + triggers exist (idempotent).
        MessengerDatabase.ensureSearchIndex(db.openHelper.writableDatabase)
        dao = db.messageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun message(
        id: String,
        chatId: String = "chatA",
        text: String,
        sequence: Long? = 1,
        status: String = "delivered",
        kind: String = "content",
        hidden: Boolean = false,
        deleted: Boolean = false,
    ) = MessageEntity(
        clientMessageId = id,
        serverId = id,
        chatId = chatId,
        senderId = "alice",
        text = text,
        sequence = sequence,
        createdAt = "2026-09-06T00:00:00Z",
        status = status,
        kind = kind,
        hiddenLocally = hidden,
        isDeletedForEveryone = deleted,
    )

    private fun search(q: String, chatId: String? = null): List<String> = runBlocking {
        val repository = com.messenger.app.data.search.MessageSearchRepositoryImpl(db.searchDao())
        (if (chatId == null) repository.globalSearch(q) else repository.chatSearch(chatId, q)).map { it.messageId }
    }

    private fun ftsCount(): Int {
        val c = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages_fts", arrayOf<Any>())
        c.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    @Test
    fun insertTextIsSearchable() = runBlocking {
        dao.upsert(message("m1", text = "hello world"))
        assertEquals(listOf("m1"), search("hello"))
    }

    @Test
    fun cyrillicIsSearchable() = runBlocking {
        dao.upsert(message("m2", text = "Привет мир"))
        assertEquals(listOf("m2"), search("Привет"))
    }

    @Test
    fun caseInsensitiveSearch() = runBlocking {
        dao.upsert(message("m3", text = "Hello World"))
        assertEquals(listOf("m3"), search("hello"))
        assertEquals(listOf("m3"), search("HELLO"))
    }

    @Test
    fun prefixSearchWorks() = runBlocking {
        dao.upsert(message("m4", text = "hello"))
        assertEquals(listOf("m4"), search("hel"))
    }

    @Test
    fun multipleTermsAreAnd() = runBlocking {
        // Distinct sequences: (chatId, sequence) is a UNIQUE index, so a
        // shared sequence would REPLACE one row instead of keeping both.
        dao.upsert(message("m5", text = "alpha beta", sequence = 1))
        dao.upsert(message("m6", text = "alpha gamma", sequence = 2))
        assertEquals(listOf("m5"), search("alpha beta"))
    }

    @Test
    fun editUpdatesIndex() = runBlocking {
        dao.upsert(message("m7", text = "old text", sequence = 1))
        assertEquals(listOf("m7"), search("old"))
        dao.applyEdit("m7", "new text", 2)
        assertEquals(emptyList<String>(), search("old"))
        assertEquals(listOf("m7"), search("new"))
    }

    @Test
    fun deleteForEveryoneRemovesFromIndex() = runBlocking {
        dao.upsert(message("m8", text = "secret", sequence = 1))
        assertEquals(listOf("m8"), search("secret"))
        dao.applyDelete("m8", 2)
        assertEquals(emptyList<String>(), search("secret"))
    }

    @Test
    fun hideLocallyRemovesFromIndex() = runBlocking {
        dao.upsert(message("m9", text = "private note", sequence = 1))
        assertEquals(listOf("m9"), search("private"))
        dao.hideLocally("m9")
        assertEquals(emptyList<String>(), search("private"))
    }

    @Test
    fun decryptFailedIsNotIndexed() = runBlocking {
        dao.upsert(message("m10", text = "[Unable to decrypt message]", status = "decrypt_failed"))
        assertEquals(emptyList<String>(), search("Unable"))
        assertEquals(emptyList<String>(), search("decrypt"))
    }

    @Test
    fun lifecycleEventRowsAreNotIndexed() = runBlocking {
        dao.upsert(message("m11", text = "[edit]", kind = "edit"))
        dao.upsert(message("m12", text = "real message"))
        assertEquals(listOf("m12"), search("real"))
        assertEquals(emptyList<String>(), search("edit"))
    }

    @Test
    fun chatScopedAndGlobalSearch() = runBlocking {
        dao.upsert(message("a1", chatId = "chatA", text = "unique alpha", sequence = 1))
        dao.upsert(message("b1", chatId = "chatB", text = "unique beta", sequence = 2))
        // Newest first: b1 (sequence 2) before a1 (sequence 1).
        assertEquals(listOf("b1", "a1"), search("unique"))
        assertEquals(listOf("a1"), search("unique", chatId = "chatA"))
        assertEquals(listOf("b1"), search("unique", chatId = "chatB"))
    }

    @Test
    fun specialCharactersDoNotCrash() = runBlocking {
        dao.upsert(message("m13", text = "hello"))
        // These must never throw - symbols-only queries degrade to no results.
        assertTrue(search("*** --- :::").isEmpty())
        assertTrue(search("(x):* -quote").isEmpty() || search("(x):* -quote").isNotEmpty())
    }

    @Test
    fun ftsCountMatchesSearchableProjection() = runBlocking {
        dao.upsert(message("n1", text = "one", sequence = 1))
        dao.upsert(message("n2", text = "two", sequence = 2))
        dao.upsert(message("n3", text = "hidden", sequence = 3, hidden = true))
        dao.upsert(message("n4", text = "deleted", sequence = 4, deleted = true))
        dao.upsert(message("n5", text = "[Unable to decrypt message]", sequence = 5, status = "decrypt_failed"))
        dao.upsert(message("n6", text = "[edit]", sequence = 6, kind = "edit"))
        assertEquals(2, ftsCount())
    }
    @Test fun newestOfflineMessageIsNotDisplacedByConfirmedHistory() = runBlocking {
        dao.upsert(message("confirmed", text = "needle", sequence = 99).copy(createdAt = "2026-09-26T10:00:00Z"))
        dao.upsert(message("pending", text = "needle", sequence = null, status = "pending").copy(createdAt = "2026-09-26T10:01:00Z"))
        val repository = com.messenger.app.data.search.MessageSearchRepositoryImpl(db.searchDao())
        assertEquals(listOf("pending"), repository.globalSearch("needle", limit = 1).map { it.messageId })
    }

    @Test fun readFailureIsNotReportedAsNoMatches() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DROP TABLE messages_fts")
        val repository = com.messenger.app.data.search.MessageSearchRepositoryImpl(db.searchDao())
        try { repository.globalSearch("needle"); org.junit.Assert.fail("Expected an actionable database error") }
        catch (_: android.database.sqlite.SQLiteException) { }
    }

}
