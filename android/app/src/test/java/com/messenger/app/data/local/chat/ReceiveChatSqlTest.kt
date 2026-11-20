package com.messenger.app.data.local.chat

import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.messageSortKey
import com.messenger.app.data.local.message.withSortKeys
import com.messenger.app.data.sync.InMemoryMessageDao
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.test.*

/** Executes the actual migration and both Room observer queries on real SQLite. */
class ReceiveChatSqlTest {
    @Test fun publicIdentityMigrationPreservesOldPeersAndStoresNewPublicFields() {
        Database().use { fixture ->
            fixture.db.createStatement().use {
                it.execute("INSERT INTO chat_participants(userId,username,displayName) VALUES('peer','private_login','Alice')")
                it.execute("INSERT INTO chat_members(chatId,userId) VALUES('chat','peer')")
            }
            fixture.migrate()
            val legacy = fixture.query(ChatListSql.CHAT) { it.getString("otherUsername") to it.getString("otherPublicNumber") }.single()
            assertEquals("private_login" to null, legacy)
            assertEquals("", com.messenger.app.data.identity.PeerIdentityPolicy.handle(null, legacy.second, legacy.first))
            fixture.db.createStatement().use { it.execute("UPDATE chat_participants SET publicNumber='123456789',publicUsername='alice'") }
            assertEquals("123456789" to "alice", fixture.query(ChatListSql.CHAT) { it.getString("otherPublicNumber") to it.getString("otherPublicUsername") }.single())
        }
    }

    private class Database : AutoCloseable {
        val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        init {
            db.createStatement().use {
                it.execute("""CREATE TABLE messages(clientMessageId TEXT PRIMARY KEY, chatId TEXT, senderId TEXT,
                    sequence INTEGER, createdAt TEXT, text TEXT, kind TEXT DEFAULT 'content', hiddenLocally INTEGER DEFAULT 0,
                    isDeletedForEveryone INTEGER DEFAULT 0, status TEXT DEFAULT 'sent')""")
                it.execute("CREATE INDEX index_messages_chatId_sequence ON messages(chatId,sequence)")
                it.execute("CREATE INDEX index_messages_chatId ON messages(chatId)")
                it.execute("CREATE TABLE chats(chatId TEXT PRIMARY KEY, type TEXT DEFAULT 'direct', createdAt TEXT DEFAULT '2026-01-01T00:00:00Z', lastMessageAt TEXT, lastReadSequence INTEGER DEFAULT 0, isMuted INTEGER DEFAULT 0, mutedUntil TEXT, isBlocked INTEGER DEFAULT 0, blockedUserId TEXT)")
                it.execute("CREATE TABLE chat_members(chatId TEXT, userId TEXT)")
                it.execute("CREATE TABLE chat_participants(userId TEXT, username TEXT, displayName TEXT, avatarUrl TEXT, avatarVersion INTEGER, hasAvatar INTEGER)")
                it.execute("CREATE TABLE attachments(attachmentId TEXT PRIMARY KEY, clientMessageId TEXT, filename TEXT, mediaType TEXT, mimeType TEXT)")
                it.execute("CREATE INDEX index_attachments_clientMessageId ON attachments(clientMessageId)")
                it.execute("CREATE TABLE chat_workspace(chatId TEXT, name TEXT, value TEXT, PRIMARY KEY(chatId,name))")
                it.execute("INSERT INTO chats(chatId) VALUES('chat')")
            }
        }
        fun migrate() {
            val adapter = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                check(method.name == "execSQL")
                db.createStatement().use { it.execute(args!![0] as String) }
                null
            } as SupportSQLiteDatabase
            MessengerDatabase.MIGRATION_21_22.migrate(adapter)
            MessengerDatabase.MIGRATION_22_23.migrate(adapter)
        }
        fun add(id: String, date: String = "2026-11-18T12:00:00Z", sequence: Long? = null,
                chat: String = "chat", status: String = "sent", kind: String = "content", hidden: Int = 0, deleted: Int = 0, indexed: Boolean = true) {
            val columns = "clientMessageId,chatId,senderId,sequence,createdAt,text,status,kind,hiddenLocally,isDeletedForEveryone"
            val values = mutableListOf<Any?>(id, chat, "peer", sequence, date, id, status, kind, hidden, deleted)
            if (indexed) {
                val entity = MessageEntity(id, null, chat, "peer", id, sequence, date, status).withSortKeys()
                values += entity.sortKey; values += entity.sortSequence
            }
            db.prepareStatement("INSERT INTO messages($columns${if (indexed) ",sortKey,sortSequence" else ""}) VALUES(${values.joinToString { "?" }})").use {
                values.forEachIndexed { index, value -> it.setObject(index + 1, value) }; it.executeUpdate()
            }
        }
        fun <T> query(sql: String, parameters: Map<String, Any?> = mapOf("chatId" to "chat", "myUserId" to "me", "historyCutoff" to -1), read: (ResultSet) -> T): List<T> {
            val names = mutableListOf<String>()
            val bound = Regex(":([A-Za-z][A-Za-z0-9]*)").replace(sql) { names += it.groupValues[1]; "?" }
            return db.prepareStatement(bound).use { statement ->
                names.forEachIndexed { index, name -> statement.setObject(index + 1, parameters.getValue(name)) }
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }
        fun latest() = query(ChatListSql.LATEST.replace("c.chatId", ":chatId")) { it.getString(1) }.singleOrNull()
        fun execute(sql: String) = db.createStatement().use { it.execute(sql) }
        override fun close() = db.close()
    }

    private val dates = listOf(
        "2026-11-18T12:00:00Z", "2026-11-18T14:00:00.000+02:00", "2026-11-18T07:00:00.000000-05:00",
        "2026-11-18T12:00:00.001Z", "2026-11-18T12:00:00.123456Z", "2026-11-18T12:00:00.123500Z",
        "2026-11-18T12:00:00.999499Z", "2026-11-18T12:00:00.999500Z", "2026-11-18T12:00:00.000001Z",
        "2026-11-18T12:00:00.000999Z", "1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00Z",
        "2026-11-18 12:00:00.001", "2026-11-18", "invalid",
    )

    @Test fun `21 to 22 backfills UTC milliseconds without Julian floating point off by one and normalizes null sequences`() {
        Database().use { database ->
            dates.forEachIndexed { index, date -> database.add("$index", date, index.toLong().takeIf { index % 2 == 0 }, indexed = false) }
            database.migrate()
            val rows = database.query("SELECT createdAt,sequence,sortKey,sortSequence FROM messages") {
                Triple(it.getString(1), (it.getObject(3) as? Number)?.toLong(), it.getLong(4))
            }
            rows.forEachIndexed { index, (date, key, sequence) ->
                assertEquals(messageSortKey(date), key, date)
                assertEquals(if (index % 2 == 0) index.toLong() else 0, sequence)
            }
            assertEquals(1, database.query("SELECT COUNT(*) FROM sqlite_master WHERE name='index_messages_latest'") { it.getInt(1) }.single())
        }
    }

    @Test fun `new ordering matches julianday on mixed offsets fractional precision null zero negative sequences and ties`() {
        Database().use { database ->
            database.migrate()
            dates.forEachIndexed { index, date ->
                listOf(null, -1L, 0L, 1L, 10L).forEachIndexed { tie, sequence -> database.add("$index-$tie", date, sequence) }
            }
            val old = database.query("SELECT clientMessageId FROM messages ORDER BY julianday(createdAt) DESC, COALESCE(sequence,0) DESC, clientMessageId DESC") { it.getString(1) }
            val indexed = database.query("SELECT clientMessageId FROM messages ORDER BY sortKey DESC,sortSequence DESC,clientMessageId DESC") { it.getString(1) }
            assertEquals(old, indexed)
            assertEquals(old.first(), database.latest())
        }
    }

    @Test fun `single indexed latest lookup has no temp sort and observers use it once`() {
        Database().use { database ->
            database.migrate()
            repeat(2000) { database.add("old-$it", "2026-11-17T12:00:00Z", it.toLong()) }
            database.add("latest", sequence = 3000)
            val plan = database.query("EXPLAIN QUERY PLAN " + ChatListSql.LATEST.replace("c.chatId", ":chatId")) { it.getString("detail") }
            assertTrue(plan.any { it.contains("USING INDEX index_messages_latest") }, plan.toString())
            assertFalse(plan.any { it.contains("TEMP B-TREE") }, plan.toString())
            for (sql in listOf(ChatListSql.LIST, ChatListSql.CHAT)) {
                val fullPlan = database.query("EXPLAIN QUERY PLAN $sql") { it.getString("detail") }
                assertEquals(1, fullPlan.count { it.contains("SEARCH newest USING INDEX index_messages_latest") }, fullPlan.toString())
                assertTrue(fullPlan.any { it.contains("SEARCH m USING INDEX index_messages_chatId_sequence") && it.contains("sequence>?") }, fullPlan.toString())
                assertEquals("latest", database.query(sql) { it.getString("lastPreviewText") }.single())
            }
        }
    }

    @Test fun `both observers retain visibility unread attachment empty-chat and activity semantics`() {
        Database().use { database ->
            database.migrate()
            database.add("readable", sequence = 7)
            database.add("hidden", "2026-11-19T00:00:00Z", 8, hidden = 1)
            database.add("deleted", "2026-11-19T00:00:00Z", 9, deleted = 1)
            database.add("undecryptable", "2026-11-19T00:00:00Z", 10, status = "decrypt_failed")
            database.add("changed", "2026-11-19T00:00:00Z", 11, status = "identity_changed")
            database.add("edit", "2026-11-19T00:00:00Z", 12, kind = "edit")
            database.execute("INSERT INTO attachments VALUES('attachment','readable','clip.mp4','file','video/mp4')")
            database.execute("INSERT INTO chats(chatId) VALUES('empty')")
            for (sql in listOf(ChatListSql.LIST, ChatListSql.CHAT)) {
                val chat = database.query(sql) { listOf(it.getString("chatId"), it.getString("lastPreviewText"), it.getString("lastPreviewFilename"), it.getString("lastPreviewKind"), it.getString("lastMessageAt"), it.getInt("unreadCount")) }.first { it[0] == "chat" }
                assertEquals(listOf("chat", "readable", "clip.mp4", "video", "2026-11-18T12:00:00Z", 3), chat)
            }
            assertNull(database.query(ChatListSql.LIST) { it.getString("lastPreviewText") }.last())
            database.execute("UPDATE chats SET lastMessageAt='2026-11-20T12:00:00Z' WHERE chatId='chat'")
            assertEquals("2026-11-20T12:00:00Z", database.query(ChatListSql.CHAT) { it.getString("lastMessageAt") }.single())
            database.execute("INSERT INTO chat_workspace VALUES('empty','personal_clear','50')")
            assertEquals(listOf("chat"), database.query(ChatListSql.LIST) { it.getString("chatId") })
        }
    }

    @Test fun `history cutoff opened saved call and personal clear rules remain`() {
        Database().use { database ->
            database.migrate()
            database.execute("INSERT INTO chats(chatId) VALUES('old-error'),('new-error'),('opened'),('called')")
            database.execute("INSERT INTO chats(chatId,type) VALUES('saved','saved')")
            database.add("readable", sequence = 4)
            database.add("old", sequence = 40, chat = "old-error", status = "decrypt_failed")
            database.add("new", sequence = 60, chat = "new-error", status = "identity_changed")
            database.execute("INSERT INTO chat_workspace VALUES('opened','opened_on_device','1'),('called','call_activity_at','2026-11-18'),('called','call_unread','2')")
            val parameters = mapOf("myUserId" to "me", "historyCutoff" to 50)
            val visible = database.query(ChatListSql.LIST, parameters) { it.getString("chatId") }.toSet()
            assertEquals(setOf("chat", "new-error", "opened", "called", "saved"), visible)
            assertEquals(2, database.query(ChatListSql.LIST, parameters) { it.getString("chatId") to it.getInt("unreadCount") }.toMap()["called"])
            database.execute("INSERT INTO chat_workspace VALUES('chat','personal_clear','50')")
            assertFalse("chat" in database.query(ChatListSql.LIST, parameters) { it.getString("chatId") })
            database.add("after-clear", sequence = 51)
            assertTrue("chat" in database.query(ChatListSql.LIST, parameters) { it.getString("chatId") })
        }
    }

    @Test fun `reconciliation reparses changed timestamp and sequence at the production insert boundary`() = runTest {
        var inserted: MessageEntity? = null
        val dao = object : MessageDao by InMemoryMessageDao() {
            override suspend fun upsert(message: MessageEntity) = super<MessageDao>.upsert(message)
            override suspend fun upsertSorted(message: MessageEntity) { inserted = message }
        }
        dao.upsert(MessageEntity("one", null, "chat", "me", "hello", null, "2026-11-18T12:00:00Z", "pending"))
        val original = checkNotNull(inserted)
        dao.upsert(original.copy(createdAt = "2026-11-18T14:00:01+02:00", sequence = 4, status = "sent"))
        val reconciled = checkNotNull(inserted)
        assertEquals(original.sortKey!! + 1000, reconciled.sortKey)
        assertEquals(4L, reconciled.sortSequence)
    }
}
