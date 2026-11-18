import Foundation

struct OwnerIdentity: Codable, Equatable, Sendable {
    let userId: String
    let unyriNumber: String
    let publicUsername: String?
    let discoverability: String
    let revision: Int64
    let usernameChangedAt: String?
    let publicOrigin: String

    enum CodingKeys: String, CodingKey {
        case userId, unyriNumber, publicUsername, discoverability, revision, usernameChangedAt, publicOrigin
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        userId = try values.decode(String.self, forKey: .userId)
        unyriNumber = try values.decode(String.self, forKey: .unyriNumber)
        publicUsername = try values.decodeIfPresent(String.self, forKey: .publicUsername)
        discoverability = try values.decode(String.self, forKey: .discoverability)
        revision = try values.decode(Int64.self, forKey: .revision)
        usernameChangedAt = try values.decodeIfPresent(String.self, forKey: .usernameChangedAt)
        publicOrigin = try values.decode(String.self, forKey: .publicOrigin)
        guard UUID(uuidString: userId) != nil, PublicIdentityPolicy.number(unyriNumber)?.count == 9,
              publicUsername == nil || PublicIdentityPolicy.username(publicUsername!) == publicUsername,
              ["everyone", "contacts", "nobody"].contains(discoverability), revision >= 0,
              PublicIdentityPolicy.origin(publicOrigin) != nil else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid owner identity"))
        }
    }
    var link: String? { PublicIdentityPolicy.link(number: unyriNumber, origin: publicOrigin) }
}

struct IdentityUpdate: Encodable {
    let expectedRevision: Int64
    let publicUsername: Patch<String>
    var discoverability: String? = nil
    enum CodingKeys: String, CodingKey { case expectedRevision, publicUsername, discoverability }
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(expectedRevision, forKey: .expectedRevision)
        switch publicUsername {
        case .absent: break
        case .clear: try values.encodeNil(forKey: .publicUsername)
        case .set(let value): try values.encode(value, forKey: .publicUsername)
        }
        try values.encodeIfPresent(discoverability, forKey: .discoverability)
    }
}

/// No legacy `username` (private login) exists in discovery responses.
struct DiscoveredProfile: Decodable, Equatable, Identifiable, Sendable {
    let id: String
    let unyriNumber: String
    let publicUsername: String?
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let hasAvatar: Bool
    let profileVersion: Int64
    let avatarVersion: Int64


    enum CodingKeys: String, CodingKey {
        case id, unyriNumber, publicUsername, displayName, bio, avatarUrl, hasAvatar, profileVersion, avatarVersion
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        unyriNumber = try values.decode(String.self, forKey: .unyriNumber)
        publicUsername = try values.decodeIfPresent(String.self, forKey: .publicUsername)
        displayName = try values.decode(String.self, forKey: .displayName)
        bio = try values.decodeIfPresent(String.self, forKey: .bio)
        avatarUrl = try values.decodeIfPresent(String.self, forKey: .avatarUrl)
        hasAvatar = try values.decode(Bool.self, forKey: .hasAvatar)
        profileVersion = try values.decode(Int64.self, forKey: .profileVersion)
        avatarVersion = try values.decode(Int64.self, forKey: .avatarVersion)
        guard UUID(uuidString: id) != nil, PublicIdentityPolicy.number(unyriNumber)?.count == 9,
              publicUsername == nil || PublicIdentityPolicy.username(publicUsername!) == publicUsername,
              profileVersion >= 0, avatarVersion >= 0 else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid public profile"))
        }
    }
    var contact: PublicUser {
        PublicUser(id: id, username: "", displayName: displayName, bio: bio, avatarUrl: avatarUrl,
                   profileVersion: profileVersion, avatarVersion: avatarVersion, hasAvatar: hasAvatar,
                   unyriNumber: unyriNumber, publicUsername: publicUsername)
    }
}

enum IdentityErrorText {
    static func key(status: Int, code: String?) -> String {
        switch (status, code) {
        case (409, "unavailable"): return "Public username is unavailable"
        case (409, "conflict"): return "Identity settings changed. Reloaded; review and try again."
        case (400, "validation_error"): return "Use 5–32 ASCII letters, digits or _, start with a letter, and avoid __."
        case (429, _): return "Too many attempts or username changed within 7 days. Try again later."
        case (403, _): return "This server does not allow this account to change its username."
        case (404, _): return "Profile not available."
        default: return "Could not load or save identity. Check your connection and try again."
        }
    }
    static func message(_ error: Error) -> String {
        if let error = error as? APIStatusError { return UnyriL10n.string(key(status: error.status, code: error.code)) }
        if let error = error as? APIError {
            switch error {
            case .server(let code, _) where code == "not_found": return UnyriL10n.string("Profile not available.")
            case .http(404): return UnyriL10n.string("Profile not available.")
            case _ where error.isRateLimited: return UnyriL10n.string(key(status: 429, code: nil))
            default: break
            }
        }
        return UnyriL10n.string(key(status: 0, code: nil))
    }
}
