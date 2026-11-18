package com.messenger.app.data.message

import com.messenger.app.data.attachment.MediaPreparationException
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.sync.InMemoryMessageDao
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The map fake cannot reproduce SQLite REPLACE's changing rowid. Run the
 * actual outbox with the production eligibility, failure and retry SQL. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageOutboxSqlOrderTest {
    private class SqlDao(val db: Connection) : MessageDao by InMemoryMessageDao() {
        private val queries: Map<String, String> = run {
            val path = "src/main/java/com/messenger/app/data/local/message/MessageDao.kt"
            val source = listOf(File(path), File("app/$path"), File("android/app/$path")).first { it.isFile }.readText()
            Regex("@Query\\((.*?)\\)\\s*(?:suspend\\s+)?fun\\s+(\\w+)", RegexOption.DOT_MATCHES_ALL)
                .findAll(source).associate {
                    val literal = it.groupValues[1].trim()
                    it.groupValues[2] to if (literal.startsWith("\"\"\"")) literal.removeSurrounding("\"\"\"") else literal.removeSurrounding("\"")
                }
        }
        init {
            db.createStatement().use { statement ->
                statement.execute("""CREATE TABLE messages (
                    clientMessageId TEXT PRIMARY KEY, serverId TEXT, chatId TEXT, senderId TEXT,
                    text TEXT, sequence INTEGER, createdAt TEXT, status TEXT, kind TEXT, targetMessageId TEXT,
                    hiddenLocally INTEGER, isDeletedForEveryone INTEGER, caption TEXT, albumId TEXT,
                    retryCount INTEGER, nextRetryAtEpochMs INTEGER, lastErrorClass TEXT,
                    payloadText TEXT, pendingEditText TEXT, pendingEditRevision INTEGER, sendAttempted INTEGER)
                """)
                statement.execute("CREATE INDEX index_messages_chatId ON messages(chatId)")
                statement.execute("CREATE TABLE chat_workspace(chatId TEXT, name TEXT, value TEXT, PRIMARY KEY(chatId,name))")
            }
        }

        override suspend fun upsert(message: MessageEntity) {
            // Same conflict strategy as Room's @Insert(REPLACE), deliberately
            // not an UPDATE: a regression to upsert-on-failure must fail here.
            val columns = "clientMessageId,serverId,chatId,senderId,text,sequence,createdAt,status,kind,targetMessageId," +
                "hiddenLocally,isDeletedForEveryone,caption,albumId,retryCount,nextRetryAtEpochMs,lastErrorClass," +
                "payloadText,pendingEditText,pendingEditRevision,sendAttempted"
            val values = listOf(message.clientMessageId, message.serverId, message.chatId, message.senderId,
                message.text, message.sequence, message.createdAt, message.status, message.kind, message.targetMessageId,
                if (message.hiddenLocally) 1 else 0, if (message.isDeletedForEveryone) 1 else 0,
                message.caption, message.albumId, message.retryCount, message.nextRetryAtEpochMs, message.lastErrorClass,
                message.payloadText, message.pendingEditText, message.pendingEditRevision, if (message.sendAttempted) 1 else 0)
            db.prepareStatement("INSERT OR REPLACE INTO messages ($columns) VALUES (${values.joinToString { "?" }})").use {
                values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.executeUpdate()
            }
        }

        private fun <T> query(name: String, parameters: Map<String, Any?>, read: (ResultSet) -> T): List<T> {
            val names = mutableListOf<String>()
            val sql = Regex(":([A-Za-z][A-Za-z0-9]*)").replace(queries.getValue(name)) {
                names += it.groupValues[1]; "?"
            }
            return db.prepareStatement(sql).use { statement ->
                names.forEachIndexed { index, key -> statement.setObject(index + 1, parameters.getValue(key)) }
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }
        private fun update(name: String, parameters: Map<String, Any?>) {
            val names = mutableListOf<String>()
            val sql = Regex(":([A-Za-z][A-Za-z0-9]*)").replace(queries.getValue(name)) {
                names += it.groupValues[1]; "?"
            }
            db.prepareStatement(sql).use { statement ->
                names.forEachIndexed { index, key -> statement.setObject(index + 1, parameters.getValue(key)) }
                statement.executeUpdate()
            }
        }
        private fun read(rows: ResultSet) = MessageEntity(
            rows.getString("clientMessageId"), rows.getString("serverId"), rows.getString("chatId"),
            rows.getString("senderId"), rows.getString("text"), (rows.getObject("sequence") as? Number)?.toLong(),
            rows.getString("createdAt"), rows.getString("status"), kind = rows.getString("kind"),
            targetMessageId = rows.getString("targetMessageId"), hiddenLocally = rows.getInt("hiddenLocally") != 0,
            isDeletedForEveryone = rows.getInt("isDeletedForEveryone") != 0, caption = rows.getString("caption"),
            albumId = rows.getString("albumId"), retryCount = rows.getInt("retryCount"),
            nextRetryAtEpochMs = rows.getLong("nextRetryAtEpochMs"), lastErrorClass = rows.getString("lastErrorClass"),
            payloadText = rows.getString("payloadText"), pendingEditText = rows.getString("pendingEditText"),
            pendingEditRevision = rows.getInt("pendingEditRevision"), sendAttempted = rows.getInt("sendAttempted") != 0)

        override suspend fun findByClientMessageId(clientMessageId: String) =
            query("findByClientMessageId", mapOf("clientMessageId" to clientMessageId), ::read).singleOrNull()
        override suspend fun pendingEligible(myUserId: String, nowEpochMs: Long) =
            query("pendingEligible", mapOf("myUserId" to myUserId, "nowEpochMs" to nowEpochMs), ::read)
        override suspend fun markFailed(clientMessageId: String, lastErrorClass: String?) =
            update("markFailed", mapOf("clientMessageId" to clientMessageId, "lastErrorClass" to lastErrorClass))
        override suspend fun resetToPending(clientMessageId: String) =
            update("resetToPending", mapOf("clientMessageId" to clientMessageId))
        override suspend fun updateStatus(clientMessageId: String, status: String) =
            update("updateStatus", mapOf("clientMessageId" to clientMessageId, "status" to status))
        fun rowid(id: String): Long = db.prepareStatement("SELECT rowid FROM messages WHERE clientMessageId=?").use {
            it.setString(1, id); it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
    }

    private fun member(id: String) = MessageEntity(id, null, "chat", "me", "[Photo]", null,
        "2026-11-13T03:01:39Z", "pending", albumId = "album", caption = "caption".takeIf { id == "A" })

    @Test fun `SQLite failure then retry preserves A caption before B at the same timestamp`() = runTest {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            val dao = SqlDao(db)
            val original = member("A").copy(payloadText = "frozen", pendingEditText = "new edit", pendingEditRevision = 3, sendAttempted = true)
            dao.upsert(original); dao.upsert(member("B"))
            val rowid = dao.rowid("A")
            val release = CompletableDeferred<Unit>()
            var failA = true
            var bReady = false
            val sent = mutableListOf<String>()
            val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
                mediaReadiness = { if (it.clientMessageId == "B" && !bReady) OutboxMediaReadiness.WAITING else OutboxMediaReadiness.NONE },
                prepareMedia = { release.await(); bReady = true },
                attemptSend = {
                    if (it.clientMessageId == "A" && failA) throw MediaPreparationException(IllegalArgumentException())
                    sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent")
                })
            processor.flush(); runCurrent()
            assertEquals(original.copy(status = "failed", lastErrorClass = "MediaPreparationException"), dao.findByClientMessageId("A"))
            assertEquals(rowid, dao.rowid("A"), "Failure must not REPLACE the reserved enqueue row")
            dao.resetToPending("A")
            assertEquals(listOf("A", "B"), dao.pendingEligible("me", 1000L).map { it.clientMessageId })
            assertEquals(rowid, dao.rowid("A"))
            failA = false
            processor.flush(); runCurrent()
            assertEquals(listOf("A"), sent)
            release.complete(Unit); runCurrent()
            assertEquals(listOf("A", "B"), sent)
            assertEquals("caption", dao.findByClientMessageId("A")!!.caption)
            assertNull(dao.findByClientMessageId("B")!!.caption)
        }
    }

    @Test fun `SQLite late failure cannot overwrite acknowledgements hidden rows or deleted members`() = runTest {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            val dao = SqlDao(db)
            val protectedRows = listOf(member("sent").copy(status = "sent", serverId = "server", sequence = 1),
                member("delivered").copy(status = "delivered"), member("read").copy(status = "read"),
                member("hidden").copy(hiddenLocally = true), member("deleted").copy(isDeletedForEveryone = true),
                member("failed").copy(status = "failed", lastErrorClass = "original"))
            for (message in protectedRows) {
                dao.upsert(message)
                val rowid = dao.rowid(message.clientMessageId)
                dao.markFailed(message.clientMessageId, "late failure")
                assertEquals(message, dao.findByClientMessageId(message.clientMessageId))
                assertEquals(rowid, dao.rowid(message.clientMessageId))
            }
            dao.markFailed("missing", "late failure")
            assertNull(dao.findByClientMessageId("missing"))
        }
    }
}
