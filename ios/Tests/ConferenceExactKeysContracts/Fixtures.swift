import Foundation

enum APIError: Error {
    case invalidURL, http(Int), decoding(String)
    var isRateLimited: Bool { if case .http(429) = self { return true }; return false }
}
struct FixtureUser { var id = "11111111-1111-4111-8111-111111111111" }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var currentUser: FixtureUser? = FixtureUser()
    func currentAccessToken() async throws -> String { "synthetic-not-live" }
}
@MainActor enum ExactKeysTransport {
    static var paths = [String]()
    static var respond: ((String) async throws -> Data)!
}
@MainActor struct APIClient {
    func get<T: Decodable>(_ path: String, accessToken: String, as: T.Type) async throws -> T {
        ExactKeysTransport.paths.append(path)
        let data = try await ExactKeysTransport.respond(path)
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: data)
    }
    func post<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as: T.Type) async throws -> T {
        throw APIError.http(500)
    }
}
