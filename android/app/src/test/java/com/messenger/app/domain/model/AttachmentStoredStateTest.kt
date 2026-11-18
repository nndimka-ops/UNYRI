package com.messenger.app.domain.model

import java.util.Locale
import kotlin.test.*

/** Regression: discardPendingUpload writes "cancelled"; opening its chat must not throw. */
class AttachmentStoredStateTest {
    @Test fun `cancelled database upload is a distinct terminal state`() {
        assertEquals(AttachmentUploadState.CANCELLED, AttachmentUploadState.fromStored("cancelled"))
        assertEquals(AttachmentUploadState.CANCELLED, AttachmentUploadState.fromStored("CANCELLED"))
        assertNotEquals(AttachmentUploadState.PENDING, AttachmentUploadState.fromStored("cancelled"))
        assertNotEquals(AttachmentUploadState.SENT, AttachmentUploadState.fromStored("cancelled"))
    }
    @Test fun `all existing upload database values preserve their semantics`() {
        val expected = listOf("pending" to AttachmentUploadState.PENDING, "encrypting" to AttachmentUploadState.ENCRYPTING,
            "uploading" to AttachmentUploadState.UPLOADING, "uploaded" to AttachmentUploadState.UPLOADED,
            "sending" to AttachmentUploadState.SENDING, "sent" to AttachmentUploadState.SENT, "failed" to AttachmentUploadState.FAILED)
        expected.forEach { (raw, state) -> assertEquals(state, AttachmentUploadState.fromStored(raw)) }
    }
    @Test fun `future malformed upload values fail closed instead of crashing or retrying`() {
        listOf("", "future_status", " sent ", "cancelled_v2").forEach { raw ->
            assertEquals(AttachmentUploadState.FAILED, AttachmentUploadState.fromStored(raw))
        }
    }
    @Test fun `all existing download values round trip and future values are failed`() {
        val values = listOf("not_downloaded" to AttachmentDownloadState.NOT_DOWNLOADED, "downloading" to AttachmentDownloadState.DOWNLOADING,
            "downloaded" to AttachmentDownloadState.DOWNLOADED, "failed" to AttachmentDownloadState.FAILED, "corrupt" to AttachmentDownloadState.CORRUPT)
        values.forEach { (raw, state) -> assertEquals(state, AttachmentDownloadState.fromStored(raw)) }
        assertEquals(AttachmentDownloadState.FAILED, AttachmentDownloadState.fromStored("unknown"))
    }
    @Test fun `media values remain typed and future media becomes a generic file`() {
        assertEquals(AttachmentMediaType.IMAGE, AttachmentMediaType.fromStored("image"))
        assertEquals(AttachmentMediaType.VOICE, AttachmentMediaType.fromStored("voice"))
        assertEquals(AttachmentMediaType.FILE, AttachmentMediaType.fromStored("file"))
        assertEquals(AttachmentMediaType.FILE, AttachmentMediaType.fromStored("future_media"))
    }
    @Test fun `stored state decoding is independent of Turkish device locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(AttachmentUploadState.SENDING, AttachmentUploadState.fromStored("SENDING"))
            assertEquals(AttachmentUploadState.FAILED, AttachmentUploadState.fromStored("FAILED"))
            assertEquals(AttachmentMediaType.IMAGE, AttachmentMediaType.fromStored("IMAGE"))
            assertEquals(AttachmentMediaType.VOICE, AttachmentMediaType.fromStored("VOICE"))
        } finally { Locale.setDefault(before) }
    }
}
