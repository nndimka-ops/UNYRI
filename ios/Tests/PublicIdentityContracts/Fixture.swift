import Foundation

// HTTP/localization boundaries only; assertions exercise production models/policy.
struct APIStatusError: Error { let status: Int; let code: String? }
enum APIError: Error {
    case server(code: String, message: String), http(Int), network
    var isRateLimited: Bool {
        switch self { case .http(429), .server("rate_limited", _): return true; default: return false }
    }
}
enum UnyriL10n { static func string(_ key: String) -> String { key } }
