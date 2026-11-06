import Foundation

/// Shared room geometry; portrait may fall back to two rows on compact widths.
/// These are visible/hit sizes, not transforms of larger invisible controls.
enum ConferenceRoomMetrics {
    static let tileRadius: CGFloat = 18
    static let gridGap: CGFloat = 6
    static let controlSize: CGFloat = 48
    static let controlIcon: CGFloat = 21
    static let panelRadius: CGFloat = 24
    static func videoDemand(grid: Set<String>, focused: String?) -> Set<String> {
        guard let focused else { return grid }
        return grid.union([focused])
    }
    static func showsSinglePlaceholder(total: Int, hasOwnTile: Bool, cameraEnabled: Bool) -> Bool {
        total == 1 && hasOwnTile && !cameraEnabled
    }
}

/// Pure wire/policy types. No capture, Signal state, persistence or UI work.
enum ConferenceAccessMode: String, CaseIterable, Codable {
    case open, password, invite, invitePassword = "invite_password"
    var needsPassword: Bool { self == .password || self == .invitePassword }
    var needsInvitation: Bool { self == .invite || self == .invitePassword }
    var title: String {
        switch self {
        case .open: return "Open room"
        case .password: return "Password required"
        case .invite: return "Invitation required"
        case .invitePassword: return "Invitation and password"
        }
    }
    var icon: String {
        switch self {
        case .open: return "door.left.hand.open"
        case .password: return "lock"
        case .invite: return "link"
        case .invitePassword: return "lock.badge.clock"
        }
    }
}

struct ConferenceDirectoryRoom: Decodable, Identifiable {
    let id: UUID
    let title: String
    let description: String
    let ownerUserId: UUID
    let ownerDisplayName: String
    let participantCount: Int
    let maxParticipants: Int
    let accessMode: String
    let admissionLocked: Bool
    var approvalRequired: Bool? = nil
    let keyAvailable: Bool
    let createdAt: Date
    let expiresAt: Date
    var access: ConferenceAccessMode? { ConferenceAccessMode(rawValue: accessMode) }
    var canRequestKey: Bool { access.map { !$0.needsInvitation } == true && !admissionLocked && participantCount >= 0 && (2...30).contains(maxParticipants) && participantCount < maxParticipants && expiresAt > Date() }
}
struct ConferenceDirectoryPage: Decodable {
    let items: [ConferenceDirectoryRoom]
    let nextCursor: String?
}

enum ConferencePasswordAction: String, Encodable { case keep, set, remove }
struct ConferenceRoomConfiguration: Equatable {
    var title: String
    var description = ""
    var listed = false
    var accessMode: ConferenceAccessMode = .invite
    var admissionLocked = false
    var approvalRequired: Bool? = nil
    var passwordAction: ConferencePasswordAction = .keep
    var password: String?

    func validate(creating: Bool) throws {
        guard Self.validText(title.trimmingCharacters(in: .whitespacesAndNewlines), scalars: 1...80, bytes: 320),
              Self.validText(description, scalars: 0...240, bytes: 960) else { throw ConferenceDirectoryFailure.invalidSettings }
        if passwordAction == .set {
            guard let password, Self.validText(password, scalars: 6...128, bytes: 512), accessMode.needsPassword else { throw ConferenceDirectoryFailure.invalidPassword }
        } else if password != nil { throw ConferenceDirectoryFailure.invalidPassword }
        if creating && accessMode.needsPassword && passwordAction != .set { throw ConferenceDirectoryFailure.invalidPassword }
        if accessMode.needsPassword && passwordAction == .remove { throw ConferenceDirectoryFailure.invalidPassword }
    }
    static func validText(_ value: String, scalars: ClosedRange<Int>, bytes: Int) -> Bool {
        scalars.contains(value.unicodeScalars.count) && value.utf8.count <= bytes &&
            !value.unicodeScalars.contains { $0.properties.generalCategory == .control || $0.value == 0x2028 || $0.value == 0x2029 }
    }
}

enum ConferenceDirectoryFailure: Error, LocalizedError, Equatable {
    case unavailable, invalidSettings, invalidPassword, passwordRequired, incorrectPassword, changed, expired, invalidHandoff, identity, waiting, removed
    var errorDescription: String? {
        switch self {
        case .unavailable: return "Rooms directory is unavailable on this server."
        case .invalidSettings: return "Check the room name and description."
        case .invalidPassword: return "Use a password of 6–128 characters. Spaces are preserved."
        case .passwordRequired: return "Enter this room's password."
        case .incorrectPassword: return "Incorrect room password. Try again."
        case .changed: return "Room access changed. Refresh and try again."
        case .expired: return "The key request expired. Try again when a participant is online."
        case .invalidHandoff: return "The encrypted room key could not be verified."
        case .identity: return "The participant's identity needs verification before receiving the room key."
        case .waiting: return "Waiting for an online participant with the room key."
        case .removed: return "The creator removed you from this room. This account cannot rejoin the room."
        }
    }
}

/// Never route these bytes through MessageContentCodec or the chat outbox.
struct ConferenceKeyRequest: Decodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    let id: String
    let roomId: String
    let requesterUserId: String
    let requesterDeviceId: String
    let providerUserId: String?
    let providerDeviceId: String?
    let providerIdentity: String?
    let assignmentRevision: Int64
    let nonce: String
    let accessRevision: Int64
    let expiresAt: Date
    let status: String
    let envelope: EnvelopeInput?
    var description: String { "ConferenceKeyRequest(<redacted>)" }
    var debugDescription: String { description }

    /// A ciphertext may be retried only for this exact admission assignment.
    /// Keep the immutable request bindings as well as the revision counters:
    /// a changed recipient, nonce or deadline must never reuse Signal output.
    var encryptedEnvelopeBinding: String {
        [roomId, id, requesterUserId, requesterDeviceId, providerUserId ?? "", providerDeviceId ?? "",
         providerIdentity ?? "", String(assignmentRevision), nonce, String(accessRevision),
         String(expiresAt.timeIntervalSince1970.bitPattern)].joined(separator: "#")
    }

    func validate(now: Date, allowConsumed: Bool = false) throws {
        guard [id, roomId, requesterUserId, requesterDeviceId].allSatisfy(ConferenceKeyPayload.canonicalID),
              accessRevision > 0, assignmentRevision >= 0, ConferenceKeyPayload.canonicalKey(nonce),
              expiresAt.timeIntervalSince1970.isFinite, expiresAt > now,
              expiresAt.timeIntervalSince(now) <= 90,
              ["pending", "ready", "consumed", "cancelled", "expired", "revoked"].contains(status),
              allowConsumed || status != "consumed" else { throw ConferenceDirectoryFailure.invalidHandoff }
        if assignmentRevision > 0 {
            guard let providerUserId, let providerDeviceId, let providerIdentity,
                  ConferenceKeyPayload.canonicalID(providerUserId), ConferenceKeyPayload.canonicalID(providerDeviceId),
                  ConferenceKeyPayload.validIdentity(providerIdentity, user: providerUserId, device: providerDeviceId) else { throw ConferenceDirectoryFailure.invalidHandoff }
        } else if providerUserId != nil || providerDeviceId != nil || providerIdentity != nil || envelope != nil || status == "ready" {
            throw ConferenceDirectoryFailure.invalidHandoff
        }
        if let envelope {
            guard envelope.recipientDeviceId == requesterDeviceId, (1...2).contains(envelope.messageType),
                  let data = Data(base64Encoded: envelope.ciphertext), !data.isEmpty, data.count <= 8192,
                  data.base64EncodedString() == envelope.ciphertext else { throw ConferenceDirectoryFailure.invalidHandoff }
        }
        if status == "ready" && envelope == nil { throw ConferenceDirectoryFailure.invalidHandoff }
        if status == "pending" && envelope != nil { throw ConferenceDirectoryFailure.invalidHandoff }
    }
}
struct ConferenceKeyRequests: Decodable { let items: [ConferenceKeyRequest] }

/// Keys are RAM-only, never serializable as an invitation. Catalog visitors do
/// not learn the shared invitation secret and cannot export a capability card.
struct ConferenceCatalogCredential: CustomStringConvertible, CustomDebugStringConvertible {
    let roomID: UUID
    let requestID: UUID
    let mediaKey: String
    var description: String { "ConferenceCatalogCredential(<redacted>)" }
    var debugDescription: String { description }
}
enum ConferenceAdmissionCredential: CustomStringConvertible, CustomDebugStringConvertible {
    case invitation(ConferenceInvite, password: String?)
    case catalog(ConferenceCatalogCredential)
    var roomID: UUID { switch self { case .invitation(let v, _): return v.roomID; case .catalog(let v): return v.roomID } }
    var mediaKey: String { switch self { case .invitation(let v, _): return v.mediaKey; case .catalog(let v): return v.mediaKey } }
    var invitation: ConferenceInvite? { if case .invitation(let value, _) = self { return value }; return nil }
    var description: String { "ConferenceAdmissionCredential(<redacted>)" }
    var debugDescription: String { description }
}

struct ConferenceKeyPayload: Codable, CustomStringConvertible, CustomDebugStringConvertible {
    let version: Int
    let origin: String
    let roomID: String
    let requestID: String
    let requesterUserID: String
    let requesterDeviceID: String
    let providerUserID: String
    let providerDeviceID: String
    let providerIdentity: String
    let assignmentRevision: Int64
    let nonce: String
    let accessRevision: Int64
    let expiresAt: Int64
    let mediaKey: String
    enum CodingKeys: String, CodingKey, CaseIterable {
        case version, origin, roomID = "room_id", requestID = "request_id", requesterUserID = "requester_user_id"
        case requesterDeviceID = "requester_device_id", providerUserID = "provider_user_id", providerDeviceID = "provider_device_id"
        case providerIdentity = "provider_identity", assignmentRevision = "assignment_revision", nonce, accessRevision = "access_revision"
        case expiresAt = "expires_at", mediaKey = "media_key"
    }
    var description: String { "ConferenceKeyPayload(<redacted>)" }
    var debugDescription: String { description }
    static func canonicalID(_ value: String) -> Bool { UUID(uuidString: value)?.uuidString.lowercased() == value }
    static func canonicalKey(_ value: String) -> Bool { (try? ConferenceInvite.decodeKey(value)) != nil }
    static func validIdentity(_ value: String, user: String, device: String) -> Bool {
        let pieces = value.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        return pieces.count == 3 && pieces[0] == user && pieces[1] == device && pieces.allSatisfy(canonicalID)
    }
    func validate(request: ConferenceKeyRequest, server: URL, requester: String, device: String, now: Date) throws {
        try request.validate(now: now)
        guard version == 1, let url = URL(string: origin), try ConferenceInvite.origin(url).absoluteString == origin,
              try ConferenceInvite.origin(server).absoluteString == origin,
              request.status == "ready" || request.status == "pending",
              [roomID, requestID, requesterUserID, requesterDeviceID, providerUserID, providerDeviceID].allSatisfy(Self.canonicalID),
              roomID == request.roomId, requestID == request.id,
              requesterUserID == request.requesterUserId, requesterDeviceID == request.requesterDeviceId,
              requesterUserID == requester, requesterDeviceID == device,
              providerUserID == request.providerUserId, providerDeviceID == request.providerDeviceId,
              providerIdentity == request.providerIdentity, Self.validIdentity(providerIdentity, user: providerUserID, device: providerDeviceID),
              assignmentRevision > 0, assignmentRevision == request.assignmentRevision,
              nonce == request.nonce, Self.canonicalKey(nonce),
              accessRevision > 0, accessRevision == request.accessRevision,
              expiresAt == Int64(floor(request.expiresAt.timeIntervalSince1970)), Double(expiresAt) > now.timeIntervalSince1970,
              Self.canonicalKey(mediaKey) else { throw ConferenceDirectoryFailure.invalidHandoff }
    }
    static func decode(_ bytes: Data, request: ConferenceKeyRequest, server: URL, requester: String, device: String, now: Date) throws -> ConferenceKeyPayload {
        try strictShape(bytes)
        let value = try JSONDecoder().decode(Self.self, from: bytes)
        try value.validate(request: request, server: server, requester: requester, device: device, now: now)
        return value
    }
    /// Foundation accepts duplicate JSON keys and integral floating numbers.
    /// This flat, bounded protocol rejects both before ordinary Codable runs.
    private static func strictShape(_ data: Data) throws {
        guard data.count <= 4096 else { throw ConferenceDirectoryFailure.invalidHandoff }
        let bytes = Array(data); var i = 0; var keys = Set<String>()
        let integerKeys = Set(["version", "assignment_revision", "access_revision", "expires_at"])
        let expected = Set(CodingKeys.allCases.map(\.rawValue))
        func space() { while i < bytes.count && [9, 10, 13, 32].contains(bytes[i]) { i += 1 } }
        func take(_ c: UInt8) throws { space(); guard i < bytes.count, bytes[i] == c else { throw ConferenceDirectoryFailure.invalidHandoff }; i += 1 }
        func string() throws -> String {
            space(); let start = i
            guard i < bytes.count, bytes[i] == 34 else { throw ConferenceDirectoryFailure.invalidHandoff }; i += 1
            var closed = false
            while i < bytes.count {
                let c = bytes[i]; i += 1
                if c == 34 { closed = true; break }
                if c == 92 { guard i < bytes.count else { throw ConferenceDirectoryFailure.invalidHandoff }; i += 1 }
                else if c < 32 { throw ConferenceDirectoryFailure.invalidHandoff }
            }
            guard closed else { throw ConferenceDirectoryFailure.invalidHandoff }
            return try JSONDecoder().decode(String.self, from: Data(bytes[start..<i]))
        }
        try take(123); space()
        while i < bytes.count && bytes[i] != 125 {
            let key = try string()
            guard expected.contains(key), keys.insert(key).inserted else { throw ConferenceDirectoryFailure.invalidHandoff }
            try take(58); space()
            if integerKeys.contains(key) {
                let start = i
                while i < bytes.count && (48...57).contains(bytes[i]) { i += 1 }
                guard i > start, (i - start == 1 || bytes[start] != 48), Int64(String(decoding: bytes[start..<i], as: UTF8.self)) != nil else { throw ConferenceDirectoryFailure.invalidHandoff }
            } else { _ = try string() }
            space(); guard i < bytes.count else { throw ConferenceDirectoryFailure.invalidHandoff }
            if bytes[i] == 125 { break }
            try take(44); space(); guard i < bytes.count, bytes[i] != 125 else { throw ConferenceDirectoryFailure.invalidHandoff }
        }
        try take(125); space()
        guard i == bytes.count, keys == expected else { throw ConferenceDirectoryFailure.invalidHandoff }
    }
    static func providing(request: ConferenceKeyRequest, server: URL, mediaKey: String, now: Date) throws -> Data {
        guard let user = request.providerUserId, let device = request.providerDeviceId, let identity = request.providerIdentity else { throw ConferenceDirectoryFailure.invalidHandoff }
        let value = Self(version: 1, origin: try ConferenceInvite.origin(server).absoluteString, roomID: request.roomId, requestID: request.id,
            requesterUserID: request.requesterUserId, requesterDeviceID: request.requesterDeviceId,
            providerUserID: user, providerDeviceID: device, providerIdentity: identity, assignmentRevision: request.assignmentRevision,
            nonce: request.nonce, accessRevision: request.accessRevision, expiresAt: Int64(floor(request.expiresAt.timeIntervalSince1970)), mediaKey: mediaKey)
        try value.validate(request: request, server: server, requester: request.requesterUserId, device: request.requesterDeviceId, now: now)
        return try JSONEncoder().encode(value)
    }
}
