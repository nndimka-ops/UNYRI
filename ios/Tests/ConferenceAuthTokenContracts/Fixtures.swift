import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

enum UnyriL10n { static func string(_ value: String) -> String { value } }
__API_ERROR__
struct APIErrorBody: Decodable { struct Failure: Decodable { let code: String; let message: String }; let error: Failure }
final class ClientDiagnostics { static let shared = ClientDiagnostics(); func report(baseURL: URL, path: String, token: String?, status: Int) {} }
final class ScriptedTransport {
    var status = 401
    var body = Data(#"{"error":{"code":"session_revoked","message":"Synthetic revoked session"}}"#.utf8)
    var sentBearer: String?
    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        sentBearer = request.value(forHTTPHeaderField: "Authorization")
        return (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}
struct APIClient {
    let baseURL = URL(string: "https://conference-test.invalid")!
    let session: ScriptedTransport
    let decoder = JSONDecoder()
    let onAuthenticatedError: ((APIError) async -> Void)?
    let onAuthenticatedRequestError: ((APIError, String) async -> Void)?
    __SEND__
    __PARSE_ERROR__
}
@MainActor final class TokenStore {
    var token: String? = "S0"
    var reads = 0
    func accessToken() -> String? { reads += 1; return token }
}
@MainActor final class AuthRepository {
    let store = TokenStore()
    var currentUser: String? = "synthetic-account"
    var sessionGeneration: UInt64 = 7
    var refreshTask: Task<String, Error>?
    var erasures = 0
    func beginSessionChange() -> UInt64 { sessionGeneration += 1; refreshTask = nil; return sessionGeneration }
    func finishLocalSession() async { erasures += 1; store.token = nil; currentUser = nil }
    __TOKEN_HANDLER__
    __TERMINAL_POLICY__
}
@MainActor final class Gate {
    var entered = false
    var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
