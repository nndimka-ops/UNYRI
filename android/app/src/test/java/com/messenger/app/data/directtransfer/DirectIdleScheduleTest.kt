package com.messenger.app.data.directtransfer

import kotlin.test.*

class DirectIdleScheduleTest {
    @Test fun `dispatcher sleeps without candidates and uses earliest eligible deadline`() {
        assertNull(DirectPollSchedule.delayMillis(100, emptyList(), 0, 0))
        assertEquals(50L, DirectPollSchedule.delayMillis(100, listOf(DirectPollWake(400, true), DirectPollWake(150, false)), 0, 0))
        assertEquals(1L, DirectPollSchedule.delayMillis(100, listOf(DirectPollWake(0, true)), 0, 0))
    }
    @Test fun `full lanes wait for completion events and preserve the live reserve`() {
        val candidates = listOf(DirectPollWake(100, true), DirectPollWake(500, false))
        assertNull(DirectPollSchedule.delayMillis(100, candidates, 32, 16))
        assertEquals(400L, DirectPollSchedule.delayMillis(100, candidates, 16, 16))
        assertNull(DirectPollSchedule.delayMillis(100, candidates.take(1), 16, 16))
    }
    @Test fun `watchdog exists only for a live current account execution lease`() {
        val live = DirectExecutionSession("transfer", 3)
        assertNull(DirectExecutionWatchdog.delayMillis(null, 3))
        assertNull(DirectExecutionWatchdog.delayMillis(live, 4))
        assertNull(DirectExecutionWatchdog.delayMillis(live.copy(revoked = true), 3))
        assertEquals(1_000L, DirectExecutionWatchdog.delayMillis(live, 3))
        assertEquals(1_000L, DirectExecutionWatchdog.delayMillis(live.copy(granted = true), 3))
    }
}
