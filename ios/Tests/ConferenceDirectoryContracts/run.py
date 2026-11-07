"""Real Foundation models, strict payload vectors and actual API request methods.
No SDK/Signal transport, live requests, account data or capture is used.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
support = r'''
import Foundation
enum UnyriL10n { static func string(_ key: String) -> String { key } }
enum AppConfig {
    static let publicDefaultOrigin = "https://conference-test.invalid"
    static let apiBaseURL = URL(string: publicDefaultOrigin)!
}
enum APIError: Error { case missingSession, invalidURL, network(String), http(Int), server(code: String, message: String), decoding(String) }
struct User { var id = "33333333-3333-4333-8333-333333333333" }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var currentUser: User? = User()
    var currentDeviceId: UUID? = UUID(uuidString: "44444444-4444-4444-8444-444444444444")
    func currentAccessToken() async throws -> String { "synthetic-not-live" }
    func handleAuthenticatedError(_ error: APIError, generation: UInt64) async {}
    func handleAuthenticatedRequestError(_ error: APIError, generation: UInt64, requestToken: String) async {}
}
@MainActor enum ClientProbe {
    static var reply = Data(), lastBody: Data?, lastPath = "", lastQuery: [URLQueryItem] = []
    static var beforeReply: (() -> Void)?
}
@MainActor struct APIClient {
    init(baseURL: URL, preventRedirects: Bool = false, ephemeral: Bool = false, onAuthenticatedError: ((APIError) async -> Void)? = nil,
         onAuthenticatedRequestError: ((APIError, String) async -> Void)? = nil) {}
    private func response<T: Decodable>(_ path: String, body: Data?, query: [URLQueryItem] = [], type: T.Type) throws -> T {
        ClientProbe.lastBody = body; ClientProbe.lastPath = path; ClientProbe.lastQuery = query
        ClientProbe.beforeReply?()
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase; decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(type, from: ClientProbe.reply)
    }
    func get<T: Decodable>(_ path: String, accessToken: String, query: [URLQueryItem] = [], as: T.Type) async throws -> T { try response(path, body: nil, query: query, type: T.self) }
    func post<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as: T.Type) async throws -> T {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        return try response(path, body: encoder.encode(body), type: T.self)
    }
    func patch<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as: T.Type) async throws -> T { try await post(path, accessToken: accessToken, body: body, as: T.self) }
    func delete<T: Decodable>(_ path: String, accessToken: String, as: T.Type) async throws -> T { try response(path, body: nil, type: T.self) }
}
'''
sources = ["ios/UNYRI/Signal/MessageContentCodec.swift", "ios/UNYRI/Models/SignalModels.swift",
           "ios/UNYRI/Conferences/ConferenceInvite.swift", "ios/UNYRI/Conferences/ConferenceDirectoryModels.swift",
           "ios/UNYRI/Conferences/ConferencesAPI.swift", "ios/Tests/ConferenceDirectoryContracts/main.swift"]
with tempfile.TemporaryDirectory(prefix="coco-room-directory-") as temp:
    folder = Path(temp)
    adapter = folder / "Support.swift"
    adapter.write_text(support, encoding="utf-8")
    executable = folder / ("contracts.exe" if os.name == "nt" else "contracts")
    subprocess.run(["swiftc", "-parse-as-library", str(adapter), *[str(ROOT / s) for s in sources], "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable), str(ROOT / "shared/conference-discovery-v1-vectors.json")], check=True, timeout=20)
