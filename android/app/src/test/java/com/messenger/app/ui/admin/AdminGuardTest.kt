package com.messenger.app.ui.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADMIN PRODUCT PACK 2.1 - administration state guard.
//
// Locking the panel, losing the session or losing the role must leave nothing
// behind: no cached rows, no cached screenshots, no derived state that a later
// screen could render as if it were current.
class AdminGuardTest {

    private fun populated(): AdminCache = AdminCache().apply {
        summary = AdminSummaryDto(openReports = 3)
        summaryState = AdminLoadState.Ready
        summaryFailed = AdminPresentation.Failure.Network

        reports = AdminPage(
            listOf(AdminReportDto(id = "r1"), AdminReportDto(id = "r2")),
            nextCursor = "c1",
            hasMore = true,
        )
        reportsState = AdminLoadState.Ready
        reportsFailed = AdminPresentation.Failure.Server

        feedback = AdminPage(listOf(AdminFeedbackDto(id = "f1")), nextCursor = "c2", hasMore = true)
        feedbackState = AdminLoadState.Ready
        feedbackDetails = mapOf("f1" to AdminFeedbackDto(id = "f1", description = "full"))
        feedbackFailed = AdminPresentation.Failure.RateLimited

        users = AdminPage(listOf(AdminUserRowDto(id = "u1", userId = "u1", username = "anna")))
        usersState = AdminLoadState.Ready
        usersSearched = true
        usersFailed = AdminPresentation.Failure.Session

        audit = AdminPage(listOf(AdminAuditDto(id = "7")), nextCursor = "c3", hasMore = true)
        auditState = AdminLoadState.Ready
        auditFailed = AdminPresentation.Failure.Failed

        moderation = AdminUserDetailDto(
            profile = AdminProfileDto(userId = "u1", username = "anna", displayName = "Anna"),
            devices = listOf(
                AdminDeviceDto(id = "d1", installationId = "i1", restrictionActive = true),
            ),
        )
        moderationState = AdminLoadState.Ready
        moderationFailed = AdminPresentation.Failure.Server

        timeline = AdminPage(listOf(AdminTimelineEventDto(kind = "note_added", ref = "audit:9")))
        timelineState = AdminLoadState.Ready
        timelineFailed = AdminPresentation.Failure.Server

        notes = AdminPage(
            listOf(AdminNoteDto(id = "1", authorUserId = "a1", body = "watch this")),
            nextCursor = "c4",
            hasMore = true,
        )
        notesState = AdminLoadState.Ready
        notesFailed = AdminPresentation.Failure.Server

        accountReports = AdminPage(listOf(AdminReportDto(id = "r9")), hasMore = true)
        accountReportsState = AdminLoadState.Ready
        accountReportsFailed = AdminPresentation.Failure.Server

        storage = AdminStorageDto(usedBytes = 10)
        policy = AdminPolicyDto(name = "COCO")
        screenshots = mapOf("f1:0" to byteArrayOf(1, 2, 3))
        screenshotFailures = setOf("f1:1")
    }

    @Test
    fun `locking the panel wipes every cached administration row`() {
        val cache = populated()
        assertTrue(cache.screenshots.isNotEmpty())

        cache.clear()

        assertNull(cache.summary)
        assertEquals(AdminLoadState.Idle, cache.summaryState)
        assertNull(cache.summaryFailed)

        assertTrue(cache.reports.rows.isEmpty())
        assertNull(cache.reports.nextCursor)
        assertFalse(cache.reports.hasMore)
        assertEquals(AdminLoadState.Idle, cache.reportsState)
        assertNull(cache.reportsFailed)

        assertTrue(cache.feedback.rows.isEmpty())
        assertTrue(cache.feedbackDetails.isEmpty())
        assertNull(cache.feedbackFailed)

        assertTrue(cache.users.rows.isEmpty())
        assertFalse(cache.usersSearched)
        assertNull(cache.usersFailed)

        assertTrue(cache.audit.rows.isEmpty())
        assertNull(cache.auditFailed)

        assertNull(cache.moderation)
        assertTrue(cache.timeline.rows.isEmpty())
        assertTrue(cache.notes.rows.isEmpty())
        assertTrue(cache.accountReports.rows.isEmpty())
        assertNull(cache.moderationFailed)
        assertNull(cache.timelineFailed)
        assertNull(cache.notesFailed)
        assertNull(cache.accountReportsFailed)

        assertNull(cache.storage)
        assertNull(cache.policy)

        // Screenshot bytes are the most sensitive thing in the cache.
        assertTrue(cache.screenshots.isEmpty())
        assertTrue(cache.screenshotFailures.isEmpty())
    }

    @Test
    fun `a cleared cache cannot resolve a stale detail by id`() {
        val cache = populated()
        assertEquals("r1", cache.report("r1")?.id)
        assertEquals("u1", cache.userRow("u1")?.userId)
        assertEquals("i1", cache.device("i1")?.installationId)

        cache.clear()

        assertNull(cache.report("r1"))
        assertNull(cache.feedbackRow("f1"))
        assertNull(cache.userRow("u1"))
        assertNull(cache.device("i1"))
        // A missing id is never treated as a lookup of the first row.
        assertNull(cache.report(null))
        assertNull(cache.userRow(null))
    }

    @Test
    fun `screenshots are cached per item and index and only when fetched`() {
        val cache = AdminCache()
        assertNull(cache.screenshot("f1", 0))
        assertFalse(cache.screenshotFailed("f1", 0))

        cache.markScreenshotFailed("f1", 0)
        assertTrue(cache.screenshotFailed("f1", 0))
        assertNull(cache.screenshot("f1", 0))

        // A later success replaces the failure marker.
        cache.rememberScreenshot("f1", 0, byteArrayOf(9))
        assertFalse(cache.screenshotFailed("f1", 0))
        assertEquals(1, cache.screenshot("f1", 0)?.size)

        // A different index of the same item is a different entry.
        assertNull(cache.screenshot("f1", 1))
        assertNull(cache.screenshot("f2", 0))
    }

    @Test
    fun `the loaded detail wins over the list row for the same item`() {
        val cache = AdminCache().apply {
            feedback = AdminPage(listOf(AdminFeedbackDto(id = "f1", description = "summary")))
        }
        assertEquals("summary", cache.feedbackRow("f1")?.description)

        cache.rememberFeedback(AdminFeedbackDto(id = "f1", description = "full detail"))
        assertEquals("full detail", cache.feedbackRow("f1")?.description)
        assertEquals("summary", cache.feedback.rows[0].description)
    }

    @Test
    fun `a note must be non-blank and within the server limit`() {
        assertFalse(AdminNotePolicy.valid(""))
        assertFalse(AdminNotePolicy.valid("   "))
        assertFalse(AdminNotePolicy.valid("\n\t "))
        assertTrue(AdminNotePolicy.valid("ok"))
        assertTrue(AdminNotePolicy.valid("a".repeat(AdminNotePolicy.Max)))
        assertFalse(AdminNotePolicy.valid("a".repeat(AdminNotePolicy.Max + 1)))
        // The payload is trimmed, so surrounding whitespace is never stored.
        assertEquals("note", AdminNotePolicy.payload("  note  "))
    }

    @Test
    fun `closing a report or a feedback item requires a second step`() {
        assertTrue(AdminConfirmations.forReportStatus("resolved"))
        assertTrue(AdminConfirmations.forReportStatus("dismissed"))
        assertFalse(AdminConfirmations.forReportStatus("open"))
        assertFalse(AdminConfirmations.forReportStatus("in_progress"))

        assertTrue(AdminConfirmations.forFeedbackStatus("resolved"))
        assertTrue(AdminConfirmations.forFeedbackStatus("dismissed"))
        assertFalse(AdminConfirmations.forFeedbackStatus("open"))

        assertTrue(AdminConfirmations.forRestore(blocked = false))
        assertFalse(AdminConfirmations.forRestore(blocked = true))
    }

    @Test
    fun `storage cleanup cannot run before a preview exists`() {
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Idle, 3, "p1"))
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Previewing, 3, "p1"))
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Previewed, 0, "p1"))
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Previewed, 3, ""))
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Previewed, 3, null))
        assertTrue(AdminCleanupPolicy.canExecute(CleanupStage.Previewed, 3, "p1"))
        assertFalse(AdminCleanupPolicy.canExecute(CleanupStage.Executing, 3, "p1"))
    }

    @Test
    fun `an unknown audit action is marked instead of printed raw`() {
        assertNull(AdminPresentation.auditAction("something:new"))
        assertNull(AdminPresentation.auditAction(null))
        assertEquals(
            AdminPresentation.auditAction("report:resolved"),
            AdminPresentation.auditAction("report:open"),
        )
    }
}
