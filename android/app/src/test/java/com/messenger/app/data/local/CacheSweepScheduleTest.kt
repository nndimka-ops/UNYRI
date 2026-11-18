package com.messenger.app.data.local

import kotlin.test.*

class CacheSweepScheduleTest {
    @Test fun `disabled retention empty cache and size-only policies own no timer`() {
        assertNull(CacheSweepSchedule.delayMillis(0, listOf(1), 100))
        assertNull(CacheSweepSchedule.delayMillis(1, emptyList(), 100))
    }
    @Test fun `next file expiry determines the deadline and protected old files retry slowly`() {
        assertEquals(86_399_900L, CacheSweepSchedule.delayMillis(1, listOf(500, 0), 100))
        assertEquals(3_600_000L, CacheSweepSchedule.delayMillis(1, listOf(0), 86_400_000))
    }
}
