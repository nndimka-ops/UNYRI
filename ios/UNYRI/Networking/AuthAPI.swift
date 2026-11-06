import Foundation

/// REST endpoints matching the existing backend contract used by Android.
struct AuthAPI {
    let client: APIClient

    func register(_ request: RegisterRequest) async throws -> AuthResponse {
        guard request.installationCredential == nil || client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        return try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/auth/register", body: request, as: AuthResponse.self)
    }

    func login(_ request: LoginRequest) async throws -> AuthResponse {
        guard request.installationCredential == nil || client.baseURL.scheme?.lowercased() == "https" else { throw APIError.invalidURL }
        return try await APIClient(baseURL: client.baseURL, preventRedirects: true).post("/auth/login", body: request, as: AuthResponse.self)
    }

    func refresh(_ request: RefreshRequest) async throws -> RefreshResponse {
        try await client.post("/auth/refresh", body: request, as: RefreshResponse.self)
    }

    func logout(accessToken: String) async throws {
        try await client.postNoContent("/auth/logout", bearerToken: accessToken)
    }

    func historyContext(accessToken: String) async throws -> HistoryContext {
        try await client.get("/auth/history-context", accessToken: accessToken, as: HistoryContext.self)
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
}
