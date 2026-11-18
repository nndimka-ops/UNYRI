package com.messenger.app.ui.media

import com.messenger.app.domain.model.MediaItem
import com.messenger.app.ui.chats.MediaViewerItem
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GalleryViewerPagesTest {
    @Test fun `remote and local mixed pages retain gallery order and replace only the selected ID`() {
        val first = item("remote-photo", "image/jpeg", 400)
        val selected = item("selected-video", "video/mp4", 300).copy(localPath = "/old/path.mp4")
        val third = item("remote-video", "video/mp4", 200)
        val downloaded = File("/fresh/verified.mp4")
        val opened = MediaViewerItem(downloaded, selected.mimeType, "Opened", selected.attachmentId)
        val pages = galleryViewerPages(listOf(first, selected, item("voice", "audio/mp4", 250), third,
            selected.copy(localPath = "/duplicate.mp4"), item("document", "application/pdf", 100)), opened)
        assertEquals(listOf("remote-photo", "selected-video", "remote-video"), pages.map { it.id })
        assertNull(pages[0].file)
        assertEquals(downloaded, pages[1].file)
        assertNull(pages[2].file)
        assertEquals(listOf("thumb-remote-photo", "thumb-selected-video", "thumb-remote-video"), pages.map { it.thumbnailBase64 })
    }

    @Test fun `missing selected item is isolated instead of appended after unrelated media`() {
        val opened = MediaViewerItem(File("/opened.png"), "image/png", "Opened", "missing")
        assertEquals(listOf(opened), galleryViewerPages(listOf(item("other", "image/png", 5)), opened))
    }

    @Test fun `opening a document does not include image siblings`() {
        val opened = MediaViewerItem(File("/opened.pdf"), "application/pdf", "Opened", "pdf")
        assertEquals(listOf(opened), galleryViewerPages(listOf(item("other", "image/png", 5)), opened))
    }

    private fun item(id: String, mime: String, sequence: Long) = MediaItem(id, "message-$id", "chat", "image",
        mime, "$id.dat", 100, null, "thumb-$id", "not_downloaded", null, "peer", sequence, "2026-10-27T00:00:00Z")
}
