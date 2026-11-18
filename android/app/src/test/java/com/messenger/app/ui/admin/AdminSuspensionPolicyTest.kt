package com.messenger.app.ui.admin

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADMIN UX PACK 1 - restriction duration presets.
class AdminSuspensionPolicyTest {

    private val now: Instant = Instant.parse("2026-09-25T10:00:00Z")

    @Test
    fun `presets offer the requested durations`() {
        assertEquals(
            listOf("1h", "24h", "7d", "30d", "indefinite", "custom"),
            AdminSuspensionPolicy.presets.map {
                when (it.hours) {
                    null -> if (it == AdminSuspensionDuration.Permanent) "indefinite" else "custom"
                    1L -> "1h"
                    24L -> "24h"
                    24L * 7 -> "7d"
                    24L * 30 -> "30d"
                    else -> "?"
                }
            },
        )
    }

    @Test
    fun `a one hour restriction ends one hour later`() {
        assertEquals("2026-09-25T11:00:00Z", AdminSuspensionPolicy.until(AdminSuspensionDuration.OneHour, null, now))
    }

    @Test
    fun `a thirty day restriction ends thirty days later`() {
        assertEquals("2026-10-25T10:00:00Z", AdminSuspensionPolicy.until(AdminSuspensionDuration.ThirtyDays, null, now))
    }

    @Test
    fun `a permanent restriction has no expiry`() {
        assertNull(AdminSuspensionPolicy.until(AdminSuspensionDuration.Permanent, null, now))
        assertNull(AdminSuspensionPolicy.until(AdminSuspensionDuration.Permanent, 500L, now))
    }

    @Test
    fun `a custom duration uses the supplied hour count`() {
        assertEquals("2026-09-27T10:00:00Z", AdminSuspensionPolicy.until(AdminSuspensionDuration.Custom, 48L, now))
    }

    @Test
    fun `a custom duration without a value has no expiry`() {
        assertNull(AdminSuspensionPolicy.until(AdminSuspensionDuration.Custom, null, now))
    }

    @Test
    fun `custom hours must be a positive whole number within a year`() {
        assertTrue(AdminSuspensionPolicy.validCustomHours("1"))
        assertTrue(AdminSuspensionPolicy.validCustomHours(" 72 "))
        assertTrue(AdminSuspensionPolicy.validCustomHours(AdminSuspensionPolicy.MAX_CUSTOM_HOURS.toString()))
        assertFalse(AdminSuspensionPolicy.validCustomHours("0"))
        assertFalse(AdminSuspensionPolicy.validCustomHours("-5"))
        assertFalse(AdminSuspensionPolicy.validCustomHours("12.5"))
        assertFalse(AdminSuspensionPolicy.validCustomHours("week"))
        assertFalse(AdminSuspensionPolicy.validCustomHours(""))
        assertFalse(AdminSuspensionPolicy.validCustomHours((AdminSuspensionPolicy.MAX_CUSTOM_HOURS + 1).toString()))
    }

    @Test
    fun `restricting an account always requires a confirmation step`() {
        AdminSuspensionPolicy.presets.forEach {
            assertTrue(AdminSuspensionPolicy.requiresConfirmation(it))
        }
    }
}
