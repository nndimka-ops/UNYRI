package com.messenger.app.ui.chats

import org.junit.Test
import kotlin.test.assertEquals

class GallerySendModeTest {
    @Test fun `explicit saved gallery choice wins over a stale legacy preference`() {
        for (saved in listOf("image", "image_high", "file")) {
            for (legacy in listOf(null, "image", "image_high", "file"))
                assertEquals(saved, galleryPreferredSendQuality(saved, legacy))
        }
    }
    @Test fun `missing or invalid new preference migrates quality but not legacy documents`() {
        for (saved in listOf(null, "", "obsolete")) {
            assertEquals("image_high", galleryPreferredSendQuality(saved, "image_high"))
            for (legacy in listOf(null, "file", "obsolete", "image"))
                assertEquals("image", galleryPreferredSendQuality(saved, legacy))
        }
    }
    @Test fun `legacy file preference cannot silently split gallery albums`() {
        assertEquals("image", gallerySendQuality("file", false))
        assertEquals("image", gallerySendQuality(null, false))
        assertEquals("image", gallerySendQuality("obsolete", false))
        assertEquals("image_high", gallerySendQuality("image_high", false))
    }
    @Test fun `originals require explicit choice and remain unconverted`() {
        for (preferred in listOf(null, "image", "image_high", "file"))
            assertEquals("file", gallerySendQuality(preferred, true))
        assertEquals("file", galleryQueueKind("file", true))
        assertEquals("file", galleryQueueKind("file", false))
    }
    @Test fun `mixed gallery batches select the correct preparation pipeline`() {
        assertEquals("image", galleryQueueKind("image", false))
        assertEquals("video", galleryQueueKind("image", true))
        assertEquals("image_high", galleryQueueKind("image_high", false))
        assertEquals("video_high", galleryQueueKind("image_high", true))
    }
}
