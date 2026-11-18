import Foundation

/// REST endpoints matching the existing backend contract used by Android.
struct AuthAPI {
    let client: APIClient

    func signupRecovery(accessToken: String, password: String) async throws -> String {
        guard client.baseURL.scheme?.lowercased() == "https" || AppConfig.hasIdentityHTTPConsent(client.baseURL) else { throw APIError.invalidURL }
        struct Reply: Decodable { let code: String }
        let result = try await APIClient(baseURL: client.baseURL, preventRedirects: true, ephemeral: true)
            .post("/auth/recovery/code", accessToken: accessToken, body: ["password": password], as: Reply.self)
        guard PublicIdentityPolicy.isRecoveryCode(result.code) else { throw APIError.decoding("Invalid recovery code") }
        return result.code
    }

    func registrationPolicy() async throws -> RegistrationPolicy {
        try await client.identityTransport().getPublic("/server-info", as: RegistrationPolicy.self)
    }

    func register(_ request: RegisterRequest) async throws -> AuthResponse {
        guard request.installationCredential == nil || client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        return try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/auth/register", body: request, as: AuthResponse.self)
    }

    func login(_ request: LoginRequest) async throws -> AuthResponse {
        guard request.installationCredential == nil || client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        return try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/auth/login", body: request, as: AuthResponse.self)
    }

    func refresh(_ request: RefreshRequest) async throws -> RefreshResponse {
        try await client.identityTransport().post("/auth/refresh", body: request, as: RefreshResponse.self)
    }

    func logout(accessToken: String) async throws {
        try await client.identityTransport().postNoContent("/auth/logout", bearerToken: accessToken)
    }

    func historyContext(accessToken: String) async throws -> HistoryContext {
        try await client.identityTransport().get("/auth/history-context", accessToken: accessToken, as: HistoryContext.self)
    }

    func createInstallationCredential() async throws -> String {
        guard client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        do {
            let response = try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/auth/installations", as: InstallationCredentialResponse.self)
            return response.installationCredential
        } catch APIError.http(let status) where status == 404 || status == 405 {
            throw InstallationProofError.unsupportedServer
        }
    }

    func bindInstallationCredential(_ credential: String, accessToken: String) async throws {
        guard client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        // This route returns HTTP 200 JSON null, rather than HTTP 204.
        let _: Bool? = try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/devices/me/installation", accessToken: accessToken,
            body: InstallationCredentialBinding(installationCredential: credential), as: Bool?.self)
    }

    /// Public version policy; readable before sign-in and on any server.
    func clientConfig() async throws -> ClientConfigResponse {
        try await client.getPublic("/client/config", as: ClientConfigResponse.self)
    }
}

/// `GET /client/config`. Only the iOS entry is read; every field is optional so
/// a newer server schema never breaks decoding.
struct ClientConfigResponse: Decodable {
    struct Platform: Decodable {
        let minimumVersion: String?
        let recommendedVersion: String?
        let updateUrl: String?
    }
    struct Platforms: Decodable { let ios: Platform? }
    let platforms: Platforms
}
