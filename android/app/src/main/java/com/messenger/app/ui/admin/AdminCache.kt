package com.messenger.app.ui.admin

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - in-memory administration state.
//
// One typed cursor page per list plus the loaded detail rows, so navigating to a
// detail screen and back neither refetches nor resets what the administrator
// already loaded. Cleared the moment the administrative session locks, at the
// same point the token is dropped.
//
// Never stores message plaintext, ciphertext or authentication material.
// Screenshot bytes live here only while the panel is unlocked.
// ---------------------------------------------------------------------------
@Stable
internal class AdminCache {

    // ----- dashboard -------------------------------------------------------
    var summary by mutableStateOf<AdminSummaryDto?>(null)
    var summaryState by mutableStateOf(AdminLoadState.Idle)
    var summaryFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    // ----- paged lists -----------------------------------------------------
    var reports by mutableStateOf(AdminPage<AdminReportDto>())
    var reportsState by mutableStateOf(AdminLoadState.Idle)
    var reportsFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    var feedback by mutableStateOf(AdminPage<AdminFeedbackDto>())
    var feedbackState by mutableStateOf(AdminLoadState.Idle)
    var feedbackFailed by mutableStateOf<AdminPresentation.Failure?>(null)
    var feedbackDetails by mutableStateOf<Map<String, AdminFeedbackDto>>(emptyMap())

    var users by mutableStateOf(AdminPage<AdminUserRowDto>())
    var usersState by mutableStateOf(AdminLoadState.Idle)
    var usersFailed by mutableStateOf<AdminPresentation.Failure?>(null)
    /** True once a search actually ran, so Idle and Empty stay distinct. */
    var usersSearched by mutableStateOf(false)

    var audit by mutableStateOf(AdminPage<AdminAuditDto>())
    var auditState by mutableStateOf(AdminLoadState.Idle)
    var auditFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    // ----- User Moderation Center -----------------------------------------
    var moderation by mutableStateOf<AdminUserDetailDto?>(null)
    var moderationState by mutableStateOf(AdminLoadState.Idle)
    var moderationFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    var timeline by mutableStateOf(AdminPage<AdminTimelineEventDto>())
    var timelineState by mutableStateOf(AdminLoadState.Idle)
    var timelineFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    var notes by mutableStateOf(AdminPage<AdminNoteDto>())
    var notesState by mutableStateOf(AdminLoadState.Idle)
    var notesFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    /** Reports involving the account currently open. */
    var accountReports by mutableStateOf(AdminPage<AdminReportDto>())
    var accountReportsState by mutableStateOf(AdminLoadState.Idle)
    var accountReportsFailed by mutableStateOf<AdminPresentation.Failure?>(null)

    // ----- owner surfaces --------------------------------------------------
    var storage by mutableStateOf<AdminStorageDto?>(null)
    var policy by mutableStateOf<AdminPolicyDto?>(null)

    /** Screenshots are fetched lazily, one at a time, on demand. */
    var screenshots by mutableStateOf<Map<String, ByteArray>>(emptyMap())
    var screenshotFailures by mutableStateOf<Set<String>>(emptySet())

    fun report(id: String?): AdminReportDto? = id?.let { key -> reports.rows.firstOrNull { it.id == key } }

    fun feedbackRow(id: String?): AdminFeedbackDto? = id?.let { key ->
        feedbackDetails[key] ?: feedback.rows.firstOrNull { it.id == key }
    }

    fun userRow(id: String?): AdminUserRowDto? = id?.let { key -> users.rows.firstOrNull { it.userId == key } }

    fun device(installationId: String?): AdminDeviceDto? =
        installationId?.let { key -> moderation?.devices?.firstOrNull { it.installationId == key } }

    fun rememberFeedback(dto: AdminFeedbackDto) {
        feedbackDetails = feedbackDetails + (dto.id to dto)
    }

    fun screenshot(feedbackId: String, index: Int): ByteArray? = screenshots["$feedbackId:$index"]

    fun screenshotFailed(feedbackId: String, index: Int): Boolean = "$feedbackId:$index" in screenshotFailures

    fun rememberScreenshot(feedbackId: String, index: Int, bytes: ByteArray) {
        screenshots = screenshots + ("$feedbackId:$index" to bytes)
        screenshotFailures = screenshotFailures - "$feedbackId:$index"
    }

    fun markScreenshotFailed(feedbackId: String, index: Int) {
        screenshotFailures = screenshotFailures + "$feedbackId:$index"
    }

    /** Wipes every cached administration row and every derived failure state. */
    fun clear() {
        summary = null; summaryState = AdminLoadState.Idle; summaryFailed = null

        reports = AdminPage(); reportsState = AdminLoadState.Idle; reportsFailed = null
        feedback = AdminPage(); feedbackState = AdminLoadState.Idle; feedbackFailed = null
        feedbackDetails = emptyMap()
        users = AdminPage(); usersState = AdminLoadState.Idle; usersFailed = null; usersSearched = false
        audit = AdminPage(); auditState = AdminLoadState.Idle; auditFailed = null

        moderation = null; moderationState = AdminLoadState.Idle; moderationFailed = null
        timeline = AdminPage(); timelineState = AdminLoadState.Idle; timelineFailed = null
        notes = AdminPage(); notesState = AdminLoadState.Idle; notesFailed = null
        accountReports = AdminPage(); accountReportsState = AdminLoadState.Idle; accountReportsFailed = null

        storage = null; policy = null
        screenshots = emptyMap(); screenshotFailures = emptySet()
    }
}
