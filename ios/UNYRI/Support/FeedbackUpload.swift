import Foundation

struct FeedbackScreenshot: Codable, Sendable {
    var mimeType = "image/jpeg"
    let dataBase64: String
}
struct FeedbackRequest: Codable, Sendable {
    let id: String
    let description: String
    var platform = "ios"
    let appVersion: String
    let osVersion: String
    let screenshots: [FeedbackScreenshot]
    let performance: PerformanceSnapshot?
    // Sanitized system metadata, separate from the optional user comment.
    var diagnostics: String? = nil
    var automatic = false
    var trigger: DiagnosticTrigger? = nil
}
struct FeedbackReceipt: Decodable { let id: String }

/// All three entry points use the same endpoint, coding and redirect policy.
#if canImport(UIKit)
@MainActor enum FeedbackUpload {
    static func send(_ request: FeedbackRequest, auth: AuthRepository,
                     server: URL, generation: UInt64, restrictCellular: Bool = false,
                     admission: @MainActor () -> Bool = { true }) async throws -> FeedbackReceipt {
        guard let owner = auth.currentUser?.id else { throw CancellationError() }
        let token = try await auth.currentAccessToken()
        try Task.checkCancellation()
        guard admission(), auth.currentUser?.id == owner, generation == auth.sessionGeneration, server == auth.authenticatedServerURL else { throw CancellationError() }
        return try await APIClient(baseURL: server, preventRedirects: true, allowsCellularAccess: !restrictCellular)
            .post("/feedback", accessToken: token, body: request, as: FeedbackReceipt.self)
    }
}

#endif
