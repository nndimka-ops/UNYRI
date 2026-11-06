import Foundation

// ---------------------------------------------------------------------------
// ADMIN PRODUCT PACK 2.1 - typed administration transport (iOS).
//
// The screens never see raw JSON. `AdminPanelView` supplies one raw callback
// that already enforces the security model (HTTPS only, redirect rejection, the
// `X-COCO-Admin` header, the ephemeral URLSession, and stale-result rejection
// through the session/panel generation); this type only turns its payloads into
// typed values.
// ---------------------------------------------------------------------------

/// The raw administration call: returns response bytes, throws on a non-2xx.
typealias AdminRawCall = @Sendable (_ path: String, _ body: Data?) async throws -> Data

struct AdminTransport {
    let call: AdminRawCall

    /// Unknown keys are ignored so a new server field cannot break an older
    /// client. Encoding converts to snake_case, and a nil optional is omitted
    /// (which is exactly what the server's own `#[serde(default)]` expects).
    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return encoder
    }()

    func get<T: Decodable>(_ path: String) async throws -> T {
        try Self.decoder.decode(T.self, from: try await call(path, nil))
    }

    func post<B: Encodable, T: Decodable>(_ path: String, _ body: B) async throws -> T {
        let data = try Self.encoder.encode(body)
        return try Self.decoder.decode(T.self, from: try await call(path, data))
    }

    /// For endpoints that answer `{"ok":true}`.
    func postOk<B: Encodable>(_ path: String, _ body: B) async throws -> Bool {
        let reply: AdminOkDTO = try await post(path, body)
        return reply.ok
    }

    /// For POST endpoints that take no body. An empty JSON object is sent so the
    /// request still has a body, and the server ignores it.
    func postEmpty<T: Decodable>(_ path: String) async throws -> T {
        try Self.decoder.decode(T.self, from: try await call(path, Data("{}".utf8)))
    }

    /// Screenshot bytes are never decoded as JSON and never logged.
    func screenshot(_ path: String) async throws -> Data {
        try await call(path, nil)
    }
}
