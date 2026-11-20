import Foundation
import CryptoKit
import Security

// AuthModels has display defaults supplied by UIKit in the application.
enum DeviceInfo {
    static let currentAppVersion = "contract"
    static let currentOSVersion = "contract"
}

@main
struct DeviceBoundSessionContracts {
    static func check(_ value: Bool, _ name: String) {
        precondition(value, name)
        print("PASS:", name)
    }

    static func main() throws {
        let session = "11111111-2222-3333-4444-555555555555"
        let jti = UUID(uuidString: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!
        let timestamp: Int64 = 1_800_000_000
        let expected = "UNYRI-DBS-1\nPOST\n/auth/login\n\(session)\n1800000000\naaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"
        let canonical = try DeviceBoundProof.canonical(method: "POST", target: "/auth/login",
            session: session.uppercased(), timestamp: timestamp, jti: jti, token: "")
        check(String(data: canonical, encoding: .utf8) == expected, "exact cross-platform LF and empty-token hash")
        check(canonical.last != 10, "no trailing newline")

        let key = P256.Signing.PrivateKey()
        let token = session + ".test-token"
        let header = try DeviceBoundProof.build(method: "POST", target: "/auth/refresh", session: session,
            token: token, timestamp: timestamp, jti: jti, sign: { try key.signature(for: $0).derRepresentation })
        let wire = try JSONSerialization.jsonObject(with: DeviceBoundProof.decodeBase64URL(header)!) as! [String: Any]
        check(Set(wire.keys) == Set(["ts", "jti", "sig"]), "only version-one proof fields")
        check(wire["jti"] as? String == jti.uuidString.lowercased(), "lowercase jti")
        let signature = try P256.Signing.ECDSASignature(derRepresentation:
            DeviceBoundProof.decodeBase64URL(wire["sig"] as! String)!)
        let signed = try DeviceBoundProof.canonical(method: "POST", target: "/auth/refresh", session: session,
            timestamp: timestamp, jti: jti, token: token)
        check(key.publicKey.isValidSignature(signature, for: signed), "DER P-256 signature verifies")
        let otherKey = P256.Signing.PrivateKey()
        check(!otherKey.publicKey.isValidSignature(signature, for: signed), "another device key cannot prove possession")
        for (method, path, value) in [("GET", "ws", token), ("POST", "/auth/login", token), ("POST", "/auth/refresh", token + "copied")] {
            let altered = try DeviceBoundProof.canonical(method: method, target: path, session: session,
                timestamp: timestamp, jti: jti, token: value)
            check(!key.publicKey.isValidSignature(signature, for: altered), "proof binds method, target and exact token")
        }
        let fresh = try DeviceBoundProof.build(method: "POST", target: "/auth/refresh", session: session,
            token: token, timestamp: timestamp, sign: { try key.signature(for: $0).derRepresentation })
        let freshWire = try JSONSerialization.jsonObject(with: DeviceBoundProof.decodeBase64URL(fresh)!) as! [String: Any]
        check(freshWire["jti"] as? String != wire["jti"] as? String, "transport retry receives fresh replay id")
        check(try DeviceBoundProof.refreshSession(token) == session, "refresh token session context")
        let claims = DeviceBoundProof.base64URL(Data("{\"session_id\":\"\(session)\"}".utf8))
        check(try DeviceBoundProof.accessSession("header." + claims + ".signature") == session, "JWT session context")
        do {
            _ = try DeviceBoundProof.canonical(method: "POST", target: "/auth/refresh?x=1", session: session,
                timestamp: timestamp, jti: jti, token: token)
            preconditionFailure("unrecognized target accepted")
        } catch DeviceBoundProof.Failure.invalidContext { print("PASS: unrecognized target rejected") }
        check(DeviceBoundProof.decodeBase64URL("a===") == nil, "padded/noncanonical wire rejected")

        let now = Date(timeIntervalSince1970: 1_000_000)
        let clock = DeviceBoundClock()
        clock.correct(serverTime: 1_018_000, now: now)
        check(clock.timestamp(now: now.addingTimeInterval(1)) == 1_018_001, "server clock correction follows elapsed time")
        clock.correct(serverTime: Int64.max, now: now)
        check(clock.timestamp(now: now) == 1_018_000, "hostile clock correction cannot overflow")

        // Compile and exercise the actual Security signing API/algorithm. The
        // ephemeral key is never persisted and needs neither Enclave nor UI.
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                                       kSecAttrKeySizeInBits as String: 256]
        var error: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let publicKey = SecKeyCopyPublicKey(secKey),
              let publicData = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?,
              let signatureData = SecKeyCreateSignature(secKey, .ecdsaSignatureMessageX962SHA256,
                                                       signed as CFData, &error) as Data? else {
            preconditionFailure("Security signing API unavailable")
        }
        let interoperableKey = try P256.Signing.PublicKey(x963Representation: publicData)
        check(interoperableKey.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation: signatureData),
            for: signed), "Security message-X962-SHA256 matches portable verification")
        check(publicData.count == 65 && publicData.first == 4, "uncompressed SEC1 public key")
    }
}
