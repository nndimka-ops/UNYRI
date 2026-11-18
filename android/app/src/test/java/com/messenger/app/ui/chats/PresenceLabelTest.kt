package com.messenger.app.ui.chats
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.Locale
class PresenceLabelTest {
    @org.junit.Test fun `last seen changes only at relevant calendar boundaries`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-11-13T23:00:00Z")
        org.junit.Assert.assertEquals(3_600_000L, nextPresenceLabelChange("2026-11-13T12:00:00Z", now, zone))
        org.junit.Assert.assertEquals(3_600_000L, nextPresenceLabelChange("2026-11-12T12:00:00Z", now, zone))
        val newYear = java.time.Instant.parse("2027-01-01T00:00:00Z")
        org.junit.Assert.assertEquals(java.time.Duration.between(now, newYear).toMillis(), nextPresenceLabelChange("2026-11-01T12:00:00Z", now, zone))
        org.junit.Assert.assertNull(nextPresenceLabelChange("2025-11-13T12:00:00Z", now, zone))
        org.junit.Assert.assertNull(nextPresenceLabelChange("malformed", now, zone))
    }
    @org.junit.Test fun `next label deadline follows local midnight across daylight saving`() {
        val zone = java.time.ZoneId.of("America/New_York")
        val start = java.time.LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant()
        org.junit.Assert.assertEquals(23 * 3_600_000L, nextPresenceLabelChange(start.toString(), start, zone))
    }
    @Test fun `today yesterday and prior year use local timezone`() {
        val now = Instant.parse("2026-01-01T22:30:00Z")
        val zone = ZoneId.of("Europe/Bucharest")
        assertEquals(0, presenceDate("2026-01-01T22:01:00Z", now, zone, Locale.US)!!.first)
        assertEquals(1, presenceDate("2026-01-01T20:01:00Z", now, zone, Locale.US)!!.first)
        assertTrue(presenceDate("2025-12-25T10:00:00Z", now, zone, Locale.US)!!.second.contains("2025"))
        assertNull(presenceDate("bad", now, zone, Locale.US))
    }
}
