package com.messenger.app.ui.chats

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.AttachmentsApi
import com.messenger.app.data.attachment.*
import com.messenger.app.data.directtransfer.*
import com.messenger.app.data.local.chat.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Real 150 MiB source, production confirmation UI, direct storage and Room. No network. */
class ComposerOriginalStorageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun previouslyStagedOriginalUsesTheSameInodeAndCancellationKeepsTheDraft() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "original-link-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        try {
            val source = File(isolated.noBackupFilesDir, "media-drafts/${UUID.randomUUID()}/original.pdf").apply { parentFile.mkdirs() }
            RandomAccessFile(source, "rw").use { it.setLength(150L * 1024 * 1024) }
            val storage = DirectFileStorage(isolated, "isolated-owner") {}
            val uri = Uri.fromFile(source)
            assertFalse(storage.describe(uri).copyRequired)
            assertEquals(0L, storage.describe(uri).reservationBytes(8L * 1024 * 1024))
            val id = UUID.randomUUID().toString()
            val staged = withContext(Dispatchers.IO) { storage.stage(id, uri) }
            assertEquals(source.length(), staged.size)
            assertEquals(android.system.Os.stat(source.path).st_ino, android.system.Os.stat(storage.file(id, "source").path).st_ino)
            storage.deleteStagingSource(id) // An abandoned direct ticket must not destroy the draft's link.
            assertTrue(source.isFile)
            assertEquals(150L * 1024 * 1024, source.length())
        } finally { root.walkTopDown().forEach { it.setWritable(true) }; root.deleteRecursively() }
    }

    @Test fun addRetainsReferenceCancelCopiesNothingAndConfirmCopiesOnceAndConsumesDurably() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val root = File(app.cacheDir, "original-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(isolated, MessengerDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val source = File(root, "external-original.pdf")
            RandomAccessFile(source, "rw").use { it.setLength(150L * 1024 * 1024) }
            val uri = Uri.fromFile(source)
            val client = OkHttpClient()
            val manager = AttachmentManager(isolated, client, AttachmentsApi(client, Json, "https://unused.invalid"), app.authRepository, db.attachmentDao())
            assertTrue(ComposerOriginals.needsDirect(manager.composerOriginalSize(uri)))
            val imported = CompletableDeferred<List<String>>()
            val coordinator = MediaPreparationCoordinator(scope, { 1 }, { _, value -> manager.stageDraft(Uri.parse(value)).toString() },
                { _, chat, value, _ -> db.workspaceDao().updateComposerDrafts(chat, emptyList(), listOf(value), emptySet()); Unit },
                { _, _, _ -> }, { _, _ -> }, retainOriginal = { _, value -> manager.retainComposerOriginal(Uri.parse(value)).toString() },
                inspectOriginals = { _, _, values -> ComposerOriginals(values.filter { ComposerOriginals.needsDirect(manager.composerOriginalSize(Uri.parse(it))) }.toSet()) })
            withContext(Dispatchers.Main.immediate) {
                coordinator.start("chat", listOf(uri.toString()), true, originals = true, onComplete = { imported.complete(it) })
            }
            assertEquals(listOf(uri.toString()), withTimeout(5000) { imported.await() })
            assertTrue(db.workspaceDao().composerDrafts("chat").files.isEmpty())
            assertFalse(File(isolated.noBackupFilesDir, "media-drafts").exists())

            val id = UUID.randomUUID().toString()
            val storage = DirectFileStorage(isolated, "isolated-owner") {}
            val accepted = CompletableDeferred<DirectFileStorage.Staged>()
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme {
                if (visible.value) DirectOriginalConsentDialog(listOf("${source.name} · 150 MiB"), false, onConfirm = {
                    visible.value = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val staged = storage.stage(id, uri)
                                val offer = DirectFileOffer(transferId = id, offerClientMessageId = UUID.randomUUID().toString(),
                                    senderUserId = UUID.randomUUID().toString(), senderDeviceId = UUID.randomUUID().toString(),
                                    recipientUserId = UUID.randomUUID().toString(), offerNonce = DirectOfferNonce.create(),
                                    filename = staged.filename, mimeType = staged.mimeType, size = staged.size, sha256 = staged.sha256,
                                    expiresAt = System.currentTimeMillis() / 1000 + 3600).validated()
                                val record = DirectTransferRecord(offer, "chat", "sender", state = "waiting")
                                db.workspaceDao().putDirectComposerOffer(ChatWorkspaceValue("chat", "direct_transfer:$id",
                                    DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(), record)), null, null, uri.toString())
                                staged
                            }
                        }.onSuccess { accepted.complete(it) }.onFailure { accepted.completeExceptionally(it) }
                    }
                }, onDismiss = { visible.value = false })
            } }
            compose.onNodeWithTag("direct-original-cancel").performClick()
            assertFalse(storage.file(id, "source").exists())
            assertNull(db.workspaceDao().get("chat", "direct_transfer:$id"))
            assertTrue(db.workspaceDao().composerDrafts("chat").files.isEmpty())
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithTag("direct-original-confirm").performClick()
            val staged = withTimeout(30_000) { accepted.await() }
            assertEquals(source.length(), staged.size)
            assertEquals(staged.size, storage.file(id, "source").length())
            assertFalse(storage.file(id, "source.tmp").exists())
            assertFalse(File(isolated.noBackupFilesDir, "media-drafts").exists())
            assertNotNull(db.workspaceDao().get("chat", "direct_transfer:$id"))
            assertTrue(db.workspaceDao().composerDrafts("chat").files.isEmpty())
            // The selection is session-only; its URI was never in the draft journal.
            assertEquals(1, coordinator.state.value.getValue("chat").items.size)
        } finally {
            scope.cancel(); db.close()
            root.walkTopDown().forEach { it.setWritable(true) }; root.deleteRecursively()
        }
    }
}
