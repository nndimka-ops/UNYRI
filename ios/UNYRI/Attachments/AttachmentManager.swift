import UniformTypeIdentifiers
import AVFoundation
import Combine
import CryptoKit
import Foundation
import UIKit
import ImageIO

/// The ONE place every attachment (photo/file) is prepared, encrypted,
/// uploaded, downloaded, verified, and decrypted — never spread across views
/// (mirrors Android's `AttachmentManager`, Этап H п.15). Local plaintext caches
/// live in an account-scoped caches directory (app-private), never the public
/// Photos/Files, until the user explicitly saves/shares.
///
/// New uploads and downloads use the same bounded-memory v2 streaming
/// format as Android. Legacy v1 remains readable.
@MainActor
final class AttachmentDownloadMeter: ObservableObject {
    @Published var percent = 0
}

@MainActor
final class AttachmentSendMeter: ObservableObject {
    @Published var stage = AttachmentSendStage.queued
    @Published var percent: Int?
}

@MainActor
final class AttachmentManager: ObservableObject {
    private var downloadMeters: [String: AttachmentDownloadMeter] = [:]
    func downloadMeter(_ id: String) -> AttachmentDownloadMeter {
        if let value = downloadMeters[id] { return value }
        let value = AttachmentDownloadMeter(); downloadMeters[id] = value; return value
    }
    private var sendMeters: [String: AttachmentSendMeter] = [:]
    func sendMeter(_ id: String) -> AttachmentSendMeter {
        if let meter = sendMeters[id] { return meter }
        let meter = AttachmentSendMeter(); sendMeters[id] = meter; return meter
    }
    private func setUploadStage(_ id: String, _ stage: AttachmentSendStage) {
        let meter = sendMeter(id)
        if meter.stage != stage {
            meter.stage = stage
            #if os(iOS)
            let phase: Double
            switch stage { case .queued: phase = 0; case .preparing: phase = 1; case .compressing: phase = 2; case .encrypting: phase = 3; case .uploading: phase = 4; case .sent: phase = 5 }
            PerformanceRecorder.shared.record(.queue, ["phase": phase, "operation": PerformanceRecorder.shared.operation(id)])
            PerformanceMonitor.shared.activity()
            #endif
        }
    }
    private func setUploadPercent(_ id: String, _ value: Int?) {
        guard let meter = sendMeters[id] else { return }
        let bounded = value.map { min(99, max(0, $0)) }
        if meter.percent != bounded { meter.percent = bounded }
    }
    private func clearUploadMeter(_ id: String) {
        guard let meter = sendMeters.removeValue(forKey: id) else { return }
        meter.percent = nil; meter.stage = .sent
    }
    func finishSendMeter(_ id: String) { clearUploadMeter(id) }
    func pendingUploadSize(_ clientMessageId: String) -> Int64? {
        (try? store.attachment(byClientMessageId: clientMessageId))?.size
    }
    static let maxImageBytes: Int64 = 25 * 1024 * 1024
    static let maxFileBytes: Int64 = 100 * 1024 * 1024

    private let store: AttachmentStore
    private let api: AttachmentsAPI
    private let auth: AuthRepository
    private let cacheDirectory: URL
    private let generation: UInt64
    private var cacheWrites: [UUID: Task<Void, Never>] = [:]
    private let downloads = AttachmentDownloadFlights()

    func finishSessionCacheWrites() async {
        for task in Array(cacheWrites.values) { await task.value }
    }

    private func writeCache<T: Sendable>(_ operation: @escaping @Sendable () throws -> T) async throws -> T {
        try requireSession()
        let id = UUID()
        let task = Task.detached(priority: .userInitiated, operation: operation)
        cacheWrites[id] = Task { _ = try? await task.value }
        defer { cacheWrites[id] = nil }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }

    private func writeAsyncCache<T: Sendable>(_ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try requireSession()
        let id = UUID()
        let task = Task.detached(priority: .userInitiated, operation: operation)
        cacheWrites[id] = Task { _ = try? await task.value }
        defer { cacheWrites[id] = nil }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }
    func cancelDownload(_ id: String) { downloads.cancel(id) }
    func discardPendingUpload(clientMessageId: String) {
        clearUploadMeter(clientMessageId)
        guard generation == auth.sessionGeneration, let row = try? store.attachment(byClientMessageId: clientMessageId) else { return }
        try? FileManager.default.removeItem(at: cacheDirectory.appendingPathComponent("upload-\(row.attachmentId).enc"))
        try? store.updateUploadState(attachmentId: row.attachmentId, state: "cancelled", progress: 0)
    }

    func resumeUpload(clientMessageId: String) async throws -> AttachmentMetadata? {
        try requireSession()
        guard let row = try store.attachment(byClientMessageId: clientMessageId) else { return nil }
        if row.uploadState == AttachmentRecord.uploadStateUploaded { setUploadStage(clientMessageId, .sent); return attachmentMetadata(from: row) }
        let file = cacheDirectory.appendingPathComponent("upload-\(row.attachmentId).enc")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        do { try await uploadMultipart(record: row, file: file, onProgress: { _ in }) }
        catch APIError.server(let code, _) where code == "not_found" {
            try? FileManager.default.removeItem(at: file); return nil
        } catch APIError.http(404) {
            try? FileManager.default.removeItem(at: file); return nil
        } catch is AttachmentIntegrityError {
            try? FileManager.default.removeItem(at: file); return nil
        }
        try? FileManager.default.removeItem(at: file)
        return attachmentMetadata(from: row)
    }

    init(
        store: AttachmentStore,
        api: AttachmentsAPI,
        auth: AuthRepository,
        cacheDirectory: URL
    ) {
        self.store = store
        self.api = api
        self.auth = auth
        self.cacheDirectory = cacheDirectory
        self.generation = auth.sessionGeneration
    }

    func stageDraftFile(_ source: URL, destination: URL, isVideo: Bool = false, coordinated: Bool = false) async throws -> URL {
        #if os(iOS)
        // media_prepare span phase remains 0=start/1=end. Static status 21
        // identifies document copying without recording any document identity.
        let documentSpan = coordinated ? PerformanceRecorder.shared.begin(.mediaPrepare, ["status": 21]) : nil
        var documentSuccess = false
        defer { PerformanceRecorder.shared.end(documentSpan, success: documentSuccess) }
        #endif
        let sourceLease = MediaCacheLease(path: source.path)
        defer { withExtendedLifetime(sourceLease) {} }
        let reader = coordinated ? AttachmentFileCopy.DocumentRead() : nil
        do {
            try await withTaskCancellationHandler(operation: {
                try await writeCache {
                    try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                    let limit: Int64 = isVideo ? AttachmentFileCopy.maxVideoSourceBytes : 100 * 1024 * 1024
                    if let reader { try reader.copy(from: source, to: destination, maxBytes: limit) }
                    else { try AttachmentFileCopy.copy(from: source, to: destination, maxBytes: limit) }
                }
            }, onCancel: { reader?.cancel() })
            try requireSession()
            #if os(iOS)
            documentSuccess = true
            #endif
            return destination
        } catch {
            // Destination is a unique unpublished draft path. Cancellation can
            // win after the copy finishes but before its URL reaches the caller.
            try? FileManager.default.removeItem(at: destination)
            if let contents = try? FileManager.default.contentsOfDirectory(atPath: destination.deletingLastPathComponent().path), contents.isEmpty {
                try? FileManager.default.removeItem(at: destination.deletingLastPathComponent())
            }
            throw error
        }
    }

    /// Provider metadata can materialize remotely. Keep it on the same owned
    /// IO executor as coordinated copying, never in the SwiftUI callback.
    func draftFileSize(_ source: URL) async throws -> Int64? {
        #if os(iOS)
        // Static status 20 identifies provider metadata; begin/end supply
        // bounded duration/success/cancellation only when diagnostics are on.
        let documentSpan = PerformanceRecorder.shared.begin(.mediaPrepare, ["status": 20])
        var documentSuccess = false
        defer { PerformanceRecorder.shared.end(documentSpan, success: documentSuccess) }
        #endif
        let reader = AttachmentFileCopy.DocumentRead()
        let size = try await withTaskCancellationHandler(operation: {
            try await writeCache { try reader.size(of: source) }
        }, onCancel: { reader.cancel() })
        try requireSession()
        #if os(iOS)
        documentSuccess = true
        #endif
        return size
    }

    // MARK: - Upload (prepare -> encrypt -> create -> PUT -> complete)

    func prepareAndUploadImage(
        fileURL: URL,
        chatId: String,
        clientMessageId: String,
        highQuality: Bool = false,
        onProgress: @escaping (Int) -> Void = { _ in }
    ) async throws -> AttachmentMetadata {
        let sourceLease = MediaCacheLease(path: fileURL.path)
        defer { withExtendedLifetime(sourceLease) {} }
        setUploadStage(clientMessageId, .preparing)
        let outputURL = newTmpURL("jpg")
        let processed = try await writeCache {
            #if os(iOS)
            let span = PerformanceRecorder.shared.begin(.mediaPrepare, ["operation": PerformanceRecorder.shared.operation(clientMessageId)])
            var success = false
            defer { PerformanceRecorder.shared.end(span, success: success) }
            #endif
            do {
                let result = try ImageProcessor.processImage(sourceURL: fileURL, outputURL: outputURL, highQuality: highQuality)
                #if os(iOS)
                success = true
                #endif
                return result
            }
            catch { throw OutboxError.mediaPreparation(error.localizedDescription) }
        }
        defer {
            if (try? referencesLocalFile(processed.fileURL)) == false { try? FileManager.default.removeItem(at: processed.fileURL) }
        }
        try requireSession()
        guard (try? FileManager.default.attributesOfItem(atPath: processed.fileURL.path)[.size] as? Int64) ?? 0 <= Self.maxImageBytes else {
            throw OutboxError.mediaPreparation("Photo exceeds the 25MB limit after compression.")
        }
        return try await encryptUploadComplete(
            plaintextFile: processed.fileURL,
            mediaType: AttachmentMetadata.mediaTypeImage,
            mimeType: processed.mimeType,
            filename: nil,
            width: processed.width,
            height: processed.height,
            durationMs: nil,
            thumbnailBase64: processed.thumbnailBase64,
            clientMessageId: clientMessageId,
            chatId: chatId,
            onProgress: onProgress
        )
    }

    func prepareAndUploadVideo(fileURL: URL, chatId: String, clientMessageId: String, highQuality: Bool = false) async throws -> AttachmentMetadata {
        let sourceLease = MediaCacheLease(path: fileURL.path)
        defer { withExtendedLifetime(sourceLease) {} }
        setUploadStage(clientMessageId, .compressing)
        let output = newTmpURL("mp4")
        // Install cleanup before the first await: logout can retire the session
        // immediately after an otherwise successful export.
        defer { try? FileManager.default.removeItem(at: output) }
        try await writeAsyncCache {
            #if os(iOS)
            let span = PerformanceRecorder.shared.begin(.mediaCompress, ["operation": PerformanceRecorder.shared.operation(clientMessageId)])
            var success = false
            defer { PerformanceRecorder.shared.end(span, success: success) }
            #endif
            let asset = AVURLAsset(url: fileURL)
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: asset)
            let presets = VideoCompressionPolicy.ceilings(highQuality: highQuality).map { ceiling in
                ceiling == 1080 ? AVAssetExportPreset1920x1080 : ceiling == 720 ? AVAssetExportPreset1280x720 : AVAssetExportPreset960x540
            }
            var attemptedExport = false
            for preset in presets where compatible.contains(preset) {
                try Task.checkCancellation()
                try? FileManager.default.removeItem(at: output)
                guard let exporter = AVAssetExportSession(asset: asset, presetName: preset), exporter.supportedFileTypes.contains(.mp4) else { continue }
                attemptedExport = true
                exporter.outputURL = output; exporter.outputFileType = .mp4; exporter.shouldOptimizeForNetworkUse = true; exporter.metadata = []
                try await withTaskCancellationHandler(operation: {
                    await withCheckedContinuation { continuation in exporter.exportAsynchronously { continuation.resume() } }
                    try Task.checkCancellation()
                    guard exporter.status == .completed else {
                        if exporter.status == .cancelled { throw NSError(domain: AVFoundationErrorDomain, code: AVError.Code.operationInterrupted.rawValue) }
                        if let error = exporter.error as NSError?, error.domain == AVFoundationErrorDomain,
                           error.code == AVError.Code.operationInterrupted.rawValue { throw error }
                        throw OutboxError.mediaPreparation(exporter.error?.localizedDescription ?? UnyriL10n.string("Video conversion failed.", comment: ""))
                    }
                }, onCancel: { exporter.cancelExport() })
                let bytes = (try FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.int64Value ?? 0
                if VideoCompressionPolicy.accepts(bytes: bytes) {
                    #if os(iOS)
                    success = true
                    #endif
                    return
                }
                guard bytes > 0 else { throw OutboxError.mediaPreparation("Video conversion failed.") }
            }
            throw OutboxError.mediaPreparation(UnyriL10n.string(attemptedExport
                ? "Video is still larger than 100 MB after compression. Choose a shorter video."
                : "Video cannot be converted. Send the original file instead.", comment: ""))
        }
        try requireSession()
        return try await prepareAndUploadFile(fileURL: output, chatId: chatId, clientMessageId: clientMessageId)
    }

    func prepareAndUploadFile(
        fileURL: URL,
        chatId: String,
        clientMessageId: String,
        onProgress: @escaping (Int) -> Void = { _ in }
    ) async throws -> AttachmentMetadata {
        let sourceLease = MediaCacheLease(path: fileURL.path)
        defer { withExtendedLifetime(sourceLease) {} }
        setUploadStage(clientMessageId, .preparing)
        let filename = fileURL.lastPathComponent
        let handle = try FileHandle(forReadingFrom: fileURL)
        let header = try handle.read(upToCount: 32) ?? Data(); try handle.close()
        let mimeType = Self.detectMime(header, fallback: Self.mimeType(for: filename))
        // Copy into the app-private cache first (mirrors Android's
        // `copyUriToFile`) so the local plaintext reference stays valid after
        // the document picker's security-scoped URL is released.
        let plaintextFile = newTmpURL(UTType(mimeType: mimeType)?.preferredFilenameExtension ?? "bin")
        // A record owns its plaintext cache after upload creation, including
        // interrupted uploads. Only remove copies never adopted by a record.
        defer {
            if (try? referencesLocalFile(plaintextFile)) == false { try? FileManager.default.removeItem(at: plaintextFile) }
        }
        do {
            try await writeCache {
                try AttachmentFileCopy.copy(from: fileURL, to: plaintextFile, maxBytes: 100 * 1024 * 1024)
            }
            try requireSession()
        } catch {
            try? FileManager.default.removeItem(at: plaintextFile)
            throw error
        }
        let actualSize = (try? FileManager.default.attributesOfItem(atPath: plaintextFile.path)[.size] as? Int64) ?? 0
        if actualSize > Self.maxFileBytes {
            try? FileManager.default.removeItem(at: plaintextFile)
            throw OutboxError.mediaPreparation("File exceeds the 100MB limit.")
        }
        let preview = mimeType.hasPrefix("video/") ? try await writeAsyncCache { await VideoPreviewMetadata.read(plaintextFile) } : nil
        let imageThumbnail = mimeType.hasPrefix("image/") ? try await writeAsyncCache { originalImageThumbnail(plaintextFile) } : nil
        try requireSession()
        return try await encryptUploadComplete(
            plaintextFile: plaintextFile,
            mediaType: AttachmentMetadata.mediaTypeFile,
            mimeType: mimeType,
            filename: filename,
            width: preview?.width,
            height: preview?.height,
            durationMs: preview?.durationMs,
            thumbnailBase64: imageThumbnail ?? preview?.thumbnail,
            clientMessageId: clientMessageId,
            chatId: chatId,
            onProgress: onProgress
        )
    }

    /// Voice: `recordedFile` is already-encoded AAC/M4A (see `VoiceRecorder`)
    /// — just the common encrypt/upload pipeline on top (mirrors Android's
    /// `prepareAndUploadVoice`). `durationMs` is the wall-clock recording
    /// length, carried as `voice.duration_ms` inside the Signal-encrypted
    /// metadata.
    func prepareAndUploadVoice(
        fileURL: URL,
        durationMs: Int64,
        chatId: String,
        clientMessageId: String,
        onProgress: @escaping (Int) -> Void = { _ in }
    ) async throws -> AttachmentMetadata {
        let sourceLease = MediaCacheLease(path: fileURL.path)
        defer { withExtendedLifetime(sourceLease) {} }
        try requireSession()
        let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? Int64) ?? 0
        if size > Self.maxImageBytes {
            throw OutboxError.mediaPreparation("Voice message exceeds the 25MB limit.")
        }
        return try await encryptUploadComplete(
            plaintextFile: fileURL,
            mediaType: AttachmentMetadata.mediaTypeVoice,
            mimeType: "audio/mp4",
            filename: nil,
            width: nil,
            height: nil,
            durationMs: durationMs,
            thumbnailBase64: nil,
            clientMessageId: clientMessageId,
            chatId: chatId,
            onProgress: onProgress
        )
    }

    private func encryptUploadComplete(
        plaintextFile: URL,
        mediaType: String,
        mimeType: String,
        filename: String?,
        width: Int?,
        height: Int?,
        durationMs: Int64?,
        thumbnailBase64: String?,
        clientMessageId: String,
        chatId: String,
        onProgress: @escaping (Int) -> Void
    ) async throws -> AttachmentMetadata {
        try requireSession()
        let plaintextSize = (try? FileManager.default.attributesOfItem(atPath: plaintextFile.path)[.size] as? Int64) ?? 0

        if let prior = try store.attachment(byClientMessageId: clientMessageId) {
            if prior.uploadState == AttachmentRecord.uploadStateUploaded { return attachmentMetadata(from: prior) }
            let saved = cacheDirectory.appendingPathComponent("upload-\(prior.attachmentId).enc")
            if FileManager.default.fileExists(atPath: saved.path) {
                try await uploadMultipart(record: prior, file: saved, onProgress: onProgress)
                try? FileManager.default.removeItem(at: saved)
                return attachmentMetadata(from: prior)
            }
        }

        let maximum = mediaType == AttachmentMetadata.mediaTypeFile ? Self.maxFileBytes : Self.maxImageBytes
        guard plaintextSize <= maximum else { throw AttachmentError.tooLarge("File exceeds the allowed size.") }
        try AttachmentFileCopy.requireSpace(at: cacheDirectory, bytes: plaintextSize + 4096)
        let keyset = AttachmentCrypto.generateV2Keyset()
        setUploadStage(clientMessageId, .encrypting)
        let ciphertextFile = newTmpURL("enc")
        defer { try? FileManager.default.removeItem(at: ciphertextFile) }
        let sha256 = try await writeCache {
            try AttachmentCrypto.encryptV2(plaintextFile: plaintextFile, ciphertextFile: ciphertextFile, keyset: keyset)
        }
        try requireSession()
        let encryptedSize = (try? FileManager.default.attributesOfItem(atPath: ciphertextFile.path)[.size] as? Int64) ?? 0

        let token = try await auth.currentAccessToken()
        try requireSession()

        // Idempotent create: retrying after a lost response reuses the same
        // attachment/object_key/URL (never an orphan object).
        let clientAttachmentId = UUID().uuidString.lowercased()
        let intent = try await api.create(
            accessToken: token,
            clientAttachmentId: clientAttachmentId,
            encryptedSize: encryptedSize
        )

        try requireSession()
        let record = AttachmentRecord(
            attachmentId: intent.attachmentId,
            clientMessageId: clientMessageId,
            chatId: chatId,
            mediaType: mediaType,
            mimeType: mimeType,
            filename: filename,
            size: plaintextSize,
            width: width,
            height: height,
            durationMs: durationMs,
            thumbnailBase64: thumbnailBase64,
            localPath: plaintextFile.path,
            downloadState: AttachmentRecord.downloadStateDownloaded,
            uploadState: AttachmentRecord.uploadStateUploading,
            progress: 0,
            objectKey: intent.objectKey,
            encryptedSize: encryptedSize,
            encryptionVersion: AttachmentCrypto.versionV2StreamingAead,
            keyBase64: nil,
            nonceBase64: nil,
            keysetBase64: keyset.base64EncodedString(),
            sha256Base64: Data(sha256).base64EncodedString()
        )
        try store.upsert(record)

        var uploaded = false
        defer {
            if !uploaded && generation == auth.sessionGeneration {
                try? store.updateUploadState(attachmentId: intent.attachmentId, state: AttachmentRecord.uploadStateFailed, progress: 0)
            }
        }

        let saved = cacheDirectory.appendingPathComponent("upload-\(intent.attachmentId).enc")
        try FileManager.default.moveItem(at: ciphertextFile, to: saved)
        try await uploadMultipart(record: record, file: saved, onProgress: onProgress)
        try? FileManager.default.removeItem(at: saved)
        try requireSession()
        try store.updateUploadState(attachmentId: intent.attachmentId, state: AttachmentRecord.uploadStateUploaded, progress: 100)
        uploaded = true

        return attachmentMetadata(from: record)
    }

    private func uploadMultipart(record: AttachmentRecord, file: URL, onProgress: @escaping (Int) -> Void) async throws {
        var finished = false
        defer {
            if !finished { setUploadStage(record.clientMessageId, .queued); setUploadPercent(record.clientMessageId, nil) }
        }
        try requireSession()
        let size = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value
        guard size == record.encryptedSize else { throw AttachmentIntegrityError() }
        let hash = try await writeCache { try AttachmentCrypto.sha256File(file) }
        guard hash.base64EncodedString() == record.sha256Base64 else { throw AttachmentIntegrityError() }
        let plan = try await api.multipart(accessToken: auth.currentAccessToken(), attachmentId: record.attachmentId)
        try requireSession()
        if !plan.complete {
            guard plan.partSize == 5 * 1024 * 1024, plan.parts.count <= 21,
                  plan.parts.enumerated().allSatisfy({ $0.element.number == $0.offset + 1 && $0.element.size > 0 && $0.element.size == min(plan.partSize, record.encryptedSize - Int64($0.offset) * plan.partSize) }),
                  plan.parts.reduce(Int64(0), { $0 + $1.size }) == record.encryptedSize else {
                throw AttachmentIntegrityError()
            }
            var completed = plan.parts.filter(\.complete).reduce(Int64(0)) { $0 + $1.size }
            // Persist the lifecycle boundary once. The server's acknowledged
            // multipart plan owns resume offsets; transient UI percentages do
            // not need synchronous SQLite commits on the main actor.
            try store.updateUploadState(attachmentId: record.attachmentId, state: AttachmentRecord.uploadStateUploading, progress: min(99, Int(completed * 100 / record.encryptedSize)))
            setUploadStage(record.clientMessageId, .uploading)
            setUploadPercent(record.clientMessageId, Int(completed * 100 / record.encryptedSize))
            for part in plan.parts where !part.complete {
                try requireSession()
                let chunk = newTmpURL("upload-part")
                defer { try? FileManager.default.removeItem(at: chunk) }
                let offset = Int64(part.number - 1) * plan.partSize
                try await writeCache {
                    try AttachmentFileCopy.requireSpace(at: chunk.deletingLastPathComponent(), bytes: part.size)
                    let input = try FileHandle(forReadingFrom: file)
                    defer { try? input.close() }
                    try input.seek(toOffset: UInt64(offset))
                    guard FileManager.default.createFile(atPath: chunk.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
                    let output = try FileHandle(forWritingTo: chunk)
                    defer { try? output.close() }
                    var remaining = part.size
                    while remaining > 0 {
                        try Task.checkCancellation()
                        guard let data = try input.read(upToCount: Int(min(remaining, 64 * 1024))), !data.isEmpty else { throw AttachmentIntegrityError() }
                        try output.write(contentsOf: data)
                        remaining -= Int64(data.count)
                    }
                }
                let base = completed
                #if os(iOS)
                let performanceOperation = PerformanceRecorder.shared.operation(record.clientMessageId)
                #else
                let performanceOperation = 0.0
                #endif
                let etag = try await uploadFile(url: part.uploadUrl, fileURL: chunk, operation: performanceOperation, bytes: Double(part.size)) { [weak self] percent in
                    guard let self, self.generation == self.auth.sessionGeneration else { return }
                    let value = Int((base + part.size * Int64(percent) / 100) * 100 / record.encryptedSize)
                    self.setUploadPercent(record.clientMessageId, value)
                    onProgress(min(99,value))
                }
                try requireSession()
                try await api.acknowledgePart(accessToken: auth.currentAccessToken(), attachmentId: record.attachmentId, number: part.number, etag: etag)
                completed += part.size
            }
        }
        try requireSession()
        try await api.complete(accessToken: auth.currentAccessToken(), attachmentId: record.attachmentId)
        try requireSession()
        try store.updateUploadState(attachmentId: record.attachmentId, state: AttachmentRecord.uploadStateUploaded, progress: 100)
        setUploadStage(record.clientMessageId, .sent)
        setUploadPercent(record.clientMessageId, nil)
        finished = true
        onProgress(100)
    }

    private func uploadFile(url: String, fileURL: URL, operation: Double, bytes: Double, onProgress: @escaping (Int) -> Void) async throws -> String {
        #if os(iOS)
        let span = PerformanceRecorder.shared.begin(.mediaUpload, ["operation": operation, "bytes": bytes])
        var success = false
        defer { PerformanceRecorder.shared.end(span, success: success) }
        #endif
        guard let uploadURL = URL(string: url) else { throw APIError.invalidURL }
        var request = URLRequest(url: uploadURL)
        request.httpMethod = "PUT"
        request.timeoutInterval = 120
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let progress = AttachmentTransferProgress(onProgress: onProgress)
        defer { progress.invalidate() }
        do {
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: fileURL, delegate: progress)
            try Task.checkCancellation()
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw AttachmentError.uploadFailed(http.statusCode)
            }
            guard let etag = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag"), !etag.isEmpty else { throw AttachmentError.uploadFailed(0) }
            #if os(iOS)
            success = true
            #endif
            return etag
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as AttachmentError {
            throw error
        } catch {
            throw AttachmentError.uploadFailed(0)
        }
    }

    // MARK: - Download (fetch -> verify -> decrypt -> cache)

    /// Downloads (if not already cached), verifies the ciphertext SHA-256,
    /// then decrypts — never returns a partially-verified file. Safe to call
    /// repeatedly: an already-downloaded attachment is served from cache.
    func download(attachmentId: String, origin: AttachmentDownloadOrigin = .unspecified,
                  onProgress: @escaping (Int) -> Void = { _ in }) async throws -> URL {
        try requireSession()
        if origin == .automatic {
            guard let record = try store.attachment(byId: attachmentId),
                  UnyriDeviceSettings.shared.allows(mediaType: record.mediaType, mime: record.mimeType, bytes: record.size) else { throw CancellationError() }
        }
        let result = try await downloads.run(attachmentId) {
            try await self.downloadOnce(attachmentId: attachmentId, origin: origin, onProgress: onProgress)
        }
        try Task.checkCancellation()
        try requireSession()
        return result
    }

    private func downloadOnce(attachmentId: String, origin: AttachmentDownloadOrigin, onProgress: @escaping (Int) -> Void) async throws -> URL {
        #if os(iOS)
        // media_download.operation is this shared worker's initial initiator:
        // 0 unknown, 1 automatic, 2 message tap, 3 viewer, 4 forward. A later
        // manual/viewer consumer can keep it alive after automatic is disabled.
        // Network-request operation IDs remain separate.
        let span = PerformanceRecorder.shared.begin(.mediaDownload, ["operation": Double(origin.rawValue)])
        var success = false
        var cacheHit = false
        defer { PerformanceRecorder.shared.end(span, success: success, metrics: ["cache_hit": cacheHit ? 1 : 0]) }
        #endif
        let meter = downloadMeter(attachmentId)
        defer { downloadMeters[attachmentId] = nil }
        let reportProgress: (Int) -> Void = { value in
            meter.percent = min(99, max(0, value))
            onProgress(value)
        }
        try requireSession()
        guard let record = try store.attachment(byId: attachmentId) else {
            throw AttachmentError.notFound
        }
        let lease = record.localPath.map { MediaCacheLease(path: $0) }
        defer { withExtendedLifetime(lease) {} }
        if let localPath = record.localPath {
            let cached = URL(fileURLWithPath: localPath)
            if FileManager.default.fileExists(atPath: cached.path) {
                #if os(iOS)
                success = true
                cacheHit = true
                PerformanceRecorder.shared.record(.previewCache, ["cache_hit": 1])
                #endif
                return cached
            }
        }

        guard [AttachmentCrypto.versionV1AesGcm, AttachmentCrypto.versionV2StreamingAead].contains(record.encryptionVersion) else {
            throw AttachmentV2UnsupportedError()
        }
        guard let expectedSha256 = Data(base64Encoded: record.sha256Base64), expectedSha256.count == 32 else {
            throw AttachmentIntegrityError()
        }
        guard record.size >= 0, record.size <= Self.maxFileBytes,
              record.encryptedSize >= 0, record.encryptedSize <= Self.maxFileBytes + 4096 else {
            throw AttachmentError.tooLarge("File exceeds the allowed size.")
        }
        try AttachmentFileCopy.requireSpace(at: cacheDirectory, bytes: record.size + record.encryptedSize)

        try store.updateDownloadState(attachmentId: attachmentId, state: AttachmentRecord.downloadStateDownloading, progress: 0)
        let token = try await auth.currentAccessToken()
        try requireSession()
        let downloadInfo = try await api.download(accessToken: token, attachmentId: attachmentId)

        let ciphertextFile = newTmpURL("enc")
        defer { try? FileManager.default.removeItem(at: ciphertextFile) }
        do {
            try await downloadFile(url: downloadInfo.downloadUrl, to: ciphertextFile, expectedSize: record.encryptedSize, onProgress: reportProgress)
        } catch {
            try store.updateDownloadState(attachmentId: attachmentId, state: AttachmentRecord.downloadStateFailed, progress: 0)
            throw error
        }

        let plaintextFile = cacheDirectory
            .appendingPathComponent("\(attachmentId)_\(Self.sanitizeFilename(record.filename ?? ("UNYRI." + (UTType(mimeType: record.mimeType)?.preferredFilenameExtension ?? "bin"))))")
        do {
            try requireSession()
            try await writeCache {
            if record.encryptionVersion == AttachmentCrypto.versionV2StreamingAead {
                guard let encoded = record.keysetBase64, let keyset = Data(base64Encoded: encoded) else {
                    throw AttachmentIntegrityError()
                }
                try AttachmentCrypto.decryptV2(ciphertextFile: ciphertextFile, plaintextFile: plaintextFile,
                    keyset: keyset, expectedSha256: expectedSha256)
            } else {
                guard let encodedKey = record.keyBase64, let encodedNonce = record.nonceBase64,
                      let keyData = Data(base64Encoded: encodedKey), keyData.count == 32,
                      let nonceData = Data(base64Encoded: encodedNonce), nonceData.count == 12 else {
                    throw AttachmentIntegrityError()
                }
                try AttachmentCrypto.decryptV1(
                ciphertextFile: ciphertextFile,
                plaintextFile: plaintextFile,
                key: SymmetricKey(data: keyData),
                nonce: AES.GCM.Nonce(data: nonceData),
                expectedSha256: expectedSha256
            )
            }
            }
        } catch let integrityError as AttachmentIntegrityError {
            try store.updateDownloadState(attachmentId: attachmentId, state: AttachmentRecord.downloadStateCorrupt, progress: 0)
            throw integrityError
        } catch {
            try store.updateDownloadState(attachmentId: attachmentId, state: AttachmentRecord.downloadStateFailed, progress: 0)
            throw error
        }

        try requireSession()
        try store.markDownloaded(attachmentId: attachmentId, localPath: plaintextFile.path)
        #if os(iOS)
        success = true
        #endif
        return plaintextFile
    }

    private func downloadFile(url: String, to destURL: URL, expectedSize: Int64, onProgress: @escaping (Int) -> Void) async throws {
        guard let downloadURL = URL(string: url) else { throw APIError.invalidURL }
        let request = URLRequest(url: downloadURL)
        do {
            let progress = AttachmentTransferProgress(maximumBytes: expectedSize, onProgress: onProgress)
            defer { progress.invalidate() }
            let (tempURL, response) = try await URLSession.shared.download(for: request, delegate: progress)
            defer { try? FileManager.default.removeItem(at: tempURL) }
            try requireSession()
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw AttachmentError.downloadFailed(http.statusCode)
            }
            let size = (try FileManager.default.attributesOfItem(atPath: tempURL.path)[.size] as? NSNumber)?.int64Value
            guard size == expectedSize else { throw AttachmentIntegrityError() }
            if FileManager.default.fileExists(atPath: destURL.path) {
                try FileManager.default.removeItem(at: destURL)
            }
            try FileManager.default.moveItem(at: tempURL, to: destURL)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as AttachmentError {
            throw error
        } catch {
            throw AttachmentError.downloadFailed(0)
        }
        onProgress(100)
    }

    // MARK: - Helpers

    /// Synchronously returns the cached decrypted local path for an attachment
    /// (only if the file still exists) — used by bubbles to decide whether to
    /// show a play affordance vs a download affordance.
    func cachedLocalPath(attachmentId: String) -> String? {
        guard let record = try? store.attachment(byId: attachmentId),
              let path = record.localPath,
              FileManager.default.fileExists(atPath: path) else { return nil }
        return path
    }

    func invalidatePathSnapshot() { store.invalidatePathSnapshot() }

    func cachedPathsAsync(chatId: String, attachmentIDs: Set<String>? = nil) async throws -> [String: String] {
        try requireSession()
        let paths = try await store.cachedPathsAsync(chatId: chatId, attachmentIDs: attachmentIDs)
        try requireSession()
        return paths
    }

    func referencesLocalFile(_ url: URL) throws -> Bool {
        try store.referencesLocalPath(url.path)
    }

    func purgePersonalChat(_ chatId: String, messageIDs: Set<String>) async throws -> Set<URL> {
        try requireSession()
        let initial = try store.attachments(chatId: chatId).filter { messageIDs.contains($0.clientMessageId) }
        await downloads.cancelAndWait(initial.map(\.attachmentId))
        await finishSessionCacheWrites()
        try requireSession()
        let records = try store.attachments(chatId: chatId).filter { messageIDs.contains($0.clientMessageId) }
        let root = cacheDirectory.standardizedFileURL.resolvingSymlinksInPath()
        for row in records {
            clearUploadMeter(row.clientMessageId)
            var files = [cacheDirectory.appendingPathComponent("upload-\(row.attachmentId).enc")]
            if let path = row.localPath { files.append(URL(fileURLWithPath: path)) }
            for file in files {
                let resolved = file.standardizedFileURL.resolvingSymlinksInPath()
                guard resolved.path.hasPrefix(root.path + "/") else { continue }
                if FileManager.default.fileExists(atPath: resolved.path) { try FileManager.default.removeItem(at: resolved) }
            }
        }
        try store.deleteRecords(messageIDs)
        return Set(records.compactMap { $0.localPath.map { URL(fileURLWithPath: $0) } })
    }

    func purgeDeletedChat(_ chatId: String) async throws -> Set<URL> {
        try requireSession()
        let initial = try store.attachments(chatId: chatId)
        await downloads.cancelAndWait(initial.map(\.attachmentId))
        await finishSessionCacheWrites()
        try requireSession()
        let records = try store.attachments(chatId: chatId)
        let root = cacheDirectory.standardizedFileURL.resolvingSymlinksInPath()
        for row in records {
            clearUploadMeter(row.clientMessageId)
            var files = [cacheDirectory.appendingPathComponent("upload-\(row.attachmentId).enc")]
            if let path = row.localPath { files.append(URL(fileURLWithPath: path)) }
            for file in files {
                let resolved = file.standardizedFileURL.resolvingSymlinksInPath()
                guard resolved.path.hasPrefix(root.path + "/") else { continue }
                if FileManager.default.fileExists(atPath: resolved.path) { try FileManager.default.removeItem(at: resolved) }
            }
        }
        try store.deleteChat(chatId)
        return Set(records.compactMap { $0.localPath.map { URL(fileURLWithPath: $0) } })
    }

    /// Read-only media index used by the chat gallery. It deliberately
    /// returns only attachments already known to the encrypted message store;
    /// it does not expose object keys or create a second download path.
    func galleryAttachments(chatId: String) -> [AttachmentRecord] {
        (try? store.attachments(chatId: chatId)) ?? []
    }

    private func newTmpURL(_ ext: String) -> URL {
        cacheDirectory.appendingPathComponent("\(UUID().uuidString).\(ext)")
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }

    private static func sanitizeFilename(_ name: String?) -> String {
        let cleaned = (name ?? "file")
            .replacingOccurrences(of: #"[^A-Za-z0-9._-]"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "._"))
        return cleaned.isEmpty ? "file" : String(cleaned.prefix(100))
    }

    private static func detectMime(_ data: Data, fallback: String) -> String {
        let b = Array(data)
        if b.starts(with: [255,216,255]) { return "image/jpeg" }
        if b.starts(with: [137,80,78,71,13,10,26,10]) { return "image/png" }
        let text = String(data: data, encoding: .isoLatin1) ?? ""
        if text.hasPrefix("GIF87a") || text.hasPrefix("GIF89a") { return "image/gif" }
        if text.hasPrefix("%PDF-") { return "application/pdf" }
        if text.hasPrefix("RIFF"), b.count >= 12, String(bytes: b[8..<12], encoding: .ascii) == "WEBP" { return "image/webp" }
        if b.count >= 12, String(bytes: b[4..<8], encoding: .ascii) == "ftyp" {
            switch String(bytes: b[8..<12], encoding: .ascii) {
            case "heic", "heix", "hevc", "hevx", "mif1": return "image/heic"
            case "avif", "avis": return "image/avif"
            case "M4A ": return "audio/mp4"
            case "qt  ": return "video/quicktime"
            default: return "video/mp4"
            }
        }
        return fallback
    }
    private static func mimeType(for filename: String) -> String {
        let ext = (filename as NSString).pathExtension.lowercased()
        let map: [String: String] = [
            "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif",
            "webp": "image/webp", "pdf": "application/pdf", "txt": "text/plain",
            "mp4": "video/mp4", "mov": "video/quicktime", "m4a": "audio/mp4"
        ]
        return map[ext] ?? UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
    }
}

/// URLSession streams from/to disk. Delegate callbacks are throttled to one
/// update per percentage point, and all UI/store updates run on MainActor.
private final class AttachmentTransferProgress: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let onProgress: @MainActor (Int) -> Void
    private let maximumBytes: Int64?
    private let lock = NSLock()
    private var lastPercent = -1
    private var active = true
    func invalidate() { lock.lock(); active = false; lock.unlock() }
    private func shouldDeliver(_ percent: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return active && lastPercent == percent
    }
    init(maximumBytes: Int64? = nil, onProgress: @escaping @MainActor (Int) -> Void) {
        self.maximumBytes = maximumBytes
        self.onProgress = onProgress
    }
    private func update(_ sent: Int64, total: Int64) {
        guard total > 0 else { return }
        let percent = Int(min(99, Double(sent) * 100 / Double(total)))
        lock.lock()
        let changed = percent > lastPercent
        if changed { lastPercent = percent }
        lock.unlock()
        if changed { Task { @MainActor in if self.shouldDeliver(percent) { self.onProgress(percent) } } }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        update(totalBytesSent, total: totalBytesExpectedToSend)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if let maximumBytes, totalBytesWritten > maximumBytes || totalBytesExpectedToWrite > maximumBytes {
            downloadTask.cancel(); return
        }
        update(totalBytesWritten, total: maximumBytes ?? totalBytesExpectedToWrite)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

private func originalImageThumbnail(_ file: URL) -> String? {
    guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
          let frame = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 512,
            kCGImageSourceShouldCacheImmediately: true
          ] as CFDictionary) else { return nil }
    let image = UIImage(cgImage: frame)
    return ImageProcessor.previewJPEG(image)?.base64EncodedString()
}

private struct VideoPreviewMetadata: Sendable {
    var width: Int?
    var height: Int?
    var durationMs: Int64?
    var thumbnail: String?
    static func read(_ file: URL) async -> VideoPreviewMetadata {
        var result = VideoPreviewMetadata()
        let asset = AVURLAsset(url: file)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 512, height: 512)
        return await withTaskCancellationHandler(operation: {
            if let duration = try? await asset.load(.duration).seconds,
               duration.isFinite, duration >= 0, duration < 365 * 86400 {
                result.durationMs = Int64(duration * 1000)
            }
            // Dimensions describe the oriented video, not its 512px thumbnail.
            if let track = try? await asset.loadTracks(withMediaType: .video).first,
               let size = try? await track.load(.naturalSize),
               let transform = try? await track.load(.preferredTransform) {
                let oriented = size.applying(transform)
                if oriented.width.isFinite, oriented.height.isFinite,
                   abs(oriented.width) >= 1, abs(oriented.height) >= 1,
                   abs(oriented.width) <= 100_000, abs(oriented.height) <= 100_000 {
                    result.width = Int(abs(oriented.width)); result.height = Int(abs(oriented.height))
                }
            }
            do {
                try Task.checkCancellation()
                let time = CMTime(seconds: min(0.5, Double(result.durationMs ?? 0) / 2000), preferredTimescale: 600)
                let frame = try await generator.image(at: time).image
                try Task.checkCancellation()
                if result.width == nil || result.height == nil { result.width = frame.width; result.height = frame.height }
                let image = UIImage(cgImage: frame)
                result.thumbnail = ImageProcessor.previewJPEG(image)?.base64EncodedString()
            } catch { /* Optional preview: never make a readable original unsendable. */ }
            return result
        }, onCancel: { generator.cancelAllCGImageGeneration() })
    }
}

enum AttachmentError: Error, LocalizedError {
    case tooLarge(String)
    case notFound
    case uploadFailed(Int)
    case downloadFailed(Int)

    var errorDescription: String? {
        switch self {
        case .tooLarge(let message): return message
        case .notFound: return "Attachment not found."
        case .uploadFailed(let code): return "Upload failed\(code == 0 ? "" : " (HTTP \(code))")."
        case .downloadFailed(let code): return "Download failed\(code == 0 ? "" : " (HTTP \(code))")."
        }
    }
}
