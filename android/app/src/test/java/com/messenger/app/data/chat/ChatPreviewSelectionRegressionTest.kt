package com.messenger.app.data.chat

import com.messenger.app.data.local.chat.ChatListSql
import com.messenger.app.data.local.message.messageSortKey
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/** Execute both production Room queries, rather than a Kotlin imitation of their ordering. */
class ChatPreviewSelectionRegressionTest {
    private val queries = listOf(ChatListSql.LIST, ChatListSql.CHAT)

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        listOf(
            "CREATE TABLE chats(chatId TEXT PRIMARY KEY,type TEXT,createdAt TEXT,lastMessageAt TEXT,lastReadSequence INTEGER DEFAULT 0,isMuted INTEGER DEFAULT 0,mutedUntil TEXT,isBlocked INTEGER DEFAULT 0,blockedUserId TEXT)",
            "CREATE TABLE chat_members(chatId TEXT,userId TEXT)",
            "CREATE TABLE chat_participants(userId TEXT,username TEXT,displayName TEXT,avatarUrl TEXT,avatarVersion INTEGER,hasAvatar INTEGER)",
            "CREATE TABLE messages(clientMessageId TEXT PRIMARY KEY,chatId TEXT,senderId TEXT,kind TEXT,hiddenLocally INTEGER,isDeletedForEveryone INTEGER,sequence INTEGER,status TEXT,createdAt TEXT,text TEXT,sortKey INTEGER,sortSequence INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX index_messages_chatId_sequence ON messages(chatId,sequence)",
            "CREATE TABLE attachments(attachmentId TEXT PRIMARY KEY,clientMessageId TEXT,mediaType TEXT,mimeType TEXT,filename TEXT)",
            "CREATE TABLE chat_workspace(chatId TEXT,name TEXT,value TEXT)",
            "INSERT INTO chats(chatId,type,createdAt,lastMessageAt) VALUES('qa','direct','2026-11-13T00:00:00Z','2026-11-13T03:06:40Z')",
            "INSERT INTO chats(chatId,type,createdAt,lastMessageAt) VALUES('other','direct','2026-11-13T00:00:00Z','2026-11-13T03:04:00Z')"
        ).forEach { sql -> createStatement().use { it.execute(sql) } }
    }

    private fun Connection.message(id: String, time: String, sequence: Long?, status: String,
        hidden: Int = 0, deleted: Int = 0, kind: String = "content") {
        prepareStatement("INSERT INTO messages VALUES(?,'qa','me',?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, kind); it.setInt(3, hidden); it.setInt(4, deleted)
            it.setObject(5, sequence); it.setString(6, status); it.setString(7, time); it.setString(8, id); it.setObject(9, messageSortKey(time)); it.setLong(10, sequence ?: 0)
            it.executeUpdate()
        }
    }

    private data class Preview(val id: String, val text: String?, val kind: String?, val filename: String?, val time: String?)
    private fun Connection.previews(query: String): List<Preview> = createStatement().use { statement ->
        statement.executeQuery(query.replace(":myUserId", "'me'").replace(":historyCutoff", "-1").replace(":chatId", "'qa'")).use { rows ->
            buildList { while (rows.next()) add(Preview(rows.getString("chatId"), rows.getString("lastPreviewText"),
                rows.getString("lastPreviewKind"), rows.getString("lastPreviewFilename"), rows.getString("lastMessageAt"))) }
        }
    }

    @Test fun `D8 failed album and older pending member cannot mask newer read text or borrow its time`() {
        for (status in listOf("failed", "pending")) database().use { db ->
            db.message("QA78-reverse-album", "2026-11-13T05:01:39+02:00", null, status)
            db.message("captionless-member", "2026-11-13T03:01:39Z", 100, "delivered")
            db.message("QA78target2", "2026-11-13T03:06:40Z", 101, "read")
            db.createStatement().use { it.execute("INSERT INTO attachments VALUES('attachment','QA78-reverse-album','image','image/jpeg',NULL)") }
            queries.forEach { query ->
                val rows = db.previews(query)
                assertEquals("qa", rows.first().id)
                assertEquals(Preview("qa", "QA78target2", "text", null, "2026-11-13T03:06:40Z"), rows.first())
            }
        }
    }

    @Test fun `newer local sends promote the chat and all preview fields come from that message`() {
        for (status in listOf("pending", "failed")) database().use { db ->
            db.message("text", "2026-11-13T03:06:40Z", 900, "read")
            db.message("new-local", "2026-11-13T05:07:00.125+02:00", null, status)
            db.createStatement().use { it.execute("INSERT INTO attachments VALUES('attachment','new-local','file','application/pdf','report.pdf')") }
            queries.forEach { assertEquals(Preview("qa", "new-local", "file", "report.pdf", "2026-11-13T05:07:00.125+02:00"), db.previews(it).first()) }
        }
    }

    @Test fun `hidden deleted controls and unreadable rows cannot supply preview or local activity`() {
        database().use { db ->
            db.message("latest-text", "2026-11-13T03:06:40Z", 100, "read")
            db.message("hidden", "2026-11-13T04:00:00Z", null, "pending", hidden = 1)
            db.message("deleted", "2026-11-13T04:01:00Z", 101, "read", deleted = 1)
            db.message("decrypt", "2026-11-13T04:02:00Z", 102, "decrypt_failed")
            db.message("identity", "2026-11-13T04:03:00Z", 103, "identity_changed")
            db.message("edit", "2026-11-13T04:04:00Z", null, "pending", kind = "edit")
            queries.forEach { assertEquals(Preview("qa", "latest-text", "text", null, "2026-11-13T03:06:40Z"), db.previews(it).first()) }
        }
    }

    @Test fun `equal instants break ties by acknowledged sequence then stable client ID`() {
        database().use { db ->
            db.message("pending-z", "2026-11-13T05:06:40+02:00", null, "pending")
            db.message("ack-a", "2026-11-13T03:06:40Z", 100, "read")
            db.message("ack-b", "2026-11-13T03:06:40Z", 101, "read")
            queries.forEach { assertEquals("ack-b", db.previews(it).first().text) }
            db.createStatement().use { it.executeUpdate("DELETE FROM messages WHERE sequence IS NOT NULL") }
            db.message("pending-a", "2026-11-13T03:06:40Z", null, "pending")
            queries.forEach { assertEquals("pending-z", db.previews(it).first().text) }
        }
    }
}
