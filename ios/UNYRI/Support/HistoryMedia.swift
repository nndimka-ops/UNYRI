import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// Required attachments come from the final merged visible archive, not a page
/// of local history. Missing OR extra entries must fail before commit.
func validateHistoryMedia(_ entries: [HistoryMediaEntry], required: Set<String>, byteLimit: Int64) throws -> Int64 {
    guard entries.count <= 50_000, byteLimit >= 0 else { throw HistoryArchive.Failure.invalidArchive }
    var attachments = Set<String>(), blobs = Set<String>(), total: Int64 = 0
    for entry in entries {
        try entry.validate()
        guard attachments.insert(entry.attachment).inserted, blobs.insert(entry.blob).inserted,
              entry.bytes <= byteLimit - total else { throw HistoryArchive.Failure.invalidArchive }
        total += entry.bytes
    }
    guard attachments == required else { throw HistoryArchive.Failure.invalidArchive }
    return total
}

protocol HistoryMediaDigest {
    mutating func update(_ bytes: Data)
    mutating func finish() -> String
}

/// Synchronous bounded IO: invoke on the account's background IO executor, not
/// MainActor. The root must be private and scoped to server/account. Caller must
/// fence its eventual native import; receiving ciphertext alone is not restore.
final class HistoryMediaSpool {
    private let root: URL
    private let digest: () -> any HistoryMediaDigest
    private let lock = NSLock()
    init(root: URL, digest: @escaping () -> any HistoryMediaDigest) {
        self.root = root.standardizedFileURL.resolvingSymlinksInPath()
        self.digest = digest
    }
    func receive(_ entry: HistoryMediaEntry, open: () throws -> InputStream, assertCurrent: () throws -> Void) throws -> URL {
        lock.lock(); defer { lock.unlock() }
        try entry.validate(); try assertCurrent()
        let fm = FileManager.default
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent("\(entry.blob)-\(entry.sha256).cipher")
        if fm.fileExists(atPath: destination.path) {
            try verify(destination, entry: entry, assertCurrent: assertCurrent)
            return destination
        }
        // A private, unpredictable staging directory prevents accidental reuse
        // and ensures move stays on the same filesystem. Nothing imports .partial.
        let staging = root.appendingPathComponent("transfer-\(UUID().uuidString).partial", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? fm.removeItem(at: staging) }
        let temporary = staging.appendingPathComponent("cipher")
        guard fm.createFile(atPath: temporary.path, contents: nil) else { throw Failure.storage }
        let output = try FileHandle(forWritingTo: temporary)
        do {
            var hash = digest()
            try readBounded(try open(), expected: entry.bytes, assertCurrent: assertCurrent) { data in
                hash.update(data); try output.write(contentsOf: data)
            }
            guard hash.finish() == entry.sha256 else { throw Failure.integrity }
            try output.synchronize(); try output.close()
        } catch { try? output.close(); throw error }
        try assertCurrent()
        do { try fm.moveItem(at: temporary, to: destination) }
        catch {
            guard fm.fileExists(atPath: destination.path) else { throw error }
            try verify(destination, entry: entry, assertCurrent: assertCurrent)
        }
        return destination
    }
    func verify(_ file: URL, entry: HistoryMediaEntry, assertCurrent: () throws -> Void) throws {
        try entry.validate(); try assertCurrent()
        let canonical = file.standardizedFileURL.resolvingSymlinksInPath()
        guard canonical.path == file.standardizedFileURL.path,
              canonical.deletingLastPathComponent().path == root.path else { throw Failure.storage }
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular,
              (attributes[.size] as? NSNumber)?.int64Value == entry.bytes,
              let stream = InputStream(url: file) else { throw Failure.integrity }
        var hash = digest()
        try readBounded(stream, expected: entry.bytes, assertCurrent: assertCurrent) { hash.update($0) }
        guard hash.finish() == entry.sha256 else { throw Failure.integrity }
        try assertCurrent()
    }
    private func readBounded(_ stream: InputStream, expected: Int64, assertCurrent: () throws -> Void, consume: (Data) throws -> Void) throws {
        stream.open(); defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: 64 * 1024), total: Int64 = 0
        while true {
            try assertCurrent()
            let limit = Int(min(Int64(buffer.count), expected - total + 1))
            let count = stream.read(&buffer, maxLength: limit)
            guard count >= 0 else { throw stream.streamError ?? Failure.storage }
            if count == 0 { break }
            guard Int64(count) <= expected - total else { throw Failure.integrity }
            try consume(Data(buffer[0..<count])); total += Int64(count)
        }
        guard total == expected else { throw Failure.integrity }
        try assertCurrent()
    }
    enum Failure: Error { case integrity, storage }
}

#if canImport(CryptoKit)
private struct PlatformHistoryMediaDigest: HistoryMediaDigest {
    private var hash = SHA256()
    mutating func update(_ bytes: Data) { hash.update(data: bytes) }
    mutating func finish() -> String { hash.finalize().map { String(format: "%02x", $0) }.joined() }
}
extension HistoryMediaSpool {
    convenience init(root: URL) { self.init(root: root, digest: { PlatformHistoryMediaDigest() }) }
}
#endif
