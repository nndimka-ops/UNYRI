package com.messenger.app.data.local

import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.sync.InMemoryAttachmentDao
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MediaCacheManagerTest {
    @Test fun accountCacheCountsAndClearsNestedOwnedMediaWithoutTouchingAnotherAccount() = runBlocking {
        val root = Files.createTempDirectory("account-cache-contract").toFile()
        try {
            val own = File(root,"attachments/account-a").apply { mkdirs() }
            val foreign = File(root,"attachments/account-b").apply { mkdirs() }
            val file = File(own,"voice.m4a").apply { writeText("voice") }
            val canary = File(foreign,"canary").apply { writeText("private") }
            val dao = InMemoryAttachmentDao()
            fun row(id: String, path: File) = AttachmentEntity(id,id,"chat","voice","audio/mp4",null,path.length(),null,null,1000,null,path.path,
                "downloaded","sent",objectKey=id,encryptedSize=22,keyBase64=null,nonceBase64=null,sha256Base64="hash")
            dao.upsert(row("own",file)); dao.upsert(row("foreign",canary))
            val manager = MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"),attachmentPathAllowed = { path ->
                path != null && com.messenger.app.data.attachment.AttachmentBoundary.contained(own,File(path))
            })
            assertEquals(file.length(),manager.scan().voiceBytes)
            manager.clear("chat")
            assertFalse(file.exists()); assertTrue(canary.exists())
            assertNull(dao.findByAttachmentId("own")?.localPath)
            assertEquals(canary.path,dao.findByAttachmentId("foreign")?.localPath)
        } finally { root.deleteRecursively() }
    }
    @Test fun clearPreservesDraftsAndAllPreviewsWithoutAnObservableConsumerLifetime() = runBlocking {
        val root = Files.createTempDirectory("draft-cache-cleanup").toFile()
        try {
            val drafts = File(root, "drafts").apply { mkdirs() }
            fun draft(id: String, old: Boolean = true) = File(drafts, "$id/file.bin").apply {
                parentFile.mkdirs(); writeText("data")
                if (old) setLastModified(System.currentTimeMillis() - 2 * 86400000L)
            }
            val live = draft("live")
            val retry = draft("retry")
            val orphan = draft("orphan")
            val fresh = draft("fresh", false)
            val values = mutableListOf(
                com.messenger.app.data.local.chat.ChatWorkspaceValue("chat", "fileDraftUri", live.toURI().toString()),
                com.messenger.app.data.local.chat.ChatWorkspaceValue("chat", "upload:retry", kotlinx.serialization.json.buildJsonObject {
                    put("uri", kotlinx.serialization.json.JsonPrimitive(retry.toURI().toString()))
                }.toString()),
            )
            val workspace = object : com.messenger.app.data.local.chat.ChatWorkspaceDao {
                override suspend fun directPreviewPage(chat: String, after: String) = emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>()
                override fun observeSecurityFreeze(chatId: String) = kotlinx.coroutines.flow.flowOf(false)
                // This read-only cache fixture contains drafts/uploads, never direct records.
                override suspend fun directRecoveryUpperRowId(): Long {
                    check(values.none { it.name.startsWith("direct_transfer:") })
                    return 0L
                }
                override suspend fun directRecoveryPage(afterRowId: Long, upperRowId: Long): List<com.messenger.app.data.local.chat.ChatWorkspaceRecoveryRow> {
                    check(values.none { it.name.startsWith("direct_transfer:") })
                    return emptyList()
                }
                override suspend fun historyDirectTransferPage(offset: Int) = allDirectTransfers().drop(offset).take(8)
    override suspend fun allDirectTransfers() = values.filter { it.name.startsWith("direct_transfer:") }
                override fun observeAllDirectTransfers() = kotlinx.coroutines.flow.flow { emit(allDirectTransfers()) }
                override fun directTransfers(chatId: String) = kotlinx.coroutines.flow.flow { emit(allDirectTransfers().filter { it.chatId == chatId }) }
                override fun conferenceDecisions(chatId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>())
                override suspend fun projectMute(chat: String, enabled: Boolean, until: String?) = Unit
                override suspend fun clearCallActivity() { values.removeAll { it.name in setOf("call_activity_at", "call_unread") } }
                override suspend fun mediaJobs(chatId: String) = values.filter { it.chatId == chatId && it.name.startsWith("upload:") }
                override suspend fun mediaPresentations(chatId: String) = values.filter { it.chatId == chatId && it.name.startsWith("presentation:") }
                override suspend fun historyArchiveValues() = values.filter { it.name == "personal_clear" || (it.chatId == "__server_history__" && it.name == "cleared_through_sequence") }.take(10002)
                override suspend fun pendingPreferences() = emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>()
                override fun observePendingPreferences() = kotlinx.coroutines.flow.flowOf(false)
                override fun observeCacheProtection() = kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>())
                override suspend fun allValues() = values
                override suspend fun removeCompletedMediaJobs() = Unit
                override fun schedules(chatId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>())
                override suspend fun get(chatId: String, name: String): String? = values.find { it.chatId == chatId && it.name == name }?.value
                override suspend fun put(value: com.messenger.app.data.local.chat.ChatWorkspaceValue) = Unit
                override suspend fun remove(chatId: String, name: String) = Unit
            }
            val cache = File(root, "cache").apply { mkdirs() }
            val preview = File(cache, "attachments/previews/preview").apply { parentFile.mkdirs(); writeText("copy") }
            val building = File(preview.parentFile, "active.building").apply { writeText("busy") }
            val manager = MediaCacheManager(cache, InMemoryAttachmentDao(), File(root,"avatars"), File(root,"db"), drafts, workspace)
            assertEquals(20L, manager.scan().tempBytes)
            values.add(com.messenger.app.data.local.chat.ChatWorkspaceValue("chat", "upload:corrupt", "{invalid"))
            assertEquals(0, manager.clear().deletedFiles)
            assertTrue(orphan.exists()); assertTrue(preview.exists())
            values.removeAt(values.lastIndex)
            assertEquals(0, manager.clear().deletedFiles)
            assertTrue(live.exists()); assertTrue(retry.exists()); assertTrue(fresh.exists()); assertTrue(building.exists())
            assertTrue(orphan.exists()); assertTrue(preview.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun clearPreservesUnregisteredDraftsAndPendingUploads() = runBlocking {
        val directory = Files.createTempDirectory("coco-cache-contract").toFile()
        try {
            val dao = InMemoryAttachmentDao()
            val media = File(directory, "attachments").apply { mkdirs() }
            val camera = File(directory, "camera").apply { mkdirs() }
            val preparing = File(media, "preparing.jpg").apply { writeText("new upload") }
            val draft = File(camera, "draft.jpg").apply { writeText("camera draft") }
            for (state in listOf("uploading", "failed", "sent")) {
                val source = File(media, "$state.jpg").apply { writeText("source") }
                dao.upsert(AttachmentEntity(
                    attachmentId = state, clientMessageId = state, chatId = "chat",
                    mediaType = "image", mimeType = "image/jpeg", filename = null, size = 6,
                    width = null, height = null, durationMs = null, thumbnailBase64 = null,
                    localPath = source.path, downloadState = "downloaded", uploadState = state,
                    objectKey = state, encryptedSize = 22, keyBase64 = null, nonceBase64 = null,
                    sha256Base64 = "hash",
                ))
            }
            val restoredRoot = File(directory, "restored").apply { mkdirs() }
            val restored = File(restoredRoot, "original.bin").apply { writeText("original") }
            dao.upsert(requireNotNull(dao.findByAttachmentId("sent")).copy(attachmentId="restored",clientMessageId="restored",localPath=restored.absolutePath))
            val manager = MediaCacheManager(directory, dao, File(directory, "avatars"), File(directory, "db"), restoredRoot=restoredRoot)
            val result = manager.clear()
            assertEquals(3, result.protectedFiles)
            assertTrue(restored.exists())
            assertEquals(restored.absolutePath,dao.findByAttachmentId("restored")?.localPath)
            assertEquals(1, result.deletedFiles)
            assertTrue(preparing.exists())
            assertTrue(draft.exists())
            assertTrue(File(media, "uploading.jpg").exists())
            assertTrue(File(media, "failed.jpg").exists())
            assertNull(dao.findByAttachmentId("sent")?.localPath)
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test fun perChatClearPreservesOtherChatAndFailedDeletionReference() = runBlocking {
        val root=Files.createTempDirectory("cache-chat-scope").toFile()
        try {
            val dao=InMemoryAttachmentDao()
            fun row(id: String, chat: String): AttachmentEntity {
                val file=File(root,"attachments/$id").apply { parentFile.mkdirs(); writeText("data") }
                return AttachmentEntity(id,id,chat,"file","application/pdf",null,4,null,null,null,null,file.path,"downloaded","sent",0,id,20,1,null,null,null,"hash")
            }
            dao.upsert(row("a","chat-a")); dao.upsert(row("b","chat-b"))
            val failed=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"),deleteCachedFile={ _, _ -> false })
            assertEquals(1,failed.clear("chat-a").failedFiles)
            assertTrue(File(dao.findByAttachmentId("a")!!.localPath!!).exists())
            val manager=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"))
            assertEquals(1,manager.clear("chat-a").deletedFiles)
            assertNull(dao.findByAttachmentId("a")?.localPath)
            assertTrue(File(dao.findByAttachmentId("b")!!.localPath!!).exists())
            assertEquals(listOf("chat-b"),manager.chatUsage().map { it.chatId })
        } finally { root.deleteRecursively() }
    }
    @Test fun staleSessionGateCancelsBeforeAnyDeletion() = runBlocking {
        val root=Files.createTempDirectory("cache-owner-fence").toFile()
        try {
            val dao=InMemoryAttachmentDao()
            val manager=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"),withWorkspaceWrite={ _, _ -> null })
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { manager.clear() }
            Unit
        } finally { root.deleteRecursively() }
    }

    @Test fun oneHundredProtectedOldFilesDoNotStarveNewerEligibleCache() = runBlocking {
        val root=Files.createTempDirectory("cache-starvation").toFile()
        val leases=mutableListOf<AutoCloseable>()
        try {
            val dao=InMemoryAttachmentDao()
            for (n in 0..100) {
                val file=File(root,"attachments/$n").apply { parentFile.mkdirs(); writeText("0123456789"); setLastModified(n+1L) }
                dao.upsert(AttachmentEntity("$n","$n","chat","file","application/pdf",null,10,null,null,null,null,file.path,"downloaded","sent",0,"$n",26,1,null,null,null,"hash"))
                if(n<100) leases.add(MediaCacheUse.acquire(listOf(file)))
            }
            val manager=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"))
            assertEquals(1,manager.enforcePolicy(CachePolicy(maxBytes=1000)).deletedFiles)
            assertFalse(File(root,"attachments/100").exists())
            assertEquals(100,dao.allWithLocalPath().size)
            assertTrue(File(root,"attachments/0").exists())
        } finally { leases.forEach { it.close() }; root.deleteRecursively() }
    }

    @Test fun cancellationAfterDeleteStillInvalidatesCacheReference() = runBlocking {
        val root=Files.createTempDirectory("cache-cancel-after-delete").toFile()
        try {
            val dao=InMemoryAttachmentDao()
            val file=File(root,"attachments/media").apply { parentFile.mkdirs(); writeText("data") }
            dao.upsert(AttachmentEntity("id","id","chat","file","application/pdf",null,4,null,null,null,null,file.path,"downloaded","sent",0,"id",20,1,null,null,null,"hash"))
            lateinit var clearing: kotlinx.coroutines.Job
            val manager=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"),deleteCachedFile={ target,_ ->
                val deleted=target.delete(); clearing.cancel(); deleted
            })
            clearing=launch(start=kotlinx.coroutines.CoroutineStart.LAZY) { manager.clear() }
            clearing.start(); clearing.join()
            assertFalse(file.exists()); assertNull(dao.findByAttachmentId("id")?.localPath)
        } finally { root.deleteRecursively() }
    }

    @Test fun automaticSweepProtectsVisibleChatWithoutDisablingExplicitChatClear() = runBlocking {
        val root=Files.createTempDirectory("cache-visible-chat").toFile()
        try {
            val dao=InMemoryAttachmentDao()
            for(chat in listOf("visible","other")) {
                val file=File(root,"attachments/$chat").apply { parentFile.mkdirs(); writeText("0123456789") }
                dao.upsert(AttachmentEntity(chat,chat,chat,"image","image/jpeg",null,10,null,null,null,null,file.path,"downloaded","sent",0,chat,26,1,null,null,null,"hash"))
            }
            val manager=MediaCacheManager(root,dao,File(root,"avatars"),File(root,"db"),activeChatId={"visible"})
            assertEquals(1,manager.enforcePolicy(CachePolicy(maxBytes=1)).deletedFiles)
            assertTrue(File(root,"attachments/visible").exists()); assertFalse(File(root,"attachments/other").exists())
            assertEquals(1,manager.clear("visible").deletedFiles)
            assertNull(dao.findByAttachmentId("visible")?.localPath)
        } finally { root.deleteRecursively() }
    }

}
