package com.messenger.app.data.local

import kotlin.test.*

class VideoFrameFailurePolicyTest {
    @Test fun transientFailureWaitsTenSecondsUntilNaturalStartAndUsesOneKey() {
        val p = VideoFrameFailurePolicy(); p.lifecycle(true)
        val key = videoFrameKey("/a/" + "long".repeat(200), 768)
        p.failed(key, FrameFailure.UNKNOWN, true, p.attempt(), 1, 1, 50)
        assertTrue(p.holds(key, 10_049)); assertFalse(p.holds(key, 10_050))
        assertEquals(key, videoFrameKey("/a/" + "long".repeat(200), 768))
        assertNotEquals(key, videoFrameKey("/a/" + "long".repeat(200), 480, true))
    }
    @Test fun permanentFailureHoldsOnlyExistingFileUntilActivation() {
        val p = VideoFrameFailurePolicy(); p.lifecycle(true)
        p.failed("key", FrameFailure.UNREADABLE, true, p.attempt(), 1, 1, 0)
        assertTrue(p.holds("key", Long.MAX_VALUE - 1))
        p.lifecycle(false); assertTrue(p.lifecycle(true)); assertFalse(p.holds("key", 1))
        p.failed("missing", FrameFailure.UNREADABLE, false, p.attempt(), 1, 1, 0)
        assertFalse(p.holds("missing", 10_000))
    }
    @Test fun interruptedPosterAndCodecFailuresOnlyArmResumeAndNeverMemoize() {
        for (kind in listOf(FrameFailure.INTERRUPTED, FrameFailure.POSTER)) {
            val p = VideoFrameFailurePolicy(); p.lifecycle(true)
            assertEquals(FrameRetry.WAIT, p.failed("key", kind, true, p.attempt(), 1, 1, 0))
            assertFalse(p.holds("key", 0)); p.lifecycle(false); assertTrue(p.lifecycle(true)); assertFalse(p.lifecycle(true))
        }
    }
    @Test fun pauseCounterJudgesWholeAttemptAndRetriesImmediatelyAfterEarlierResume() {
        val p = VideoFrameFailurePolicy(); p.lifecycle(true); val token = p.attempt()
        p.lifecycle(false); p.lifecycle(true)
        assertEquals(FrameRetry.NOW, p.failed("key", FrameFailure.UNREADABLE, true, token, 1, 1, 0))
        assertFalse(p.holds("key", 0))
        p.lifecycle(false)
        assertEquals(FrameRetry.WAIT, p.failed("key", FrameFailure.UNKNOWN, true, null, 1, 1, 0))
        assertTrue(p.lifecycle(true))
    }
    @Test fun cacheGenerationDropsLateFailuresAndClearResetsBoundedMemo() {
        val p = VideoFrameFailurePolicy(4); p.lifecycle(true)
        p.failed("old", FrameFailure.UNREADABLE, true, p.attempt(), 1, 2, 0)
        assertFalse(p.holds("old", 0)); assertFalse(p.lifecycle(true))
        repeat(100) { p.failed("$it", FrameFailure.UNKNOWN, true, p.attempt(), 2, 2, 0) }
        assertEquals(4, p.size()); p.clear(); assertEquals(0, p.size())
        p.lifecycle(false); assertFalse(p.lifecycle(true))
    }
}
