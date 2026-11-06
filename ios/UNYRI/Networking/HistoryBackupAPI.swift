import Foundation

/// Captured account/server transport. Owner supplies generation-fenced token and
/// session checks; no token or plaintext is persisted by this object.
final class HistoryBackupAPI: HistoryBackupTransport {
    private let baseURL: URL
    private let accessToken: () async throws -> String
    private let assertCurrent: () async throws -> Void
    private let session: URLSession
    private let redirects = NoHistoryBackupRedirects()

    init(baseURL: URL, accessToken: @escaping () async throws -> String, assertCurrent: @escaping () async throws -> Void, allowsCellularAccess: Bool = true) {
        self.baseURL = baseURL; self.accessToken = accessToken; self.assertCurrent = assertCurrent
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 120
        config.urlCache = nil
        config.allowsCellularAccess = allowsCellularAccess
        self.session = URLSession(configuration: config)
    }
    deinit { session.invalidateAndCancel() }
    func cancel() { session.invalidateAndCancel() }

    func restoreComplete(_ backupId: String) async throws {
        try HistoryArchive.validateId(backupId)
        struct Request: Encodable { let backupId: String }
        struct Response: Decodable { let ok: Bool }
        let value: Response = try await request("/history-backup/restore-complete", body: encode(Request(backupId: backupId)))
        guard value.ok else { throw HistoryArchive.Failure.invalidArchive }
    }
    func status() async throws -> HistoryBackupState { try await request("/history-backup") }
    func download(_ id: String) async throws -> HistoryBackupObject {
        try HistoryArchive.validateId(id)
        return try await request("/history-backup/objects/" + id, maxBytes: 12 * 1024 * 1024)
    }
    func stage(_ value: HistoryBackupUpload) async throws -> HistoryBackupState {
        try await request("/history-backup/stage", body: encode(value))
    }
    func confirm(_ value: HistoryBackupConfirm) async throws -> HistoryBackupState {
        try await request("/history-backup/confirm", body: encode(value))
    }
    func preference(_ value: HistoryBackupPreference) async throws -> HistoryBackupState {
        try await request("/history-backup/preference", body: encode(value))
    }
    func delete(_ value: HistoryBackupDelete) async throws -> HistoryBackupState {
        try await request("/history-backup/delete", body: encode(value))
    }
    struct MediaObject: Decodable {
        let blobId: String; let bytes: Int64; let sha256: String
        let uploadUrl: String?; let downloadUrl: String?
    }
    func startMedia(_ entry: HistoryMediaEntry, revision: Int64) async throws -> MediaObject {
        struct Start: Encodable { let expectedRevision: Int64; let blobId: String; let bytes: Int64; let sha256: String }
        return try await request("/history-backup/media/start", body: encode(Start(expectedRevision: revision, blobId: entry.blob, bytes: entry.bytes, sha256: entry.sha256)))
    }
    func sealMedia(_ entry: HistoryMediaEntry, revision: Int64) async throws -> MediaObject {
        try entry.validate()
        struct Seal: Encodable { let expectedRevision: Int64 }
        return try await request("/history-backup/media/" + entry.blob + "/seal", body: encode(Seal(expectedRevision: revision)))
    }
    func media(_ entry: HistoryMediaEntry) async throws -> MediaObject {
        try entry.validate()
        return try await request("/history-backup/media/" + entry.blob)
    }
    func liveMediaURL(_ id: String) async throws -> String {
        try HistoryArchive.validateId(id)
        struct Download: Decodable { let downloadUrl: String }
        let value: Download = try await request("/attachments/" + id + "/download")
        return value.downloadUrl
    }
    func serverDeletions(_ ids: [String]) async throws -> [String: Int64] {
        guard ids.count <= 500, Set(ids).count == ids.count else { throw HistoryArchive.Failure.invalidArchive }
        for id in ids { try HistoryArchive.validateId(id) }
        struct Request: Encodable { let messageIds: [String] }
        struct Entry: Decodable { let id: String; let sequence: Int64 }
        struct Response: Decodable { let deleted: [Entry] }
        let response: Response = try await request("/history-backup/restore-check", body: encode(Request(messageIds: ids)))
        var result: [String: Int64] = [:]
        for entry in response.deleted {
            guard ids.contains(entry.id), entry.sequence > 0, result[entry.id] == nil else { throw HistoryArchive.Failure.invalidArchive }
            result[entry.id] = entry.sequence
        }
        return result
    }
    func serverCallDeletions(_ ids: [String]) async throws -> Set<String> {
        guard ids.count <= 500, Set(ids).count == ids.count else { throw HistoryArchive.Failure.invalidArchive }
        for id in ids { try HistoryArchive.validateId(id) }
        struct Request: Encodable { let messageIds: [String] = []; let callIds: [String] }
        struct Response: Decodable { let deletedCallIds: [String]? }
        let response: Response = try await request("/history-backup/restore-check", body: encode(Request(callIds: ids)))
        guard let deleted = response.deletedCallIds, Set(deleted).count == deleted.count,
              Set(deleted).isSubset(of: Set(ids)) else { throw HistoryBackupCoordinator.Failure.unsupportedServer }
        return Set(deleted)
    }
    func sourceComplete(_ ids: [String], calls: [String] = []) async throws -> Bool {
        guard ids.count <= 50_000, Set(ids).count == ids.count,
              calls.count <= 50_000, Set(calls).count == calls.count else { throw HistoryArchive.Failure.invalidArchive }
        for id in ids { try HistoryArchive.validateId(id) }
        for id in calls { try HistoryArchive.validateId(id) }
        struct Request: Encodable { let messageIds: [String]; let callIds: [String] }
        struct Response: Decodable { let complete: Bool }
        let response: Response = try await request("/history-backup/source-check", body: encode(Request(messageIds: ids, callIds: calls)))
        return response.complete
    }
    /// The owner actor calls this off MainActor. Responses are bounded on disk
    /// while receiving, including when the peer omits Content-Length.
    func receiveMedia(url: String, entry: HistoryMediaEntry, root: URL,
                      check: @escaping () throws -> Void) async throws -> URL {
        try entry.validate(); try check(); try await assertCurrent()
        guard let source = URL(string: url), ["https", "http"].contains(source.scheme ?? ""),
              source.user == nil, source.password == nil, source.fragment == nil,
              baseURL.scheme != "https" || source.scheme == "https" else { throw APIError.invalidURL }
        let target = root.appendingPathComponent("network-" + UUID().uuidString + ".partial")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: target) }
        guard FileManager.default.createFile(atPath: target.path, contents: nil) else { throw HistoryMediaSpool.Failure.storage }
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        let (bytes, response) = try await session.bytes(for: URLRequest(url: source), delegate: redirects)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              response.expectedContentLength < 0 || response.expectedContentLength == entry.bytes else { throw AttachmentIntegrityError() }
        var chunk = Data(), count: Int64 = 0
        for try await byte in bytes {
            guard count < entry.bytes else { throw AttachmentIntegrityError() }
            chunk.append(byte); count += 1
            if chunk.count == 65536 { try check(); try Task.checkCancellation(); try output.write(contentsOf: chunk); chunk.removeAll(keepingCapacity: true) }
        }
        try check(); try Task.checkCancellation()
        guard count == entry.bytes else { throw AttachmentIntegrityError() }
        try output.write(contentsOf: chunk); try output.synchronize(); try output.close()
        try await assertCurrent()
        return try HistoryMediaSpool(root: root).receive(entry, open: {
            guard let input = InputStream(url: target) else { throw AttachmentIntegrityError() }; return input
        }, assertCurrent: check)
    }
    func uploadMedia(url: String, file: URL, bytes: Int64) async throws {
        try await assertCurrent(); try Task.checkCancellation()
        guard let target = URL(string: url), ["https", "http"].contains(target.scheme ?? ""),
              target.user == nil, target.password == nil, target.fragment == nil,
              baseURL.scheme != "https" || target.scheme == "https" else { throw APIError.invalidURL }
        var request = URLRequest(url: target); request.httpMethod = "PUT"
        request.setValue(String(bytes), forHTTPHeaderField: "Content-Length")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        guard let input = InputStream(url: file) else { throw HistoryMediaSpool.Failure.storage }
        request.httpBodyStream = input
        let (responseBytes, response) = try await session.bytes(for: request, delegate: redirects)
        defer { responseBytes.task.cancel(); input.close() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.network("Media upload") }
        guard response.expectedContentLength <= 65536 else { throw AttachmentIntegrityError() }
        var received = 0
        for try await _ in responseBytes {
            try Task.checkCancellation(); received += 1
            guard received <= 65536 else { throw AttachmentIntegrityError() }
        }
        try Task.checkCancellation(); try await assertCurrent()
    }
    func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        return try encoder.encode(value)
    }
    func request<T: Decodable>(_ path: String, body: Data? = nil, maxBytes: Int = 64 * 1024) async throws -> T {
        try Task.checkCancellation(); try await assertCurrent()
        let token = try await accessToken()
        try await assertCurrent()
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              components.user == nil, components.password == nil,
              components.scheme == "https" || components.scheme == "http" else { throw APIError.invalidURL }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = (components.path.isEmpty ? "" : "/" + components.path) + path
        components.query = nil; components.fragment = nil
        guard let url = components.url else { throw APIError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.httpBody = body
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Unlike data(for:), bound response memory while receiving it.
        let (bytes, response) = try await session.bytes(for: request, delegate: redirects)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse else { throw APIError.network("Backup response") }
        if path == "/history-backup", [404, 405].contains(http.statusCode) { throw HistoryBackupCoordinator.Failure.unsupportedServer }
        guard (200..<300).contains(http.statusCode) else { throw APIError.http(http.statusCode) }
        guard response.expectedContentLength <= Int64(maxBytes) else { throw HistoryBackupCoordinator.Failure.invalidResponse }
        var data = Data()
        for try await byte in bytes {
            if data.count % 8192 == 0 { try Task.checkCancellation() }
            guard data.count < maxBytes else { throw HistoryBackupCoordinator.Failure.invalidResponse }
            data.append(byte)
        }
        try Task.checkCancellation(); try await assertCurrent()
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: data)
    }
}

private final class NoHistoryBackupRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
