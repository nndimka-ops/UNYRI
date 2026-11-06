import Foundation

// MARK: - Requests
// JSON keys are snake_case and produced by APIClient's encoder strategy,
// matching the Rust structs (serde rename_all = "lowercase" / snake_case).

struct RegisterRequest: Encodable {
    let username: String
    let password: String
    let displayName: String
    let installationId: String
    let devicePlatform: String
    let deviceName: String
    let osVersion: String
    let appVersion: String
    var installationCredential: String? = nil
}

struct LoginRequest: Encodable {
    let username: String
    let password: String
    let installationId: String
    let devicePlatform: String
    let deviceName: String
    let osVersion: String
    let appVersion: String
    var installationCredential: String? = nil
}

struct RefreshRequest: Encodable {
    let refreshToken: String
    var requestId: String? = nil
}

// MARK: - Responses

struct User: Codable, Equatable {
    let id: String
    let username: String
    let displayName: String
}

struct AuthResponse: Decodable {
    var historyBeforeSequence: Int64? = nil
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let deviceId: String
    let user: User
}

struct RefreshResponse: Decodable {
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
}

struct HistoryContext: Codable, Equatable {
    let beforeSequence: Int64
    let beforeDate: Date
}

// MARK: - Error body
// Uniform backend error shape: {"error":{"code":"...","message":"..."}}.

struct APIErrorBody: Decodable {
    let error: APIErrorDetail
}

struct APIErrorDetail: Decodable {
    let code: String
    let message: String
}
