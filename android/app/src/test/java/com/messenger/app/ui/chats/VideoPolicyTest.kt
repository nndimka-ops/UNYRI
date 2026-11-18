package com.messenger.app.ui.chats

import kotlin.test.*

class VideoPolicyTest {
    @Test fun relativeFineScrubbingRebasesAndClamps() {
        assertEquals(listOf(1f, .5f, .25f, .125f), listOf(0f, 50f, -100f, 150f).map(VideoPolicy::scrubFactor))
        assertEquals(1f, VideoPolicy.scrubFactor(Float.NaN))
        assertEquals(25_000L, VideoPolicy.scrub(10_000, 50f, 100f, 60_000, .5f))
        assertEquals(60_000L, VideoPolicy.scrub(50_000, 1000f, 100f, 60_000, 1f))
        assertEquals(0L, VideoPolicy.scrub(1000, -1000f, 100f, 60_000, 1f))
        assertFalse(VideoPolicy.tapSeeks(1000, 3000, 11f)); assertFalse(VideoPolicy.tapSeeks(1000, 1033, 12f))
        assertTrue(VideoPolicy.tapSeeks(1000, 1034, 12f))
    }
    @Test fun remainingPlusElapsedEqualsRoundedTotal() {
        for (duration in listOf(0L, 1499, 1500, 9999, 60_500)) for (elapsed in listOf(0L, 100, 1200, 3000)) {
            val bounded = elapsed.coerceAtMost(VideoPolicy.total(duration))
            assertEquals(VideoPolicy.total(duration), bounded / 1000 * 1000 + VideoPolicy.remaining(bounded, duration))
        }
    }
    @Test fun restoreLoopsSpeedAndGestureThresholdsMatchIOS() {
        assertEquals(0L, VideoPolicy.restore(2999, 10_000)); assertEquals(0L, VideoPolicy.restore(9000, 10_000)); assertEquals(8000L, VideoPolicy.restore(8000, 10_000))
        assertTrue(VideoPolicy.loops(30_000, false)); assertFalse(VideoPolicy.loops(30_001, false)); assertFalse(VideoPolicy.loops(1000, true))
        assertEquals(1f, VideoPolicy.speed(2f, 29_999)); assertEquals(2f, VideoPolicy.speed(2f, 30_000)); assertEquals(1f, VideoPolicy.speed(3f, 30_000))
        assertEquals(listOf(-1, 0, 0, 1), listOf(.29f, .3f, .7f, .71f).map(VideoPolicy::zone))
        assertEquals(1.5f, VideoPolicy.holdRate(1f, -40f)); assertEquals(2f, VideoPolicy.holdRate(1.5f, -40f)); assertEquals(3f, VideoPolicy.holdRate(1f, 40f)); assertEquals(2f, VideoPolicy.holdRate(1f, 40f, false))
        assertNull(VideoPolicy.pinchFill(1.08f)); assertEquals(true, VideoPolicy.pinchFill(1.081f)); assertEquals(false, VideoPolicy.pinchFill(.919f))
        assertFalse(VideoPolicy.offersFill(1f, 1f)); assertTrue(VideoPolicy.offersFill(2f, 1f))
        assertEquals(60, VideoPolicy.fps(1000f, 1000)); assertEquals(4, VideoPolicy.fps(1000f, 600_000))
    }
    @Test fun chaseKeepsOneSeekInFlightAndOnlyNewestWaiter() {
        val chase = VideoSeekChaser()
        assertEquals(1L, chase.offer(1)); assertNull(chase.offer(2)); assertNull(chase.offer(3))
        assertEquals(3L, chase.completed()); assertNull(chase.completed()); assertEquals(4L, chase.offer(4))
        chase.reset(); assertNull(chase.completed())
    }
    @Test fun liveGatesNeverLatchAndNotesNeverAutoplay() {
        assertTrue(mediaWorkAllowed(true, false, true))
        assertFalse(mediaWorkAllowed(false, false, true)); assertFalse(mediaWorkAllowed(true, true, true)); assertFalse(mediaWorkAllowed(true, false, false))
        assertTrue(noteMayPlay(true, false, false, false))
        assertFalse(noteMayPlay(false, false, false, false)); assertFalse(noteMayPlay(true, true, false, false)); assertFalse(noteMayPlay(true, false, true, false)); assertFalse(noteMayPlay(true, false, false, true))
    }
    @Test fun compactCountGeometryAndGalleryAllowOnlyVisualOriginals() {
        assertEquals(20, mediaReactionHeight(17f, 1.2f, 1f)); assertEquals(18, mediaReactionHeight(10f, 1f, 1f)); assertEquals(30, mediaReactionHeight(30f, 1.3f, 2f))
        assertNull(mediaReactionCount(1)); assertEquals("2", mediaReactionCount(2)); assertEquals("100", mediaReactionCount(100))
        assertTrue(mediaCanSaveToGallery("image/jpeg")); assertTrue(mediaCanSaveToGallery("video/mp4")); assertFalse(mediaCanSaveToGallery("application/pdf"))
    }
}
