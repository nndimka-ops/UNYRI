import Foundation

// Minimal non-UI error types for compiling the production HTTP adapter alone.
enum APIError: Error { case invalidURL, network(String), http(Int) }
struct AttachmentIntegrityError: Error {}

@main struct HistoryMediaHTTPContracts {
    static func reject(_ operation: () async throws -> Void) async throws {
        do { try await operation() } catch { return }
        fatalError("Expected HTTP transfer rejection")
    }
    static func main() async throws {
        let base = URL(string: CommandLine.arguments[1])!
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("coco-media-http-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("upload")
        try Data((0..<65536).map { UInt8($0 % 256) }).write(to: file)
        let api = HistoryBackupAPI(baseURL: base, accessToken: { "must-not-reach-storage" }, assertCurrent: {})
        func url(_ path: String) -> String { base.appendingPathComponent(path).absoluteString }
        try await api.uploadMedia(url: url("put"), file: file, bytes: 65536)
        try await reject { try await api.uploadMedia(url: url("put-overflow"), file: file, bytes: 65536) }
        try await reject { try await api.uploadMedia(url: url("put-redirect"), file: file, bytes: 65536) }
        let entry = HistoryMediaEntry(attachment: "00000000-0000-4000-8000-000000000001",
            blob: "00000000-0000-4000-8000-000000000002", bytes: 3,
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        let downloaded = try await api.receiveMedia(url: url("get"), entry: entry, root: root.appendingPathComponent("valid"), check: {})
        let actual = try Data(contentsOf: downloaded)
        precondition(actual == Data("abc".utf8))
        try await reject { _ = try await api.receiveMedia(url: url("get-overflow"), entry: entry, root: root.appendingPathComponent("oversize"), check: {}) }
        try await reject { _ = try await api.receiveMedia(url: url("get-redirect"), entry: entry, root: root.appendingPathComponent("redirect"), check: {}) }
        let secure = HistoryBackupAPI(baseURL: URL(string: "https://localhost")!, accessToken: { "never" }, assertCurrent: {})
        try await reject { try await secure.uploadMedia(url: url("put"), file: file, bytes: 65536) }
        try await reject { _ = try await secure.receiveMedia(url: url("get"), entry: entry, root: root, check: {}) }
        print("History media HTTP contracts passed: exact streamed PUT, bounded GET/PUT, no bearer, no redirect, no TLS downgrade")
    }
}
