package com.messenger.app.ui.chats

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTimestampTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val locale = Locale.forLanguageTag("ru")
    private fun label(value: String) = chatTimestamp(value, now, zone, locale, "Вчера")

    @Test fun `today uses local clock including UTC day boundary`() {
        assertEquals("01:30", chatTimestamp("2026-09-10T22:30:00Z", Instant.parse("2026-09-10T23:00:00Z"), ZoneId.of("Europe/Bucharest"), Locale.UK, "Yesterday"))
    }
    @Test fun `chat list shares the calls calendar policy`() {
        assertEquals("10:15", label("2026-11-19T10:15:00Z"))
        assertEquals("Вчера", label("2026-11-18T10:15:00Z"))
        assertEquals("вт", label("2026-11-17T10:15:00Z"))
        assertEquals("6 нояб.", label("2026-11-06T10:15:00Z"))
        assertEquals("06.11.25", label("2025-11-06T10:15:00Z"))
        assertNull(label("invalid"))
    }
    @Test fun `year boundary prefers localized yesterday before older year date`() {
        assertEquals("Yesterday", chatTimestamp("2025-12-31T23:00:00Z", Instant.parse("2026-01-01T10:00:00Z"), zone, Locale.UK, "Yesterday"))
    }
    @Test fun `call bubbles only display local time`() {
        assertEquals("23:24", callEventTime("2026-11-12T21:24:00Z", ZoneId.of("Europe/Bucharest"), locale))
        assertEquals("", callEventTime("bad", zone, locale))
    }
}
