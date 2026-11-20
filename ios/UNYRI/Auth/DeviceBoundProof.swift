import Foundation
import CryptoKit

/// Wire bytes shared with Android and the server. There is no trailing LF.
enum DeviceBoundProof {
    static let header = "X-UNYRI-Device-Proof"
    static let clockHeader = "X-UNYRI-Proof-Time"

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func decodeBase64URL(_ value: String) -> Data? {
        guard !value.isEmpty, value.utf8.allSatisfy({
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }) else { return nil }
        let text = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: text + String(repeating: "=", count: (4 - text.count % 4) % 4))
    }

    static func canonical(method: String, target: String, session: String, timestamp: Int64,
                          jti: UUID, token: String) throws -> Data {
        guard let id = UUID(uuidString: session),
              token.utf8.count <= 16_384,
              (method == "POST" && ["/auth/login", "/auth/register", "/auth/refresh"].contains(target)) ||
                (method == "GET" && target == "ws") else { throw Failure.invalidContext }
        let hash = base64URL(Data(SHA256.hash(data: Data(token.utf8))))
        return Data(["UNYRI-DBS-1", method, target, id.uuidString.lowercased(), String(timestamp),
                     jti.uuidString.lowercased(), hash].joined(separator: "\n").utf8)
    }

    static func build(method: String, target: String, session: String, token: String, timestamp: Int64,
                      jti: UUID = UUID(), sign: (Data) throws -> Data) throws -> String {
        let message = try canonical(method: method, target: target, session: session,
                                    timestamp: timestamp, jti: jti, token: token)
        struct Wire: Encodable { let ts: Int64; let jti: String; let sig: String }
        let wire = Wire(ts: timestamp, jti: jti.uuidString.lowercased(), sig: base64URL(try sign(message)))
        let result = base64URL(try JSONEncoder().encode(wire))
        guard result.utf8.count <= 2048 else { throw Failure.invalidContext }
        return result
    }

    static func refreshSession(_ token: String) throws -> String {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[1].isEmpty,
              let uuid = UUID(uuidString: String(parts[0])) else { throw Failure.invalidContext }
        return uuid.uuidString.lowercased()
    }

    /// The server authenticates the JWT; locally this only selects signed context.
    static func accessSession(_ token: String) throws -> String {
        let fields = token.split(separator: ".", omittingEmptySubsequences: false)
        guard fields.count == 3, let bytes = decodeBase64URL(String(fields[1])),
              let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let raw = object["session_id"] as? String, let id = UUID(uuidString: raw) else { throw Failure.invalidContext }
        return id.uuidString.lowercased()
    }

    enum Failure: Error { case invalidContext, missingKey, persistence, unavailable }
}

struct DeviceBoundClockChallenge: Error {
    let serverTime: Int64
}

/// A clock correction is learned only from a signed-proof challenge, kept in RAM.
final class DeviceBoundClock {
    private let lock = NSLock()
    private var offset: TimeInterval = 0
    func timestamp(now: Date = Date()) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return Int64(max(0, min(253_402_300_799, now.timeIntervalSince1970 + offset)))
    }
    func correct(serverTime: Int64, now: Date = Date()) {
        guard (0...253_402_300_799).contains(serverTime) else { return }
        lock.lock(); defer { lock.unlock() }
        offset = Double(serverTime) - now.timeIntervalSince1970
    }
}
