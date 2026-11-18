package com.messenger.app.ui.admin

import com.messenger.app.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADMIN PRODUCT PACK 2.1 - presentation mapping.
//
// The interface must never render a raw backend token, a raw exception message
// or a raw HTTP status. Every one of those mappings is asserted here.
class AdminPresentationTest {

    @Test
    fun `a report status maps to a localized label and an unknown value is not invented`() {
        assertEquals(AdminPresentation.ReportStatus.Open, AdminPresentation.reportStatus("open"))
        assertEquals(
            AdminPresentation.ReportStatus.InProgress,
            AdminPresentation.reportStatus("in_progress"),
        )
        assertEquals(
            AdminPresentation.ReportStatus.Resolved,
            AdminPresentation.reportStatus("resolved"),
        )
        assertEquals(
            AdminPresentation.ReportStatus.Dismissed,
            AdminPresentation.reportStatus("dismissed"),
        )
        // Unknown, null and empty all fall back to the safe default.
        assertEquals(AdminPresentation.ReportStatus.Open, AdminPresentation.reportStatus("weird"))
        assertEquals(AdminPresentation.ReportStatus.Open, AdminPresentation.reportStatus(null))
        assertEquals(AdminPresentation.ReportStatus.Open, AdminPresentation.reportStatus(""))

        assertTrue(AdminPresentation.ReportStatus.Resolved.closed)
        assertTrue(AdminPresentation.ReportStatus.Dismissed.closed)
        assertFalse(AdminPresentation.ReportStatus.Open.closed)
        assertTrue(AdminPresentation.ReportStatus.InProgress.reviewing)
    }

    @Test
    fun `feedback and account statuses have their own vocabularies`() {
        assertEquals(AdminPresentation.FeedbackStatus.Open, AdminPresentation.feedbackStatus("open"))
        assertEquals(
            AdminPresentation.FeedbackStatus.Resolved,
            AdminPresentation.feedbackStatus("resolved"),
        )
        assertEquals(AdminPresentation.FeedbackStatus.Open, AdminPresentation.feedbackStatus("unknown"))

        assertEquals(
            AdminPresentation.AccountStatus.Suspended,
            AdminPresentation.accountStatus("suspended"),
        )
        assertEquals(AdminPresentation.AccountStatus.Active, AdminPresentation.accountStatus("active"))
        assertEquals(AdminPresentation.AccountStatus.Active, AdminPresentation.accountStatus(null))
    }

    @Test
    fun `every server timeline kind has a client label and unknown kinds are marked`() {
        val kinds = mapOf(
            "report_received" to AdminPresentation.TimelineKind.ReportReceived,
            "report_open" to AdminPresentation.TimelineKind.ReportOpen,
            "report_started" to AdminPresentation.TimelineKind.ReportStarted,
            "report_resolved" to AdminPresentation.TimelineKind.ReportResolved,
            "report_dismissed" to AdminPresentation.TimelineKind.ReportDismissed,
            "account_suspended" to AdminPresentation.TimelineKind.AccountSuspended,
            "account_restored" to AdminPresentation.TimelineKind.AccountRestored,
            "device_suspended" to AdminPresentation.TimelineKind.DeviceSuspended,
            "device_restored" to AdminPresentation.TimelineKind.DeviceRestored,
            "note_added" to AdminPresentation.TimelineKind.NoteAdded,
            "other" to AdminPresentation.TimelineKind.Other,
        )
        kinds.forEach { (api, expected) ->
            assertEquals(expected, AdminPresentation.timelineKind(api), "kind=$api")
        }
        assertEquals(AdminPresentation.TimelineKind.Other, AdminPresentation.timelineKind(null))
        assertEquals(AdminPresentation.TimelineKind.Other, AdminPresentation.timelineKind("future_kind"))
        // The fallback must be visibly distinct from a real event.
        assertNotEquals(
            AdminPresentation.TimelineKind.Other.label,
            AdminPresentation.TimelineKind.NoteAdded.label,
        )
    }

    @Test
    fun `device state is derived only from the explicit server flags`() {
        assertEquals(AdminPresentation.DeviceState.Active, AdminPresentation.deviceState(false, false))
        assertEquals(
            AdminPresentation.DeviceState.Restricted,
            AdminPresentation.deviceState(false, true),
        )
        assertEquals(AdminPresentation.DeviceState.Revoked, AdminPresentation.deviceState(true, false))
        // An active restriction is more specific than a past revocation.
        assertEquals(
            AdminPresentation.DeviceState.Restricted,
            AdminPresentation.deviceState(true, true),
        )
    }

    @Test
    fun `the report filter matches only the selected status`() {
        val open = AdminReportDto(id = "r1", status = "open")
        val resolved = AdminReportDto(id = "r2", status = "resolved")

        assertTrue(AdminPresentation.matches(open, AdminPresentation.ReportFilter.All))
        assertTrue(AdminPresentation.matches(resolved, AdminPresentation.ReportFilter.All))
        assertTrue(AdminPresentation.matches(open, AdminPresentation.ReportFilter.Open))
        assertFalse(AdminPresentation.matches(resolved, AdminPresentation.ReportFilter.Open))
        assertTrue(AdminPresentation.matches(resolved, AdminPresentation.ReportFilter.Resolved))
        assertFalse(AdminPresentation.matches(open, AdminPresentation.ReportFilter.Resolved))
    }

    @Test
    fun `a failure is never the raw status code`() {
        assertEquals(AdminPresentation.Failure.Network, AdminPresentation.failure(null, true))
        assertEquals(AdminPresentation.Failure.Session, AdminPresentation.failure(401, false))
        assertEquals(AdminPresentation.Failure.RateLimited, AdminPresentation.failure(429, false))
        assertEquals(AdminPresentation.Failure.Server, AdminPresentation.failure(500, false))
        assertEquals(AdminPresentation.Failure.Server, AdminPresentation.failure(503, false))
        assertEquals(AdminPresentation.Failure.Failed, AdminPresentation.failure(400, false))
        assertEquals(AdminPresentation.Failure.Failed, AdminPresentation.failure(404, false))
        // A dropped connection outranks a status code that may have been decoded.
        assertEquals(AdminPresentation.Failure.Network, AdminPresentation.failure(500, true))
    }

    @Test
    fun `an opaque identifier is shortened and never shown as a primary label`() {
        val full = "9f1c4d3a-77aa-4c62-b1f0-0d1b2c3d4e5f"
        assertEquals("9f1c4d3a", AdminPresentation.shortId(full))
        assertEquals("—", AdminPresentation.shortId(null))
        assertEquals("—", AdminPresentation.shortId("abc"))
    }

    @Test
    fun `the user search distinguishes idle, searching, empty and results`() {
        assertEquals(
            AdminPresentation.SearchState.Idle,
            AdminPresentation.searchState(null, false, false, 0),
        )
        assertEquals(
            AdminPresentation.SearchState.Idle,
            AdminPresentation.searchState("   ", false, false, 0),
        )
        assertEquals(
            AdminPresentation.SearchState.Searching,
            AdminPresentation.searchState("anna", true, false, 0),
        )
        assertEquals(
            AdminPresentation.SearchState.Empty,
            AdminPresentation.searchState("anna", false, true, 0),
        )
        assertEquals(
            AdminPresentation.SearchState.Results,
            AdminPresentation.searchState("anna", false, true, 2),
        )
        // A typed query that has not been submitted yet is neither empty nor results.
        assertEquals(
            AdminPresentation.SearchState.Results,
            AdminPresentation.searchState("anna", false, false, 0),
        )
    }

    @Test
    fun `a device label uses only human readable fields`() {
        assertEquals("Pixel · android 16", AdminPresentation.deviceLabel("Pixel", "android", "16"))
        assertEquals("Pixel · android", AdminPresentation.deviceLabel("Pixel", "android", null))
        assertEquals("Pixel", AdminPresentation.deviceLabel("Pixel", null, null))
        assertEquals("android", AdminPresentation.deviceLabel(null, "android", null))
        assertEquals("—", AdminPresentation.deviceLabel(null, null, null))
        assertEquals("—", AdminPresentation.deviceLabel("  ", "  ", "  "))
        // A JSON null written as the string "null" is not a label.
        assertEquals("android", AdminPresentation.deviceLabel("null", "android", null))
        // The platform is never repeated when it is already the headline.
        assertEquals("android · 16", AdminPresentation.deviceLabel(null, "android", "16"))
    }

    @Test
    fun `byte counters are formatted and a missing counter is not a zero`() {
        assertEquals("—", AdminPresentation.bytes(null))
        assertEquals("—", AdminPresentation.bytes(-1))
        assertEquals("0 B", AdminPresentation.bytes(0))
        assertEquals("512 B", AdminPresentation.bytes(512))
        assertEquals("1 KB", AdminPresentation.bytes(1024))
        assertEquals("1.5 KB", AdminPresentation.bytes(1536))
        assertEquals("1 MB", AdminPresentation.bytes(1024L * 1024))
        assertEquals("1 GB", AdminPresentation.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `account labels prefer the username and fall back to a shortened id`() {
        val cache = AdminCache().apply {
            users = AdminPage(
                listOf(AdminUserRowDto(id = "u1", userId = "u1", username = "anna")),
            )
        }
        assertEquals("@anna", AdminTitles.account("u1", cache))
        // A row that was not loaded still renders, but never as an empty string.
        assertEquals("9f1c4d3a", AdminTitles.account("9f1c4d3a-77aa", cache))
        assertEquals("—", AdminTitles.account(null, cache))
        assertEquals("—", AdminTitles.account("  ", cache))

        assertEquals("@bob", AdminTitles.accountLabel("u2", "bob"))
        assertEquals("9f1c4d3a", AdminTitles.accountLabel("9f1c4d3a-77aa", null))
    }

    @Test
    fun `an account row falls back through display name, username and id`() {
        assertEquals(
            "Anna",
            AdminTitles.displayName(AdminUserRowDto(id = "u1", userId = "u1", displayName = "Anna")),
        )
        assertEquals(
            "anna",
            AdminTitles.displayName(AdminUserRowDto(id = "u1", userId = "u1", username = "anna")),
        )
        assertEquals(
            "9f1c4d3a",
            AdminTitles.displayName(AdminUserRowDto(id = "u1", userId = "9f1c4d3a-77aa")),
        )
        assertEquals("—", AdminTitles.displayName(null))

        assertEquals(
            "Anna",
            AdminTitles.profileName(
                AdminProfileDto(userId = "u1", username = "anna", displayName = "Anna"),
            ),
        )
        assertEquals("—", AdminTitles.profileName(null))
    }

    @Test
    fun `a timestamp is rendered locally and an unreadable one is still reported`() {
        assertNull(AdminTitles.meaningful(""))
        assertNull(AdminTitles.meaningful(null))
        assertNull(AdminTitles.meaningful("null"))
        assertEquals("x", AdminTitles.meaningful("x"))

        val formatted = AdminTitles.time("2026-09-25T10:11:12.000000Z")
        assertTrue(
            Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}""").matches(formatted),
            "expected a localized timestamp, got '$formatted'",
        )
        assertEquals("", AdminTitles.time(null))
        assertEquals("", AdminTitles.time("  "))
        assertEquals("not-a-date", AdminTitles.time("not-a-date"))
        assertNull(AdminTitles.timeOrNull(null))
        assertNull(AdminTitles.timeOrNull(""))
    }

    @Test
    fun `filter chip collections are the user facing vocabulary`() {
        assertEquals(5, AdminPresentation.filters.size)
        assertEquals(4, AdminPresentation.statusFilters.size)
        assertEquals(3, AdminPresentation.platforms.size)
        assertEquals(3, AdminPresentation.accountFilters.size)
        // A filter that accepts every status is labelled as such, not left blank.
        assertEquals(R.string.admin_filter_all, AdminPresentation.ReportFilter.All.label)
        assertNull(AdminPresentation.ReportFilter.All.status)
    }
}
