package com.messenger.app.ui.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - typed administration contract.
//
// These DTOs mirror the actual server handlers in `server/src/admin.rs` (see
// docs/ADMIN_API_CONTRACT.md). Administration responses are no longer read
// through org.json: Android's org.json is only a stub in local unit tests, so
// logic built on it could not be tested at all.
//
// Every optional field defaults to null/0 so a field the server omits can never
// be mistaken for real data, and unknown keys are ignored by the shared Json
// configuration (forward compatibility when the server adds a field).
// ---------------------------------------------------------------------------

/** The envelope every paginated administration list returns. */
@Serializable
internal data class AdminPageDto<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
internal data class AdminStorageFiguresDto(
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("reserved_bytes") val reservedBytes: Long = 0,
    @SerialName("budget_bytes") val budgetBytes: Long = 0,
)

@Serializable
internal data class AdminSummaryDto(
    @SerialName("open_reports") val openReports: Long = 0,
    @SerialName("in_progress_reports") val inProgressReports: Long = 0,
    @SerialName("unresolved_feedback") val unresolvedFeedback: Long = 0,
    @SerialName("active_account_suspensions") val activeAccountSuspensions: Long = 0,
    @SerialName("active_installation_suspensions") val activeInstallationSuspensions: Long = 0,
    @SerialName("reports_last_24h") val reportsLast24h: Long = 0,
    /** Null for a moderator: a missing figure must never render as a zero. */
    val storage: AdminStorageFiguresDto? = null,
)

@Serializable
internal data class AdminReportDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("reporter_user_id") val reporterUserId: String? = null,
    val reason: String? = null,
    val description: String? = null,
    val status: String = "open",
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class AdminScreenshotDto(
    val index: Int,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long = 0,
)

@Serializable
internal data class AdminFeedbackDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val description: String? = null,
    val platform: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("screenshot_count") val screenshotCount: Int = 0,
    val status: String = "open",
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Descriptors only; the bytes come from the dedicated screenshot route. */
    val screenshots: List<AdminScreenshotDto> = emptyList(),
)

@Serializable
internal data class AdminUserRowDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    /**
     * The search endpoint reuses the report/feedback row keys: `description`
     * carries the display name and `reason` carries the username. The wire
     * shape is not changed here, so the DTO documents the reuse instead of
     * pretending the fields are something else.
     */
    @SerialName("description") val displayName: String? = null,
    @SerialName("reason") val username: String? = null,
    @SerialName("sort_key") val sortKey: String? = null,
    @SerialName("installation_blocks") val installationBlocks: Long = 0,
    val status: String = "active",
    @SerialName("suspended_until") val suspendedUntil: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class AdminProfileDto(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class AdminModerationStateDto(
    val status: String = "active",
    val until: String? = null,
    val reason: String? = null,
    @SerialName("reports_total") val reportsTotal: Long = 0,
    @SerialName("reports_open") val reportsOpen: Long = 0,
    val notes: Long = 0,
)

@Serializable
internal data class AdminDeviceDto(
    val id: String,
    @SerialName("installation_id") val installationId: String,
    val platform: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("signal_device_id") val signalDeviceId: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    val revoked: Boolean = false,
    @SerialName("restriction_active") val restrictionActive: Boolean = false,
    @SerialName("restriction_until") val restrictionUntil: String? = null,
    @SerialName("restriction_reason") val restrictionReason: String? = null,
    @SerialName("is_administrator_device") val isAdministratorDevice: Boolean = false,
)

@Serializable
internal data class AdminUserDetailDto(
    val profile: AdminProfileDto,
    val moderation: AdminModerationStateDto = AdminModerationStateDto(),
    val devices: List<AdminDeviceDto> = emptyList(),
    @SerialName("is_administrator") val isAdministrator: Boolean = false,
    @SerialName("suspend_allowed") val suspendAllowed: Boolean = false,
)

/** Machine-readable, non-secret context carried by timeline and audit events. */
@Serializable
internal data class AdminEventDetailDto(
    @SerialName("report_id") val reportId: String? = null,
    @SerialName("feedback_id") val feedbackId: String? = null,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("note_id") val noteId: Long? = null,
    val status: String? = null,
)

@Serializable
internal data class AdminTimelineEventDto(
    val kind: String = "other",
    @SerialName("occurred_at") val occurredAt: String? = null,
    val actor: String? = null,
    val reason: String? = null,
    val detail: AdminEventDetailDto = AdminEventDetailDto(),
    /** Stable identity of the event inside the response; the dedup key. */
    val ref: String? = null,
)

@Serializable
internal data class AdminAuditDto(
    val id: String,
    val actor: String? = null,
    val action: String? = null,
    val target: String? = null,
    val reason: String? = null,
    val detail: AdminEventDetailDto = AdminEventDetailDto(),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class AdminNoteDto(
    val id: String,
    @SerialName("author_user_id") val authorUserId: String? = null,
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

// ----- owner surfaces (unchanged shapes from Pack 1) -----------------------

@Serializable
internal data class AdminStorageDto(
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("reserved_bytes") val reservedBytes: Long = 0,
    @SerialName("budget_bytes") val budgetBytes: Long = 0,
    val warning: Boolean = false,
    @SerialName("physical_free_bytes") val physicalFreeBytes: Long? = null,
    val users: List<AdminStorageUserDto> = emptyList(),
)

@Serializable
internal data class AdminStorageUserDto(
    @SerialName("user_id") val userId: String,
    val bytes: Long = 0,
)

@Serializable
internal data class AdminPolicyDto(
    val name: String = "",
    val operator: String = "",
    val contact: String = "",
    val rules: String = "",
    val retention: String = "",
    @SerialName("max_file_bytes") val maxFileBytes: Long = 0,
    @SerialName("user_quota_bytes") val userQuotaBytes: Long = 0,
    @SerialName("storage_budget_bytes") val storageBudgetBytes: Long = 0,
)

@Serializable
internal data class AdminCleanupPlanDto(
    @SerialName("plan_id") val planId: String,
    val bytes: Long = 0,
    val items: List<AdminCleanupItemDto> = emptyList(),
)

@Serializable
internal data class AdminCleanupItemDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val bytes: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class AdminCleanupResultDto(
    val deleted: Long = 0,
    val skipped: Long = 0,
    val failed: Long = 0,
)

// ----- request bodies ------------------------------------------------------

@Serializable
internal data class AdminReviewBody(
    val status: String,
    /**
     * Deliberately has no default. `encodeDefaults = false` drops any property
     * that has one, and `POST /admin/reports/:id/review` rejects a body without
     * `note`, so an empty note must still be encoded as `"note":""`.
     */
    val note: String,
    @SerialName("decision_reason") val decisionReason: String = "",
)

@Serializable
internal data class AdminSuspensionBody(
    val blocked: Boolean,
    val reason: String,
    val until: String? = null,
    /**
     * Left null when the account is being restricted on its own: the server
     * defaults to false, and sending an explicit value on restore would be a
     * different request (it would also lift installation restrictions).
     */
    @SerialName("include_installations") val includeInstallations: Boolean? = null,
)

@Serializable
internal data class AdminDeviceSuspensionBody(
    val blocked: Boolean,
    val reason: String,
    val until: String? = null,
)

@Serializable
internal data class AdminNoteBody(val body: String)

@Serializable
internal data class AdminCleanupBody(@SerialName("plan_id") val planId: String)

@Serializable
internal data class AdminNoteCreatedDto(val id: String)

/**
 * ADMIN CONSOLE PACK 3 - one-time handoff for the server-hosted console.
 *
 * The handoff is a credential for exactly one exchange and is short-lived; it is
 * never persisted, never logged and never placed in the clipboard.
 */
@Serializable
internal data class AdminHandoffDto(
    val handoff: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("console_path") val consolePath: String? = null,
)

/** An empty JSON object, so a POST still has a body the server can parse. */
@Serializable
internal data object AdminEmptyBody

@Serializable
internal data class AdminOkDto(val ok: Boolean = false)

// ----- authentication (unchanged shapes) -----------------------------------

@Serializable
internal data class AdminStatusDto(
    val configured: Boolean = false,
    @SerialName("key_protocol") val keyProtocol: Int = 0,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("signing_public_key") val signingPublicKey: String? = null,
)

@Serializable
internal data class AdminSetupDto(
    @SerialName("qr_png") val qrPng: String? = null,
    val secret: String? = null,
)
