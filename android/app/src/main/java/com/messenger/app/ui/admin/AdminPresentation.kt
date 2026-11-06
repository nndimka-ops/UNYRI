package com.messenger.app.ui.admin

import androidx.annotation.StringRes
import com.messenger.app.R

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - presentation mapping.
//
// Pure Kotlin. Backend values (statuses, actions, HTTP codes) are translated to
// localized, human-readable labels. Raw backend tokens, JSON and exception
// messages must never reach the UI: everything goes through here.
// ---------------------------------------------------------------------------

object AdminPresentation {

    /** Moderation status of a user report. */
    enum class ReportStatus(val api: String, @StringRes val label: Int, val reviewing: Boolean) {
        Open("open", R.string.manage_open, false),
        InProgress("in_progress", R.string.manage_progress, true),
        Resolved("resolved", R.string.manage_resolved, false),
        Dismissed("dismissed", R.string.manage_dismissed, false);

        val closed: Boolean get() = this == Resolved || this == Dismissed

        companion object {
            fun from(value: String?): ReportStatus = entries.firstOrNull { it.api == value } ?: Open
        }
    }

    /** Filter chips above the report list. `status == null` means "all". */
    enum class ReportFilter(val status: ReportStatus?, @StringRes val label: Int) {
        All(null, R.string.admin_filter_all),
        Open(ReportStatus.Open, R.string.manage_open),
        InProgress(ReportStatus.InProgress, R.string.manage_progress),
        Resolved(ReportStatus.Resolved, R.string.manage_resolved),
        Dismissed(ReportStatus.Dismissed, R.string.manage_dismissed);
    }

    val filters: List<ReportFilter> = ReportFilter.entries.toList()

    /** Resolution state of a technical feedback item. */
    enum class FeedbackStatus(val api: String, @StringRes val label: Int) {
        Open("open", R.string.manage_open),
        Resolved("resolved", R.string.manage_resolved),
        Dismissed("dismissed", R.string.manage_dismissed);

        companion object {
            fun from(value: String?): FeedbackStatus = entries.firstOrNull { it.api == value } ?: Open
        }
    }

    enum class StatusFilter(val status: String?, @StringRes val label: Int) {
        All(null, R.string.admin_filter_all),
        Open(FeedbackStatus.Open.api, R.string.manage_open),
        Resolved(FeedbackStatus.Resolved.api, R.string.manage_resolved),
        Dismissed(FeedbackStatus.Dismissed.api, R.string.manage_dismissed);
    }

    val statusFilters: List<StatusFilter> = StatusFilter.entries.toList()

    /** Feedback platform, localized. */
    enum class Platform(val api: String, @StringRes val label: Int) {
        All("", R.string.admin_filter_all),
        Android("android", R.string.admin_platform_android),
        Ios("ios", R.string.admin_platform_ios);
    }

    val platforms: List<Platform> = Platform.entries.toList()

    /** Account-level moderation state. */
    enum class AccountStatus(val api: String, @StringRes val label: Int) {
        Active("active", R.string.admin_status_active),
        Suspended("suspended", R.string.admin_status_suspended);

        companion object {
            fun from(value: String?): AccountStatus = entries.firstOrNull { it.api == value } ?: Active
        }
    }

    enum class AccountFilter(val status: String?, @StringRes val label: Int) {
        All(null, R.string.admin_filter_all),
        Active(AccountStatus.Active.api, R.string.admin_status_active),
        Suspended(AccountStatus.Suspended.api, R.string.admin_status_suspended);
    }

    val accountFilters: List<AccountFilter> = AccountFilter.entries.toList()

    /**
     * Semantic moderation events. The server maps raw audit actions to these
     * values, so both clients render an identical event stream. `Other` exists so
     * an unmapped action shows an explicit "unrecognised" marker instead of a raw
     * machine token.
     */
    enum class TimelineKind(val api: String, @StringRes val label: Int) {
        ReportReceived("report_received", R.string.admin_event_report_received),
        ReportOpen("report_open", R.string.admin_event_report_open),
        ReportStarted("report_started", R.string.admin_event_report_started),
        ReportResolved("report_resolved", R.string.admin_event_report_resolved),
        ReportDismissed("report_dismissed", R.string.admin_event_report_dismissed),
        AccountSuspended("account_suspended", R.string.admin_event_account_suspended),
        AccountRestored("account_restored", R.string.admin_event_account_restored),
        DeviceSuspended("device_suspended", R.string.admin_event_device_suspended),
        DeviceRestored("device_restored", R.string.admin_event_device_restored),
        NoteAdded("note_added", R.string.admin_event_note_added),
        Other("other", R.string.admin_event_other);

        companion object {
            fun from(value: String?): TimelineKind = entries.firstOrNull { it.api == value } ?: Other
        }
    }

    /** Device-level restriction state, derived only from explicit server fields. */
    enum class DeviceState(@StringRes val label: Int) {
        Active(R.string.admin_device_active),
        Restricted(R.string.admin_device_restricted),
        Revoked(R.string.admin_device_revoked);
    }

    /**
     * Localized failure kinds. The raw status code and any backend payload stay
     * out of the UI; only these messages are rendered.
     */
    enum class Failure(@StringRes val message: Int) {
        Network(R.string.admin_network_error),
        Session(R.string.admin_auth_rejected),
        RateLimited(R.string.admin_rate_limited),
        Server(R.string.admin_server_error),
        Failed(R.string.admin_failed),
    }

    fun reportStatus(value: String?): ReportStatus = ReportStatus.from(value)

    fun feedbackStatus(value: String?): FeedbackStatus = FeedbackStatus.from(value)

    fun accountStatus(value: String?): AccountStatus = AccountStatus.from(value)

    fun timelineKind(value: String?): TimelineKind = TimelineKind.from(value)

    fun deviceState(revoked: Boolean, restricted: Boolean): DeviceState = when {
        restricted -> DeviceState.Restricted
        revoked -> DeviceState.Revoked
        else -> DeviceState.Active
    }

    /**
     * Client-side guard for the report filter chips (the server filters too).
     * Internal because the typed row DTO is internal.
     */
    internal fun matches(report: AdminReportDto, filter: ReportFilter): Boolean =
        filter.status == null || reportStatus(report.status) == filter.status

    fun failure(status: Int?, network: Boolean): Failure = when {
        network -> Failure.Network
        status == 401 -> Failure.Session
        status == 429 -> Failure.RateLimited
        status != null && status >= 500 -> Failure.Server
        else -> Failure.Failed
    }

    /**
     * Human-readable label for an audit action, or null when the action is
     * unknown. Unknown values are shown as an explicit marker by the caller
     * instead of a raw machine token.
     */
    @StringRes
    fun auditAction(action: String?): Int? = when (action) {
        "suspend" -> R.string.admin_action_suspend
        "unsuspend" -> R.string.admin_action_unsuspend
        "suspend_with_installations" -> R.string.admin_action_suspend_installations
        "unsuspend_with_installations" -> R.string.admin_action_unsuspend_installations
        "device_suspend" -> R.string.admin_action_device_suspend
        "device_unsuspend" -> R.string.admin_action_device_unsuspend
        "note:add" -> R.string.admin_action_note
        "rotate-signing-key" -> R.string.admin_action_rotate
        "enroll" -> R.string.admin_action_enroll
        "unlock" -> R.string.admin_action_unlock
        "server:policy" -> R.string.admin_action_policy
        else -> when {
            action == null -> null
            action.startsWith("report:") -> R.string.admin_action_report
            action.startsWith("feedback:") -> R.string.admin_action_feedback
            action.startsWith("local:") -> R.string.admin_action_local
            else -> null
        }
    }

    /** Shortens an opaque id so it can appear as secondary, non-primary text. */
    fun shortId(value: String?): String =
        value?.takeIf { it.length >= 8 }?.take(8) ?: "—"

    /** Which of the four mandatory list states the user search is in. */
    enum class SearchState { Idle, Searching, Empty, Results }

    fun searchState(query: String?, searching: Boolean, searched: Boolean, count: Int): SearchState = when {
        query.isNullOrBlank() -> SearchState.Idle
        searching -> SearchState.Searching
        searched && count == 0 -> SearchState.Empty
        else -> SearchState.Results
    }

    /** A device label built from safe, human-readable fields only. */
    fun deviceLabel(displayName: String?, platform: String?, osVersion: String?): String {
        val name = text(displayName)
        val platformName = text(platform)
        val os = text(osVersion)
        val head = name ?: platformName ?: "—"
        // Never repeat the platform: when it is already the headline, only the OS
        // version adds anything.
        val detail = when {
            platformName == null -> null
            name == null -> os
            os == null -> platformName
            else -> "$platformName $os"
        }
        return if (detail == null) head else "$head · $detail"
    }

    /** Rough byte formatting for storage counters (no BigDecimal, no locale pain). */
    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        val rounded = (size * 10).toLong() / 10.0
        val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        return "$text ${units[unit]}"
    }

    private fun text(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** Destructive administration actions always require an explicit second step. */
internal object AdminConfirmations {
    fun forReportStatus(status: String): Boolean = status == "resolved" || status == "dismissed"

    fun forCleanup(plannedCount: Int): Boolean = plannedCount > 0

    /** Closing feedback is a decision, so it is always confirmed. */
    fun forFeedbackStatus(status: String): Boolean = status != "open"

    /** Restoring an account or a device is reversible but never casual. */
    fun forRestore(blocked: Boolean): Boolean = !blocked
}

/** Two-step storage cleanup: nothing can be deleted before a preview exists. */
internal enum class CleanupStage { Idle, Previewing, Previewed, Executing }

/** Client-side guard mirroring the server's internal-note limits. */
internal object AdminNotePolicy {
    const val Max = 2000

    fun valid(text: String): Boolean = text.trim().length in 1..Max

    /** The text that would actually be sent: trimmed, never padded. */
    fun payload(text: String): String = text.trim()
}

internal object AdminCleanupPolicy {
    fun canExecute(stage: CleanupStage, plannedCount: Int, planId: String?): Boolean =
        stage == CleanupStage.Previewed && plannedCount > 0 && !planId.isNullOrBlank()
}
