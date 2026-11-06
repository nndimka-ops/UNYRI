import Foundation

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

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return UnyriL10n.string("Invalid server address.")
        case .network:
            return UnyriL10n.string("Could not reach the server. Check your network connection and server address.")
        case .http(let code):
            return String(format: UnyriL10n.string("Server error (HTTP %d)."), code)
        case .server(_, let message):
            return UnyriL10n.string(message)
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
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let onAuthenticatedError: ((APIError) async -> Void)?
    private let onAuthenticatedRequestError: ((APIError, String) async -> Void)?

    init(baseURL: URL, preventRedirects: Bool = false, ephemeral: Bool = false, onAuthenticatedError: ((APIError) async -> Void)? = nil,
         onAuthenticatedRequestError: ((APIError, String) async -> Void)? = nil) {
        self.baseURL = baseURL
        self.onAuthenticatedError = onAuthenticatedError
        self.onAuthenticatedRequestError = onAuthenticatedRequestError

        let configuration = ephemeral ? URLSessionConfiguration.ephemeral : URLSessionConfiguration.default
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
        let (data, _) = try await send(path, method: "POST", query: [], body: nil, bearerToken: nil)
        return try decode(data, as: Response.self)
    }

    func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        body: Body,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await send(path, method: "POST", query: [], body: data, bearerToken: nil)
        return try decode(responseData, as: Response.self)
    }

    func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        accessToken: String,
        body: Body,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await send(path, method: "POST", query: [], body: data, bearerToken: accessToken)
        return try decode(responseData, as: Response.self)
    }

    func postNoContent(_ path: String, bearerToken: String) async throws {
        _ = try await send(path, method: "POST", query: [], body: nil, bearerToken: bearerToken)
    }

    func postNoContent<Body: Encodable>(_ path: String, bearerToken: String, body: Body) async throws {
        let (_, response) = try await send(path, method: "POST", query: [], body: encoder.encode(body), bearerToken: bearerToken)
        guard response.statusCode == 204 else { throw APIError.http(response.statusCode) }
    }

    func revokePendingSession(_ refreshToken: String) async throws {
        struct Proof: Encodable { let refreshToken: String }
        _ = try await send("/auth/logout-pending", method: "POST", query: [],
            body: encoder.encode(Proof(refreshToken: refreshToken)), bearerToken: nil)
    }

    func deleteNoContent(_ path: String, accessToken: String) async throws {
        _ = try await send(path, method: "DELETE", query: [], body: nil, bearerToken: accessToken)
    }

    func delete<Response: Decodable>(_ path: String, accessToken: String, as: Response.Type) async throws -> Response {
        let (data, _) = try await send(path, method: "DELETE", query: [], body: nil, bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    func get<Response: Decodable>(
        _ path: String,
        accessToken: String,
        query: [URLQueryItem] = [],
        as: Response.Type
    ) async throws -> Response {
        let (data, _) = try await send(path, method: "GET", query: query, body: nil, bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    func patch<Body: Encodable, Response: Decodable>(
        _ path: String,
        accessToken: String,
        body: Body,
        as: Response.Type
    ) async throws -> Response {
        let data = try encoder.encode(body)
        let (responseData, _) = try await send(path, method: "PATCH", query: [], body: data, bearerToken: accessToken)
        return try decode(responseData, as: Response.self)
    }

    func put<Body: Encodable, Response: Decodable>(_ path: String, accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        let (data, _) = try await send(path, method: "PUT", query: [], body: encoder.encode(body), bearerToken: accessToken)
        return try decode(data, as: Response.self)
    }

    /// A suspended task on the existing configured session. Admission resumes it
    /// synchronously; all response waiting/decoding remains outside its lock.
    func prepareBoundedDirectJSON<Response: Decodable>(_ path: String, method: String, accessToken: String,
        query: [URLQueryItem] = [], body: Data? = nil, observed429: @escaping (String?) -> Void,
        as: Response.Type) throws -> DirectFileHTTPGate.Prepared<Response> {
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
        bearerToken: String?
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
        if path.hasPrefix("/keys/") { request.cachePolicy = .reloadIgnoringLocalCacheData }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let bearerToken {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = body

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            ClientDiagnostics.shared.report(baseURL: baseURL, path: path, token: bearerToken, status: 0)
            throw APIError.network(error.localizedDescription)
        }

        guard let http = response as? HTTPURLResponse else {
            throw APIError.network("Invalid response")
        }
        #if os(iOS)
        performanceStatus = http.statusCode
        #endif
        guard (200..<300).contains(http.statusCode) else {
            ClientDiagnostics.shared.report(baseURL: baseURL, path: path, token: bearerToken, status: http.statusCode)
            let error = parseError(data: data, statusCode: http.statusCode)
            if let bearerToken {
                // Carry the bearer used by this request, not a refreshed token
                // read later. Legacy clients keep their existing callback.
                if let onAuthenticatedRequestError { await onAuthenticatedRequestError(error, bearerToken) }
                else { await onAuthenticatedError?(error) }
            }
            throw error
        }
        #if os(iOS)
        performanceSuccess = true
        #endif
        return (data, http)
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
        transport.dataTask(with: request) { _, _, _ in }.resume()
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

private final class NoCredentialRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// Per-task streaming only; redirect/authentication remain session policy.
private final class DirectJSONCollector: NSObject, URLSessionDataDelegate {
    private let lock = NSLock()
    private let observed429: (String?) -> Void
    private let maximum = 4 * 1024 * 1024
    private weak var task: URLSessionDataTask?
    private var data = Data()
    private var response: HTTPURLResponse?
    private var failure: Error?
    private var cancelled = false
    private var completed: Result<(Data, HTTPURLResponse), Error>?
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?
    init(observed429: @escaping (String?) -> Void) { self.observed429 = observed429 }
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
                failure = APIError.decoding("Transfer response exceeds limit"); return false
            }
            return true
        }
        completionHandler(acceptable ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        let overflow = locked { () -> Bool in
            guard completed == nil, failure == nil else { return false }
            guard chunk.count <= maximum - data.count else {
                failure = APIError.decoding("Transfer response exceeds limit"); return true
            }
            data.append(chunk); return false
        }
        if overflow { cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let output = locked { () -> (CheckedContinuation<(Data, HTTPURLResponse), Error>?, Result<(Data, HTTPURLResponse), Error>)? in
            guard completed == nil else { return nil }
            let result: Result<(Data, HTTPURLResponse), Error>
            if let failure { result = .failure(failure) }
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
