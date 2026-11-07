import Foundation

/// Tokens are sent only to the already-selected authenticated origin. Never
/// redirect a bearer/token request, log its body, or select a provider URL here.
struct UnyriPushAPI {
    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    }
    private let origin: URL
    init(origin: URL) { self.origin = origin }

    func register(_ value: UnyriPushPut, access: String) async throws -> UnyriPushReceipt {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try await send(method: "PUT", access: access, body: encoder.encode(value))
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let result = try decoder.decode(UnyriPushReceipt.self, from: data)
        guard result.provider == value.provider, result.registrationId == value.registrationId,
              result.revision == value.revision else { throw UnyriPushAPIError.invalid }
        return result
    }

    func retire(_ binding: UnyriPushBinding, access: String) async throws {
        _ = try await send(method: "DELETE", access: access, query: [
            URLQueryItem(name: "provider", value: binding.context.provider.rawValue),
            URLQueryItem(name: "registration_id", value: binding.id.uuidString.lowercased()),
            URLQueryItem(name: "revision", value: String(binding.revision))])
    }

    private func send(method: String, access: String, body: Data? = nil, query: [URLQueryItem] = []) async throws -> Data {
        guard !access.isEmpty, body == nil || body!.count <= 16 * 1024,
              var components = URLComponents(url: origin, resolvingAgainstBaseURL: false),
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              ["https", "http"].contains(components.scheme ?? ""), components.host?.isEmpty == false else { throw UnyriPushAPIError.invalid }
        components.path = "/devices/me/push-token"; components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw UnyriPushAPIError.invalid }
        var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10; config.timeoutIntervalForResource = 20
        let session = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
        // Each bounded request owns its ephemeral delegate/session. Releasing
        // the value type alone would not break URLSession's delegate lifetime.
        defer { session.invalidateAndCancel() }
        let (stream, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse,
              response.expectedContentLength <= 32 * 1024 else { throw UnyriPushAPIError.invalid }
        var data = Data()
        for try await byte in stream {
            guard data.count < 32 * 1024 else { throw UnyriPushAPIError.invalid }
            data.append(byte)
        }
        if response.statusCode == 409 {
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let error = object["error"] as? [String: Any], let code = error["code"] as? String else { throw UnyriPushAPIError.invalid }
            if code == "push_registration_retired" { throw UnyriPushAPIError.retired }
            if code == "push_registration_stale", let revision = UnyriPushPayload.integer(error["current_revision"]), revision > 0 {
                throw UnyriPushAPIError.stale(revision)
            }
            throw UnyriPushAPIError.invalid
        }
        guard response.statusCode == (method == "DELETE" ? 204 : 200) else { throw UnyriPushAPIError.http(response.statusCode) }
        return data
    }
}
