package com.messenger.app.data.websocket

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDraftScheduleTest {
    @Test fun `trailing clear waits only for the remaining throttle window`() {
        assertEquals(0L, LiveDraftSchedule.sendDelayMillis(10, 0))
        assertEquals(500L, LiveDraftSchedule.sendDelayMillis(2_000, 1_500))
        assertEquals(1L, LiveDraftSchedule.sendDelayMillis(2_499, 1_500))
        assertEquals(0L, LiveDraftSchedule.sendDelayMillis(2_500, 1_500))
        assertEquals(0L, LiveDraftSchedule.sendDelayMillis(5_000, 1_500))
    }
    @Test fun `disabled empty hidden and disconnected drafts never refresh`() {
        assertEquals(5_000L, LiveDraftSchedule.refreshDelay(true, true, true, "typing"))
        assertTrue(LiveDraftSchedule.refreshDelay(true, true, true, "typing")!! < LiveDraftSchedule.expiryDelay("typing")!!)
        assertNull(LiveDraftSchedule.refreshDelay(false, true, true, "typing"))
        assertNull(LiveDraftSchedule.refreshDelay(true, false, true, "typing"))
        assertNull(LiveDraftSchedule.refreshDelay(true, true, false, "typing"))
        assertNull(LiveDraftSchedule.refreshDelay(true, true, true, ""))
        assertNull(LiveDraftSchedule.expiryDelay(""))
    }
    @Test fun `expiry is renewed once and clearing cancels all future publications`() = runTest {
        val seen = mutableListOf<String>()
        val expiry = DraftExpiry(backgroundScope) { seen += it }
        expiry.update("first"); runCurrent()
        advanceTimeBy(7_000); expiry.update("second"); runCurrent()
        advanceTimeBy(1_000); runCurrent(); assertEquals(listOf("first", "second"), seen)
        advanceTimeBy(7_000); runCurrent(); assertEquals(listOf("first", "second", ""), seen)
        expiry.update("third"); expiry.update(""); runCurrent()
        val count = seen.size
        advanceTimeBy(120_000); runCurrent(); assertEquals(count, seen.size)
    }
}
