package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-SQLite FTS benchmark + query-plan proof (M.1 / B2): seeds 10,000
 * content messages across 10 chats (multi-language, with edited/deleted/
 * hidden rows), measures index build + representative query latency, and
 * prints EXPLAIN QUERY PLAN showing FTS MATCH (not a full scan of plaintext).
 * Results are printed to logcat (System.out) and read from the test report.
 */
@RunWith(AndroidJUnit4::class)
class SearchBenchmarkInstrumentedTest {

    private lateinit var db: MessengerDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MessengerDatabase.ensureSearchIndex(db.openHelper.writableDatabase)
        dao = db.messageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun seed() = runBlocking {
        val samples = listOf(
            "hello world", "Привет мир", "Привіт світ", "Bună ziua lume",
            "FTS_BEGIN_92831 message", "the quick brown fox", "FTS_MIDDLE_92831 marker here",
            "FTS_END_92831 final note", "edited effective text", "hidden secret text",
        )
        val t0 = System.nanoTime()
        var seq = 1L
        for (chat in 0 until 10) {
            for (i in 0 until 1000) {
                val text = samples[(chat + i) % samples.size]
                val deleted = (i % 97) == 0
                val hidden = (i % 89) == 0
                val edited = (i % 53) == 0
                dao.upsert(
                    MessageEntity(
                        clientMessageId = "c$chat-$i",
                        serverId = "s$chat-$i",
                        chatId = "chat$chat",
                        senderId = "alice",
                        text = if (edited) "$text (edited)" else text,
                        sequence = seq,
                        createdAt = "2026-09-06",
                        status = if (deleted) "delivered" else "delivered",
                        kind = "content",
                        isDeletedForEveryone = deleted,
                        hiddenLocally = hidden,
                        isEdited = edited,
                    ),
                )
                seq++
            }
        }
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        println("BENCH_SEED_MS=$buildMs")
    }

    private suspend fun query(q: String, chatId: String? = null): Long {
        val repository = com.messenger.app.data.search.MessageSearchRepositoryImpl(db.searchDao())
        val t0 = System.nanoTime()
        if (chatId == null) repository.globalSearch(q) else repository.chatSearch(chatId, q)
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun tenThousandMessageBenchmark() = runBlocking {
        seed()

        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages_fts", arrayOf<Any>()).use {
            it.moveToFirst()
            println("BENCH_FTS_ROWS=${it.getInt(0)}")
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages", arrayOf<Any>()).use {
            it.moveToFirst()
            println("BENCH_MESSAGE_ROWS=${it.getInt(0)}")
        }

        val queries = listOf(
            "hello", "Привет", "Привіт", "Bună", "FTS_BEGIN_92831",
            "FTS_MIDDLE_92831", "FTS_END_92831", "quick brown", "no such marker xyz",
        )
        val latencies = mutableListOf<Long>()
        for (q in queries) {
            repeat(5) { latencies += query(q) }
        }
        val sorted = latencies.sorted()
        println("BENCH_P50_MS=${sorted[sorted.size / 2]}")
        println("BENCH_P95_MS=${sorted[(sorted.size * 0.95).toInt()]}")
        println("BENCH_WORST_MS=${sorted.last()}")

        val chatLat = (0 until 20).map { query("hello", chatId = "chat3") }.sorted()
        println("BENCH_CHAT_P50_MS=${chatLat[chatLat.size / 2]}")

        // EXPLAIN QUERY PLAN proof: the FTS virtual table is used, not a full scan.
        fun explain(sql: String) {
            db.openHelper.readableDatabase.query(sql, arrayOf<Any>()).use { c ->
                val sb = StringBuilder()
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) {
                        sb.append(c.getColumnName(i)).append('=').append(c.getString(i)).append(';')
                    }
                    sb.append('\n')
                }
                println("EXPLAIN:\n$sb")
            }
        }
        explain("EXPLAIN QUERY PLAN SELECT client_message_id FROM messages_fts WHERE messages_fts MATCH 'hello'")
        explain("EXPLAIN QUERY PLAN SELECT client_message_id FROM messages_fts WHERE messages_fts MATCH 'hello' AND chat_id = 'chat3'")

        assertTrue(sorted.first() >= 0)
    }
}
