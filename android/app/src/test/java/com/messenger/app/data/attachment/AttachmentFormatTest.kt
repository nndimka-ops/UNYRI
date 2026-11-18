package com.messenger.app.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentFormatTest {

    @Test
    fun `file size formats bytes through gigabytes`() {
        assertEquals("0 B", AttachmentFormat.fileSize(0))
        assertEquals("512 B", AttachmentFormat.fileSize(512))
        assertEquals("1 KB", AttachmentFormat.fileSize(1024))
        assertEquals("12 KB", AttachmentFormat.fileSize(12 * 1024))
        assertEquals("3.5 MB", AttachmentFormat.fileSize(3_670_016))
        assertEquals("1.2 GB", AttachmentFormat.fileSize(1_288_490_188))
        assertEquals("0 B", AttachmentFormat.fileSize(-1))
    }

    @Test
    fun `image and file limits are enforced by the helper`() {
        assertTrue(AttachmentFormat.isImageWithinLimit(25L * 1024 * 1024))
        assertFalse(AttachmentFormat.isImageWithinLimit(25L * 1024 * 1024 + 1))
        assertTrue(AttachmentFormat.isFileWithinLimit(100L * 1024 * 1024))
        assertFalse(AttachmentFormat.isFileWithinLimit(100L * 1024 * 1024 + 1))
    }

    @Test
    fun `selection cap is ten items`() {
        assertEquals(10, AttachmentFormat.MAX_ITEMS)
    }
}
