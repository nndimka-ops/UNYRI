import Foundation
import CryptoKit
import Security

/// 150 random bits + 10 checksum bits; neither code nor seed is uploaded.
enum AdminSigningKey {
    static func acceptsPin(previous: String?, pending: String?, server: String, publicKey: String?) -> Bool {
        let observed = server + "|" + (publicKey ?? "null")
        return (previous == nil && pending == nil) || previous == observed || pending == observed ||
            (previous == nil && publicKey == nil && pending?.components(separatedBy: "|").first == server)
    }
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    private static func hash(_ value: String) -> [UInt8] { Array(SHA256.hash(data: Data(value.utf8))) }
    static func normalize(_ value: String) -> String { value.uppercased().filter { $0 != "-" && !$0.isWhitespace } }
    private static func checksum(_ value: String) -> String {
        let bytes = hash("COCO-ADMIN-CHECK-V1\0" + value)
        return String([alphabet[Int(bytes[0] >> 3)], alphabet[Int(((bytes[0] & 7) << 2) | (bytes[1] >> 6))]])
    }
    static func valid(_ value: String) -> Bool {
        let raw = normalize(value)
        return raw.count == 32 && raw.allSatisfy { alphabet.contains($0) } && String(raw.suffix(2)) == checksum(String(raw.prefix(30)))
    }
    static func generate() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 30)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw CocoaError(.coderInvalidValue) }
        let raw = String(bytes.map { alphabet[Int($0 & 31)] })
        bytes = [UInt8](repeating: 0, count: bytes.count)
        let code = Array(raw + checksum(raw))
        return stride(from: 0, to: 32, by: 8).map { String(code[$0..<$0+8]) }.joined(separator: "-")
    }
    private static func key(_ value: String) throws -> Curve25519.Signing.PrivateKey {
        guard valid(value) else { throw CocoaError(.coderInvalidValue) }
        return try Curve25519.Signing.PrivateKey(rawRepresentation: Data(hash("COCO-ADMIN-SEED-V1\0" + normalize(value))))
    }
    private static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func publicKey(_ value: String) throws -> String { encode(try key(value).publicKey.rawRepresentation) }
    static func sign(_ value: String, payload: String) throws -> String { encode(try key(value).signature(for: Data(payload.utf8))) }
    static func payload(server: String, user: String, session: String, id: String, purpose: String, publicKey: String, nonce: String) throws -> String {
        guard [server,user,session,id].allSatisfy({ UUID(uuidString: $0)?.uuidString.lowercased() == $0 }),
              ["unlock","rotate"].contains(purpose),
              [publicKey,nonce].allSatisfy({ $0.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil }) else { throw CocoaError(.coderInvalidValue) }
        return ["COCO-ADMIN-PROOF-V1",server,user,session,id,purpose,publicKey,nonce].joined(separator: "\n")
    }
}

/// Only unfinished sign-in forms may survive a short authenticator switch.
enum AdminSetupPolicy {
    static func validCode(_ value: String, recovery: Bool) -> Bool {
        value.utf8.count == (recovery ? 43 : 6) && value.range(of: recovery ? "^[A-Za-z0-9_-]{43}$" : "^[0-9]{6}$", options: .regularExpression) != nil
    }
    static let resumeWindow: TimeInterval = 180
    static func expired(backgroundAt: TimeInterval?, now: TimeInterval) -> Bool {
        guard let backgroundAt else { return false }
        return now < backgroundAt || now - backgroundAt >= resumeWindow
    }
}

/// ADMIN CONSOLE PACK 3 - native side of the server-hosted console.
///
/// iOS keeps the secure core only: role visibility, the three-factor unlock, the
/// paper key, the session lifecycle and the privacy protections. The
/// administration product itself is served by the UNYRI server, so no screen is
/// duplicated here. This device's whole job is to hand the browser a single-use
/// URL.
enum AdminConsolePolicy {
    /// A handoff is a 32-byte base64url token.
    static let handoffLength = 43
    static let consolePath = "administration"

    /// The entry exists only for a role the server actually granted.
    static func entryVisible(role: String?) -> Bool {
        role == "owner" || role == "moderator"
    }

    /// Only the native channel may mint a handoff, so a stolen browser cookie
    /// cannot be turned into a durable foothold.
    static func canMintHandoff(role: String?) -> Bool { entryVisible(role: role) }

    /// The console URL, or nil when it cannot be built safely.
    ///
    /// The handoff travels in the fragment: a fragment is never sent to the
    /// server, never recorded by a reverse proxy and never leaks through a
    /// `Referer` header. The console page clears it from the address bar before
    /// it does anything else. Cleartext is refused outright - the console sends
    /// `Secure` cookies and enforces same-origin requests.
    static func consoleUrl(base: URL, handoff: String) -> URL? {
        guard handoff.utf8.count == handoffLength,
              handoff.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
              base.scheme == "https",
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        components.path = "/" + consolePath
        components.query = nil
        components.fragment = "handoff=" + handoff
        return components.url
    }
}
