import Foundation

/// REST client for the key endpoints. Pure transport — no crypto here.
struct KeysAPI {
    let client: APIClient

    func uploadKeys(accessToken: String, request: UploadKeysRequest) async throws -> UploadKeysResponse {
        try await client.post("/keys", accessToken: accessToken, body: request, as: UploadKeysResponse.self)
    }

    func getBundles(accessToken: String, userId: String) async throws -> UserBundlesResponse {
        try await client.get("/keys/bundles/\(userId)", accessToken: accessToken, as: UserBundlesResponse.self)
    }

    func getDeviceBundles(accessToken: String, userId: String) async throws -> UserBundlesResponse {
        try await client.get("/keys/devices/\(userId)", accessToken: accessToken, as: UserBundlesResponse.self)
    }
    /// Dedicated mailbox handoff must not consume another device's prekey.
    func getBundle(accessToken: String, userId: String, deviceId: String) async throws -> UserBundlesResponse {
        try await client.get("/keys/bundles/\(userId)/\(deviceId)", accessToken: accessToken, as: UserBundlesResponse.self)
    }

    func oneTimePreKeyCount(accessToken: String) async throws -> OneTimePreKeyCountResponse {
        try await client.get("/keys/one-time-count", accessToken: accessToken, as: OneTimePreKeyCountResponse.self)
    }
}
