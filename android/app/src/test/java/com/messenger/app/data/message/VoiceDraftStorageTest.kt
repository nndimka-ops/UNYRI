package com.messenger.app.data.message

import com.messenger.app.data.attachment.discardUnreferencedDraft
import com.messenger.app.data.attachment.mediaDraftPaths
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.QueuedMediaRow
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class VoiceDraftStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun draft(id: String, file: File) = buildJsonObject {
        put("id", id); put("path", file.path); put("duration", 1500)
    }.toString()
    private fun row(id: String, file: File) = QueuedMediaRow(
        MessageEntity(id, null, "chat", "owner", "Voice", null, "2026-11-13T00:00:00Z", "pending"),
        buildJsonObject { put("kind", "voice"); put("uri", file.toURI().toString()); put("duration", 1500) }.toString(), "{}")

    @Test fun `enqueue and consume commit together and reopened screen cannot delete the queued file`() = runTest {
        val database = temporary.newFile("voice.db")
        val audio = temporary.newFile("запись + voice.m4a")
        SqlVoiceStore(database).use { store ->
            store.dao.saveMediaJob("chat", "voiceDraft", draft("take", audio))
            store.dao.enqueueVoiceDraft(row("take", audio))
            assertNull(store.dao.composerValue("chat", "voiceDraft"))
        }
        SqlVoiceStore(database).use { reopened ->
            assertNull(reopened.dao.restorableVoiceDraft("chat"))
            assertNotNull(reopened.dao.findByClientMessageId("take"))
            assertNotNull(reopened.dao.composerValue("chat", "upload:take"))
            assertFalse(reopened.dao.consumeVoiceDraftForDiscard("chat", "take", audio.path))
            assertTrue(audio.exists())
        }
    }

    @Test fun `pointer-clear failure rolls back the entire enqueue and restoration retains an unqueued draft`() = runTest {
        val database = temporary.newFile("rollback.db")
        val audio = temporary.newFile("take.m4a")
        val pointer = draft("take", audio)
        SqlVoiceStore(database).use { store ->
            store.dao.saveMediaJob("chat", "voiceDraft", pointer)
            store.failPointerClear(true)
            assertFailsWith<VoiceStoreWriteFailure> { store.dao.enqueueVoiceDraft(row("take", audio)) }
        }
        SqlVoiceStore(database).use { reopened ->
            assertNull(reopened.dao.findByClientMessageId("take"))
            assertNull(reopened.dao.composerValue("chat", "upload:take"))
            assertNull(reopened.dao.composerValue("chat", "presentation:take"))
            assertEquals(pointer, reopened.dao.restorableVoiceDraft("chat"))
            assertTrue(audio.exists())
            reopened.failPointerClear(false)
            reopened.dao.enqueueVoiceDraft(row("take", audio))
            assertNull(reopened.dao.restorableVoiceDraft("chat"))
            assertEquals(1, reopened.messageCount())
        }
    }

    @Test fun `legacy committed outbox with a stuck draft pointer stays protected after reopening`() = runTest {
        val database = temporary.newFile("legacy.db")
        val audio = temporary.newFile("queued.m4a")
        SqlVoiceStore(database).use { store ->
            // The pre-fix failure: the queue committed but its separate pointer clear did not.
            store.dao.saveMediaJob("chat", "voiceDraft", draft("take", audio))
            store.dao.enqueueMediaBatch(listOf(row("take", audio)))
            store.failPointerClear(true)
            assertFailsWith<VoiceStoreWriteFailure> { store.dao.enqueueVoiceDraft(row("take", audio)) }
        }
        SqlVoiceStore(database).use { reopened ->
            // Restoration must not need a successful cleanup write to hide an outbox-owned take.
            assertNotNull(reopened.dao.composerValue("chat", "voiceDraft"))
            assertNull(reopened.dao.restorableVoiceDraft("chat"))
            assertFailsWith<VoiceStoreWriteFailure> { reopened.dao.consumeVoiceDraftForDiscard("chat", "take", audio.path) }
            assertTrue(audio.exists())
            reopened.failPointerClear(false)
            assertFalse(reopened.dao.consumeVoiceDraftForDiscard("chat", "take", audio.path))
            assertTrue(audio.exists())
            assertEquals(1, reopened.messageCount())
        }
    }

    @Test fun `stale send and delete cannot consume a replacement take`() = runTest {
        val first = temporary.newFile("first.m4a")
        val second = temporary.newFile("second.m4a")
        SqlVoiceStore(temporary.newFile("replacement.db")).use { store ->
            val replacement = draft("new", second)
            store.dao.saveMediaJob("chat", "voiceDraft", replacement)
            assertFailsWith<IllegalStateException> { store.dao.enqueueVoiceDraft(row("old", first)) }
            assertFalse(store.dao.consumeVoiceDraftForDiscard("chat", "old", first.path))
            assertEquals(replacement, store.dao.restorableVoiceDraft("chat"))
            assertEquals(0, store.messageCount())
        }
    }

    @Test fun `idempotent old enqueue preserves the newer draft pointer`() = runTest {
        val first = temporary.newFile("first.m4a")
        val second = temporary.newFile("second.m4a")
        SqlVoiceStore(temporary.newFile("retry.db")).use { store ->
            store.dao.saveMediaJob("chat", "voiceDraft", draft("old", first))
            store.dao.enqueueVoiceDraft(row("old", first))
            val replacement = draft("new", second)
            store.dao.saveMediaJob("chat", "voiceDraft", replacement)
            store.dao.enqueueVoiceDraft(row("old", first))
            assertEquals(replacement, store.dao.restorableVoiceDraft("chat"))
            assertEquals(1, store.messageCount())
        }
    }

    @Test fun `ownership-aware cleanup preserves a source queued under another message ID`() = runTest {
        val root = temporary.newFolder("media-drafts")
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val audio = File(directory, "take.m4a").apply { writeText("audio") }
        SqlVoiceStore(temporary.newFile("shared.db")).use { store ->
            store.dao.enqueueMediaBatch(listOf(row("outbox-owner", audio)))
            store.dao.saveMediaJob("chat", "voiceDraft", draft("uncommitted", audio))
            assertTrue(store.dao.consumeVoiceDraftForDiscard("chat", "uncommitted", audio.path))
            // The repository uses this existing reference graph/cleanup, never File.delete().
            val references = store.workspace().flatMap { (name, value) -> mediaDraftPaths(name, value) }.toSet()
            assertFalse(discardUnreferencedDraft(root, audio, references))
            assertEquals("audio", audio.readText())
            assertNotNull(store.dao.composerValue("chat", "upload:outbox-owner"))
        }
    }
}

/** Run the production DAO default methods inside real SQLite transactions, with disk reopen/faults. */
private class VoiceStoreWriteFailure(cause: SQLException) : RuntimeException(cause)

private class SqlVoiceStore(file: File) : AutoCloseable {
    private val db = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private var depth = 0
    init {
        db.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS chat_workspace(chatId TEXT, name TEXT, value TEXT, PRIMARY KEY(chatId,name))")
            it.execute("CREATE TABLE IF NOT EXISTS messages(clientMessageId TEXT PRIMARY KEY, chatId TEXT, senderId TEXT, text TEXT, createdAt TEXT, status TEXT)")
        }
    }
    fun failPointerClear(fail: Boolean) {
        db.createStatement().use {
            it.execute("DROP TRIGGER IF EXISTS fail_voice_clear")
            if (fail) it.execute("CREATE TRIGGER fail_voice_clear BEFORE DELETE ON chat_workspace WHEN OLD.name='voiceDraft' BEGIN SELECT RAISE(ABORT, 'injected pointer-clear failure'); END")
        }
    }
    fun messageCount(): Int = db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM messages").use { rows -> rows.next(); rows.getInt(1) } }
    fun workspace(): List<Pair<String, String>> = db.createStatement().use {
        it.executeQuery("SELECT name,value FROM chat_workspace").use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
    }
    private fun update(sql: String, values: List<Any?>) {
        try {
            db.prepareStatement(sql).use { query -> values.forEachIndexed { index, value -> query.setObject(index + 1, value) }; query.executeUpdate() }
        } catch (error: SQLException) {
            // Android SQLite failures are unchecked; avoid Proxy wrapping a checked JDBC exception.
            throw VoiceStoreWriteFailure(error)
        }
    }
    val dao = Proxy.newProxyInstance(MessageDao::class.java.classLoader, arrayOf(MessageDao::class.java)) { proxy, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "composerValue" -> db.prepareStatement("SELECT value FROM chat_workspace WHERE chatId=? AND name=?").use {
                it.setString(1, args[0] as String); it.setString(2, args[1] as String)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            "saveMediaJob" -> { update("INSERT OR REPLACE INTO chat_workspace VALUES (?,?,?)", args.take(3)); Unit }
            "removeComposerValue" -> { update("DELETE FROM chat_workspace WHERE chatId=? AND name=?", args.take(2)); Unit }
            "findByClientMessageId" -> db.prepareStatement("SELECT * FROM messages WHERE clientMessageId=?").use {
                it.setString(1, args[0] as String)
                it.executeQuery().use { rows -> if (!rows.next()) null else MessageEntity(rows.getString("clientMessageId"), null,
                    rows.getString("chatId"), rows.getString("senderId"), rows.getString("text"), null, rows.getString("createdAt"), rows.getString("status")) }
            }
            "upsert" -> {
                val row = args[0] as MessageEntity
                update("INSERT OR REPLACE INTO messages VALUES (?,?,?,?,?,?)", listOf(row.clientMessageId, row.chatId, row.senderId, row.text, row.createdAt, row.status)); Unit
            }
            else -> transaction {
                val implementation = Class.forName(MessageDao::class.java.name + "\$DefaultImpls").methods.single {
                    it.name == method.name && it.parameterCount == args.size + 1
                }
                try { implementation.invoke(null, proxy, *args) }
                catch (error: InvocationTargetException) { throw error.targetException }
            }
        }
    } as MessageDao

    private fun <T> transaction(operation: () -> T): T {
        val outer = depth++ == 0
        if (outer) db.autoCommit = false
        try { return operation().also { if (outer) db.commit() } }
        catch (error: Throwable) { if (outer) db.rollback(); throw error }
        finally { depth--; if (outer) db.autoCommit = true }
    }
    override fun close() = db.close()
}
