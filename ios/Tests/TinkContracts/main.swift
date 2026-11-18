import Foundation
import CryptoKit

let root = URL(fileURLWithPath: "ios/Tests/TinkFixtures/vectors")
let work = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: work) }

func keyset(_ size: Int) throws -> Data {
    let encoded = try String(contentsOf: root.appendingPathComponent("\(size).keyset"), encoding: .utf8)
    return Data(base64Encoded: encoded)!
}

for size in [0, 1, 1048520, 1048521, 2097161] {
    let cipher = root.appendingPathComponent("\(size).bin")
    let expected = Data(base64Encoded: try String(contentsOf: root.appendingPathComponent("\(size).sha256"), encoding: .utf8))!
    let destination = work.appendingPathComponent("\(size).plain")
    try AttachmentCrypto.decryptV2(ciphertextFile: cipher, plaintextFile: destination,
        keyset: keyset(size), expectedSha256: expected)
    let plain = try Data(contentsOf: destination)
    precondition(plain.count == size)
    precondition(plain.enumerated().allSatisfy { $0.element == UInt8($0.offset % 251) })
    print("Tink Android vector decrypted: \(size) bytes")
}

func mustReject(_ cipher: Data, key: Data, hash: Data? = nil) throws {
    let input = work.appendingPathComponent("bad.bin")
    let output = work.appendingPathComponent("rejected.plain")
    try cipher.write(to: input)
    do {
        try AttachmentCrypto.decryptV2(ciphertextFile: input, plaintextFile: output,
            keyset: key, expectedSha256: hash ?? Data(SHA256.hash(data: cipher)))
        fatalError("Accepted malformed Tink attachment")
    } catch {
        precondition(!FileManager.default.fileExists(atPath: output.path), "Published unauthenticated plaintext")
        let remnants = try FileManager.default.contentsOfDirectory(atPath: work.path)
        precondition(!remnants.contains { $0.hasSuffix(".partial") }, "Leaked partial plaintext")
    }
}
let valid = try Data(contentsOf: root.appendingPathComponent("2097161.bin"))
let key = try keyset(2097161)
var badTag = valid; badTag[badTag.count - 1] ^= 1
try mustReject(badTag, key: key) // Recomputed SHA: this specifically tests GCM authentication.
try mustReject(Data(valid.prefix(1_048_576)), key: key) // Complete non-final segment cannot become final.
try mustReject(valid + Data([0]), key: key)
try mustReject(valid, key: key, hash: Data(repeating: 0, count: 32))
try mustReject(valid, key: keyset(1))
for length in [0, 1, 7, key.count - 1] { try mustReject(valid, key: Data(key.prefix(length))) }
var badHeader = valid; badHeader[0] = 24
try mustReject(badHeader, key: key)
print("Tink corruption, truncation, wrong-key and partial-file checks passed")
