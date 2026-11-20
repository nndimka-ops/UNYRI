import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Identity mutation errors need both status and code: `unavailable` is also
/// used for infrastructure outages, which must never look like a taken handle.
struct APIStatusError: Error {
    let status: Int
    let code: String?
}

/// Error surfaced to the UI, mapped to a readable message.
enum APIError: Error, LocalizedError {
    case invalidURL
    case network(String)
    case http(Int)
    case server(code: String, message: String)
    case decoding(String)
    case missingSession

    var isRateLimited: Bool {
        switch self {
        case .http(429), .server(code: "rate_limited", message: _): return true
        default: return false
        }
    }

    /// Name/handle errors use stable codes, never server English wording.
    static func localizedServerMessage(code: String, message: String) -> String {
        let key: String
        switch code {
        case "account_deleted": key = "Account deleted"
        case "account_suspended": key = "Account suspended by the server administrator"
        case "session_revoked", "invalid_refresh_token": key = "Session ended"
        case "display_name_reserved": key = "This name is reserved for the service. Choose another name."
        case "display_name_link": key = "Display name must not contain URLs or domains."
        case "display_name_leading_at": key = "Display name must not start with '@'."
        case "username_reserved": key = "This username is reserved for the service. Choose another username."
        case "username_link": key = "Username must not contain URLs or domains."
        case "validation_error": key = "Check the entered values."
        case "common_password": key = "This password is too common. Choose a different password."
        case "too_many_devices": key = "This account is signed in on too many devices. Sign out of an old device first."
        case "invite_required": key = "An invitation code is required."
        case "invite_invalid": key = "The invitation code is invalid or no longer available."
        default: return UnyriL10n.string(message)
        }
        return UnyriL10n.string(key)
    }

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return UnyriL10n.string("Invalid server address.")
        case .network:
            return UnyriL10n.string("Could not reach the server. Check your network connection and server address.")
        case .http(let code):
            return String(format: UnyriL10n.string("Server error (HTTP %d)."), code)
        case .server(let code, let message):
            return Self.localizedServerMessage(code: code, message: message)
        case .decoding:
            return UnyriL10n.string("Unexpected server response.")
        case .missingSession:
            return UnyriL10n.string("You are not signed in.")
        }
    }
}

/// Thin JSON HTTP client over URLSession (async/await) with Codable models.
/// Mirrors the backend's uniform error shape `{"error":{"code","message"}}`.
struct APIClient {
    let baseURL: URL
    let directHTTPGate = DirectFileHTTPGate() // shared by copies of this configured client, never owner epochs
    private let profileReadBudget = ServerProfileReadBudget()
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let onAuthenticatedError: ((APIError) async -> Void)?
    private let onAuthenticatedRequestError: ((APIError, String) async -> Void)?

    func identityTransport() -> APIClient {
        APIClient(baseURL: baseURL, preventRedirects: true, ephemeral: true,
                  onAuthenticatedError: onAuthenticatedError, onAuthenticatedRequestError: onAuthenticatedRequestError)
    }

    init(baseURL: URL, preventRedirects: Bool = false, ephemeral: Bool = false, allowsCellularAccess: Bool = true, onAuthenticatedError: ((APIError) async -> Void)? = nil,
         onAuthenticatedRequestError: ((APIError, String) async -> Void)? = nil) {
        self.baseURL = baseURL
        self.onAuthenticatedError = onAuthenticatedError
        self.onAuthenticatedRequestError = onAuthenticatedRequestError

        let configuration = ephemeral ? URLSessionConfiguration.ephemeral : URLSessionConfiguration.default
        configuration.allowsCellularAccess = allowsCellularAccess
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        self.session = URLSession(configuration: configuration, delegate: preventRedirects ? NoCredentialRedirects() : nil, delegateQueue: nil)

        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        self.encoder = encoder

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            guard let date = ISO8601Cocoa.parse(string) else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "Unrecognized date format: \(string)"
                )
            }
            return date
        }
        self.decoder = decoder
    }

    func post<Response: Decodable>(_ path: String, as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "POST", query: [], body: nil, bearerToken: nil)
        return try decode(data, as: Response.self)
    }

    func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        body: Body,
        deviceProof: String? = nil,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await dispatch(path, method: "POST", query: [], body: data, bearerToken: nil, deviceProof: deviceProof)
        return try decode(responseData, as: Response.self)
    }

    func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        accessToken: String,
        body: Body,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await dispatch(path, method: "POST", query: [], body: data, bearerToken: accessToken)
        return try decode(responseData, as: Response.self)
    }

    /// Bodyless POST; preserve 5xx for the acknowledgement's bounded retry.
    func post<Response: Decodable>(_ path: String, accessToken: String, timeout: TimeInterval,
                                   as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "POST", query: [], body: nil,
            bearerToken: accessToken, requestTimeout: timeout, preserveServerStatus: true)
        return try decode(data, as: Response.self)
    }

    func postNoContent(_ path: String, bearerToken: String) async throws {
        _ = try await dispatch(path, method: "POST", query: [], body: nil, bearerToken: bearerToken)
    }

    func postNoContent<Body: Encodable>(_ path: String, bearerToken: String, body: Body) async throws {
        let (_, response) = try await dispatch(path, method: "POST", query: [], body: encoder.encode(body), bearerToken: bearerToken)
        guard response.statusCode == 204 else { throw APIError.http(response.statusCode) }
    }

    func revokePendingSession(_ refreshToken: String) async throws {
        struct Proof: Encodable { let refreshToken: String }
        _ = try await dispatch("/auth/logout-pending", method: "POST", query: [],
            body: encoder.encode(Proof(refreshToken: refreshToken)), bearerToken: nil)
    }

    func deleteNoContent(_ path: String, accessToken: String) async throws {
        _ = try await dispatch(path, method: "DELETE", query: [], body: nil, bearerToken: accessToken)
    }

    func delete<Response: Decodable>(_ path: String, accessToken: String, as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "DELETE", query: [], body: nil, bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    func get<Response: Decodable>(
        _ path: String,
        accessToken: String,
        query: [URLQueryItem] = [],
        as: Response.Type
    ) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "GET", query: query, body: nil, bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    /// Keep HTTP status on every identity read, including old-server detection.
    func getIdentity<Response: Decodable>(_ path: String, accessToken: String,
        query: [URLQueryItem] = [], as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "GET", query: query, body: nil,
            bearerToken: accessToken, identityErrors: true)
        return try decode(data, as: Response.self)
    }

    /// Unauthenticated read (no bearer at all), for public server metadata.
    func getPublic<Response: Decodable>(_ path: String, query: [URLQueryItem] = [], as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "GET", query: query, body: nil, bearerToken: nil)
        return try decode(data, as: Response.self)
    }

    func putIdentity<Body: Encodable, Response: Decodable>(accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch("/users/me/identity", method: "PUT", query: [], body: encoder.encode(body),
                                          bearerToken: accessToken, identityErrors: true)
        return try decode(data, as: Response.self)
    }

    func patch<Body: Encodable, Response: Decodable>(
        _ path: String,
        accessToken: String,
        body: Body,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await dispatch(path, method: "PATCH", query: [], body: data, bearerToken: accessToken)
        return try decode(responseData, as: Response.self)
    }

    func put<Body: Encodable, Response: Decodable>(_ path: String, accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        let (data, _) = try await dispatch(path, method: "PUT", query: [], body: encoder.encode(body), bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    /// A suspended task on the existing configured session. Admission resumes it
    /// synchronously; all response waiting/decoding remains outside its lock.
    func prepareBoundedDirectJSON<Response: Decodable>(_ path: String, method: String, accessToken: String,
        query: [URLQueryItem] = [], body: Data? = nil, observed429: @escaping (String?) -> Void,
        as: Response.Type) throws -> DirectFileHTTPGate.Prepared<Response> {
        guard UnyriBearerOrigins.permits(accessToken, to: baseURL) else { throw APIError.missingSession }
        guard path.hasPrefix("/direct-transfers/"), ["GET", "POST", "PUT"].contains(method),
              body == nil || body!.count <= 96 * 1024,
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { throw APIError.invalidURL }
        components.path = path; components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw APIError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = method; request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let collector = DirectJSONCollector(observed429: observed429)
        let task = session.dataTask(with: request)
        task.delegate = collector // iOS15+, unimplemented policy methods forward to session delegate
        collector.attach(task)
        return DirectFileHTTPGate.Prepared(start: {
            task.resume()
            return DirectFileHTTPGate.Started(result: {
                let data: Data; let http: HTTPURLResponse
                do { (data, http) = try await collector.result(); try Task.checkCancellation() }
                catch is CancellationError { throw CancellationError() }
                catch let error as URLError where error.code == .cancelled { throw CancellationError() }
                catch let error as APIError { throw error }
                catch { throw APIError.network(error.localizedDescription) }
                guard (200..<300).contains(http.statusCode) else {
                    let error = parseError(data: data, statusCode: http.statusCode)
                    if let onAuthenticatedRequestError { await onAuthenticatedRequestError(error, accessToken) }
                    else { await onAuthenticatedError?(error) }
                    throw error
                }
                return try decode(data, as: Response.self)
            }, cancel: { collector.cancel() })
        }, cancel: { collector.cancel() })
    }

    /// Every bearer is sent only to the server that issued it. During an
    /// account switch a late request of the previous account can still hold its
    /// client; it must never carry the next account's token to another server.
    private func dispatch(_ path: String, method: String, query: [URLQueryItem], body: Data?,
                          bearerToken: String?, requestTimeout: TimeInterval? = nil,
                          preserveServerStatus: Bool = false, identityErrors: Bool = false,
                          deviceProof: String? = nil) async throws -> (Data, HTTPURLResponse) {
        try ServerWorkDeadline.check()
        if method == "GET", path == "/users/me", !profileReadBudget.admit(now: ProcessInfo.processInfo.systemUptime) {
            throw APIError.http(429)
        }
        if let bearerToken, !UnyriBearerOrigins.permits(bearerToken, to: baseURL) { throw APIError.missingSession }
        let result = try await send(path, method: method, query: query, body: body, bearerToken: bearerToken,
                                   requestTimeout: requestTimeout, preserveServerStatus: preserveServerStatus,
                                   identityErrors: identityErrors, deviceProof: deviceProof)
        try ServerWorkDeadline.check()
        return result
    }

    private func decode<Response: Decodable>(_ data: Data, as: Response.Type) throws -> Response {
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error.localizedDescription)
        }
    }

    private func send(
        _ path: String,
        method: String,
        query: [URLQueryItem],
        body: Data?,
        bearerToken: String?,
        requestTimeout: TimeInterval? = nil,
        preserveServerStatus: Bool = false,
        identityErrors: Bool = false,
        deviceProof: String? = nil
    ) async throws -> (Data, HTTPURLResponse) {
        #if os(iOS)
        let performanceOperation = Self.performanceOperation(path: path, method: method)
        let performanceSpan = ["/performance", "/diagnostics"].contains(path) ? nil : PerformanceRecorder.shared.begin(.networkRequest, ["operation": performanceOperation])
        var performanceStatus = 0
        var performanceSuccess = false
        defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess, metrics: ["status": Double(performanceStatus), "operation": performanceOperation]) }
        #endif
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw APIError.invalidURL
        }
        components.path = path.hasPrefix("/") ? path : "/" + path
        if !query.isEmpty {
            components.queryItems = query
        }
        guard let url = components.url else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        if path.hasPrefix("/keys/") || path == "/users/me/identity" || path == "/users/lookup" || path.hasSuffix("/profile") {
            request.cachePolicy = .reloadIgnoringLocalCacheData
        }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let deviceProof { request.setValue(deviceProof, forHTTPHeaderField: "X-UNYRI-Device-Proof") }
        if let bearerToken {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = body
        if let requestTimeout { request.timeoutInterval = requestTimeout }

        let data: Data
        let http: HTTPURLResponse
        var pendingQuery = query
        var attempt = 0
        while true {
            try Task.checkCancellation()
            attempt += 1
            do {
                (data, http) = try await session.unyriBoundedData(for: request, maximumBytes: 16 * 1024 * 1024)
                break
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch is HTTPBodyLimitExceeded {
                // Retry only read-only message pages. Do not advance a cursor or
                // expose a partial DTO; the reduced page's own coordinates win.
                guard attempt < 8, let reduced = Self.smallerMessagePage(path: components.path, method: method, query: pendingQuery) else {
                    throw APIError.decoding("Server response exceeds limit")
                }
                pendingQuery = reduced
                components.queryItems = reduced
                guard let reducedURL = components.url else { throw APIError.invalidURL }
                request.url = reducedURL
            } catch {
                ClientDiagnostics.shared.report(baseURL: baseURL, path: path, token: bearerToken, status: 0)
                throw APIError.network(error.localizedDescription)
            }
        }

        #if os(iOS)
        performanceStatus = http.statusCode
        #endif
        UnyriDiag.observeServerDate(http.value(forHTTPHeaderField: "Date"))
        guard (200..<300).contains(http.statusCode) else {
            #if os(iOS)
            if http.statusCode == 401,
               let detail = try? decoder.decode(APIErrorBody.self, from: data).error,
               detail.code == "device_proof_stale", let serverTime = detail.serverTime {
                throw DeviceBoundClockChallenge(serverTime: serverTime)
            }
            #endif
            ClientDiagnostics.shared.report(baseURL: baseURL, path: path, token: bearerToken, status: http.statusCode)
            let error: APIError = preserveServerStatus && (500..<600).contains(http.statusCode)
                ? .http(http.statusCode) : parseError(data: data, statusCode: http.statusCode)
            if let bearerToken {
                // Carry the bearer used by this request, not a refreshed token
                // read later. Legacy clients keep their existing callback.
                if let onAuthenticatedRequestError { await onAuthenticatedRequestError(error, bearerToken) }
                else { await onAuthenticatedError?(error) }
            }
            if identityErrors {
                let code: String?
                if case .server(let value, _) = error { code = value } else { code = nil }
                throw APIStatusError(status: http.statusCode, code: code)
            }
            throw error
        }
        #if os(iOS)
        performanceSuccess = true
        #endif
        return (data, http)
    }

    private static func smallerMessagePage(path: String, method: String, query: [URLQueryItem]) -> [URLQueryItem]? {
        guard method == "GET" else { return nil }
        let parts = path.split(separator: "/")
        let isSync = path == "/sync"
        let isHistory = parts.count == 3 && parts[0] == "chats" && parts[2] == "messages" && UUID(uuidString: String(parts[1])) != nil
        guard isSync || isHistory else { return nil }
        let limits = query.filter { $0.name == "limit" }
        guard limits.count <= 1 else { return nil }
        let current: Int
        if let item = limits.first {
            guard let raw = item.value, let parsed = Int(raw), parsed > 1 else { return nil }
            current = min(parsed, isSync ? 500 : 100)
        } else { current = isSync ? 100 : 50 }
        guard current > 1 else { return nil }
        let next = min(current / 2, 64)
        var reduced = query.filter { $0.name != "limit" }
        reduced.append(URLQueryItem(name: "limit", value: String(next)))
        return reduced
    }

    /// Fixed route categories only. Paths, query strings and IDs are never exported.
    private static func performanceOperation(path: String, method: String) -> Double {
        let route = path.hasPrefix("/") ? path : "/" + path
        if route.hasPrefix("/chats/") && route.hasSuffix("/messages") { return method == "GET" ? 1 : 2 }
        if route.hasPrefix("/chats/") && (route.hasSuffix("/read") || route.hasSuffix("/delivered")) { return 3 }
        for (prefix, code) in [("chats", 4.0), ("sync", 5), ("calls", 6), ("keys", 7), ("attachments", 8), ("presence", 9)] {
            if route == "/" + prefix || route.hasPrefix("/" + prefix + "/") { return code }
        }
        return 0
    }

    private func parseError(data: Data, statusCode: Int) -> APIError {
        if let body = try? decoder.decode(APIErrorBody.self, from: data) {
            return .server(code: body.error.code, message: body.error.message)
        }
        return .http(statusCode)
    }
}

/// In-memory record of which server issued each recent access token (never
/// persisted or logged). Unknown tokens pass: the record only blocks a token
/// that is known to belong to a different server than the request's.
enum UnyriBearerOrigins {
    private static let lock = NSLock()
    private static var issued: [(token: String, origin: String)] = []

    static func record(_ token: String, origin: URL) {
        guard !token.isEmpty, let key = identity(origin) else { return }
        lock.lock(); defer { lock.unlock() }
        issued.removeAll { $0.token == token }
        issued.append((token, key))
        if issued.count > 16 { issued.removeFirst(issued.count - 16) }
    }

    static func permits(_ token: String, to url: URL) -> Bool {
        lock.lock()
        let known = issued.last(where: { $0.token == token })?.origin
        lock.unlock()
        guard let known else { return true }
        return identity(url) == known
    }

    /// The server's host. Scheme and port are ignored on purpose: the LAN TLS
    /// transport upgrade reaches the same server on another port and scheme.
    private static func identity(_ url: URL) -> String? {
        guard let host = URLComponents(url: url, resolvingAgainstBaseURL: false)?.host?.lowercased(), !host.isEmpty else { return nil }
        return host
    }
}

/// Tolerant ISO-8601 parsing: the backend (chrono) may or may not emit
/// fractional seconds.
enum ISO8601Cocoa {
    private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func parse(_ string: String) -> Date? {
        // Bound network dates before any downstream epoch/unit conversion.
        guard string.utf8.count <= 64, string.prefix(4).utf8.allSatisfy({ (48...57).contains($0) }),
              string.dropFirst(4).first == "-" else { return nil }
        if let date = fractional.date(from: string) { return date }
        if let date = plain.date(from: string) { return date }

        // chrono can emit 1-9 fractional-second digits, which the fractional
        // formatter rejects. Drop the fraction and retry at second precision.
        var cleaned = string
        if let dot = cleaned.firstIndex(of: ".") {
            var end = cleaned.index(after: dot)
            while end < cleaned.endIndex, cleaned[end].isNumber {
                end = cleaned.index(after: end)
            }
            cleaned.removeSubrange(dot..<end)
        }
        return plain.date(from: cleaned)
    }
}

/// Shared limiter; reporting never uses APIClient, avoiding recursive diagnostics.
private final class ClientDiagnostics {
    static let shared = ClientDiagnostics()
    private let lock = NSLock()
    private var lastAttempt: TimeInterval?
    private let transport: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 5
        return URLSession(configuration: config)
    }()
    func report(baseURL: URL, path: String, token: String?, status: Int) {
        guard UserDefaults.standard.bool(forKey: "shareDiagnostics"), let token,
              status == 0 || (400...599).contains(status), let operation = operation(path) else { return }
        lock.lock()
        let now = ProcessInfo.processInfo.systemUptime
        if let lastAttempt, now - lastAttempt < 60 { lock.unlock(); return }
        lastAttempt = now
        lock.unlock()
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return }
        components.path = "/diagnostics"
        components.query = nil
        components.fragment = nil
        guard let url = components.url else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["platform": "ios", "operation": operation, "status": status])
        // Best effort: never retain reports or tokens for a future account/session.
        let task = transport.dataTask(with: request)
        task.delegate = DiscardedHTTPBody()
        task.resume()
    }
    private func operation(_ path: String) -> String? {
        if path.hasPrefix("/chats/") && (path.hasSuffix("/read") || path.hasSuffix("/delivered")) { return "receipts" }
        if path.hasPrefix("/chats/") && path.hasSuffix("/messages") { return "messages" }
        for name in ["chats", "sync", "calls", "attachments", "keys"] {
            if path == "/\(name)" || path.hasPrefix("/\(name)/") { return name }
        }
        return nil
    }
}

/// Acknowledgements have no useful body, including on errors. Stop at headers
/// rather than asking URLSession's completion-handler API to buffer it all.
private final class DiscardedHTTPBody: NSObject, URLSessionDataDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.cancel)
    }
}

private final class NoCredentialRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// A successful response is never exposed partially. Kept distinct from
/// HTTP/authentication errors so only explicitly paged GETs can reduce a page.
struct HTTPBodyLimitExceeded: Error {
    let maximumBytes: Int
}

extension URLSession {
    /// Reuse the existing session and per-task collector: no new session,
    /// redirect/auth policy, byte-at-a-time actor hops, or unbounded Data buffer.
    func unyriBoundedData(for request: URLRequest, maximumBytes: Int) async throws -> (Data, HTTPURLResponse) {
        precondition(maximumBytes > 0)
        let collector = DirectJSONCollector(maximum: maximumBytes,
            overflowError: HTTPBodyLimitExceeded(maximumBytes: maximumBytes), preserveHTTPErrorStatus: true)
        let task = dataTask(with: request)
        task.delegate = collector
        collector.attach(task)
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            task.resume()
            let result = try await collector.result()
            try Task.checkCancellation()
            return result
        }, onCancel: { collector.cancel() })
    }
}

/// Per-task streaming only; redirect/authentication remain session policy.
private final class DirectJSONCollector: NSObject, URLSessionDataDelegate {
    private let lock = NSLock()
    private let observed429: (String?) -> Void
    private let maximum: Int
    private let overflowError: Error
    private let preserveHTTPErrorStatus: Bool
    private var exceededLimit = false
    private weak var task: URLSessionDataTask?
    private var data = Data()
    private var response: HTTPURLResponse?
    private var failure: Error?
    private var cancelled = false
    private var completed: Result<(Data, HTTPURLResponse), Error>?
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?
    init(maximum: Int = 4 * 1024 * 1024,
         overflowError: Error = APIError.decoding("Transfer response exceeds limit"),
         preserveHTTPErrorStatus: Bool = false, observed429: @escaping (String?) -> Void = { _ in }) {
        self.maximum = maximum
        self.overflowError = overflowError
        self.preserveHTTPErrorStatus = preserveHTTPErrorStatus
        self.observed429 = observed429
    }
    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }
    func attach(_ task: URLSessionDataTask) { locked { self.task = task } }
    func cancel() {
        let target: URLSessionDataTask? = locked {
            guard !cancelled, completed == nil else { return nil }
            cancelled = true; return task
        }
        target?.cancel()
    }
    func result() async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { pending in
            let ready = locked { () -> Result<(Data, HTTPURLResponse), Error>? in
                if let completed { return completed }
                continuation = pending; return nil
            }
            if let ready { pending.resume(with: ready) }
        }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive value: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = value as? HTTPURLResponse else {
            locked { failure = APIError.network("Invalid response") }
            completionHandler(.cancel); return
        }
        // Never hold the collector lock while entering the admission core.
        // This update must precede cancellation, completion and permit release.
        if http.statusCode == 429 { observed429(http.value(forHTTPHeaderField: "Retry-After")) }
        let acceptable = locked { () -> Bool in
            response = http
            if http.expectedContentLength > maximum {
                failure = overflowError; exceededLimit = true; return false
            }
            return true
        }
        if !acceptable { cancel() }
        completionHandler(acceptable ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        let overflow = locked { () -> Bool in
            guard completed == nil, failure == nil else { return false }
            guard chunk.count <= maximum - data.count else {
                failure = overflowError; exceededLimit = true; return true
            }
            data.append(chunk); return false
        }
        if overflow { cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let output = locked { () -> (CheckedContinuation<(Data, HTTPURLResponse), Error>?, Result<(Data, HTTPURLResponse), Error>)? in
            guard completed == nil else { return nil }
            let result: Result<(Data, HTTPURLResponse), Error>
            if exceededLimit, preserveHTTPErrorStatus, let response, !(200..<300).contains(response.statusCode) {
                // Keep 401/403/429/5xx classification even if an error document
                // is hostile/oversized. The retained prefix is still bounded.
                result = .success((data, response))
            } else if let failure { result = .failure(failure) }
            else if let error { result = .failure(error) }
            else if let response { result = .success((data, response)) }
            else { result = .failure(APIError.network("Invalid response")) }
            completed = result
            let pending = continuation; continuation = nil
            return (pending, result)
        }
        if let output { output.0?.resume(with: output.1) }
    }
}


/// Text for an error shown to people. Cancelled work (leaving a screen, a newer
/// request replacing an older one) shows nothing: "Swift.CancellationError error 1"
/// appeared on the Devices screen (report 0.3.27). Errors with a proper description
/// (server, validation) keep it; anything else gets a plain sentence.
enum UnyriErrorText {
    static func message(_ error: Error) -> String? {
        if error is CancellationError { return nil }
        if let url = error as? URLError, url.code == .cancelled { return nil }
        UnyriDiag.errorShown(error)
        if let url = error as? URLError {
            if url.code == .cancelled { return nil }
            return UnyriL10n.string("Could not reach the server. Check your network connection and server address.")
        }
        if let localized = error as? LocalizedError, let text = localized.errorDescription, !text.isEmpty { return text }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return nil }
        return UnyriL10n.string("Something went wrong. Try again.")
    }
}
