package com.messenger.app.ui.chats

import com.messenger.app.data.directtransfer.DirectOriginalRoute
import com.messenger.app.data.directtransfer.DIRECT_NORMAL_SOURCE_LIMIT
import java.sql.DriverManager
import org.junit.Test
import kotlin.test.*

class AttachmentFilesStateTest {
    @Test fun `reopening after resend keeps the latest source once ahead of older files`() {
        val old = RecentFile("same", "Contract.pdf", 32, "old", "/old", "old-uri")
        val other = old.copy(id = "other", name = "Report.txt")
        val latest = old.copy(sentAt = "new", path = "/new", uri = "new-uri")
        assertEquals(listOf(latest, other), latestRecentFiles(listOf(latest, other, old)))
    }

    @Test fun `selection preserves tap order and renumbers on removal without accepting overflow`() {
        var picked = emptyList<String>()
        for (id in listOf("pdf", "txt", "key")) picked = toggleAttachmentSelection(picked, id, 10)
        assertEquals(listOf("pdf", "txt", "key"), picked)
        picked = toggleAttachmentSelection(picked, "txt", 10)
        assertEquals(listOf("pdf", "key"), picked)
        picked = toggleAttachmentSelection(picked, "txt", 10)
        assertEquals(listOf("pdf", "key", "txt"), picked)
        assertEquals(picked, toggleAttachmentSelection(picked, "overflow", 3))
        assertEquals(emptyList(), toggleAttachmentSelection(emptyList(), "none", 0))
        assertEquals((1..10).toList(), toggleAttachmentSelection((1..10).toList(), 11, 100))
        // A shrinking remaining tray capacity must still allow deselection.
        assertEquals(listOf("pdf", "txt"), toggleAttachmentSelection(picked, "key", 1))
    }

    @Test fun `picker results require the launching account and session and cannot replay consumed requests`() {
        assertTrue(filePickerResultIsCurrent(3, 3, "owner", "owner"))
        assertFalse(filePickerResultIsCurrent(3, 4, "owner", "owner"))
        assertFalse(filePickerResultIsCurrent(3, 3, "owner", "other"))
        assertFalse(filePickerResultIsCurrent(-1, -1, "owner", "owner"))
        assertFalse(filePickerResultIsCurrent(3, 3, null, null))
    }

    @Test fun `explicit direct sends small unknown and large sources directly and never falls back when unavailable`() {
        for (bytes in listOf(-1L, 0L, 32L, DIRECT_NORMAL_SOURCE_LIMIT, DIRECT_NORMAL_SOURCE_LIMIT + 1)) {
            assertEquals(DirectOriginalRoute.DIRECT, pickedOriginalRoute(bytes, true, true))
            assertEquals(DirectOriginalRoute.UNAVAILABLE, pickedOriginalRoute(bytes, false, true))
        }
        assertEquals(DirectOriginalRoute.NORMAL, pickedOriginalRoute(32, true, false))
        assertEquals(DirectOriginalRoute.DIRECT, pickedOriginalRoute(DIRECT_NORMAL_SOURCE_LIMIT + 1, true, false))
        assertEquals(DirectOriginalRoute.NORMAL, pickedOriginalRoute(-1, false, false))
    }

    @Test fun `recent query crosses chats latest first while excluding another owner unsent and removed records`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE attachments (attachmentId TEXT, clientMessageId TEXT, filename TEXT, size INTEGER, localPath TEXT, mediaType TEXT, sha256Base64 TEXT)")
                sql.execute("CREATE TABLE messages (clientMessageId TEXT, chatId TEXT, senderId TEXT, kind TEXT, status TEXT, isDeletedForEveryone INTEGER, hiddenLocally INTEGER, createdAt TEXT, sequence INTEGER)")
                sql.execute("CREATE TABLE deleted_chats (chatId TEXT)")
            }
            fun row(id: String, chat: String = "chat1", owner: String = "me", state: String = "sent", hidden: Int = 0, deleted: Int = 0,
                media: String = "file", kind: String = "content", path: String? = "/cache/$id", time: String = "2026-11-11T12:00:00Z") {
                db.prepareStatement("INSERT INTO attachments VALUES (?, ?, ?, 32, ?, ?, ?)").use {
                    it.setString(1, id); it.setString(2, id); it.setString(3, "$id.pdf"); it.setString(4, path); it.setString(5, media); it.setString(6, id); it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)").use {
                    it.setString(1, id); it.setString(2, chat); it.setString(3, owner); it.setString(4, kind); it.setString(5, state)
                    it.setInt(6, deleted); it.setInt(7, hidden); it.setString(8, time); it.executeUpdate()
                }
            }
            row("older"); row("newer", chat = "chat2", state = "read", time = "2026-11-12T12:00:00Z")
            row("incoming", owner = "peer"); row("failed", state = "failed"); row("pending", state = "pending")
            row("hidden", hidden = 1); row("deleted", deleted = 1); row("image", media = "image"); row("event", kind = "edit"); row("evicted", path = null)
            row("removed-chat", chat = "removed")
            db.createStatement().use { it.execute("INSERT INTO deleted_chats VALUES ('removed')") }
            db.prepareStatement(RECENT_FILES_SQL).use { query ->
                query.setString(1, "me")
                query.executeQuery().use { cursor ->
                    val names = buildList { while (cursor.next()) add(cursor.getString(1)) }
                    assertEquals(listOf("newer.pdf", "older.pdf"), names)
                }
            }
        }
    }
}
