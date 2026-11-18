package com.messenger.app.data.attachment

import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.sync.InMemoryAttachmentDao
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DownloadStateOwnershipTest {
    private fun row(id: String, state: String) = AttachmentEntity(attachmentId=id, clientMessageId="message", chatId="chat", mediaType="file", mimeType="video/mp4", filename="video.mp4", size=10,
        width=480,height=480,durationMs=1000,thumbnailBase64=null,localPath=null,downloadState=state,uploadState="sent",objectKey="key",encryptedSize=20,
        keyBase64=null,nonceBase64=null,sha256Base64="hash")
    @Test fun cleanupDoesNotOverwriteCompletedOrReplacedRows() = runTest {
        val dao = InMemoryAttachmentDao(); dao.upsert(row("a", "downloading"))
        assertEquals(0, dao.finishInterruptedDownload("a", "other", "chat", "not_downloaded"))
        assertEquals(0, dao.finishInterruptedDownload("a", "message", "deleted", "not_downloaded"))
        assertEquals(1, dao.finishInterruptedDownload("a", "message", "chat", "not_downloaded"))
        dao.upsert(row("a", "downloaded")); assertEquals(0, dao.finishInterruptedDownload("a", "message", "chat", "failed"))
        assertEquals("downloaded", dao.findByAttachmentId("a")?.downloadState)
    }
    @Test fun launchRecoveryResetsOnlyOrphanedDownloadingRows() = runTest {
        val dao = InMemoryAttachmentDao(); listOf("downloading","downloaded","corrupt","failed").forEach { dao.upsert(row(it,it)) }
        assertEquals(1, dao.recoverInterruptedDownloads())
        assertEquals("not_downloaded", dao.findByAttachmentId("downloading")?.downloadState)
        assertEquals("downloaded", dao.findByAttachmentId("downloaded")?.downloadState)
        assertEquals("corrupt", dao.findByAttachmentId("corrupt")?.downloadState)
        assertEquals("failed", dao.findByAttachmentId("failed")?.downloadState)
    }
}
