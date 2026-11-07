import Foundation

// ---------------------------------------------------------------------------
// ADMIN PRODUCT PACK 2.1 - typed administration contract (iOS).
//
// These types mirror the actual server handlers in `server/src/admin.rs` (see
// docs/ADMIN_API_CONTRACT.md). The administration surface is decoded with a
// JSONDecoder configured for `.convertFromSnakeCase`, so `next_cursor`,
// `screenshot_count` and `used_bytes` arrive as `nextCursor`, `screenshotCount`
// and `usedBytes` without hand-written CodingKeys.
//
// Every optional field is optional: a field the server omits must never be
// mistaken for real data, and an explicit null from the server stays nil.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

/// Reads a key without ever throwing.
///
/// A synthesized `Decodable` does **not** fall back to a property's default when
/// a key is missing: it throws, and one omitted field then fails the element it
/// belongs to - and, for a list, the whole list. Every DTO here that carries
/// defaults therefore decodes through this helper, so a field the server has not
/// sent yet degrades to the documented default instead of erasing real data.
extension KeyedDecodingContainer {
    func adminValue<T: Decodable>(_ type: T.Type, forKey key: Key) -> T? {
        (try? decodeIfPresent(T.self, forKey: key)) ?? nil
    }
}

/// The envelope every paginated administration list returns.
struct AdminPageDTO<T: Decodable>: Decodable {
    var items: [T] = []
    var nextCursor: String?
    var hasMore: Bool = false

    private enum CodingKeys: String, CodingKey { case items, nextCursor, hasMore }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? container.decode([T].self, forKey: .items)) ?? []
        nextCursor = try? container.decodeIfPresent(String.self, forKey: .nextCursor)
        hasMore = (try? container.decodeIfPresent(Bool.self, forKey: .hasMore)) ?? false
    }

    init(items: [T] = [], nextCursor: String? = nil, hasMore: Bool = false) {
        self.items = items
        self.nextCursor = nextCursor
        self.hasMore = hasMore
    }
}

struct AdminStorageFiguresDTO: Decodable, Equatable {
    var usedBytes: Int64 = 0
    var reservedBytes: Int64 = 0
    var budgetBytes: Int64 = 0

    private enum CodingKeys: String, CodingKey { case usedBytes, reservedBytes, budgetBytes }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        usedBytes = container.adminValue(Int64.self, forKey: .usedBytes) ?? 0
        reservedBytes = container.adminValue(Int64.self, forKey: .reservedBytes) ?? 0
        budgetBytes = container.adminValue(Int64.self, forKey: .budgetBytes) ?? 0
    }
}

struct AdminSummaryDTO: Decodable, Equatable {
    var openReports: Int64 = 0
    var inProgressReports: Int64 = 0
    var unresolvedFeedback: Int64 = 0
    var activeAccountSuspensions: Int64 = 0
    var activeInstallationSuspensions: Int64 = 0
    var reportsLast24h: Int64 = 0
    /// nil for a moderator: the server sends an explicit null, and a missing
    /// figure must never render as a zero.
    var storage: AdminStorageFiguresDTO?

    private enum CodingKeys: String, CodingKey {
        case openReports, inProgressReports, unresolvedFeedback
        case activeAccountSuspensions, activeInstallationSuspensions, storage
        /// `.convertFromSnakeCase` turns `reports_last_24h` into
        /// **`reportsLast24H`** - the digit does not stop Foundation from
        /// upper-casing the final component - so the raw value is spelled out
        /// with the spelling the decoder actually produces. Inferring it here
        /// would silently read zero for the 24-hour counter.
        case reportsLast24h = "reportsLast24H"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        openReports = container.adminValue(Int64.self, forKey: .openReports) ?? 0
        inProgressReports = container.adminValue(Int64.self, forKey: .inProgressReports) ?? 0
        unresolvedFeedback = container.adminValue(Int64.self, forKey: .unresolvedFeedback) ?? 0
        activeAccountSuspensions = container.adminValue(Int64.self, forKey: .activeAccountSuspensions) ?? 0
        activeInstallationSuspensions = container.adminValue(Int64.self, forKey: .activeInstallationSuspensions) ?? 0
        reportsLast24h = container.adminValue(Int64.self, forKey: .reportsLast24h) ?? 0
        storage = container.adminValue(AdminStorageFiguresDTO.self, forKey: .storage)
    }
}

struct AdminReportDTO: Decodable, Identifiable, Equatable {
    var id: String
    var userId: String?
    var reporterUserId: String?
    var reason: String?
    var description: String?
    var status: String = "open"
    var reviewNote: String?
    var decisionReason: String?
    var reviewedBy: String?
    var reviewedAt: String?
    var createdAt: String?

    private enum CodingKeys: String, CodingKey {
        case id, reason, description, status
        case userId, reporterUserId, reviewNote, decisionReason, reviewedBy, reviewedAt, createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        userId = try? container.decodeIfPresent(String.self, forKey: .userId)
        reporterUserId = try? container.decodeIfPresent(String.self, forKey: .reporterUserId)
        reason = try? container.decodeIfPresent(String.self, forKey: .reason)
        description = try? container.decodeIfPresent(String.self, forKey: .description)
        status = (try? container.decodeIfPresent(String.self, forKey: .status)) ?? "open"
        reviewNote = try? container.decodeIfPresent(String.self, forKey: .reviewNote)
        decisionReason = try? container.decodeIfPresent(String.self, forKey: .decisionReason)
        reviewedBy = try? container.decodeIfPresent(String.self, forKey: .reviewedBy)
        reviewedAt = try? container.decodeIfPresent(String.self, forKey: .reviewedAt)
        createdAt = try? container.decodeIfPresent(String.self, forKey: .createdAt)
    }
}

/// A screenshot *descriptor*. The bytes are never inlined in a list or a detail
/// response; they come from `/admin/feedback/:id/screenshots/:index` on demand.
struct AdminScreenshotDTO: Decodable, Hashable {
    var index: Int
    var mimeType: String
    var byteSize: Int64 = 0
}

struct AdminFeedbackDTO: Decodable, Identifiable, Equatable {
    var id: String
    var userId: String?
    var description: String?
    var platform: String?
    var appVersion: String?
    var osVersion: String?
    /// Present on list rows only.
    var screenshotCount: Int = 0
    var status: String = "open"
    var reviewNote: String?
    var decisionReason: String?
    var reviewedBy: String?
    var reviewedAt: String?
    var createdAt: String?
    /// Present on the detail response only.
    var screenshots: [AdminScreenshotDTO] = []

    private enum CodingKeys: String, CodingKey {
        case id, description, platform, status, screenshots
        case userId, appVersion, osVersion, screenshotCount
        case reviewNote, decisionReason, reviewedBy, reviewedAt, createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        userId = try? container.decodeIfPresent(String.self, forKey: .userId)
        description = try? container.decodeIfPresent(String.self, forKey: .description)
        platform = try? container.decodeIfPresent(String.self, forKey: .platform)
        appVersion = try? container.decodeIfPresent(String.self, forKey: .appVersion)
        osVersion = try? container.decodeIfPresent(String.self, forKey: .osVersion)
        screenshotCount = (try? container.decodeIfPresent(Int.self, forKey: .screenshotCount)) ?? 0
        status = (try? container.decodeIfPresent(String.self, forKey: .status)) ?? "open"
        reviewNote = try? container.decodeIfPresent(String.self, forKey: .reviewNote)
        decisionReason = try? container.decodeIfPresent(String.self, forKey: .decisionReason)
        reviewedBy = try? container.decodeIfPresent(String.self, forKey: .reviewedBy)
        reviewedAt = try? container.decodeIfPresent(String.self, forKey: .reviewedAt)
        createdAt = try? container.decodeIfPresent(String.self, forKey: .createdAt)
        screenshots = (try? container.decodeIfPresent([AdminScreenshotDTO].self, forKey: .screenshots)) ?? []
    }
}

/// A row of the account search.
///
/// The search endpoint reuses the report/feedback row keys: `description`
/// carries the display name and `reason` carries the username. The wire shape is
/// documented rather than hidden.
struct AdminUserRowDTO: Decodable, Identifiable, Equatable {
    var id: String
    var userId: String
    var displayName: String?
    var username: String?
    var sortKey: String?
    var installationBlocks: Int64 = 0
    var status: String = "active"
    var suspendedUntil: String?
    var createdAt: String?

    private enum CodingKeys: String, CodingKey {
        case id, userId, sortKey, installationBlocks, status, suspendedUntil, createdAt
        case displayName = "description"
        case username = "reason"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        userId = (try? container.decodeIfPresent(String.self, forKey: .userId)) ?? id
        displayName = try? container.decodeIfPresent(String.self, forKey: .displayName)
        username = try? container.decodeIfPresent(String.self, forKey: .username)
        sortKey = try? container.decodeIfPresent(String.self, forKey: .sortKey)
        installationBlocks = (try? container.decodeIfPresent(Int64.self, forKey: .installationBlocks)) ?? 0
        status = (try? container.decodeIfPresent(String.self, forKey: .status)) ?? "active"
        suspendedUntil = try? container.decodeIfPresent(String.self, forKey: .suspendedUntil)
        createdAt = try? container.decodeIfPresent(String.self, forKey: .createdAt)
    }
}

struct AdminProfileDTO: Decodable, Equatable {
    var userId: String
    var username: String
    var displayName: String
    var hasAvatar: Bool = false
    var createdAt: String?
}

struct AdminModerationStateDTO: Decodable, Equatable {
    var status: String = "active"
    var until: String?
    var reason: String?
    var reportsTotal: Int64 = 0
    var reportsOpen: Int64 = 0
    var notes: Int64 = 0
}

struct AdminDeviceDTO: Decodable, Identifiable, Equatable {
    var id: String
    var installationId: String
    var platform: String?
    var displayName: String?
    var appVersion: String?
    var osVersion: String?
    var signalDeviceId: Int?
    var createdAt: String?
    var lastActiveAt: String?
    var revoked: Bool = false
    var restrictionActive: Bool = false
    var restrictionUntil: String?
    var restrictionReason: String?
    var isAdministratorDevice: Bool = false

    private enum CodingKeys: String, CodingKey {
        case id, installationId, platform, displayName, appVersion, osVersion
        case signalDeviceId, createdAt, lastActiveAt, revoked, restrictionActive
        case restrictionUntil, restrictionReason, isAdministratorDevice
    }

    /// Written out on purpose rather than synthesized.
    ///
    /// A synthesized `Decodable` does **not** fall back to a property's default
    /// value when a key is missing - it throws. For a device list that is fatal
    /// in the quietest possible way: one omitted field fails the element, the
    /// array decode fails with it, and the caller sees an empty list. Decoding
    /// every field with `decodeIfPresent` means a field the server stops sending
    /// (or has not started sending yet) degrades to the documented default
    /// instead of erasing the administrator's device list.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        installationId = try container.decode(String.self, forKey: .installationId)
        platform = try? container.decodeIfPresent(String.self, forKey: .platform)
        displayName = try? container.decodeIfPresent(String.self, forKey: .displayName)
        appVersion = try? container.decodeIfPresent(String.self, forKey: .appVersion)
        osVersion = try? container.decodeIfPresent(String.self, forKey: .osVersion)
        signalDeviceId = try? container.decodeIfPresent(Int.self, forKey: .signalDeviceId)
        createdAt = try? container.decodeIfPresent(String.self, forKey: .createdAt)
        lastActiveAt = try? container.decodeIfPresent(String.self, forKey: .lastActiveAt)
        revoked = (try? container.decodeIfPresent(Bool.self, forKey: .revoked)) ?? false
        restrictionActive = (try? container.decodeIfPresent(Bool.self, forKey: .restrictionActive)) ?? false
        restrictionUntil = try? container.decodeIfPresent(String.self, forKey: .restrictionUntil)
        restrictionReason = try? container.decodeIfPresent(String.self, forKey: .restrictionReason)
        isAdministratorDevice = (try? container.decodeIfPresent(Bool.self, forKey: .isAdministratorDevice)) ?? false
    }
}

struct AdminUserDetailDTO: Decodable, Equatable {
    var profile: AdminProfileDTO
    var moderation: AdminModerationStateDTO = AdminModerationStateDTO()
    var devices: [AdminDeviceDTO] = []
    var isAdministrator: Bool = false
    /// Mirrors the server rule so the client can explain the restriction instead
    /// of discovering it as a failure.
    var suspendAllowed: Bool = false

    private enum CodingKeys: String, CodingKey {
        case profile, moderation, devices, isAdministrator, suspendAllowed
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        profile = try container.decode(AdminProfileDTO.self, forKey: .profile)
        moderation = (try? container.decodeIfPresent(AdminModerationStateDTO.self, forKey: .moderation)) ?? AdminModerationStateDTO()
        devices = (try? container.decodeIfPresent([AdminDeviceDTO].self, forKey: .devices)) ?? []
        isAdministrator = (try? container.decodeIfPresent(Bool.self, forKey: .isAdministrator)) ?? false
        suspendAllowed = (try? container.decodeIfPresent(Bool.self, forKey: .suspendAllowed)) ?? false
    }
}

/// Machine-readable, non-secret context carried by timeline and audit events.
struct AdminEventDetailDTO: Decodable, Equatable {
    var reportId: String?
    var feedbackId: String?
    var installationId: String?
    var noteId: Int64?
    var status: String?
}

struct AdminTimelineEventDTO: Decodable, Identifiable, Equatable {
    var kind: String = "other"
    var occurredAt: String?
    var actor: String?
    var reason: String?
    var detail: AdminEventDetailDTO = AdminEventDetailDTO()
    /// Stable identity of the event inside the response; the de-duplication key.
    var ref: String?

    /// The timeline has no single id column: the server's `ref` is the identity.
    /// The fallback stays deterministic so a re-render never reorders the list.
    var id: String { ref ?? "\(occurredAt ?? "")|\(kind)|\(reason ?? "")" }
}

struct AdminAuditDTO: Decodable, Identifiable, Equatable {
    var id: String
    var actor: String?
    var action: String?
    var target: String?
    var reason: String?
    var detail: AdminEventDetailDTO = AdminEventDetailDTO()
    var createdAt: String?
}

struct AdminNoteDTO: Decodable, Identifiable, Equatable {
    var id: String
    var authorUserId: String?
    var body: String = ""
    var createdAt: String?
}

// ----- owner surfaces ------------------------------------------------------

struct AdminStorageDTO: Decodable {
    var usedBytes: Int64 = 0
    var reservedBytes: Int64 = 0
    var budgetBytes: Int64 = 0
    var warning: Bool = false
    var physicalFreeBytes: Int64?
    var users: [AdminStorageUserDTO] = []
}

struct AdminStorageUserDTO: Decodable {
    var userId: String
    var bytes: Int64 = 0
}

struct AdminPolicyDTO: Decodable {
    var name: String = ""
    var `operator`: String = ""
    var contact: String = ""
    var rules: String = ""
    var retention: String = ""
    var maxFileBytes: Int64 = 0
    var userQuotaBytes: Int64 = 0
    var storageBudgetBytes: Int64 = 0
}

struct AdminCleanupPlanDTO: Decodable {
    var planId: String
    var bytes: Int64 = 0
    var items: [AdminCleanupItemDTO] = []
}

struct AdminCleanupItemDTO: Decodable, Identifiable {
    var id: String
    var userId: String?
    var bytes: Int64 = 0
    var createdAt: String?
}

struct AdminCleanupResultDTO: Decodable {
    var deleted: Int64 = 0
    var skipped: Int64 = 0
    var failed: Int64 = 0
}

struct AdminOkDTO: Decodable {
    var ok: Bool = false
}

struct AdminNoteCreatedDTO: Decodable {
    var id: String
}

// ----- request bodies ------------------------------------------------------

/// `POST /admin/reports/:id/review`, `POST /admin/feedback/:id/review`.
///
/// `note` is always encoded, even when empty: `/admin/reports/:id/review`
/// rejects a body without it. `decision_reason` is omitted when empty, which is
/// exactly the server's own default.
struct AdminReviewBody: Encodable {
    var status: String
    var note: String
    var decisionReason: String?
}

/// `POST /admin/users/:id/suspension`.
///
/// `include_installations` is omitted when nil so restoring an account does not
/// silently lift installation restrictions.
struct AdminSuspensionBody: Encodable {
    var blocked: Bool
    var reason: String
    var until: String?
    var includeInstallations: Bool?
}

/// `POST /admin/installations/:installation_id/suspension`.
struct AdminDeviceSuspensionBody: Encodable {
    var blocked: Bool
    var reason: String
    var until: String?
}

struct AdminNoteBody: Encodable {
    var body: String
}

struct AdminCleanupBody: Encodable {
    var planId: String
}
