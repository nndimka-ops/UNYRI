import Foundation
import CryptoKit

// Public interoperability vector, never a real credential.
let code = "0123456789ABCDEFGHJKMNPQRSTVWXZS"
func check(_ value: Bool, line: UInt = #line) { if !value { fatalError("Admin signing contract failed at line \(line)") } }
check(AdminSigningKey.valid(code))
check(AdminSigningKey.acceptsPin(previous: nil,pending: "server|new",server: "server",publicKey: nil))
check(AdminSigningKey.acceptsPin(previous: "server|old",pending: "server|new",server: "server",publicKey: "new"))
check(!AdminSigningKey.acceptsPin(previous: nil,pending: "server|new",server: "server",publicKey: "attacker"))
check(!AdminSigningKey.acceptsPin(previous: "server|old",pending: nil,server: "other",publicKey: "old"))
check(!AdminSigningKey.valid(String(code.dropLast()) + "0"))
check(!AdminSigningKey.valid("FFE3-43FD-FDS3-DSD2"))
check(try AdminSigningKey.publicKey(code) == "Szyj6KzonzQXpYgcyGK7U2ECJMcry7zql3_gC1muVPM")
// Interoperability requires valid Ed25519 signatures, not identical signer randomness.
func decode(_ value: String) -> Data {
    let padded = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    return Data(base64Encoded: padded + String(repeating: "=", count: (4 - padded.count % 4) % 4))!
}
let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: decode(AdminSigningKey.publicKey(code)))
let payload = Data("COCO test vector".utf8)
let reference = decode("s5Xn31303SSBmTWdT7SJe_ILhCOcveaT1V5o93jjF9TLbo0w2RA380ktPVPks7KyEYAPh5So4RLotc04qgZ3AQ")
check(publicKey.isValidSignature(reference, for: payload))
let signature = decode(try AdminSigningKey.sign(code, payload: "COCO test vector"))
check(publicKey.isValidSignature(signature, for: payload))
check(!publicKey.isValidSignature(signature, for: Data("different challenge".utf8)))
let generated = try (0..<32).map { _ in try AdminSigningKey.generate() }
check(Set(generated).count == generated.count)
check(generated.allSatisfy { AdminSigningKey.valid($0) && $0.split(separator: "-").map(\.count) == [8,8,8,8] })
print("Admin signing key contracts passed")

check(!AdminSetupPolicy.expired(backgroundAt: nil, now: 10000))
check(!AdminSetupPolicy.expired(backgroundAt: 10, now: 189.99))
check(AdminSetupPolicy.expired(backgroundAt: 10, now: 190))
check(AdminSetupPolicy.expired(backgroundAt: 10, now: 9))
print("Admin setup resume policy contracts passed")

precondition(AdminSetupPolicy.validCode("001234", recovery: false))
precondition(!AdminSetupPolicy.validCode("１２３４５６", recovery: false))
precondition(!AdminSetupPolicy.validCode(String(repeating: "A", count: 32), recovery: false))
precondition(!AdminSetupPolicy.validCode(String(repeating: "A", count: 43), recovery: false))
precondition(AdminSetupPolicy.validCode(String(repeating: "A", count: 42) + "_", recovery: true))
precondition(!AdminSetupPolicy.validCode("123456", recovery: true))
precondition(!AdminSetupPolicy.validCode("12345\n", recovery: false))
