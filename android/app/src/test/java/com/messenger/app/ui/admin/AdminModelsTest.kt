package com.messenger.app.ui.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADMIN PRODUCT PACK 2.1 - typed administration contract.
//
// These tests run on the JVM and use real kotlinx.serialization parsing, which
// is exactly what the previous org.json approach could not do: Android's org.json
// is only a stub in local unit tests.
class AdminModelsTest {
    @Test fun `account search decodes explicit public identity without legacy report keys`() {
        val row = json.decodeFromString<AdminUserRowDto>(
            """{"id":"u1","user_id":"u1","display_name":"Alice","public_username":"alice","unyri_number":"123456789"}""",
        )
        assertEquals("Alice", row.displayName)
        assertEquals("alice", row.publicUsername)
        assertEquals("123456789", row.unyriNumber)
        val text = json.encodeToString(AdminUserRowDto.serializer(), row)
        assertFalse(text.contains("\"username\"")); assertFalse(text.contains("\"reason\""))
    }


    private val json = AdminJson.format

    @Test
    fun `the list envelope keeps items, cursor and the has_more flag`() {
        val page = json.decodeFromString<AdminPageDto<AdminReportDto>>(
            """{"items":[{"id":"r1","status":"open","created_at":"2026-09-25T10:11:12.000000Z"}],
                "next_cursor":"abc","has_more":true,"unexpected":"ignored"}""",
        )
        assertEquals(1, page.items.size)
        assertEquals("r1", page.items[0].id)
        assertEquals("abc", page.nextCursor)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a missing optional field is null, never a fabricated value`() {
        val report = json.decodeFromString<AdminReportDto>("""{"id":"r1"}""")
        assertNull(report.userId)
        assertNull(report.reason)
        assertNull(report.decisionReason)
        assertEquals("open", report.status)
    }

    @Test
    fun `an explicit null from the server is still null`() {
        val report = json.decodeFromString<AdminReportDto>(
            """{"id":"r1","user_id":null,"reviewed_at":null,"description":null}""",
        )
        assertNull(report.userId)
        assertNull(report.reviewedAt)
        assertNull(report.description)
    }

    @Test
    fun `feedback detail carries descriptors and never an embedded screenshot`() {
        val raw = """{"id":"f1","user_id":"u1","description":"Camera is black",
            "platform":"android","app_version":"0.2.0","os_version":"16","status":"open",
            "created_at":"2026-09-25T10:11:12.000000Z",
            "screenshots":[{"index":0,"mime_type":"image/png","byte_size":1234}]}"""
        val detail = json.decodeFromString<AdminFeedbackDto>(raw)
        assertEquals(1, detail.screenshots.size)
        assertEquals(0, detail.screenshots[0].index)
        assertEquals("image/png", detail.screenshots[0].mimeType)
        assertEquals(1234, detail.screenshots[0].byteSize)
        // The payload must not contain the encoded image at all.
        assertFalse(raw.contains("data_base64"))
        // A list row has no descriptors, only a count.
        val row = json.decodeFromString<AdminFeedbackDto>("""{"id":"f1","screenshot_count":2}""")
        assertEquals(2, row.screenshotCount)
        assertTrue(row.screenshots.isEmpty())
    }

    @Test
    fun `the moderation centre parses nested public profile, state and devices without a login`() {
        val detail = json.decodeFromString<AdminUserDetailDto>(
            """{"profile":{"user_id":"u1","public_username":"anna","unyri_number":"123456789","display_name":"Anna","has_avatar":true,
                "created_at":"2026-01-01T00:00:00.000000Z"},
                "moderation":{"status":"suspended","until":null,"reason":"spam","reports_total":4,
                "reports_open":2,"notes":1},
                "devices":[{"id":"d1","installation_id":"i1","platform":"android",
                "display_name":"Pixel","app_version":"0.2.0","os_version":"16",
                "last_active_at":"2026-09-25T10:11:12.000000Z","revoked":false,
                "restriction_active":true,"restriction_until":null,"restriction_reason":"compromised",
                "is_administrator_device":false}],
                "is_administrator":false,"suspend_allowed":true}""",
        )
        assertEquals("anna", detail.profile.publicUsername)
        assertEquals("123456789", detail.profile.unyriNumber)
        assertEquals("Anna", detail.profile.displayName)
        assertEquals("suspended", detail.moderation.status)
        assertEquals(4, detail.moderation.reportsTotal)
        assertEquals(1, detail.devices.size)
        assertEquals("i1", detail.devices[0].installationId)
        assertTrue(detail.devices[0].restrictionActive)
        assertNull(detail.devices[0].restrictionUntil)
        assertTrue(detail.suspendAllowed)
        assertFalse(detail.isAdministrator)
    }

    @Test
    fun `an empty device list is an empty list, not a missing value`() {
        val detail = json.decodeFromString<AdminUserDetailDto>(
            """{"profile":{"user_id":"u1","display_name":"A"},"devices":[]}""",
        )
        assertTrue(detail.devices.isEmpty())
        assertEquals("active", detail.moderation.status)
        assertEquals(false, detail.suspendAllowed)
    }

    @Test
    fun `timeline events keep their semantic kind and stable reference`() {
        val page = json.decodeFromString<AdminPageDto<AdminTimelineEventDto>>(
            """{"items":[
                {"kind":"account_suspended","occurred_at":"2026-09-25T10:00:00.000000Z","actor":"a1",
                 "reason":"spam","detail":{"installation_id":"i1"},"ref":"audit:7"},
                {"kind":"report_received","occurred_at":"2026-09-24T10:00:00.000000Z",
                 "detail":{"report_id":"r1"},"ref":"report:r1"}],
                "has_more":false}""",
        )
        assertEquals(2, page.items.size)
        assertEquals("account_suspended", page.items[0].kind)
        assertEquals("i1", page.items[0].detail.installationId)
        assertEquals("audit:7", page.items[0].ref)
        assertNull(page.items[1].actor)
        assertEquals("r1", page.items[1].detail.reportId)
    }

    @Test
    fun `summary exposes counters and an explicit null storage block`() {
        val owner = json.decodeFromString<AdminSummaryDto>(
            """{"open_reports":3,"in_progress_reports":1,"unresolved_feedback":2,
                "active_account_suspensions":1,"active_installation_suspensions":4,
                "reports_last_24h":2,
                "storage":{"used_bytes":10,"reserved_bytes":4,"budget_bytes":100}}""",
        )
        assertEquals(3, owner.openReports)
        assertEquals(1, owner.inProgressReports)
        assertEquals(2, owner.unresolvedFeedback)
        assertEquals(4, owner.activeInstallationSuspensions)
        assertEquals(100, owner.storage?.budgetBytes)

        // A moderator receives an explicit null and must never see a zero.
        val moderator = json.decodeFromString<AdminSummaryDto>("""{"open_reports":3,"storage":null}""")
        assertNull(moderator.storage)
        assertEquals(3, moderator.openReports)
    }

    @Test
    fun `a device suspension request omits the fields the server must default`() {
        val permanent = json.encodeToString(
            AdminDeviceSuspensionBody.serializer(),
            AdminDeviceSuspensionBody(blocked = true, reason = "compromised", until = null),
        )
        assertEquals("""{"blocked":true,"reason":"compromised"}""", permanent)

        val timed = json.encodeToString(
            AdminDeviceSuspensionBody.serializer(),
            AdminDeviceSuspensionBody(blocked = true, reason = "compromised", until = "2026-10-25T10:00:00Z"),
        )
        assertTrue(timed.contains("\"until\":\"2026-10-25T10:00:00Z\""))

        val restore = json.encodeToString(
            AdminDeviceSuspensionBody.serializer(),
            AdminDeviceSuspensionBody(blocked = false, reason = "restored"),
        )
        assertEquals("""{"blocked":false,"reason":"restored"}""", restore)
    }

    @Test
    fun `an account restore does not silently include installations`() {
        val restore = json.encodeToString(
            AdminSuspensionBody.serializer(),
            AdminSuspensionBody(blocked = false, reason = "restored"),
        )
        assertEquals("""{"blocked":false,"reason":"restored"}""", restore)

        val include = json.encodeToString(
            AdminSuspensionBody.serializer(),
            AdminSuspensionBody(blocked = true, reason = "spam", includeInstallations = true),
        )
        assertTrue(include.contains("\"include_installations\":true"))
    }

    @Test
    fun `a review body always carries the status and an explicit note`() {
        // `/admin/reports/:id/review` rejects a body without `note`, and
        // `encodeDefaults = false` would drop a defaulted empty string.
        val simple = json.encodeToString(AdminReviewBody.serializer(), AdminReviewBody("open", ""))
        assertEquals("""{"status":"open","note":""}""", simple)

        val closed = json.encodeToString(
            AdminReviewBody.serializer(),
            AdminReviewBody("resolved", "checked", "released"),
        )
        assertTrue(closed.contains("\"note\":\"checked\""))
        assertTrue(closed.contains("\"decision_reason\":\"released\""))
    }

    @Test
    fun `audit rows keep machine-readable detail without raw payloads`() {
        val page = json.decodeFromString<AdminPageDto<AdminAuditDto>>(
            """{"items":[{"id":"12","actor":"a1","action":"device_suspend","target":"u1",
                "reason":"compromised","detail":{"installation_id":"i1"},"created_at":"2026-09-25T10:00:00.000000Z"}],
                "has_more":false,"next_cursor":null}""",
        )
        assertEquals("device_suspend", page.items[0].action)
        assertEquals("i1", page.items[0].detail.installationId)
        assertNull(page.nextCursor)
    }

    @Test
    fun `notes parse with their author and body`() {
        val page = json.decodeFromString<AdminPageDto<AdminNoteDto>>(
            """{"items":[{"id":"1","author_user_id":"a1","body":"Watch this account","created_at":"2026-09-25T10:00:00.000000Z"}],"has_more":false}""",
        )
        assertEquals("Watch this account", page.items[0].body)
        assertEquals("a1", page.items[0].authorUserId)
    }
}
