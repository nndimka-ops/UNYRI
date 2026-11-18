import Foundation

// ---------------------------------------------------------------------------
// ADMIN PRODUCT PACK 2.1 - in-memory administration state (iOS).
//
// One typed cursor page per list plus the loaded detail rows, so navigating to a
// detail screen and back neither refetches nor resets what the administrator
// already loaded. Cleared the moment the administrative session locks, at the
// same point the token is dropped.
//
// Never stores message plaintext, ciphertext or authentication material.
// Screenshot bytes live here only while the panel is unlocked.
// ---------------------------------------------------------------------------

@MainActor
final class AdminCache: ObservableObject {
    // ----- dashboard -------------------------------------------------------
    @Published var summary: AdminSummaryDTO?
    @Published var summaryState: AdminLoadState = .idle
    @Published var summaryFailed = false

    // ----- paged lists -----------------------------------------------------
    @Published var reports = AdminPage<AdminReportDTO>()
    @Published var reportsState: AdminLoadState = .idle
    @Published var reportsFailed = false

    @Published var feedback = AdminPage<AdminFeedbackDTO>()
    @Published var feedbackState: AdminLoadState = .idle
    @Published var feedbackFailed = false
    @Published var feedbackDetails: [String: AdminFeedbackDTO] = [:]

    @Published var users = AdminPage<AdminUserRowDTO>()
    @Published var usersState: AdminLoadState = .idle
    @Published var usersFailed = false
    /// True once a search actually ran, so Idle and Empty stay distinct.
    @Published var usersSearched = false

    @Published var audit = AdminPage<AdminAuditDTO>()
    @Published var auditState: AdminLoadState = .idle
    @Published var auditFailed = false

    // ----- User Moderation Center -----------------------------------------
    @Published var moderation: AdminUserDetailDTO?
    @Published var moderationState: AdminLoadState = .idle
    @Published var moderationFailed = false

    @Published var timeline = AdminPage<AdminTimelineEventDTO>()
    @Published var timelineState: AdminLoadState = .idle
    @Published var timelineFailed = false

    @Published var notes = AdminPage<AdminNoteDTO>()
    @Published var notesState: AdminLoadState = .idle
    @Published var notesFailed = false

    /// Reports involving the account currently open.
    @Published var accountReports = AdminPage<AdminReportDTO>()
    @Published var accountReportsState: AdminLoadState = .idle
    @Published var accountReportsFailed = false

    // ----- owner surfaces --------------------------------------------------
    @Published var storage: AdminStorageDTO?
    @Published var policy: AdminPolicyDTO?

    /// Screenshots are fetched lazily, one at a time, on demand.
    @Published var screenshots: [String: Data] = [:]
    @Published var screenshotFailures: Set<String> = []

    func report(_ id: String?) -> AdminReportDTO? {
        guard let id else { return nil }
        return reports.rows.first { $0.id == id }
    }

    func feedbackRow(_ id: String?) -> AdminFeedbackDTO? {
        guard let id else { return nil }
        return feedbackDetails[id] ?? feedback.rows.first { $0.id == id }
    }

    func userRow(_ id: String?) -> AdminUserRowDTO? {
        guard let id else { return nil }
        return users.rows.first { $0.userId == id }
    }

    func device(_ installationId: String?) -> AdminDeviceDTO? {
        guard let installationId else { return nil }
        return moderation?.devices.first { $0.installationId == installationId }
    }

    func remember(_ dto: AdminFeedbackDTO) {
        feedbackDetails[dto.id] = dto
    }

    func screenshot(_ feedbackId: String, _ index: Int) -> Data? {
        screenshots["\(feedbackId):\(index)"]
    }

    func screenshotFailed(_ feedbackId: String, _ index: Int) -> Bool {
        screenshotFailures.contains("\(feedbackId):\(index)")
    }

    func rememberScreenshot(_ feedbackId: String, _ index: Int, bytes: Data) {
        screenshots["\(feedbackId):\(index)"] = bytes
        screenshotFailures.remove("\(feedbackId):\(index)")
    }

    func markScreenshotFailed(_ feedbackId: String, _ index: Int) {
        screenshotFailures.insert("\(feedbackId):\(index)")
    }

    /// Wipes every cached administration row and every derived failure state.
    func clear() {
        summary = nil; summaryState = .idle; summaryFailed = false

        reports = AdminPage(); reportsState = .idle; reportsFailed = false
        feedback = AdminPage(); feedbackState = .idle; feedbackFailed = false
        feedbackDetails = [:]
        users = AdminPage(); usersState = .idle; usersFailed = false; usersSearched = false
        audit = AdminPage(); auditState = .idle; auditFailed = false

        moderation = nil; moderationState = .idle; moderationFailed = false
        timeline = AdminPage(); timelineState = .idle; timelineFailed = false
        notes = AdminPage(); notesState = .idle; notesFailed = false
        accountReports = AdminPage(); accountReportsState = .idle; accountReportsFailed = false

        storage = nil; policy = nil
        screenshots = [:]; screenshotFailures = []
    }
}
