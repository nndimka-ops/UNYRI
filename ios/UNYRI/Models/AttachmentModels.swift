import Foundation

/// Numeric diagnostics only: no filenames, message IDs, keys or URLs.
enum AttachmentDownloadOrigin: Int, Sendable {
    case unspecified = 0
    case automatic = 1
    case messageTap = 2
    case mediaViewer = 3
    case forward = 4
}

enum AttachmentAutomaticDownloadPolicy {
    static func allows(workAllowed: Bool, connected: Bool, wifi: Bool, wifiEnabled: Bool,
                       mobileEnabled: Bool, hasLocalFile: Bool, failed: Bool, bytes: Int64) -> Bool {
        let enabled = wifi ? wifiEnabled : mobileEnabled
        let limit: Int64 = (wifi ? 50 : 5) * 1024 * 1024
        return workAllowed && connected && enabled && !hasLocalFile && !failed && bytes > 0 && bytes <= limit
    }
}

/// One transfer per attachment, with one cancellation lease per caller.
/// A departing automatic consumer cannot cancel a viewer/manual consumer;
/// cancelling the final consumer stops the otherwise unowned transfer.
@MainActor
final class AttachmentDownloadFlights {
    private final class Flight {
        var worker: Task<Void, Never>?
        var waiters: [UUID: CheckedContinuation<URL, Error>] = [:]
        var cancelled = false
    }
    private var flights: [String: Flight] = [:]
    var activeFlightCount: Int { flights.count }
    var activeConsumerCount: Int { flights.values.reduce(0) { $0 + $1.waiters.count } }

    /// Explicit user/account cancellation still cancels every consumer.
    func cancel(_ id: String) {
        guard let flight = flights[id] else { return }
        flight.cancelled = true
        let waiting = Array(flight.waiters.values)
        flight.waiters.removeAll()
        flight.worker?.cancel()
        for waiter in waiting { waiter.resume(throwing: CancellationError()) }
    }
    func cancelAndWait(_ ids: [String]) async {
        let pending = ids.compactMap { flights[$0]?.worker }
        ids.forEach { cancel($0) }
        for task in pending { await task.value }
    }

    func run(_ id: String, operation: @escaping @MainActor () async throws -> URL) async throws -> URL {
        try Task.checkCancellation()
        // An abandoned transfer must drain before a new writer for this same
        // attachment starts. Rapid off/on toggles cannot overlap cache writes.
        while let abandoned = flights[id], abandoned.cancelled {
            if let worker = abandoned.worker { await worker.value }
            try Task.checkCancellation()
        }
        let caller = UUID()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
                // Cancellation may arrive before the handler was installed.
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                if let flight = flights[id] {
                    flight.waiters[caller] = continuation
                } else {
                    let flight = Flight()
                    flight.waiters[caller] = continuation
                    flights[id] = flight
                    flight.worker = Task {
                        let outcome: Result<URL, Error>
                        do {
                            try Task.checkCancellation()
                            let url = try await operation()
                            try Task.checkCancellation()
                            outcome = .success(url)
                        } catch { outcome = .failure(error) }
                        finish(id, flight: flight, outcome: outcome)
                    }
                }
            }
            try Task.checkCancellation()
            return result
        }, onCancel: { [weak self] in
            Task { @MainActor in self?.release(id, caller: caller) }
        })
    }

    private func release(_ id: String, caller: UUID) {
        guard let flight = flights[id], let waiter = flight.waiters.removeValue(forKey: caller) else { return }
        waiter.resume(throwing: CancellationError())
        if flight.waiters.isEmpty {
            flight.cancelled = true
            flight.worker?.cancel()
        }
    }
    private func finish(_ id: String, flight: Flight, outcome: Result<URL, Error>) {
        guard flights[id] === flight else { return }
        flights[id] = nil
        flight.worker = nil
        let waiting = Array(flight.waiters.values)
        flight.waiters.removeAll()
        for waiter in waiting { waiter.resume(with: outcome) }
    }
}

enum AttachmentFileCopy {
    /// Acquire a provider grant in the picker's completion callback, before
    /// its presentation releases the selected URLs. Ownership can then move
    /// into a bounded import task or the explicit original-file confirmation.
    final class DocumentSource: @unchecked Sendable {
        let url: URL
        private let granted: Bool
        private let stopAccess: (URL) -> Void
        init(_ url: URL,
             startAccess: (URL) -> Bool = { $0.startAccessingSecurityScopedResource() },
             stopAccess: @escaping (URL) -> Void = { $0.stopAccessingSecurityScopedResource() }) {
            self.url = url; self.stopAccess = stopAccess
            granted = startAccess(url)
        }
        deinit { if granted { stopAccess(url) } }
    }

    /// A document provider may move/materialize the selected URL while it is
    /// being read. Keep coordination outside the main actor and cancel it with
    /// the import. Local camera/cache copies continue to use the fast path.
    final class DocumentRead: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        #if canImport(Darwin)
        private let coordinator = NSFileCoordinator()
        #endif
        func cancel() {
            lock.lock(); cancelled = true; lock.unlock()
            #if canImport(Darwin)
            coordinator.cancel()
            #endif
        }
        private func checkCancellation() throws {
            try Task.checkCancellation()
            lock.lock(); let stopped = cancelled; lock.unlock()
            if stopped { throw CancellationError() }
        }
        func size(of source: URL) throws -> Int64? {
            try checkCancellation()
            func readSize(_ readable: URL) throws -> Int64? {
                try checkCancellation()
                return try readable.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init)
            }
            #if canImport(Darwin)
            var coordinationError: NSError?
            var result: Result<Int64?, Error> = .failure(CocoaError(.fileReadUnknown))
            coordinator.coordinate(readingItemAt: source, options: .immediatelyAvailableMetadataOnly, error: &coordinationError) { readable in
                result = Result { try readSize(readable) }
            }
            try checkCancellation()
            if let coordinationError { throw coordinationError }
            return try result.get()
            #else
            return try readSize(source)
            #endif
        }
        func copy(from source: URL, to destination: URL, maxBytes: Int64, allocation: ((Int64, Bool) throws -> Void)? = nil) throws {
            try checkCancellation()
            #if canImport(Darwin)
            var coordinationError: NSError?
            var result: Result<Void, Error> = .failure(CocoaError(.fileReadUnknown))
            coordinator.coordinate(readingItemAt: source, options: .withoutChanges, error: &coordinationError) { readable in
                result = Result {
                    try checkCancellation()
                    try AttachmentFileCopy.copy(from: readable, to: destination, maxBytes: maxBytes, allocation: allocation)
                    try checkCancellation()
                }
            }
            try checkCancellation()
            if let coordinationError { throw coordinationError }
            try result.get()
            #else
            try AttachmentFileCopy.copy(from: source, to: destination, maxBytes: maxBytes, allocation: allocation)
            try checkCancellation()
            #endif
        }
    }

    // Local input for transcoding; the upload limit remains 100 MiB.
    static let maxVideoSourceBytes: Int64 = 500 * 1024 * 1024
    enum CopyError: Error, LocalizedError, CustomNSError {
        case tooLarge
        case videoSourceTooLarge
        static var errorDomain: String { "COCO.AttachmentCopy" }
        var errorCode: Int { self == .tooLarge ? 1 : 2 }
        var errorDescription: String? { "File exceeds the allowed size." }
    }
    static func copy(from source: URL, to destination: URL, maxBytes: Int64, allocation: ((Int64, Bool) throws -> Void)? = nil) throws {
        try Task.checkCancellation()
        // URL resource values may retain an old size after a file is rewritten.
        let size = ((try FileManager.default.attributesOfItem(atPath: source.path))[.size] as? NSNumber)?.int64Value ?? 0
        guard size <= maxBytes else { throw maxBytes > 100 * 1024 * 1024 ? CopyError.videoSourceTooLarge : CopyError.tooLarge }
        try requireSpace(at: destination.deletingLastPathComponent(), bytes: Int64(size))
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        var finished = false
        defer { if !finished { try? FileManager.default.removeItem(at: destination) } }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var total: Int64 = 0
        while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
            try Task.checkCancellation()
            total += Int64(chunk.count)
            guard total <= maxBytes else { throw maxBytes > 100 * 1024 * 1024 ? CopyError.videoSourceTooLarge : CopyError.tooLarge }
            // Default nil preserves ordinary imports. Direct originals reserve
            // a bounded allocation window before writing, then account it.
            try allocation?(total, true)
            try Task.checkCancellation()
            try output.write(contentsOf: chunk)
            try allocation?(total, false)
        }
        try output.synchronize()
        finished = true
    }

    static func requireSpace(at directory: URL, bytes: Int64) throws {
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        let available = (attributes[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        let reserve: Int64 = 16 * 1024 * 1024
        guard bytes >= 0, bytes <= Int64.max - reserve, available >= bytes + reserve else {
            throw CocoaError(.fileWriteOutOfSpace)
        }
    }
}

// MARK: - Backend DTOs (mirror server/src/attachments/models.rs)

/// `POST /attachments` — the client declares only the safe minimum: how many
/// encrypted bytes it is about to upload. Never a filename, mime type, or
/// media type (those live inside the Signal-encrypted message content).
struct CreateAttachmentRequest: Encodable, Equatable {
    let clientAttachmentId: String
    let encryptedSize: Int64
}

struct CreateAttachmentResponse: Decodable, Equatable {
    let attachmentId: String
    let objectKey: String
    let uploadUrl: String
    let expiresAt: Date
}

struct CompleteAttachmentResponse: Decodable, Equatable {
    let attachmentId: String
    let status: String
}

struct MultipartUpload: Decodable {
    let partSize: Int64
    let complete: Bool
    let parts: [MultipartPart]
}
struct MultipartPart: Decodable {
    let number: Int
    let size: Int64
    let uploadUrl: String
    let complete: Bool
}
struct MultipartReceipt: Encodable { let etag: String }

struct DownloadAttachmentResponse: Decodable, Equatable {
    let downloadUrl: String
    let expiresAt: Date
}

// MARK: - App-level attachment record (mirror Android AttachmentEntity)

/// Local cache row for one attachment, keyed by the server-issued
/// `attachmentId` (attachments have no meaningful client-generated id before
/// `/attachments` returns one). Persisted in the account-scoped SQLite store.
struct AttachmentRecord: Equatable, Sendable {
    let attachmentId: String
    let clientMessageId: String
    let chatId: String
    let mediaType: String
    let mimeType: String
    let filename: String?
    let size: Int64
    let width: Int?
    let height: Int?
    let durationMs: Int64?
    let thumbnailBase64: String?
    /// Decrypted local plaintext cache path — nil until downloaded.
    var localPath: String?
    /// not_downloaded | downloading | downloaded | failed | corrupt
    var downloadState: String
    /// uploading | uploaded | failed
    var uploadState: String
    var progress: Int
    let objectKey: String
    let encryptedSize: Int64
    /// 1 = legacy AES-256-GCM, 2 = Tink Streaming AEAD (Android's current
    /// format). Determines which of key/nonce vs keyset is populated.
    let encryptionVersion: Int
    let keyBase64: String?
    let nonceBase64: String?
    let keysetBase64: String?
    let sha256Base64: String

    /// The `EncryptionInfo` this record round-trips into inside the
    /// Signal-encrypted attachment metadata.
    var encryptionInfo: EncryptionInfo {
        EncryptionInfo(
            version: encryptionVersion,
            key: keyBase64,
            nonce: nonceBase64,
            keyset: keysetBase64,
            sha256: sha256Base64
        )
    }

    /// True when this is an outgoing attachment whose upload has NOT
    /// completed — its local plaintext is the retry/re-send source and must
    /// never be deleted by "Clear Media Cache".
    var isPendingUploadSource: Bool {
        uploadState == AttachmentRecord.uploadStateUploading ||
        uploadState == AttachmentRecord.uploadStateFailed
    }
}

// MARK: - Data & Storage models (Phase 9.1)

/// Truthful LOCAL cache accounting. Represents only device storage — never
/// server/S3 bytes.
struct StorageUsage: Equatable {
    let photosBytes: Int64
    let voiceBytes: Int64
    let filesBytes: Int64
    let tempBytes: Int64
    let draftBytes: Int64
    let databaseBytes: Int64

    var mediaBytes: Int64 { photosBytes + voiceBytes + filesBytes }
    var totalCacheBytes: Int64 { mediaBytes + tempBytes + draftBytes }
}

struct CacheClearResult: Equatable {
    let deletedFiles: Int
    let deletedBytes: Int64
    let protectedFiles: Int
    let failedFiles: Int
}

extension AttachmentRecord {
    static let downloadStateNotDownloaded = "not_downloaded"
    static let downloadStateDownloading = "downloading"
    static let downloadStateDownloaded = "downloaded"
    static let downloadStateFailed = "failed"
    static let downloadStateCorrupt = "corrupt"

    static let uploadStateUploading = "uploading"
    static let uploadStateUploaded = "uploaded"
    static let uploadStateFailed = "failed"
}

/// Maps an `AttachmentRecord` to the wire `AttachmentMetadata` that travels
/// inside the Signal-encrypted message content (mirrors Android's
/// `AttachmentEntity.toMetadataDto`).
func attachmentMetadata(from record: AttachmentRecord) -> AttachmentMetadata {
    AttachmentMetadata(
        attachmentId: record.attachmentId,
        mediaType: record.mediaType,
        objectKey: record.objectKey,
        size: record.size,
        encryptedSize: record.encryptedSize,
        mimeType: record.mimeType,
        encryption: record.encryptionInfo,
        filename: record.filename,
        image: (record.width != nil && record.height != nil)
            ? ImageInfo(width: record.width!, height: record.height!)
            : nil,
        voice: record.mediaType == AttachmentMetadata.mediaTypeVoice ? record.durationMs.map { VoiceInfo(durationMs: $0) } : nil,
        thumbnail: record.thumbnailBase64,
        video: record.mimeType.hasPrefix("video/") ? record.durationMs.map { VideoInfo(durationMs: $0) } : nil
    )
}

// MARK: - Attachment presentation helpers (Phase 7 §5/§18/§19)

/// Pure attachment limit/size helpers, mirroring Android's `AttachmentFormat`.
enum AttachmentFormat {
    /// Shared product selection cap.
    static let maxItems = 10

    /// Encrypted-size ceilings (mirror the encryption/upload pipeline).
    static let maxImageBytes: Int64 = 25 * 1024 * 1024
    static let maxFileBytes: Int64 = 100 * 1024 * 1024
    static let maxVoiceBytes: Int64 = 25 * 1024 * 1024

    /// Human-readable file size: B / KB / MB / GB.
    static func fileSize(_ bytes: Int64) -> String {
        if bytes < 0 { return "0 B" }
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024
        if kb < 1024 { return "\(format(kb)) KB" }
        let mb = kb / 1024
        if mb < 1024 { return "\(format(mb)) MB" }
        return "\(format(mb / 1024)) GB"
    }

    private static func format(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        if rounded == rounded.rounded(.down) {
            return String(Int(rounded))
        }
        // Locale-independent one-decimal rendering (never "," vs ".").
        let tenths = Int((rounded * 10).rounded())
        return "\(tenths / 10).\(tenths % 10)"
    }
}
enum FilePresentation {
    static func label(mime: String, filename: String?) -> String {
        let type = mime.lowercased()
        let ext = ((filename ?? "") as NSString).pathExtension.lowercased()
        if type == "application/pdf" || ext == "pdf" { return "PDF" }
        if type.contains("word") || ["doc", "docx", "odt"].contains(ext) { return "DOC" }
        if type.contains("sheet") || type.contains("excel") || ["xls", "xlsx", "csv", "ods"].contains(ext) { return "XLS" }
        if type.contains("presentation") || type.contains("powerpoint") || ["ppt", "pptx", "odp"].contains(ext) { return "PPT" }
        if type.contains("zip") || type.contains("compressed") || ["zip", "rar", "7z", "tar", "gz"].contains(ext) { return "ZIP" }
        if type.hasPrefix("video/") { return "VIDEO" }
        if type.hasPrefix("audio/") { return "AUDIO" }
        if type.hasPrefix("image/") { return "IMG" }
        if type.hasPrefix("text/") { return "TXT" }
        if (1...6).contains(ext.count), ext.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) }) { return ext.uppercased() }
        return "FILE"
    }
    static func symbol(_ label: String) -> String {
        switch label {
        case "ZIP": return "archivebox.fill"
        case "AUDIO": return "waveform"
        case "VIDEO": return "video.fill"
        case "IMG": return "photo.fill"
        case "XLS": return "tablecells.fill"
        case "PDF", "DOC", "TXT": return "doc.text.fill"
        default: return "doc.fill"
        }
    }
    static func duration(_ milliseconds: Int64?) -> String {
        guard let milliseconds else { return "" }
        let seconds = max(0, milliseconds) / 1000
        return seconds >= 3600 ? String(format: "%lld:%02lld:%02lld", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%lld:%02lld", seconds / 60, seconds % 60)
    }
}
