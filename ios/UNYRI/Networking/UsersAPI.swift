import Foundation

/// REST client for the profile / search endpoints. Every call takes a
/// caller-supplied access token; refreshing it is the caller's responsibility
/// (AuthRepository.currentAccessToken()), mirroring Android.
struct UsersAPI {
    let client: APIClient

    // No redirects or shared HTTP cache/cookies for public identity routes.
    private var identityClient: APIClient { client.identityTransport() }
    func identity(accessToken: String) async throws -> OwnerIdentity {
        try await identityClient.getIdentity("/users/me/identity", accessToken: accessToken, as: OwnerIdentity.self)
    }
    func updateIdentity(accessToken: String, request: IdentityUpdate) async throws -> OwnerIdentity {
        try await identityClient.putIdentity(accessToken: accessToken, body: request, as: OwnerIdentity.self)
    }
    func lookup(accessToken: String, target: PublicIdentityPolicy.Target) async throws -> DiscoveredProfile {
        let profile = try await identityClient.getIdentity("/users/lookup", accessToken: accessToken,
            query: [URLQueryItem(name: target.queryName, value: target.value)], as: DiscoveredProfile.self)
        let matches: Bool
        switch target {
        case .number(let number): matches = number.count < 9 || profile.unyriNumber == number
        case .username(let username): matches = profile.publicUsername == username
        }
        guard matches else { throw APIError.decoding("Lookup target mismatch") }
        return profile
    }
    func publicProfile(accessToken: String, userID: String) async throws -> DiscoveredProfile {
        guard UUID(uuidString: userID) != nil else { throw APIError.http(404) }
        let profile = try await identityClient.getIdentity("/users/\(userID)/profile", accessToken: accessToken, as: DiscoveredProfile.self)
        guard profile.id.lowercased() == userID.lowercased() else { throw APIError.decoding("Profile target mismatch") }
        return profile
    }

    func deleteAccount(accessToken: String, password: String) async throws {
        try await client.postNoContent("/users/me/delete", bearerToken: accessToken, body: ["password": password])
    }

    func createAvatar(accessToken: String, sizeBytes: Int) async throws -> AvatarUploadResponse {
        try await client.post("/users/me/avatar", accessToken: accessToken, body: ["size_bytes": sizeBytes], as: AvatarUploadResponse.self)
    }

    func completeAvatar(accessToken: String, objectKey: String) async throws -> UserProfile {
        try await client.post("/users/me/avatar/complete", accessToken: accessToken,
            body: CompleteAvatarRequest(objectKey: objectKey), as: UserProfile.self)
    }

    func deleteAvatar(accessToken: String) async throws -> UserProfile {
        try await client.delete("/users/me/avatar", accessToken: accessToken, as: UserProfile.self)
    }

    func getMe(accessToken: String) async throws -> UserProfile {
        try await client.get("/users/me", accessToken: accessToken, as: UserProfile.self)
    }

    func updateProfile(accessToken: String, request: UpdateProfileRequest) async throws -> UserProfile {
        try await client.patch("/users/me", accessToken: accessToken, body: request, as: UserProfile.self)
    }

    func search(accessToken: String, query: String, limit: Int, offset: Int64) async throws -> SearchResponse {
        let items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "offset", value: String(offset)),
        ]
        return try await client.get("/users/search", accessToken: accessToken, query: items, as: SearchResponse.self)
    }

    func directory(accessToken: String, query: String, limit: Int = 30, offset: Int64 = 0) async throws -> SearchResponse {
        try await client.get("/users/directory", accessToken: accessToken, query: [
            URLQueryItem(name: "q", value: query), URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "offset", value: String(offset))
        ], as: SearchResponse.self)
    }

    func avatarDownloadURL(accessToken: String, userId: String) async throws -> AvatarDownloadResponse {
        try await client.get("/users/\(userId)/avatar", accessToken: accessToken, query: [URLQueryItem(name: "size", value: "640")], as: AvatarDownloadResponse.self)
    }

    func blockedAccounts(accessToken: String, query: String, cursor: String?) async throws -> BlockedAccountsPage {
        var items = [URLQueryItem(name: "limit", value: "50"), URLQueryItem(name: "q", value: query)]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        return try await client.get("/users/blocked", accessToken: accessToken, query: items, as: BlockedAccountsPage.self)
    }

    func setBlocked(accessToken: String, userID: String, blocked: Bool) async throws {
        guard UUID(uuidString: userID) != nil else { throw APIError.http(400) }
        if blocked {
            try await client.postNoContent("/users/\(userID)/block", bearerToken: accessToken)
        } else {
            try await client.deleteNoContent("/users/\(userID)/block", accessToken: accessToken)
        }
    }

    func contacts(accessToken: String, cursor: String?) async throws -> ContactsPage {
        var items = [URLQueryItem(name: "limit", value: "200")]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        return try await client.get("/users/contacts", accessToken: accessToken, query: items, as: ContactsPage.self)
    }

    /// One person's membership: what a profile needs, without paging the book.
    func contactStatus(accessToken: String, userID: String) async throws -> Bool {
        guard UUID(uuidString: userID) != nil else { throw APIError.http(400) }
        return try await client.get("/users/\(userID)/contact", accessToken: accessToken, as: ContactStatusResponse.self).isContact
    }

    /// Both directions are idempotent on the server, so a retry is always safe.
    func setContact(accessToken: String, userID: String, contact: Bool) async throws {
        guard UUID(uuidString: userID) != nil else { throw APIError.http(400) }
        if contact {
            try await client.postNoContent("/users/\(userID)/contact", bearerToken: accessToken)
        } else {
            try await client.deleteNoContent("/users/\(userID)/contact", accessToken: accessToken)
        }
    }
}

/// Account address book entry (`GET /users/contacts`). `source` is "manual",
/// "chat" (added by the first message) or "request".
struct ContactEntry: Codable, Identifiable, Equatable {
    let user: PublicUser
    let source: String
    let addedAt: String
    var id: String { user.id }
}

struct ContactsPage: Codable, Equatable {
    let items: [ContactEntry]
    let nextCursor: String?
}

/// `GET /users/:id/contact`.
struct ContactStatusResponse: Codable, Equatable {
    let userId: String
    let isContact: Bool
}
