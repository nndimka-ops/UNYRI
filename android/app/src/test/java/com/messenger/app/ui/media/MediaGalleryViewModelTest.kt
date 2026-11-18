package com.messenger.app.ui.media

import com.messenger.app.data.attachment.AttachmentDownloader
import com.messenger.app.data.attachment.MediaGallerySource
import com.messenger.app.data.attachment.VoicePlayback
import com.messenger.app.domain.model.MediaItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M.2 regression tests for the media gallery:
 *  - BUG 3: the FIRST tab (Photos) must be populated on first render
 *    (exactly one initial request, no second tap required);
 *  - BUG 4: the gallery must propagate the REAL stored attachment MIME type
 *    (never hardcode application/octet-stream).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaGalleryViewModelTest {

    private fun mediaItem(
        id: String,
        mediaType: String,
        mimeType: String,
        sequence: Long,
    ) = MediaItem(
        attachmentId = id,
        clientMessageId = "m-$id",
        chatId = "chat-1",
        mediaType = mediaType,
        mimeType = mimeType,
        filename = "file-$id",
        size = 100,
        durationMs = null,
        thumbnailBase64 = null,
        downloadState = "uploaded",
        localPath = null,
        senderId = "me",
        sequence = sequence,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private class FakeSource(
        var result: (String, Long) -> List<MediaItem> = { _, _ -> emptyList() },
    ) : MediaGallerySource {
        val requestedTypes = mutableListOf<String>()
        val requestedBeforeSequences = mutableListOf<Long>()
        override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItem> {
            requestedTypes += mediaType
            requestedBeforeSequences += beforeSequence
            return result(mediaType, beforeSequence)
        }
    }

    private class FakeDownloader : AttachmentDownloader {
        override suspend fun download(attachmentId: String): File =
            File.createTempFile("gallery-dl", ".bin")
    }

    private class FakeVoice : VoicePlayback {
        val played = mutableListOf<Pair<String, File>>()
        override fun togglePlay(attachmentId: String, file: File) {
            played += attachmentId to file
        }
    }

    /** Builds a ViewModel with the test scheduler installed as Main (and
     *  restored afterwards), so `viewModelScope` runs deterministically. */
    private fun TestScope.withMain(block: () -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `first open loads photos without a second tap (BUG 3)`() = runTest {
        withMain {
            val source = FakeSource { _, _ -> listOf(mediaItem("a1", "image", "image/jpeg", 100)) }
            val vm = MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.items.size)
            assertEquals("image/jpeg", vm.uiState.value.items[0].mimeType)
            // Exactly one initial request - init must not be duplicated.
            assertEquals(listOf("image"), source.requestedTypes)
        }
    }

    @Test
    fun `initial load issues exactly one request with a fresh cursor`() = runTest {
        withMain {
            val source = FakeSource()
            MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            advanceUntilIdle()

            assertEquals(1, source.requestedTypes.size)
            assertEquals(Long.MAX_VALUE, source.requestedBeforeSequences[0])
        }
    }

    @Test
    fun `tab switching resets and loads the selected type`() = runTest {
        withMain {
            val source = FakeSource { type, _ ->
                listOf(mediaItem("a1", type, if (type == "image") "image/jpeg" else "application/pdf", 100))
            }
            val vm = MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            advanceUntilIdle()
            assertEquals(listOf("image"), source.requestedTypes)

            vm.selectTab(MediaTab.Files)
            advanceUntilIdle()

            assertEquals(listOf("image", "file"), source.requestedTypes)
            assertEquals("file", vm.currentTab.wireType)
            assertEquals("application/pdf", vm.uiState.value.items[0].mimeType)
        }
    }

    @Test
    fun `pagination cursor advances from the last sequence of the previous page`() = runTest {
        withMain {
            var call = 0
            val source = FakeSource { _, _ ->
                call++
                if (call == 1) {
                    listOf(mediaItem("a1", "image", "image/jpeg", 200), mediaItem("a2", "image", "image/jpeg", 100))
                } else {
                    listOf(mediaItem("a3", "image", "image/jpeg", 50))
                }
            }
            val vm = MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            advanceUntilIdle()
            vm.loadPage()
            advanceUntilIdle()

            assertEquals(2, source.requestedTypes.size)
            assertEquals(Long.MAX_VALUE, source.requestedBeforeSequences[0])
            assertEquals(100L, source.requestedBeforeSequences[1])
            assertEquals(3, vm.uiState.value.items.size)
        }
    }

    @Test
    fun `open carries the real stored mime type for a JPEG (BUG 4)`() = runTest {
        withMain {
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), FakeDownloader(), FakeVoice())
            vm.open(mediaItem("a1", "image", "image/jpeg", 100))
            advanceUntilIdle()

            assertEquals("image/jpeg", vm.uiState.value.openedFileMimeType)
            assertNotNull(vm.uiState.value.openedFile)
            assertEquals("a1", vm.uiState.value.openedAttachmentId)
            vm.clearOpenedFile()
            assertNull(vm.uiState.value.openedAttachmentId)
        }
    }

    @Test
    fun `open carries the real stored mime type for a PDF`() = runTest {
        withMain {
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), FakeDownloader(), FakeVoice())
            vm.open(mediaItem("a1", "file", "application/pdf", 100))
            advanceUntilIdle()

            assertEquals("application/pdf", vm.uiState.value.openedFileMimeType)
            assertNotNull(vm.uiState.value.openedFile)
        }
    }

    @Test
    fun `open carries the real stored mime type for a text file`() = runTest {
        withMain {
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), FakeDownloader(), FakeVoice())
            vm.open(mediaItem("a1", "file", "text/plain", 100))
            advanceUntilIdle()

            assertEquals("text/plain", vm.uiState.value.openedFileMimeType)
        }
    }

    @Test
    fun `open a voice item routes to the voice player, not the file intent`() = runTest {
        withMain {
            val voice = FakeVoice()
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), FakeDownloader(), voice)
            vm.open(mediaItem("v1", "voice", "audio/mp4", 100))
            advanceUntilIdle()

            assertEquals(1, voice.played.size)
            assertNull(vm.uiState.value.openedFile)
            assertNull(vm.uiState.value.openedFileMimeType)
        }
    }
    @Test
    fun `rapid tab changes cannot publish a cancelled response`() = runTest {
        withMain {
            val pending = kotlinx.coroutines.CompletableDeferred<List<MediaItem>>()
            val source = object : MediaGallerySource {
                override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int): List<MediaItem> {
                    return if (mediaType == "image") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
                    else listOf(mediaItem("pdf", "file", "application/pdf", 20))
                }
            }
            val vm = MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            testScheduler.runCurrent()
            vm.selectTab(MediaTab.Files)
            testScheduler.runCurrent()
            pending.complete(listOf(mediaItem("old", "image", "image/jpeg", 30)))
            advanceUntilIdle()
            assertEquals(listOf("pdf"), vm.uiState.value.items.map { it.attachmentId })
            assertEquals(false, vm.uiState.value.loading)
        }
    }

    @Test
    fun `failed page stops loading and can be retried`() = runTest {
        withMain {
            val source = FakeSource { _, _ -> error("offline") }
            val vm = MediaGalleryViewModel("chat-1", source, FakeDownloader(), FakeVoice())
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.loading)
            assertNotNull(vm.uiState.value.error)
            source.result = { _, _ -> listOf(mediaItem("ok", "image", "image/jpeg", 1)) }
            vm.loadPage()
            vm.loadPage()
            advanceUntilIdle()
            assertEquals(2, source.requestedTypes.size)
            assertNull(vm.uiState.value.error)
            assertEquals(1, vm.uiState.value.items.size)
        }
    }

    @Test fun `viewer downloads the selected ID without publishing another gallery open event`() = runTest {
        withMain {
            val requested = mutableListOf<String>()
            val verified = File("/verified/viewer.mp4")
            val downloader = object : AttachmentDownloader {
                override suspend fun download(attachmentId: String): File { requested += attachmentId; return verified }
            }
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), downloader, FakeVoice())
            advanceUntilIdle()
            val before = vm.uiState.value
            var result: File? = null
            launch { result = vm.downloadMediaForViewer("selected-page") }
            advanceUntilIdle()
            assertEquals(listOf("selected-page"), requested)
            assertEquals(verified, result)
            assertEquals(before, vm.uiState.value)
        }
    }

    @Test fun `completed direct media opens and pages through checked local source without attachment download`() = runTest {
        withMain {
            val file = File("/verified/direct-original.mp4")
            val item = mediaItem("direct:transfer", "image", "video/mp4", 20).copy(directTransferId = "transfer", localPath = file.path)
            val lookups = mutableListOf<String>()
            val source = object : MediaGallerySource {
                override suspend fun page(chatId: String, mediaType: String, beforeSequence: Long, limit: Int) = listOf(item)
                override suspend fun directFile(chatId: String, id: String): File { lookups += id; return file }
            }
            val downloader = object : AttachmentDownloader {
                override suspend fun download(attachmentId: String): File = error("Direct originals must not use cloud attachment downloads")
            }
            val vm = MediaGalleryViewModel("chat-1", source, downloader, FakeVoice())
            advanceUntilIdle()
            vm.open(item)
            advanceUntilIdle()
            assertEquals(file, vm.uiState.value.openedFile)
            assertEquals("video/mp4", vm.uiState.value.openedFileMimeType)
            var page: File? = null
            launch { page = vm.downloadMediaForViewer(item.attachmentId) }
            advanceUntilIdle()
            assertEquals(file, page)
            assertEquals(listOf("transfer", "transfer"), lookups)
        }
    }

    @Test fun `missing completed direct file fails locally without a cloud fallback`() = runTest {
        withMain {
            val item = mediaItem("direct:transfer", "image", "image/jpeg", 20).copy(directTransferId = "transfer")
            val downloader = object : AttachmentDownloader {
                override suspend fun download(attachmentId: String): File = error("No cloud fallback")
            }
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), downloader, FakeVoice())
            advanceUntilIdle()
            vm.open(item)
            advanceUntilIdle()
            assertNull(vm.uiState.value.openedFile)
            assertEquals("Download failed.", vm.uiState.value.error)
        }
    }

    @Test fun `viewer rejects a completed download belonging to an old gallery generation`() = runTest {
        withMain {
            val pending = kotlinx.coroutines.CompletableDeferred<File>()
            val downloader = object : AttachmentDownloader {
                override suspend fun download(attachmentId: String): File = pending.await()
            }
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), downloader, FakeVoice())
            advanceUntilIdle()
            var resolved: File? = null
            var rejected = false
            launch {
                try { resolved = vm.downloadMediaForViewer("old-page") }
                catch (_: kotlinx.coroutines.CancellationException) { rejected = true }
            }
            testScheduler.runCurrent()
            vm.selectTab(MediaTab.Files)
            pending.complete(File("/stale/viewer.mp4"))
            advanceUntilIdle()
            assertEquals(true, rejected)
            assertNull(resolved)
            assertNull(vm.uiState.value.openedFile)
            assertNull(vm.uiState.value.openedAttachmentId)
        }
    }

    @Test fun `leaving the viewer cannot publish a late noncancellable downloader result`() = runTest {
        withMain {
            val pending = kotlinx.coroutines.CompletableDeferred<File>()
            val downloader = object : AttachmentDownloader {
                override suspend fun download(attachmentId: String): File =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
            }
            val vm = MediaGalleryViewModel("chat-1", FakeSource(), downloader, FakeVoice())
            advanceUntilIdle()
            var resolved: File? = null
            var rejected = false
            val request = launch {
                try { resolved = vm.downloadMediaForViewer("cancelled-page") }
                catch (_: kotlinx.coroutines.CancellationException) { rejected = true }
            }
            testScheduler.runCurrent()
            request.cancel()
            pending.complete(File("/cancelled/viewer.mp4"))
            advanceUntilIdle()
            assertEquals(true, rejected)
            assertNull(resolved)
            assertNull(vm.uiState.value.openedFile)
        }
    }

}
