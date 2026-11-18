package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.attachment.MediaDao
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-SQLite tests for the media gallery projection (M.1 / B4): correct
 *  chat/type scoping, deleted/hidden exclusion, keyset pagination with no
 *  duplicates, stable sequence ordering, and empty-page safety. */
@RunWith(AndroidJUnit4::class)
class MediaDaoInstrumentedTest {

    private lateinit var db: MessengerDatabase
    private lateinit var mediaDao: MediaDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MessengerDatabase.ensureSearchIndex(db.openHelper.writableDatabase)
        mediaDao = db.mediaDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun message(id: String, chatId: String, seq: Long, deleted: Boolean = false, hidden: Boolean = false) =
        MessageEntity(
            clientMessageId = id,
            serverId = id,
            chatId = chatId,
            senderId = "bob",
            text = "[Photo]",
            sequence = seq,
            createdAt = "2026-09-06",
            status = "delivered",
            kind = "content",
            isDeletedForEveryone = deleted,
            hiddenLocally = hidden,
        )

    private fun attachment(id: String, msgId: String, chatId: String, mediaType: String) = AttachmentEntity(
        attachmentId = id,
        clientMessageId = msgId,
        chatId = chatId,
        mediaType = mediaType,
        mimeType = if (mediaType == "image") "image/jpeg" else "application/pdf",
        filename = null,
        size = 100,
        width = null,
        height = null,
        durationMs = null,
        thumbnailBase64 = null,
        localPath = null,
        downloadState = "not_downloaded",
        uploadState = "sent",
        objectKey = "k/$id",
        encryptedSize = 100,
        encryptionVersion = 2,
        keyBase64 = null,
        nonceBase64 = null,
        keysetBase64 = null,
        sha256Base64 = "x",
    )

    @Test
    fun pageFiltersByChatAndType() = runBlocking {
        db.messageDao().upsert(message("m1", "chatA", 1))
        db.messageDao().upsert(message("m2", "chatA", 2))
        db.messageDao().upsert(message("m3", "chatB", 3))
        db.attachmentDao().upsert(attachment("a1", "m1", "chatA", "image"))
        db.attachmentDao().upsert(attachment("a2", "m2", "chatA", "file"))
        db.attachmentDao().upsert(attachment("a3", "m3", "chatB", "image"))

        val chatAImages = mediaDao.page("chatA", "image", Long.MAX_VALUE, 100)
        assertEquals(listOf("a1"), chatAImages.map { it.attachmentId })

        val chatBFiles = mediaDao.page("chatB", "file", Long.MAX_VALUE, 100)
        assertTrue(chatBFiles.isEmpty())
    }

    @Test
    fun deletedAndHiddenAreExcluded() = runBlocking {
        db.messageDao().upsert(message("m1", "chatA", 1))
        db.messageDao().upsert(message("m2", "chatA", 2, deleted = true))
        db.messageDao().upsert(message("m3", "chatA", 3, hidden = true))
        db.attachmentDao().upsert(attachment("a1", "m1", "chatA", "image"))
        db.attachmentDao().upsert(attachment("a2", "m2", "chatA", "image"))
        db.attachmentDao().upsert(attachment("a3", "m3", "chatA", "image"))

        val page = mediaDao.page("chatA", "image", Long.MAX_VALUE, 100)
        assertEquals(listOf("a1"), page.map { it.attachmentId })
    }

    @Test
    fun keysetPaginationHasNoDuplicates() = runBlocking {
        for (i in 1..10) {
            db.messageDao().upsert(message("m$i", "chatA", i.toLong()))
            db.attachmentDao().upsert(attachment("a$i", "m$i", "chatA", "image"))
        }

        val page1 = mediaDao.page("chatA", "image", Long.MAX_VALUE, 4)
        assertEquals(4, page1.size)
        val lastSeq = page1.last().sequence

        val page2 = mediaDao.page("chatA", "image", lastSeq, 4)
        assertEquals(4, page2.size)
        val lastSeq2 = page2.last().sequence

        val page3 = mediaDao.page("chatA", "image", lastSeq2, 4)
        assertEquals(2, page3.size)

        val all = (page1 + page2 + page3).map { it.attachmentId }
        assertEquals("no duplicates across pages", 10, all.distinct().size)
        // Newest first: a10..a1
        assertEquals(listOf("a10", "a9", "a8", "a7"), page1.map { it.attachmentId })
        assertEquals(listOf("a6", "a5", "a4", "a3"), page2.map { it.attachmentId })
        assertEquals(listOf("a2", "a1"), page3.map { it.attachmentId })
    }

    @Test
    fun emptyPageIsSafe() = runBlocking {
        assertTrue(mediaDao.page("chatA", "image", Long.MAX_VALUE, 100).isEmpty())
    }
}
