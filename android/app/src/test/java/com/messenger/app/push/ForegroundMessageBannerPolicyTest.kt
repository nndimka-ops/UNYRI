package com.messenger.app.push

import com.messenger.app.domain.model.LastMessagePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure policy contracts for the foreground message banner (Runtime Correction D).
 *  No Android framework types — deterministic on the JVM. */
class ForegroundMessageBannerPolicyTest {
    @Test fun `video MIME wins over generic file kind`() {
        assertEquals(LastMessagePreview.Video, ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, null, "file", "9534E325-FA88.mp4", "video/mp4"))
    }


    @Test
    fun `foreground other-chat incoming shows banner`() {
        assertTrue(ForegroundMessageBannerPolicy.shouldShow(true, null, "c", isOwnMessage = false, activeCall = false))
    }

    @Test
    fun `same chat suppresses banner`() {
        assertFalse(ForegroundMessageBannerPolicy.shouldShow(true, "c", "c", isOwnMessage = false, activeCall = false))
    }

    @Test
    fun `own message suppresses banner`() {
        assertFalse(ForegroundMessageBannerPolicy.shouldShow(true, null, "c", isOwnMessage = true, activeCall = false))
    }

    @Test
    fun `expanded call covers the chat so its message still gets a banner`() {
        assertTrue(ForegroundMessageBannerPolicy.shouldShow(true, "c", "c", isOwnMessage = false, activeCall = true))
    }

    @Test
    fun `background suppresses banner`() {
        assertFalse(ForegroundMessageBannerPolicy.shouldShow(false, null, "c", isOwnMessage = false, activeCall = false))
    }

    @Test
    fun `text preview`() {
        assertEquals(
            LastMessagePreview.Text("Hello"),
            ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, "Hello", null, null),
        )
    }

    @Test
    fun `voice preview`() {
        assertEquals(
            LastMessagePreview.Voice,
            ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, "[Voice message]", "voice", null),
        )
    }

    @Test
    fun `photo preview`() {
        assertEquals(
            LastMessagePreview.Photo,
            ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, "[Photo]", "image", null),
        )
    }

    @Test
    fun `file preview with filename`() {
        assertEquals(
            LastMessagePreview.File("a.pdf"),
            ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, "[File] a.pdf", "file", "a.pdf"),
        )
    }

    @Test
    fun `deleted preview`() {
        assertEquals(
            LastMessagePreview.Deleted,
            ForegroundMessageBannerPolicy.previewFor("content", "delivered", true, "", null, null),
        )
    }

    @Test
    fun `lifecycle event has no preview`() {
        assertNull(ForegroundMessageBannerPolicy.previewFor("edit", "delivered", false, "x", null, null))
    }

    @Test
    fun `decrypt failed has no preview`() {
        assertNull(ForegroundMessageBannerPolicy.previewFor("content", "decrypt_failed", false, "[Unable to decrypt message]", null, null))
    }
}
