package com.messenger.app.ui.calls

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuxiliaryListDateTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val zone = ZoneId.of("Europe/Bucharest")
    private fun label(value: String) = auxiliaryListDate(value, now, zone, Locale.ENGLISH, "Yesterday")

    @Test fun usesLocalCalendarBoundaryForYesterday() {
        assertEquals("Yesterday", label("2026-11-12T21:59:00Z"))
        assertFalse(label("2026-11-12T22:01:00Z") == "Yesterday")
    }

    @Test fun choosesWeekdayRecentDateAndPreviousYear() {
        assertEquals("Wed", label("2026-11-11T10:00:00Z"))
        assertEquals("1 Nov", label("2026-11-01T10:00:00Z"))
        assertEquals("01.11.25", label("2025-11-01T10:00:00Z"))
        assertEquals("", label("broken"))
    }
}
