package com.messenger.app.ui.call

import com.messenger.app.data.call.AudioRouteKind.BLUETOOTH
import com.messenger.app.data.call.AudioRouteKind.EARPIECE
import com.messenger.app.data.call.AudioRouteKind.SPEAKER
import com.messenger.app.data.call.AudioRouteKind.WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CallAudioOutputPresentationTest {
    private fun p(current: com.messenger.app.data.call.AudioRouteKind, video: Boolean, vararg available: com.messenger.app.data.call.AudioRouteKind, earpiece: Boolean = true) =
        CallAudioOutputPresentation.present(current, video, available.toList(), earpiece)

    @Test fun withoutHeadphonesTheButtonNeverShowsHeadphones() {
        for (current in listOf(EARPIECE, SPEAKER)) for (video in listOf(false, true)) for (routes in listOf(listOf(EARPIECE, SPEAKER), emptyList())) {
            val glyph = CallAudioOutputPresentation.present(current, video, routes, true).glyph
            assertNotEquals(CallOutputGlyph.HEADPHONES, glyph)
            assertNotEquals(CallOutputGlyph.BLUETOOTH, glyph)
            assertNotEquals(CallOutputGlyph.PHONE, glyph)
        }
    }

    @Test fun audioCallWithoutAccessoryIsASpeakerToggle() {
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, false, SPEAKER), p(EARPIECE, false, EARPIECE, SPEAKER))
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, true, EARPIECE), p(SPEAKER, false, EARPIECE, SPEAKER))
        // Routes still being discovered behave the same (no dimmed button).
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, false, SPEAKER), p(EARPIECE, false))
    }

    @Test fun videoCallWithoutAccessoryTogglesAndHighlightsTheReceiver() {
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, false, EARPIECE), p(SPEAKER, true, EARPIECE, SPEAKER))
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER_OFF, true, SPEAKER), p(EARPIECE, true, EARPIECE, SPEAKER))
    }

    @Test fun tabletWithoutReceiverHasNothingToToggle() {
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, false, null), p(SPEAKER, false, SPEAKER, earpiece = false))
    }

    @Test fun accessoriesShowTheCurrentDeviceAndOpenThePicker() {
        assertEquals(CallOutputPresentation(CallOutputGlyph.HEADPHONES, false, null), p(WIRED_HEADSET, false, EARPIECE, SPEAKER, WIRED_HEADSET))
        assertEquals(CallOutputPresentation(CallOutputGlyph.BLUETOOTH, false, null), p(BLUETOOTH, true, EARPIECE, SPEAKER, BLUETOOTH))
        assertEquals(CallOutputPresentation(CallOutputGlyph.PHONE, false, null), p(EARPIECE, false, EARPIECE, SPEAKER, BLUETOOTH))
        assertEquals(CallOutputPresentation(CallOutputGlyph.PHONE, true, null), p(EARPIECE, true, EARPIECE, SPEAKER, BLUETOOTH))
        assertEquals(CallOutputPresentation(CallOutputGlyph.SPEAKER, true, null), p(SPEAKER, false, EARPIECE, SPEAKER, BLUETOOTH))
        assertFalse(p(BLUETOOTH, false, BLUETOOTH, SPEAKER).highlighted)
    }
}
