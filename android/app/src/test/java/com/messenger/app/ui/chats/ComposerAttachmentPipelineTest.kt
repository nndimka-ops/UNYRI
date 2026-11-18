package com.messenger.app.ui.chats

import com.messenger.app.data.attachment.ComposerDrafts
import com.messenger.app.data.attachment.ComposerOriginals
import com.messenger.app.data.attachment.ComposerTraySnapshot
import com.messenger.app.data.attachment.MediaPreparationCoordinator
import com.messenger.app.data.local.chat.ChatWorkspaceDao
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerAttachmentPipelineTest {
    private fun pick(sources: List<String>, coordinator: MediaPreparationCoordinator, existing: ComposerDrafts = ComposerDrafts(),
        direct: Boolean = true, editing: Boolean = false, busy: Boolean = false, eligible: Boolean = true,
        cleanup: () -> Unit = {}, immediate: (ComposerAttachmentPick) -> Boolean = { error("Picker must enter review") }): AttachmentPickResult =
        admitComposerAttachmentPick(sources, asFile = false, direct = direct, sendNow = true,
            known = existing.all.toSet(), existing = existing.all.size, editing = editing, busy = busy, ready = true,
            captionValid = true, directEligible = eligible, cleanup = cleanup,
            review = { admitted -> coordinator.start("chat", admitted.sources, asFile = admitted.asFile,
                originals = admitted.asFile, direct = admitted.direct, append = true, maximumItems = 10 - existing.all.size) }, send = immediate)

    @Test fun `production admission rejects nine restored plus two direct files before any work`() = runTest {
        JdbcComposerWorkspace().use { disk ->
            repeat(9) { disk.dao.commitComposerDraft("chat", "old$it", true, false) }
            var copies = 0; var releases = 0
            val coordinator = coordinator(backgroundScope, disk, stage = { copies++; "copy:$it" })
            val result = pick(listOf("first", "second"), coordinator, disk.dao.composerDrafts("chat"), cleanup = { releases++ })
            runCurrent()
            assertEquals(AttachmentPickRejection.CAPACITY, result.rejection)
            assertFalse(result.accepted); assertEquals(0, copies); assertEquals(1, releases)
            assertTrue(coordinator.state.value.isEmpty())
            assertEquals(9, disk.dao.composerDrafts("chat").all.size)
        }
    }

    @Test fun `direct Standard gallery admission stages an original file and production Send offers it`() = runTest {
        for (quality in listOf("image", "image_high")) JdbcComposerWorkspace().use { disk ->
            val coordinator = coordinator(backgroundScope, disk)
            assertTrue(pick(listOf("small-photo"), coordinator).accepted)
            assertTrue(coordinator.state.value.getValue("chat").items.single().asFile)
            assertTrue(coordinator.state.value.getValue("chat").items.single().directSend)
            runCurrent()
            val restored = disk.dao.composerDrafts("chat")
            assertTrue(restored.photos.isEmpty())
            assertEquals(listOf("copy:small-photo"), restored.files)
            assertEquals(setOf("copy:small-photo"), restored.direct)
            var offered = emptyList<String>(); var ordinary = 0
            dispatchComposerAttachmentSend(ComposerTraySnapshot.create(restored, emptyList(), emptySet()), quality, restored.direct,
                needsDirect = { false }, offer = { offered = it }, ordinary = { ordinary++ })
            assertEquals(restored.files, offered); assertEquals(0, ordinary)
        }
    }

    @Test fun `application import commits direct staged identity after UI departure and restart without aliases`() = runTest {
        val file = File.createTempFile("composer-restart-", ".db")
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            JdbcComposerWorkspace(file).use { disk ->
                val coordinator = coordinator(appScope, disk, stage = { gate.await(); "copy:$it" })
                uiScope.launch { assertTrue(pick(listOf("provider:small-file"), coordinator).accepted) }
                runCurrent()
                assertTrue(disk.dao.composerDrafts("chat").all.isEmpty())
                uiScope.cancel() // No composition/effect remains to resolve or save aliases.
                gate.complete(Unit); runCurrent()
                assertFalse(coordinator.state.value.getValue("chat").active)
                assertEquals(setOf("copy:provider:small-file"), disk.dao.composerDrafts("chat").direct)
            }
            appScope.cancel() // Discard all in-memory import state, as on process restart.
            JdbcComposerWorkspace(file).use { restarted ->
                val drafts = restarted.dao.composerDrafts("chat") // Exact production restoration, not manual alias resolution.
                assertEquals(listOf("copy:provider:small-file"), drafts.files)
                assertEquals(drafts.files.toSet(), drafts.direct)
                var offered = emptyList<String>()
                dispatchComposerAttachmentSend(ComposerTraySnapshot.create(drafts, emptyList(), emptySet()), "image", drafts.direct,
                    needsDirect = { false }, offer = { offered = it }, ordinary = { error("Must not upload ordinarily after restart") })
                assertEquals(drafts.files, offered)
            }
        } finally { uiScope.cancel(); appScope.cancel(); file.delete() }
    }

    @Test fun `atomic draft commit exposes neither reference nor transport alone and rolls back failed intent write`() = runTest {
        val file = File.createTempFile("composer-atomic-", ".db")
        try {
            JdbcComposerWorkspace(file).use { disk ->
                disk.beforeIntentWrite = {
                    DriverManager.getConnection("jdbc:sqlite:${file.path}").use { reader ->
                        reader.createStatement().use { query -> query.executeQuery("SELECT count(*) FROM chat_workspace").use {
                            assertTrue(it.next()); assertEquals(0, it.getInt(1))
                        } }
                    }
                }
                disk.failIntentWrite = true
                val coordinator = coordinator(backgroundScope, disk)
                assertTrue(pick(listOf("small"), coordinator).accepted); runCurrent()
                assertTrue(coordinator.state.value.getValue("chat").failed)
                assertTrue(disk.dao.composerDrafts("chat").all.isEmpty())
                assertNull(disk.dao.get("chat", ComposerDrafts.DIRECT_KEY))
                disk.failIntentWrite = false
                assertTrue(pick(listOf("small"), coordinator).accepted); runCurrent()
                val drafts = disk.dao.composerDrafts("chat")
                assertEquals(listOf("copy:small"), drafts.files); assertEquals(drafts.files.toSet(), drafts.direct)
            }
        } finally { file.delete() }
    }

    @Test fun `production Send routes marked photo drafts regardless of global quality or file classification`() {
        val tray = ComposerTraySnapshot(listOf("photo", "ordinary"), listOf("photo", "ordinary"), emptyList(), emptySet())
        for (quality in listOf("image", "image_high", "file")) {
            var offered = emptyList<String>(); var normal = 0
            dispatchComposerAttachmentSend(tray, quality, setOf("photo"), { false }, { offered = it }, { normal++ })
            assertEquals(listOf("photo"), offered); assertEquals(0, normal)
        }
    }

    @Test fun `ordinary gallery still uses ordinary Send and blocked direct admission never starts work`() = runTest {
        JdbcComposerWorkspace().use { disk ->
            var copies = 0; var sends = 0
            val coordinator = coordinator(backgroundScope, disk, stage = { copies++; it })
            assertEquals(AttachmentPickRejection.EDITING, pick(listOf("one"), coordinator, editing = true).rejection)
            assertEquals(AttachmentPickRejection.DESTINATION, pick(listOf("one"), coordinator, eligible = false).rejection)
            runCurrent(); assertEquals(0, copies)
            assertTrue(pick(listOf("ordinary-photo"), coordinator, direct = false, immediate = { sends++; true }).accepted)
            assertEquals(1, sends); assertEquals(0, copies)
            val tray = ComposerTraySnapshot(listOf("ordinary-photo"), listOf("ordinary-photo"), emptyList(), emptySet())
            dispatchComposerAttachmentSend(tray, "image", emptySet(), { true }, { error("No direct intent") }, { sends++ })
            assertEquals(2, sends)
        }
    }

    @Test fun `draft consumption and removal retire transport in the same production transaction and isolate chats`() = runTest {
        JdbcComposerWorkspace().use { disk ->
            disk.dao.commitComposerDraft("chat", "direct", false, true)
            disk.dao.commitComposerDraft("chat", "ordinary", true, false)
            disk.dao.commitComposerDraft("other-chat", "other", true, true)
            disk.dao.putDirectComposerOffer(ChatWorkspaceValue("chat", "direct_transfer:offer", "offer"), null, null, "direct")
            assertEquals(listOf("ordinary"), disk.dao.composerDrafts("chat").files)
            assertTrue(disk.dao.composerDrafts("chat").direct.isEmpty())
            assertEquals(setOf("other"), disk.dao.composerDrafts("other-chat").direct)
            disk.dao.updateComposerDrafts("other-chat", emptyList(), emptyList(), setOf("other"))
            assertNull(disk.dao.get("other-chat", ComposerDrafts.DIRECT_KEY))
        }
    }

    @Test fun `legacy marked photo restores as an original file without composition migration`() = runTest {
        JdbcComposerWorkspace().use { disk ->
            disk.dao.put(ChatWorkspaceValue("chat", "mediaDrafts", ComposerDrafts.encode(listOf("legacy-photo"))))
            disk.dao.put(ChatWorkspaceValue("chat", ComposerDrafts.DIRECT_KEY, ComposerDrafts.encode(listOf("legacy-photo"))))
            val restored = disk.dao.composerDrafts("chat")
            assertTrue(restored.photos.isEmpty()); assertEquals(listOf("legacy-photo"), restored.files)
            assertEquals(restored.files.toSet(), restored.direct)
        }
    }

    private fun coordinator(scope: CoroutineScope, disk: JdbcComposerWorkspace, stage: suspend (String) -> String = { "copy:$it" }) =
        MediaPreparationCoordinator(scope, { 1 }, { _, source -> stage(source) },
            { _, _, _, _ -> error("Application must use atomic transport commit") }, { _, _, _ -> }, { _, _ -> },
            inspectOriginals = { _, _, _ -> ComposerOriginals(emptySet()) },
            commitWithTransport = { _, chat, uri, asFile, direct -> disk.dao.commitComposerDraft(chat, uri, asFile, direct) })
}

/** Execute the production DAO default methods, using SQLite for its primitive
 * queries and the transaction boundary supplied by Room on Android. No draft
 * mutation, alias mapping or persistence algorithm is reimplemented here. */
private class JdbcComposerWorkspace(file: File? = null) : AutoCloseable {
    private val db = DriverManager.getConnection(if (file == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:${file.path}")
    var beforeIntentWrite: (() -> Unit)? = null
    var failIntentWrite = false
    private var depth = 0
    init { db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS chat_workspace (chatId TEXT, name TEXT, value TEXT, PRIMARY KEY(chatId,name))") } }
    val dao: ChatWorkspaceDao = Proxy.newProxyInstance(ChatWorkspaceDao::class.java.classLoader, arrayOf(ChatWorkspaceDao::class.java)) { proxy, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "get" -> db.prepareStatement("SELECT value FROM chat_workspace WHERE chatId=? AND name=?").use { query ->
                query.setString(1, args[0] as String); query.setString(2, args[1] as String)
                query.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
            "put" -> {
                val row = args[0] as ChatWorkspaceValue
                if (row.name == ComposerDrafts.DIRECT_KEY) {
                    beforeIntentWrite?.invoke()
                    if (failIntentWrite) throw IllegalStateException("Injected intent write failure")
                }
                db.prepareStatement("INSERT OR REPLACE INTO chat_workspace VALUES (?,?,?)").use {
                    it.setString(1, row.chatId); it.setString(2, row.name); it.setString(3, row.value); it.executeUpdate()
                }; Unit
            }
            "remove" -> { db.prepareStatement("DELETE FROM chat_workspace WHERE chatId=? AND name=?").use {
                it.setString(1, args[0] as String); it.setString(2, args[1] as String); it.executeUpdate()
            }; Unit }
            else -> transaction {
                val implementation = Class.forName(ChatWorkspaceDao::class.java.name + "\$DefaultImpls").methods.single {
                    it.name == method.name && it.parameterCount == args.size + 1
                }
                try { implementation.invoke(null, proxy, *args) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
    } as ChatWorkspaceDao

    private fun <T> transaction(block: () -> T): T {
        val outermost = depth++ == 0
        if (outermost) db.autoCommit = false
        try { return block().also { if (outermost) db.commit() } }
        catch (failure: Throwable) { if (outermost) db.rollback(); throw failure }
        finally { depth--; if (outermost) db.autoCommit = true }
    }
    override fun close() = db.close()
}
