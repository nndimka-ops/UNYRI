package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run the real Room query, including upload/schedule workspace subqueries. */
class MediaOutboxOrderInstrumentedTest {
    @Test fun mediaBackoffAllowsTextButPreservesMediaAndLifecycleBarriers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java).build()
        try {
            MessengerDatabase.ensureSearchIndex(db.openHelper.writableDatabase)
            val dao = db.messageDao()
            fun message(id: String, time: String, retry: Long = 0) = MessageEntity(
                clientMessageId = id, serverId = null, chatId = "c", senderId = "me", text = id,
                sequence = null, createdAt = time, status = "pending", nextRetryAtEpochMs = retry)
            dao.upsert(message("video", "2026-10-24T10:00:00Z", 9000))
            dao.upsert(message("photo", "2026-10-24T10:00:01Z"))
            dao.upsert(message("text", "2026-10-24T10:00:02Z"))
            dao.saveMediaJob("c", "upload:video", "{}")
            dao.saveMediaJob("c", "upload:photo", "{}")
            assertEquals(listOf("text"), dao.pendingEligible("me", 1000).map { it.clientMessageId })
            assertEquals(listOf("video", "photo", "text"), dao.pendingEligible("me", 10000).map { it.clientMessageId })
            dao.upsert(message("earlier-text", "2026-10-24T09:59:00Z", 9000))
            assertEquals(emptyList<String>(), dao.pendingEligible("me", 1000).map { it.clientMessageId })
            dao.saveMediaJob("c", "schedule:earlier-text", "20000")
            assertEquals(listOf("text"), dao.pendingEligible("me", 1000).map { it.clientMessageId })
            assertEquals(emptyList<String>(), dao.pendingEligible("other-account", 10000).map { it.clientMessageId })
        } finally { db.close() }
    }
}
