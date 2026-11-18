package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.attachment.*
import com.messenger.app.data.local.chat.*
import com.messenger.app.data.local.message.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite rollback and admission tests. No map fake is presented as a transaction. */
@RunWith(AndroidJUnit4::class)
class ComposerTransactionsInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun row(id: String, album: String, caption: String?) = QueuedMediaRow(
        MessageEntity(id, null, "chat", "owner", id, null, "2026-11-12T08:00:00Z", "pending", caption = caption, albumId = album),
        "upload:$id", "preview:$id")

    @Test fun rollbackPreservesRetryThenSuccessRetiresOnlyThatAlbumAndKeepsLaterDraftAndCaption() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java).build()
        try {
            val workspace = db.workspaceDao(); val messages = db.messageDao()
            val batchKey = ComposerBatchIdentity.key(listOf("A", "B"), "First", "image")
            workspace.updateComposerDrafts("chat", listOf("A"), listOf("B"), emptySet())
            workspace.saveComposerCaption("chat", "First", "revision-1")
            workspace.put(ChatWorkspaceValue("chat", batchKey, "album-1"))
            workspace.put(ChatWorkspaceValue("chat", "mediaAlbum", "legacy"))
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_composer BEFORE INSERT ON chat_workspace WHEN NEW.name='fileDrafts' BEGIN SELECT RAISE(ABORT,'test rollback'); END") }
            val rows = listOf(row("A", "album-1", "First"), row("B", "album-1", null))
            assertTrue(runCatching { messages.enqueueComposerBatch(rows, setOf("A", "B"), "First", batchKey, "revision-1") }.isFailure)
            assertNull(messages.findByClientMessageId("A")); assertNull(messages.findByClientMessageId("B"))
            assertNull(messages.composerValue("chat", "upload:A"))
            assertEquals(listOf("A", "B"), workspace.composerDrafts("chat").all)
            assertEquals("album-1", workspace.get("chat", batchKey))
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_composer") }

            workspace.updateComposerDrafts("chat", emptyList(), listOf("C"), emptySet())
            workspace.saveComposerCaption("chat", "Second", "revision-2")
            val subscribed = CompletableDeferred<Unit>()
            val emissions = async(Dispatchers.Default) {
                messages.observeMessages("chat").onEach { if (it.isEmpty()) subscribed.complete(Unit) }.map { it.size }.take(2).toList()
            }
            // The subscription's first empty snapshot has to exist before admission.
            withTimeout(5000) { subscribed.await() }
            messages.enqueueComposerBatch(rows, setOf("A", "B"), "First", batchKey, "revision-1")
            assertEquals(listOf(0, 2), withTimeout(5000) { emissions.await() })
            assertEquals(listOf("C"), workspace.composerDrafts("chat").all)
            assertEquals("Second", workspace.get("chat", "draft"))
            assertEquals("revision-2", workspace.get("chat", "draftRevision"))
            assertNull(workspace.get("chat", batchKey)); assertNull(workspace.get("chat", "mediaAlbum"))

            val nextKey = ComposerBatchIdentity.key(listOf("C"), "Second", "image")
            workspace.put(ChatWorkspaceValue("chat", nextKey, "album-2"))
            messages.enqueueComposerBatch(listOf(row("C", "album-2", "Second")), setOf("C"), "Second", nextKey, "revision-2")
            assertNotEquals(messages.findByClientMessageId("A")!!.albumId, messages.findByClientMessageId("C")!!.albumId)
            assertEquals("First", messages.findByClientMessageId("A")!!.caption)
            assertEquals("Second", messages.findByClientMessageId("C")!!.caption)
            assertNull(workspace.get("chat", "draft"))
            workspace.saveComposerCaption("chat", "Second", "revision-2") // Delayed pre-commit writer.
            assertNull(workspace.get("chat", "draft"))
        } finally { db.close() }
    }

    @Test fun sameTextWithANewerRevisionSurvivesTheOldBatchCommit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MessengerDatabase::class.java).build()
        try {
            db.workspaceDao().saveComposerCaption("chat", "Same text", "new-revision")
            db.messageDao().enqueueComposerBatch(listOf(row("A", "album", "Same text")), setOf("A"), "Same text", null, "old-revision")
            assertEquals("Same text", db.workspaceDao().get("chat", "draft"))
            assertEquals("new-revision", db.workspaceDao().get("chat", "draftRevision"))
        } finally { db.close() }
    }

    @Test fun directOfferAndSourceConsumptionRollbackTogetherAndSurviveReopeningWithoutAUiCallback() = runBlocking {
        val name = "composer-transaction-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
        try {
            val value = ChatWorkspaceValue("chat", "direct_transfer:offer", "immutable-offer")
            val recovery = ChatWorkspaceValue("index", "recovery", "next-index")
            db.workspaceDao().put(ChatWorkspaceValue("index", "recovery", "prior-index"))
            db.workspaceDao().updateComposerDrafts("chat", emptyList(), listOf("original", "keep"), emptySet())
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_direct_composer BEFORE INSERT ON chat_workspace WHEN NEW.name='fileDrafts' BEGIN SELECT RAISE(ABORT,'test rollback'); END") }
            assertTrue(runCatching { db.workspaceDao().putDirectComposerOffer(value, recovery, "prior-index", "original") }.isFailure)
            assertNull(db.workspaceDao().get("chat", value.name))
            assertEquals("prior-index", db.workspaceDao().get("index", "recovery"))
            assertEquals(listOf("original", "keep"), db.workspaceDao().composerDrafts("chat").files)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_direct_composer") }
            db.workspaceDao().putDirectComposerOffer(value, recovery, "prior-index", "original")
            db.close() // Process death before coordinator/UI consumption.
            db = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
            assertEquals("immutable-offer", db.workspaceDao().get("chat", value.name))
            assertEquals("next-index", db.workspaceDao().get("index", "recovery"))
            assertEquals(listOf("keep"), db.workspaceDao().composerDrafts("chat").files)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
