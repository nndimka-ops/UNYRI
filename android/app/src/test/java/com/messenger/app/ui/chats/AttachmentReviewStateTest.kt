package com.messenger.app.ui.chats

import org.junit.Test
import kotlin.test.*

class AttachmentReviewStateTest {
    @Test fun `direct intent cannot dispatch from picker regardless of busy caption or send-now choice`() {
        for (busy in listOf(false, true)) for (valid in listOf(false, true)) for (now in listOf(false, true))
            assertEquals(AttachmentPickAction.REVIEW, attachmentPickAction(0, 2, false, busy, valid, true, now))
        assertEquals(AttachmentPickAction.EDITING, attachmentPickAction(0, 2, true, false, true, true, true))
        assertEquals(AttachmentPickAction.SEND_NOW, attachmentPickAction(0, 2, false, false, true, false, true))
        assertEquals(AttachmentPickAction.REVIEW, attachmentPickAction(0, 2, false, true, true, false, true))
    }

    @Test fun `shortcut overrides selection quality without mutating standard or high defaults`() {
        for (preferred in listOf("image", "image_high")) {
            var saved = preferred
            val picker = GalleryPickerMode(saved).asFiles()
            assertEquals("file", picker.quality)
            assertEquals(preferred, saved)
            assertEquals(preferred, GalleryPickerMode(saved).quality) // close without Add, then reopen
            val chosen = picker.choose("image_high") { saved = it }
            assertEquals("image_high", chosen.quality)
            assertFalse(chosen.mediaAsFiles)
            assertEquals("image_high", GalleryPickerMode(saved).quality)
        }
    }

    @Test fun `original shortcut mode is captured with added sources independently of future sessions`() {
        val selectedMode = GalleryPickerMode("image").asFiles().quality
        val originalKinds = listOf(false, true).map { galleryQueueKind(selectedMode, it) }
        assertEquals(listOf("file", "file"), originalKinds)
        assertEquals("image", GalleryPickerMode("image").quality)
        assertEquals(listOf("file", "file"), originalKinds)
    }
}
