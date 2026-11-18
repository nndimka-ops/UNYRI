package com.messenger.app.data.local

import com.messenger.app.data.chat.PreferenceWorkspace
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.sync.InMemoryAttachmentDao
import com.messenger.app.data.sync.InMemoryChatDao
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheMaintenanceTest {
    private class Fixture {
        val root: File = Files.createTempDirectory("cache-worker-review").toFile()
        val dao = InMemoryAttachmentDao()
        val workspace = PreferenceWorkspace(InMemoryChatDao())
        val active = MutableStateFlow<String?>(null)
        val policies = MutableStateFlow(CachePolicy(maxBytes = 1))
        val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val manager = MediaCacheManager(root, dao, File(root, "avatars"), File(root, "db"),
            workspaceDao = workspace, activeChatId = { active.value })
        val results = Channel<MediaCacheManager.ClearResult>(Channel.UNLIMITED)
        var starts = 0
        var readers = 0
        var afterSweep: suspend () -> Unit = {}
        var job: Job? = null
        suspend fun file(id: String = "file", upload: String = "sent"): AttachmentEntity {
            val file = File(root, "attachments/$id").apply { parentFile.mkdirs(); writeText("0123456789") }
            return AttachmentEntity(id, id, "chat", "file", "application/pdf", null, 10,
                null, null, null, null, file.path, "downloaded", upload, 0, id, 26, 1, null, null, null, "hash")
                .also { dao.upsert(it) }
        }
        fun start(scope: CoroutineScope) {
            job = scope.launch {
                MediaCacheMaintenance(policies,
                    manager.evictionChanges(active, MediaCacheUse.releases, writes)
                        .onStart { readers++ }.onCompletion { readers-- },
                    { policy ->
                        starts++
                        val result = manager.enforcePolicy(policy)
                        afterSweep()
                        results.send(result)
                        result
                    }, { manager.nextRetentionSweepDelay(it) },
                ).run()
            }
        }
        suspend fun close() { job?.cancelAndJoin(); root.deleteRecursively() }
    }
    private suspend fun TestScope.sweep(f: Fixture): MediaCacheManager.ClearResult {
        val before = f.starts
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(f.starts > before, "An eligibility change must wake the real worker without another write/timer")
        return f.results.receive().also { runCurrent() }
    }

    @Test fun `leaving a protected active chat wakes size-only eviction without another cache write`() = runTest {
        val f = Fixture()
        try {
            val row = f.file(); f.active.value = "chat"; f.start(backgroundScope)
            assertEquals(0, sweep(f).deletedFiles)
            assertTrue(File(row.localPath!!).exists())
            advanceTimeBy(10_000); runCurrent(); assertEquals(1, f.starts)
            f.active.value = null
            assertEquals(1, sweep(f).deletedFiles)
            assertFalse(File(row.localPath).exists()); assertNull(f.dao.findByAttachmentId(row.attachmentId)?.localPath)
        } finally { f.close() }
    }

    @Test fun `every upload completion write path wakes the worker while progress updates stay quiet`() = runTest {
        for (path in listOf("update", "acknowledgement", "upsert")) {
            val f = Fixture()
            try {
                val row = f.file(upload = "uploading"); f.start(backgroundScope)
                assertEquals(0, sweep(f).deletedFiles)
                f.dao.updateUploadState(row.attachmentId, "encrypting", 40)
                runCurrent() // A different protected state is still the same eviction eligibility.
                f.dao.updateUploadState(row.attachmentId, "uploading", 50)
                runCurrent(); advanceTimeBy(10_000); runCurrent(); assertEquals(1, f.starts)
                when (path) {
                    "update" -> f.dao.updateUploadState(row.attachmentId, "uploaded", 100)
                    "acknowledgement" -> assertEquals(1, f.dao.markUploadSent(row.attachmentId, row.clientMessageId, row.chatId))
                    else -> f.dao.upsert(row.copy(uploadState = "sent"))
                }
                assertEquals(1, sweep(f).deletedFiles, path)
                assertNull(f.dao.findByAttachmentId(row.attachmentId)?.localPath)
            } finally { f.close() }
        }
    }

    @Test fun `the final cache lease release wakes eviction and intermediate or repeated closes do not`() = runTest {
        val f = Fixture()
        var first: AutoCloseable? = null
        var last: AutoCloseable? = null
        try {
            val row = f.file()
            first = MediaCacheUse.acquire(listOf(File(row.localPath!!)))
            last = MediaCacheUse.acquire(listOf(File(row.localPath)))
            f.start(backgroundScope); assertEquals(0, sweep(f).deletedFiles)
            first.close(); first.close()
            runCurrent(); advanceTimeBy(10_000); runCurrent(); assertEquals(1, f.starts)
            last.close()
            assertEquals(1, sweep(f).deletedFiles)
        } finally { first?.close(); last?.close(); f.close() }
    }

    @Test fun `removing a composer reference wakes size-only eviction`() = runTest {
        val f = Fixture()
        try {
            val row = f.file()
            f.workspace.put(ChatWorkspaceValue("chat", "fileDraftUri", row.localPath!!))
            f.start(backgroundScope); assertEquals(0, sweep(f).deletedFiles)
            f.workspace.remove("chat", "fileDraftUri")
            assertEquals(1, sweep(f).deletedFiles)
        } finally { f.close() }
    }

    @Test fun `release during a sweep remains queued when the calculated next deadline is absent`() = runTest {
        val f = Fixture()
        val protectedRead = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        try {
            val row = f.file(); f.active.value = "chat"
            f.afterSweep = { if (f.starts == 1) { protectedRead.complete(Unit); finishRead.await() } }
            f.start(backgroundScope); runCurrent(); advanceTimeBy(500); runCurrent()
            protectedRead.await()
            f.active.value = null; runCurrent() // arrives after the old eligibility snapshot was enforced
            finishRead.complete(Unit); runCurrent()
            assertEquals(0, f.results.receive().deletedFiles)
            assertEquals(1, sweep(f).deletedFiles)
            assertFalse(File(row.localPath!!).exists())
        } finally { finishRead.complete(Unit); f.close() }
    }

    @Test fun `release during next-deadline calculation cannot be lost at the transition to indefinite sleep`() = runTest {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val calculating = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var passes = 0
        val worker = backgroundScope.launch {
            MediaCacheMaintenance(MutableStateFlow(CachePolicy(maxBytes = 1)), changes,
                { passes++; MediaCacheManager.ClearResult(0, 0, 0, 0) },
                { if (passes == 1) { calculating.complete(Unit); finish.await() }; null },
            ).run()
        }
        runCurrent(); advanceTimeBy(500); runCurrent(); calculating.await()
        changes.emit(Unit); runCurrent(); finish.complete(Unit); runCurrent()
        advanceTimeBy(500); runCurrent(); assertEquals(2, passes)
        worker.cancelAndJoin()
    }

    @Test fun `disabled policy has no observers or sweeps and enabling reconciles earlier eligibility changes`() = runTest {
        val f = Fixture()
        try {
            f.policies.value = CachePolicy(); val row = f.file(); f.active.value = "chat"
            f.start(backgroundScope); runCurrent()
            f.active.value = null; advanceTimeBy(86_400_000); runCurrent()
            assertEquals(0, f.readers); assertEquals(0, f.starts)
            f.policies.value = CachePolicy(maxBytes = 1)
            assertEquals(1, sweep(f).deletedFiles)
            assertFalse(File(row.localPath!!).exists())
            f.policies.value = CachePolicy(); runCurrent(); assertEquals(0, f.readers)
            val count = f.starts
            advanceTimeBy(86_400_000); runCurrent(); assertEquals(count, f.starts)
        } finally { f.close() }
    }

    @Test fun `failed deletion retries slowly even for size-only policy and cancellation stops the retry`() = runTest {
        val policy = MutableStateFlow(CachePolicy(maxBytes = 1))
        var attempts = 0
        val worker = backgroundScope.launch {
            MediaCacheMaintenance(policy, emptyFlow(),
                { attempts++; MediaCacheManager.ClearResult(0, 0, 0, 1) }, { null },
            ).run()
        }
        runCurrent(); advanceTimeBy(500); runCurrent(); assertEquals(1, attempts)
        advanceTimeBy(3_600_000); runCurrent(); assertEquals(1, attempts)
        advanceTimeBy(500); runCurrent(); assertEquals(2, attempts)
        policy.value = CachePolicy(); runCurrent()
        advanceTimeBy(86_400_000); runCurrent(); assertEquals(2, attempts)
        worker.cancelAndJoin()
    }
}
