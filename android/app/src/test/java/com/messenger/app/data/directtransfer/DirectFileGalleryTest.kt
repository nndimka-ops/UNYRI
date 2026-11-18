package com.messenger.app.data.directtransfer

import com.messenger.app.data.attachment.MediaGalleryRepository
import com.messenger.app.data.local.attachment.*
import com.messenger.app.data.local.message.MessageEntity
import java.io.File
import java.sql.DriverManager
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DirectFileGalleryTest {
    private val chat = "00000000-0000-0000-0000-000000000006"
    private fun record(mime: String = "image/jpeg", suffix: Int = 1): DirectTransferRecord {
        val offer = DirectFileOffer(transferId = "00000000-0000-0000-0000-%012d".format(suffix),
            offerClientMessageId = "00000000-0000-0000-0001-%012d".format(suffix),
            senderUserId = "00000000-0000-0000-0000-000000000003", senderDeviceId = "00000000-0000-0000-0000-000000000004",
            recipientUserId = "00000000-0000-0000-0000-000000000005", offerNonce = Base64.getEncoder().encodeToString(ByteArray(32)),
            filename = "original.bin", mimeType = mime, size = 3, sha256 = "ab".repeat(32), expiresAt = System.currentTimeMillis() / 1000 + 3600)
        return DirectTransferRecord(offer, chat, "receiver", state = "completed", completed = true, durableOffset = 3)
    }
    private fun message(record: DirectTransferRecord, sequence: Long = 10) = MessageEntity(
        record.offer.offerClientMessageId, null, record.chatId, record.offer.senderUserId, "file", sequence,
        "2026-11-17T00:00:00Z", "delivered")
    private fun row(record: DirectTransferRecord, sequence: Long) = DirectFileGalleryRow(message(record, sequence),
        DirectFileOffer.codec.encodeToString(record), DirectTransferCoordinator.key(record.offer.transferId))
    private fun local(): File = File.createTempFile("direct-gallery", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun completedVisualsGoToPhotosAndOtherDirectFilesStayInFilesNeverVoice() {
        val file = local()
        try {
            for ((mime, tab) in listOf("image/jpeg" to "image", "image/png" to "image", "video/mp4" to "image", "application/pdf" to "file", "audio/mp4" to "file")) {
                for (role in listOf("sender", "receiver")) {
                    val record = record(mime).copy(role = role)
                    val item = completedDirectMediaItem(record, message(record), file)!!
                    assertEquals(tab, item.mediaType)
                    assertEquals(file.path, item.localPath)
                    assertEquals(record.offer.transferId, item.directTransferId)
                    assertNull("Peer thumbnail is not needed for a completed local gallery item", item.thumbnailBase64)
                }
            }
        } finally { file.delete() }
    }

    @Test fun incompleteUnverifiedMissingWrongSizeOrUnboundFilesNeverEnterMedia() {
        val file = local()
        val record = record()
        val message = message(record)
        try {
            for (state in listOf("offered", "waiting", "connecting", "transferring", "verifying", "paused", "cancelled", "rejected", "expired")) {
                assertNull(completedDirectMediaItem(record.copy(state = state, completed = false), message, file))
                assertNull(completedDirectMediaItem(record.copy(state = state), message, file))
            }
            assertNull(completedDirectMediaItem(record.copy(completed = false), message, file))
            assertNull(completedDirectMediaItem(record.copy(role = "other"), message, file))
            assertNull(completedDirectMediaItem(record, message, null))
            for (bad in listOf(message.copy(hiddenLocally = true), message.copy(isDeletedForEveryone = true), message.copy(forwarded = true),
                message.copy(kind = "delete"), message.copy(chatId = "other"), message.copy(senderId = "other"),
                message.copy(clientMessageId = "other"), message.copy(sequence = null))) assertNull(completedDirectMediaItem(record, bad, file))
            file.writeBytes(byteArrayOf(1))
            assertNull(completedDirectMediaItem(record, message, file))
            file.delete()
            assertNull(completedDirectMediaItem(record, message, file))
        } finally { file.delete() }
    }

    @Test fun galleryPagesPastIncompleteJobsAndMergesOrdinaryAndDirectInSequenceOrder() = runBlocking {
        val file = local()
        try {
            val newest = record(suffix = 1)
            val incomplete = record(suffix = 2).copy(state = "verifying", completed = false)
            val video = record("video/mp4", 3)
            val pdf = record("application/pdf", 4)
            val older = record(suffix = 5)
            val records = listOf(newest, incomplete, video, pdf, older)
            val rows = records.mapIndexed { index, value -> row(value, 100L - index * 10) }
            val ordinary = MediaItemRow("ordinary", "message", chat, "image", "image/jpeg", "photo.jpg", 3, null, null,
                "downloaded", null, "sender", 85, "2026-11-17T00:00:00Z")
            val dao = object : MediaDao {
                override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int) =
                    listOf(ordinary).filter { mediaType == "image" && it.sequence < beforeSequence }.take(limit)
                override suspend fun directPage(chatId: String, beforeSequence: Long, limit: Int) = rows.filter { it.message.sequence!! < beforeSequence }.take(limit)
            }
            val repository = MediaGalleryRepository(dao, completedDirectFile = { _, id -> records.firstOrNull { it.offer.transferId == id && it.completed }?.let { file to it.offer } })
            val first = repository.page(chat, "image", Long.MAX_VALUE, 2)
            assertEquals(listOf(100L, 85L), first.map { it.sequence })
            val second = repository.page(chat, "image", first.last().sequence, 2)
            assertEquals(listOf(80L, 60L), second.map { it.sequence })
            assertEquals(4, (first + second).map { it.attachmentId }.distinct().size)
            assertEquals(listOf(70L), repository.page(chat, "file", Long.MAX_VALUE, 2).map { it.sequence })
            assertTrue(repository.page(chat, "voice", Long.MAX_VALUE, 2).isEmpty())
        } finally { file.delete() }
    }

    @Test fun actualGallerySqlPartitionsVisualMimeAndFiltersDeletedAndForeignChatRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE messages(clientMessageId TEXT, chatId TEXT, senderId TEXT, sequence INTEGER, createdAt TEXT, kind TEXT, isDeletedForEveryone INTEGER, hiddenLocally INTEGER)")
                sql.execute("CREATE TABLE attachments(attachmentId TEXT, clientMessageId TEXT, chatId TEXT, mediaType TEXT, mimeType TEXT, filename TEXT, size INTEGER, durationMs INTEGER, thumbnailBase64 TEXT, downloadState TEXT, localPath TEXT)")
                sql.execute("CREATE TABLE chat_workspace(chatId TEXT, name TEXT, value TEXT)")
            }
            val values = listOf("image/jpeg" to "image", "video/mp4" to "file", "image/png" to "file", "application/pdf" to "file", "audio/mp4" to "voice", "IMAGE/JPEG" to "file")
            values.forEachIndexed { index, (mime, type) ->
                db.createStatement().use { sql ->
                    sql.execute("INSERT INTO messages VALUES('m$index','chat','sender',${index + 1},'date','content',0,0)")
                    sql.execute("INSERT INTO attachments VALUES('a$index','m$index','chat','$type','$mime','name',3,NULL,NULL,'downloaded',NULL)")
                }
            }
            fun ordinary(type: String): List<String> = db.prepareStatement(MEDIA_GALLERY_PAGE_SQL).use { sql ->
                // Named SQLite parameters reuse indices for repeated occurrences.
                sql.setString(1, "chat"); sql.setString(2, type); sql.setLong(3, Long.MAX_VALUE); sql.setInt(4, 100)
                sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("attachmentId")) } }
            }
            assertEquals(listOf("a2", "a1", "a0"), ordinary("image"))
            assertEquals(listOf("a5", "a3"), ordinary("file"))
            assertEquals(listOf("a4"), ordinary("voice"))
            db.createStatement().use { it.execute("UPDATE messages SET hiddenLocally=1 WHERE clientMessageId='m2'"); it.execute("UPDATE messages SET isDeletedForEveryone=1 WHERE clientMessageId='m1'") }
            assertEquals(listOf("a0"), ordinary("image"))
            val r = record()
            val m = message(r)
            db.prepareStatement("INSERT INTO messages VALUES(?,?,?,10,'date','content',0,0)").use { it.setString(1, m.clientMessageId); it.setString(2, chat); it.setString(3, m.senderId); it.executeUpdate() }
            db.prepareStatement("INSERT INTO chat_workspace VALUES(?,?,?)").use {
                it.setString(1, chat); it.setString(2, DirectTransferCoordinator.key(r.offer.transferId)); it.setString(3, DirectFileOffer.codec.encodeToString(r)); it.executeUpdate()
            }
            fun direct(chatId: String, cursor: Long): Int = db.prepareStatement(DIRECT_GALLERY_PAGE_SQL).use { sql ->
                sql.setString(1, chatId); sql.setLong(2, cursor); sql.setInt(3, 100)
                sql.executeQuery().use { rows -> var count = 0; while (rows.next()) count++; count }
            }
            assertEquals(1, direct(chat, Long.MAX_VALUE))
            assertEquals(0, direct(chat, 10))
            assertEquals(0, direct("other", Long.MAX_VALUE))
            db.createStatement().use { it.execute("UPDATE messages SET hiddenLocally=1") }
            assertEquals(0, direct(chat, Long.MAX_VALUE))
        }
    }
}
