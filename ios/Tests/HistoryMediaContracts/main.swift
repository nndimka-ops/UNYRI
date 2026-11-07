import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

// Windows has no CryptoKit; inject a TEST-ONLY streaming checksum to execute IO
// contracts there. The macOS CI build below uses the production SHA-256 path.
#if !canImport(CryptoKit)
struct TestDigest: HistoryMediaDigest {
    var state: UInt64 = 0
    mutating func update(_ bytes: Data) { for byte in bytes { state = state &* 31 &+ UInt64(byte) } }
    mutating func finish() -> String { String(repeating: "0", count: 48) + String(format: "%016llx", state) }
}
#endif
func checksum(_ data: Data) -> String {
    #if canImport(CryptoKit)
    return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    #else
    var digest = TestDigest(); digest.update(data); return digest.finish()
    #endif
}
func check(_ value: Bool) { precondition(value) }
func mustFail(_ operation: () throws -> Void) {
    do { try operation(); fatalError("expected failure") } catch { }
}
@main struct Contracts {
    static func main() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("coco-media-test-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        #if canImport(CryptoKit)
        let spool = HistoryMediaSpool(root: root)
        #else
        let spool = HistoryMediaSpool(root: root, digest: { TestDigest() })
        #endif
        let data = Data((0..<160_001).map { UInt8($0 % 251) })
        func entry(_ blob: String = UUID().uuidString.lowercased(), bytes: Int64? = nil, hash: String? = nil) -> HistoryMediaEntry {
            .init(attachment: UUID().uuidString.lowercased(), blob: blob, bytes: bytes ?? Int64(data.count), sha256: hash ?? checksum(data))
        }
        let record = entry()
        let file = try spool.receive(record, open: { InputStream(data: data) }, assertCurrent: {})
        check(try Data(contentsOf: file) == data)
        let retried = try spool.receive(record, open: { fatalError("must reuse verified cache") }, assertCurrent: {})
        check(file == retried)
        for bad in [data.dropLast(), data + Data([1]), Data(repeating: 7, count: data.count)] {
            mustFail { _ = try spool.receive(entry(), open: { InputStream(data: Data(bad)) }, assertCurrent: {}) }
            check(try fm.contentsOfDirectory(atPath: root.path).count == 1)
        }
        var checks = 0
        mustFail { _ = try spool.receive(entry(), open: { InputStream(data: data) }, assertCurrent: {
            checks += 1; if checks >= 3 { throw CancellationError() }
        }) }
        check(try fm.contentsOfDirectory(atPath: root.path).count == 1)
        mustFail { _ = try spool.receive(entry(), open: { throw HistoryMediaSpool.Failure.storage }, assertCurrent: {}) }
        check(try fm.contentsOfDirectory(atPath: root.path).count == 1)
        try Data(repeating: 9, count: data.count).write(to: file)
        mustFail { _ = try spool.receive(record, open: { fatalError("corrupt cache must fail") }, assertCurrent: {}) }
        let a = entry(), b = entry()
        check(try validateHistoryMedia([a, b], required: [a.attachment, b.attachment], byteLimit: a.bytes + b.bytes) == a.bytes + b.bytes)
        mustFail { _ = try validateHistoryMedia([a], required: [a.attachment, b.attachment], byteLimit: Int64.max) }
        mustFail { _ = try validateHistoryMedia([a, b], required: [a.attachment], byteLimit: Int64.max) }
        mustFail { _ = try validateHistoryMedia([a, a], required: [a.attachment], byteLimit: Int64.max) }
        let duplicateBlob = HistoryMediaEntry(attachment: b.attachment, blob: a.blob, bytes: b.bytes, sha256: b.sha256)
        mustFail { _ = try validateHistoryMedia([a, duplicateBlob], required: [a.attachment, b.attachment], byteLimit: Int64.max) }
        mustFail { _ = try validateHistoryMedia([a, b], required: [a.attachment, b.attachment], byteLimit: a.bytes + b.bytes - 1) }
        mustFail { try entry("../escape").validate() }
        mustFail { try entry(bytes: Int64.max).validate() }
        mustFail { try entry(hash: String(repeating: "0", count: 63) + "/").validate() }
        let fixture = try JSONDecoder().decode(HistoryMediaEntry.self, from: Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-media-v1.json")))
        try fixture.validate()
        check(fixture.bytes == 3 && fixture.sha256 == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #if canImport(CryptoKit)
        let vector = try spool.receive(fixture, open: { InputStream(data: Data("abc".utf8)) }, assertCurrent: {})
        check(try Data(contentsOf: vector) == Data("abc".utf8))
        #endif
        print("History media contracts PASS (stream, retry, truncation, oversize, corruption, cancellation, IO failure, manifest coverage/quota)")
    }
}
