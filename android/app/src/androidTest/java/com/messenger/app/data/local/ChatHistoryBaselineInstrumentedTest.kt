package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Repeatable local-query baseline, not a UI frame-rate or physical-device energy test. */
class ChatHistoryBaselineInstrumentedTest {
    @Test fun historyReadsAtOneTenAndFiftyThousand() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java).build()
        val report = StringBuilder("messages,median_ms,max_ms,samples\n")
        try {
            val dao = db.messageDao()
            var inserted = 0
            for (count in listOf(1000, 10000, 50000)) {
                db.withTransaction {
                    for (index in inserted + 1..count) {
                        dao.upsert(MessageEntity(clientMessageId = "m-$index", serverId = "s-$index", chatId = "benchmark",
                            senderId = "peer", text = "Message $index " + "text ".repeat(30), sequence = index.toLong(),
                            createdAt = "2026-09-26T12:00:00Z", status = "delivered"))
                    }
                }
                inserted = count
                assertEquals(count, dao.observeMessages("benchmark").first().size)
                val times = (1..5).map {
                    val start = System.nanoTime()
                    assertEquals(count, dao.observeMessages("benchmark").first().size)
                    (System.nanoTime() - start) / 1_000_000.0
                }.sorted()
                report.append("$count,${times[2]},${times.last()},5\n")
            }
            assertEquals(50000, dao.observeMessages("benchmark").first().size)
            File(context.filesDir, "chat-history-baseline.csv").writeText(report.toString())
            println(report)
        } finally { db.close() }
    }
}
