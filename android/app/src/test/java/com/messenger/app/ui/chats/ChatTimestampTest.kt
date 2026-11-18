package com.messenger.app.ui.chats

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTimestampTest {
    @Test fun `today uses local clock including UTC day boundary`() {
        assertEquals("01:30", chatTimestamp("2026-09-10T22:30:00Z", Instant.parse("2026-09-10T23:00:00Z"), ZoneId.of("Europe/Bucharest"), Locale.UK))
    }
    @Test fun `older dates and malformed timestamps do not masquerade as today`() {
        assertEquals("09/09/2026", chatTimestamp("2026-09-09T12:00:00Z", Instant.parse("2026-09-11T12:00:00Z"), ZoneId.of("UTC"), Locale.UK))
        assertNull(chatTimestamp("invalid", Instant.EPOCH, ZoneId.of("UTC"), Locale.UK))
    }
}
