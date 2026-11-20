import Foundation

// Uses the unlocked AdminPanelView callback, retaining its security checks.
struct SelfHostingAdminReply: Decodable {
    struct PublicURL: Decodable { let configured: Bool }
    struct Turn: Decodable { let sane: Bool }
    struct Rooms: Decodable { let configured: Bool }
    struct Push: Decodable { let status: String }
    struct Storage: Decodable { let reachable: Bool; let clientUploadsReady: Bool }
    struct Disk: Decodable { let lowSpace: Bool? }
    var ok: Bool?
    var registrationMode: String?
    var registrationOpen: Bool?
    var items: [SelfHostingInvite]?
    var nextBefore: String?
    var id: String?
    var code: String?
    var expiresAt: String?
    var maxUses: Int?
    var uses: Int?
    var publicUrl: PublicURL?
    var turn: Turn?
    var rooms: Rooms?
    var push: Push?
    var storage: Storage?
    var disk: Disk?
}

struct SelfHostingInvite: Decodable, Identifiable {
    let id: String
    let status: String
    let maxUses: Int
    let uses: Int
    let expiresAt: String
    var statusKey: String {
        switch status {
        case "active": return "Active invitation"
        case "revoked": return "Revoked invitation"
        case "expired": return "Expired invitation"
        case "exhausted": return "Used invitation"
        default: return "Invitation"
        }
    }
}

enum AdminInvitationPolicy {
    static let defaultUses = "1"
    static let defaultDays = "7"
    static func mayManage(_ role: String) -> Bool { role == "owner" || role == "moderator" }
    static func mayChangeMode(_ role: String) -> Bool { role == "owner" }
    static func limits(uses: String, days: String) -> (uses: Int, seconds: Int)? {
        guard (1...5).contains(uses.utf8.count), (1...3).contains(days.utf8.count),
              uses.utf8.allSatisfy({ (48...57).contains($0) }),
              days.utf8.allSatisfy({ (48...57).contains($0) }),
              let count = Int(uses), (1...10000).contains(count),
              let lifetime = Int(days), (1...365).contains(lifetime) else { return nil }
        return (count, lifetime * 86400)
    }
    static func expiry(_ value: String) -> Date? {
        guard value.utf8.count <= 64 else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
    static func validPage(_ reply: SelfHostingAdminReply, after cursor: String? = nil) -> Bool {
        guard let items = reply.items, items.count <= 100,
              Set(items.map(\.id)).count == items.count,
              items.allSatisfy({ UUID(uuidString: $0.id) != nil && (1...10000).contains($0.maxUses)
                  && (0...$0.maxUses).contains($0.uses) && expiry($0.expiresAt) != nil }),
              reply.nextBefore == nil || UUID(uuidString: reply.nextBefore ?? "") != nil else { return false }
        // A cursor must be the last row and advance, otherwise Load more can loop.
        return reply.nextBefore == nil || (!items.isEmpty && reply.nextBefore == items.last?.id && reply.nextBefore != cursor)
    }
}

enum AdminRegistrationError: Error { case forbidden, invalidLimits, invalidReply }

/// Only codes created in this foreground administration screen are retained.
struct AdminIssuedInvitationCodes {
    private var values: [String: String] = [:]
    private var order: [String] = []
    subscript(_ id: String) -> String? { values[id] }
    mutating func store(_ code: String, for id: String) {
        remove(id)
        values[id] = code; order.append(id)
        if order.count > 100 { values.removeValue(forKey: order.removeFirst()) }
    }
    mutating func remove(_ id: String) { values.removeValue(forKey: id); order.removeAll { $0 == id } }
    mutating func clear() { values = [:]; order = [] }
}

@MainActor
struct AdminRegistrationClient {
    let role: String
    let request: @MainActor (String, [String: Any]?) async throws -> SelfHostingAdminReply

    func registration() async throws -> SelfHostingAdminReply {
        guard AdminInvitationPolicy.mayChangeMode(role) else { throw AdminRegistrationError.forbidden }
        let reply = try await request("registration", nil)
        guard let mode = reply.registrationMode, ["open", "invite_only"].contains(mode), reply.registrationOpen != nil else {
            throw AdminRegistrationError.invalidReply
        }
        return reply
    }
    func saveMode(_ mode: String) async throws {
        guard AdminInvitationPolicy.mayChangeMode(role) else { throw AdminRegistrationError.forbidden }
        guard ["open", "invite_only"].contains(mode) else { throw AdminRegistrationError.invalidLimits }
        let reply = try await request("registration", ["registration_mode": mode])
        guard reply.ok == true else { throw AdminRegistrationError.invalidReply }
    }
    func create(uses: String = AdminInvitationPolicy.defaultUses, days: String = AdminInvitationPolicy.defaultDays) async throws -> SelfHostingAdminReply {
        guard AdminInvitationPolicy.mayManage(role) else { throw AdminRegistrationError.forbidden }
        guard let limits = AdminInvitationPolicy.limits(uses: uses, days: days) else { throw AdminRegistrationError.invalidLimits }
        let reply = try await request("invites", ["max_uses": limits.uses, "expires_in_seconds": limits.seconds])
        guard let id = reply.id, UUID(uuidString: id) != nil,
              let code = reply.code, code.hasPrefix(id + "."), (id.utf8.count + 2...256).contains(code.utf8.count),
              code.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || [45, 46, 95].contains($0) }),
              reply.maxUses == limits.uses, reply.uses == 0,
              let expires = reply.expiresAt, AdminInvitationPolicy.expiry(expires) != nil else { throw AdminRegistrationError.invalidReply }
        return reply
    }
    func invitations(before: String? = nil) async throws -> SelfHostingAdminReply {
        guard AdminInvitationPolicy.mayManage(role) else { throw AdminRegistrationError.forbidden }
        if let cursor = before, UUID(uuidString: cursor) == nil { throw AdminRegistrationError.invalidLimits }
        let reply = try await request("invites" + (before.map { "?before=" + $0 } ?? ""), nil)
        guard AdminInvitationPolicy.validPage(reply, after: before) else { throw AdminRegistrationError.invalidReply }
        return reply
    }
    func revoke(_ id: String) async throws {
        guard AdminInvitationPolicy.mayManage(role) else { throw AdminRegistrationError.forbidden }
        guard UUID(uuidString: id) != nil else { throw AdminRegistrationError.invalidLimits }
        let reply = try await request("invites/" + id + "/revoke", [:])
        guard reply.ok == true else { throw AdminRegistrationError.invalidReply }
    }
}

/// Shared by sign-in and the native invitation screen; server bodies stay private.
enum AdminRequestFailurePolicy {
    static func messageKey(status: Int?, networkFailure: Bool = false, invitationOperation: Bool = false) -> String? {
        if networkFailure { return "Could not connect securely to the server. Check the network and server certificate." }
        guard let status else { return nil }
        switch status {
        case 401: return "The server rejected sign-in. Check your account password and the current authenticator code. This does not mean the code alone is wrong."
        case 429: return invitationOperation ? "Too many attempts. Wait before trying again." : "Too many attempts. Wait 5 minutes before trying again."
        case 500...599: return "The server could not complete the request. Contact the server owner."
        default: return nil
        }
    }
}
