package com.messenger.app.ui.chats

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MediaViewerEditorRoutingTest {
    @Test fun downloadedPhotoUsesResolvedFileAndRetainsItsPresentation() {
        val item = MediaViewerItem(null, "image/jpeg", "Remote", "remote-id",
            sender = "Peer", createdAt = "2026-11-13T00:00:00Z")
        val downloaded = File("downloaded.jpg")
        assertNull(item.editorRequest(null))
        val request = item.editorRequest(downloaded)!!
        assertEquals(downloaded, request.file)
        assertNull("History edits append to the composer rather than replacing a tray source", request.source)
        assertNull(item.file)
        assertEquals("Peer", item.sender)
    }

    @Test fun trayRequestPinsSourceAndResolvedFileTogetherAcrossPaging() {
        val original = File("original.jpg")
        val preview = File("preview.jpg")
        val item = MediaViewerItem(original, "image/jpeg", "Tray", editorSource = "content://composer/photo-a")
        val request = item.editorRequest(preview)!!
        val next = item.copy(file = File("next.jpg"), id = "next", editorSource = "content://composer/photo-b")
        assertEquals(preview, request.file)
        assertEquals("content://composer/photo-a", request.source)
        assertEquals("content://composer/photo-b", next.editorRequest(next.file)!!.source)
        assertEquals(original, item.file)
    }

    @Test fun onlyResolvedImagesCanEnterThePhotoEditor() {
        val file = File("media")
        for (mime in listOf("video/mp4", "application/pdf", "audio/mp4")) {
            assertNull(MediaViewerItem(file, mime, "Media").editorRequest(file))
        }
        val photo = MediaViewerItem(file, "image/png", "Photo", editorSource = "durable")
        assertNull("An unavailable source must not reopen a stale local file", photo.editorRequest(null))
        assertEquals(file, photo.editorRequest(file)!!.file)
    }
}
