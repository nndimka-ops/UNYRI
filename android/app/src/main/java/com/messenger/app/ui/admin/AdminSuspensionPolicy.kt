package com.messenger.app.ui.admin

import com.messenger.app.R
import java.time.Instant
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------------------
// Admin UX Pack 1 - restriction duration presets.
//
// Pure Kotlin. Replaces the old single "for one hour" checkbox with explicit
// presets and maps them to the ISO-8601 `until` value the server expects.
// `null` means an indefinite (permanent) restriction.
// ---------------------------------------------------------------------------

enum class AdminSuspensionDuration(val hours: Long?, val label: Int) {
    OneHour(1, R.string.admin_duration_1h),
    OneDay(24, R.string.admin_duration_24h),
    SevenDays(24 * 7, R.string.admin_duration_7d),
    ThirtyDays(24 * 30, R.string.admin_duration_30d),
    Permanent(null, R.string.admin_duration_permanent),
    Custom(null, R.string.admin_duration_custom),
}

object AdminSuspensionPolicy {
    val presets: List<AdminSuspensionDuration> = AdminSuspensionDuration.entries.toList()

    /** A custom restriction is limited to a year; anything else is a typo. */
    const val MAX_CUSTOM_HOURS: Long = 24L * 365L

    fun validCustomHours(value: String): Boolean =
        value.trim().toLongOrNull()?.let { it in 1..MAX_CUSTOM_HOURS } == true

    /**
     * ISO-8601 instant for the `until` field, or null for an indefinite
     * restriction. Returns null for an invalid custom value so callers can
     * block submission through [validCustomHours].
     */
    fun until(duration: AdminSuspensionDuration, customHours: Long?, now: Instant): String? {
        val hours = when (duration) {
            AdminSuspensionDuration.Custom -> customHours
            else -> duration.hours
        } ?: return null
        return now.plus(hours, ChronoUnit.HOURS).toString()
    }

    /** Destructive actions always require an explicit confirmation step. */
    fun requiresConfirmation(duration: AdminSuspensionDuration): Boolean = true
}
