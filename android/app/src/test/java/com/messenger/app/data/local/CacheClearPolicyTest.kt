package com.messenger.app.data.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheClearPolicyTest {

    @Test
    fun pendingUploadSourceIsProtected() {
        assertTrue(CacheClearPolicy.isProtectedUploadSource("uploading"))
        assertTrue(CacheClearPolicy.isProtectedUploadSource("failed"))
        listOf("pending", "encrypting", "sending", "cancelled", "future").forEach { assertTrue(CacheClearPolicy.isProtectedUploadSource(it)) }
    }

    @Test
    fun completedUploadIsNotProtected() {
        assertFalse(CacheClearPolicy.isProtectedUploadSource("uploaded"))
    }

    @Test
    fun receivedAttachmentIsNotProtected() {
        // Android marks received attachments with uploadState = "sent".
        assertFalse(CacheClearPolicy.isProtectedUploadSource("sent"))
    }
}
