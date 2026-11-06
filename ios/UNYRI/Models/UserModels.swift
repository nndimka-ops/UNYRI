import Foundation

// MARK: - GET /users/me (own profile)

struct UserProfile: Codable, Identifiable, Equatable {
    let id: String
    let username: String
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let profileVersion: Int64
    let avatarVersion: Int64
    let hasAvatar: Bool
    let createdAt: String
    let updatedAt: String
    var adminRole: String? = nil
}

// MARK: - Public user (search results)

struct PublicUser: Codable, Identifiable, Equatable {
    let id: String
    let username: String
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let profileVersion: Int64
    let avatarVersion: Int64
    let hasAvatar: Bool
    // Directory hints are never used as live presence without a WS snapshot.
    var online: Bool? = nil
    var lastSeenAt: String? = nil
}

// MARK: - GET /users/search

struct SearchResponse: Codable, Equatable {
    let items: [PublicUser]
    let nextOffset: Int64?
}

// Account-level security data, independent of chat/history availability.
struct BlockedAccount: Codable, Identifiable, Equatable {
    let user: PublicUser
    let blockedAt: String
    var id: String { user.id }
}

struct BlockedAccountsPage: Codable, Equatable {
    let items: [BlockedAccount]
    let nextCursor: String?
}

// MARK: - PATCH /users/me
// Tri-state field update matching the backend `Patch<T>`: a field can be
// omitted (leave unchanged), sent as `null` (clear), or set to a value.
// A plain Optional can't distinguish "absent" from "null", which matters for
// nullable columns like `avatar_url` / `bio`.

enum Patch<Value> {
    case absent
    case clear
    case set(Value)
}

struct UpdateProfileRequest: Encodable {
    let username: String
    let displayName: String
    let bio: Patch<String>
    let avatarUrl: Patch<String>

    enum CodingKeys: String, CodingKey {
        case username
        case displayName
        case bio
        case avatarUrl
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(username, forKey: .username)
        try container.encode(displayName, forKey: .displayName)
        switch bio {
        case .absent:
            break
        case .clear:
            try container.encodeNil(forKey: .bio)
        case .set(let value):
            try container.encode(value, forKey: .bio)
        }
        switch avatarUrl {
        case .absent:
            break
        case .clear:
            try container.encodeNil(forKey: .avatarUrl)
        case .set(let value):
            try container.encode(value, forKey: .avatarUrl)
        }
    }
}

// MARK: - GET /users/:user_id/avatar

struct AvatarDownloadResponse: Decodable, Equatable {
    let downloadUrl: String
    let expiresAt: String
}

struct AvatarUploadResponse: Decodable {
    let objectKey: String
    let uploadUrl: String
    let expiresAt: String
}

struct CompleteAvatarRequest: Encodable {
    let objectKey: String
}

struct PresenceItem: Codable, Equatable {
    let userId: String
    let online: Bool
    let lastSeenAt: String?
}

struct PresenceSnapshot: Decodable {
    let type: String
    let items: [PresenceItem]
    let observedAt: String
    let validForMs: Int
}

struct PresenceObservation: Equatable {
    let item: PresenceItem
    let observedAt: Date
    let expiresAtUptime: TimeInterval
}

/// Historical evidence is separate from the five-second online lease. It can
/// explain when a peer was last observed, but can never make them appear online.
/// Kept only in the account-scoped realtime object's memory, never on disk.
struct PresenceHistory {
    private(set) var dates: [String: Date] = [:]

    mutating func apply(_ entries: [String: PresenceObservation], requestedIDs: [String]) {
        let requested = Set(requestedIDs.compactMap { UUID(uuidString: $0)?.uuidString.lowercased() })
        // A full authoritative snapshot omits blocked/unknown peers. Do not
        // keep their former timestamp as a fallback after that omission.
        for id in requested where entries[id] == nil { dates[id] = nil }
        for (id, observation) in entries {
            guard requested.contains(id) else { continue }
            // A null is authoritative even while online remains visible. Never
            // retain an inferred last-seen fallback when its separate policy hides it.
            guard observation.item.lastSeenAt != nil else { dates[id] = nil; continue }
            let date = observation.item.online ? observation.observedAt
                : observation.item.lastSeenAt.flatMap(PresencePolicy.timestamp)
            guard let date, date.timeIntervalSince(observation.observedAt) <= 5 else {
                dates[id] = nil; continue
            }
            dates[id] = max(dates[id] ?? .distantPast, date)
        }
        if dates.count > 128 {
            dates = Dictionary(uniqueKeysWithValues: dates.sorted {
                $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value
            }.prefix(128).map { ($0.key, $0.value) })
        }
    }

    func date(for userID: String) -> Date? {
        UUID(uuidString: userID).flatMap { dates[$0.uuidString.lowercased()] }
    }
    mutating func clear() { dates.removeAll() }
}

/// Server observations expire against monotonic receipt time; changing the
/// device clock cannot prolong an online badge. Missing data stays unknown.
enum PresencePolicy {
    static func fresh(_ value: PresenceObservation, uptime: TimeInterval) -> Bool {
        uptime < value.expiresAtUptime
    }

    static func timestamp(_ raw: String) -> Date? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = parser.date(from: raw) { return date }
        parser.formatOptions = [.withInternetDateTime]
        return parser.date(from: raw)
    }

    static func snapshot(_ frame: PresenceSnapshot, requestedIDs: [String], receivedAt: Date,
                         uptime: TimeInterval, newerThan previous: Date?) -> (observedAt: Date, entries: [String: PresenceObservation], lifetime: TimeInterval)? {
        guard frame.type == "presence.snapshot", frame.items.count <= 64, frame.validForMs > 0,
              let observed = timestamp(frame.observedAt), observed.timeIntervalSince(receivedAt) <= 5,
              previous.map({ observed > $0 }) ?? true else { return nil }
        // Reduce the lease for a delayed observation instead of renewing old
        // online data for another five seconds after resuming the app.
        let lifetime = Double(min(5000, frame.validForMs)) / 1000 - max(0, receivedAt.timeIntervalSince(observed))
        guard lifetime > 0 else { return nil }
        let requested = Set(requestedIDs.compactMap { UUID(uuidString: $0)?.uuidString.lowercased() })
        var entries: [String: PresenceObservation] = [:]
        for item in frame.items {
            guard let id = UUID(uuidString: item.userId)?.uuidString.lowercased(), requested.contains(id) else { continue }
            entries[id] = PresenceObservation(item: item, observedAt: observed, expiresAtUptime: uptime + lifetime)
        }
        return (observed, entries, lifetime)
    }

    static func subscriptions(_ owners: [(priority: Int, owner: String, ids: [String])]) -> [String] {
        var seen = Set<String>()
        return owners.sorted {
            $0.priority == $1.priority ? $0.owner < $1.owner : $0.priority > $1.priority
        }.flatMap(\.ids).compactMap { raw in
            guard let id = UUID(uuidString: raw)?.uuidString.lowercased(), seen.insert(id).inserted else { return nil }
            return id
        }.prefix(64).map { $0 }
    }

    static func lastSeenLabel(_ date: Date, now: Date, locale: Locale, calendar: Calendar) -> (key: String, value: String) {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        if calendar.isDate(date, inSameDayAs: now) {
            formatter.timeStyle = .short
            return ("Last seen today at %@", formatter.string(from: date))
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            formatter.timeStyle = .short
            return ("Last seen yesterday at %@", formatter.string(from: date))
        }
        formatter.setLocalizedDateFormatFromTemplate(calendar.component(.year, from: date) == calendar.component(.year, from: now) ? "dMMMjm" : "dMMMyyyyjm")
        return ("Last seen %@", formatter.string(from: date))
    }
}

struct DirectoryIdentityCache: Codable {
    let scope: String
    let users: [PublicUser]
}

enum DirectoryCachePolicy {
    static func restored(_ cache: DirectoryIdentityCache, scope: String, query: String) -> [PublicUser] {
        guard cache.scope == scope else { return [] }
        return matching(cache.users, query: query)
    }

    static func identities(_ users: [PublicUser]) -> [PublicUser] {
        var seen = Set<String>()
        return users.compactMap { user in
            guard let id = UUID(uuidString: user.id)?.uuidString.lowercased(), seen.insert(id).inserted else { return nil }
            var identity = user
            identity.online = nil; identity.lastSeenAt = nil
            return identity
        }.prefix(100).map { $0 }
    }

    static func matching(_ users: [PublicUser], query: String) -> [PublicUser] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let term = trimmed.hasPrefix("@") ? String(trimmed.dropFirst()) : trimmed
        return identities(users).filter { term.isEmpty || $0.displayName.localizedCaseInsensitiveContains(term) || $0.username.localizedCaseInsensitiveContains(term) }
    }
}

/// Retain the last applied union only during a short consumer handoff. Server
/// snapshots and leases remain authoritative; elapsed releases never renew.
struct PresenceOwnerHandoff {
    static let grace: TimeInterval = 0.35
    private(set) var effectiveIDs: [String] = []
    private(set) var desiredIDs: [String] = []
    private(set) var releaseToken: UUID?
    private(set) var releaseDeadline: TimeInterval?

    func currentIDs(uptime: TimeInterval) -> [String] {
        if let deadline = releaseDeadline, uptime >= deadline { return desiredIDs }
        return effectiveIDs
    }
    @discardableResult
    mutating func update(desired: [String], deferRemovals: Bool, uptime: TimeInterval) -> UUID? {
        if let deadline = releaseDeadline, uptime >= deadline { reset(to: desiredIDs) }
        desiredIDs = PresencePolicy.subscriptions([(0, "desired", desired)])
        let removed = Set(effectiveIDs).subtracting(desiredIDs)
        if deferRemovals, uptime.isFinite, !removed.isEmpty {
            if releaseToken == nil {
                releaseToken = UUID(); releaseDeadline = uptime + Self.grace
            }
            effectiveIDs = PresencePolicy.subscriptions([(1, "desired", desiredIDs), (0, "handoff", effectiveIDs)])
            return releaseToken
        }
        effectiveIDs = desiredIDs; releaseToken = nil; releaseDeadline = nil
        return nil
    }
    @discardableResult
    mutating func release(_ token: UUID) -> Bool {
        guard releaseToken == token else { return false }
        reset(to: desiredIDs)
        return true
    }
    mutating func reset(to ids: [String]) {
        desiredIDs = ids; effectiveIDs = ids; releaseToken = nil; releaseDeadline = nil
    }
}
