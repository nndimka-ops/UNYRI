package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class StaticPosterAdmissionTest {
    @Test fun visibleStaticPostersRemainAdmittedWhenEmojiOrMenusPauseActiveMedia() {
        assertFalse(mediaWorkAllowed(started = true, covered = true, visible = true))
        assertTrue(mediaPreviewWorkAllowed(started = true, contentAvailable = true, visible = true))
        assertFalse(mediaPreviewWorkAllowed(started = false, contentAvailable = true, visible = true))
        assertFalse(mediaPreviewWorkAllowed(started = true, contentAvailable = false, visible = true))
        assertFalse(mediaPreviewWorkAllowed(started = true, contentAvailable = true, visible = false))
    }
}
