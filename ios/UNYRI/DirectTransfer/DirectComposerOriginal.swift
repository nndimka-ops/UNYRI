import AVFoundation
import CoreTransferable
import Foundation
import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

private protocol DirectComposerImportLimit { static var bytes: Int64 { get } }
private enum DirectComposerLargeImport: DirectComposerImportLimit { static let bytes = DirectFileOffer.maximumBytes }
private enum DirectComposerCloudImport: DirectComposerImportLimit { static let bytes = AttachmentFormat.maxFileBytes }

enum DirectComposerOriginal {
    enum Source { case asset(PHAsset), picker(PhotosPickerItem), file(URL) }
    enum PreparationFailure: Error {
        case unavailable, tooLarge, requiresDirect
        var message: String {
            switch self {
            case .unavailable: return "This media could not be converted. Choose Original or select it in Files."
            case .tooLarge: return "This file exceeds the 64 GB direct-transfer limit."
            case .requiresDirect: return "This original is over 100 MB. Turn on Send directly and send again. Your selected items are kept."
            }
        }
    }
    struct Original {
        let url: URL
        let filename: String
        let asset: AVAsset?
        let temporary: Bool
        let cleanupDirectory: URL?
        init(url: URL, filename: String, asset: AVAsset?, temporary: Bool, cleanupDirectory: URL? = nil) {
            self.url = url; self.filename = filename; self.asset = asset; self.temporary = temporary; self.cleanupDirectory = cleanupDirectory
        }
        var size: Int64 { ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? -1 }
        func cleanup() async {
            guard temporary else { return }
            let file = url, directory = cleanupDirectory
            await Task.detached(priority: .utility) {
                if let directory, directory.lastPathComponent.hasPrefix("unyri-original-"),
                   file.deletingLastPathComponent() == directory,
                   directory.deletingLastPathComponent().standardizedFileURL == FileManager.default.temporaryDirectory.standardizedFileURL {
                    try? FileManager.default.removeItem(at: directory)
                } else { try? FileManager.default.removeItem(at: file) }
            }.value
        }
    }
    private struct Imported<Limit: DirectComposerImportLimit>: Transferable {
        let url: URL
        let filename: String
        static var transferRepresentation: some TransferRepresentation {
            FileRepresentation(importedContentType: .image) { received in try await copy(received.file) }
            FileRepresentation(importedContentType: .movie) { received in try await copy(received.file) }
        }
        private static func copy(_ source: URL) async throws -> Imported {
            let name = DirectFileOffer.safeFilename(source.lastPathComponent)
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("coco-direct-original-" + UUID().uuidString.lowercased()).appendingPathExtension((name as NSString).pathExtension)
            let operation = Task.detached(priority: .userInitiated) {
                try AttachmentFileCopy.DocumentRead().copy(from: source, to: file, maxBytes: Limit.bytes)
            }
            do {
                try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
                try Task.checkCancellation()
                return Imported(url: file, filename: name)
            } catch {
                try? FileManager.default.removeItem(at: file)
                if (error as? AttachmentFileCopy.CopyError) == .tooLarge {
                    throw Limit.bytes == DirectComposerCloudImport.bytes ? PreparationFailure.requiresDirect : PreparationFailure.tooLarge
                }
                throw error
            }
        }
    }

    static func load(_ source: Source) async throws -> Original {
        try Task.checkCancellation()
        switch source {
        case .asset(let asset):
            if asset.mediaType == .image { return try await photo(asset) }
            guard asset.mediaType == .video else { throw CocoaError(.fileReadUnsupportedScheme) }
            let original = try await DirectPhotoOriginal.video(asset)
            let resource = PHAssetResource.assetResources(for: asset).first { $0.type == .video }
            return Original(url: original.url, filename: resource?.originalFilename ?? original.url.lastPathComponent,
                asset: original.asset, temporary: false)
        case .picker(let item):
            guard let original = try await item.loadTransferable(type: Imported<DirectComposerLargeImport>.self) else { throw CocoaError(.fileReadUnknown) }
            do { try Task.checkCancellation() }
            catch { try? FileManager.default.removeItem(at: original.url); throw error }
            return Original(url: original.url, filename: original.filename, asset: nil, temporary: true)
        case .file(let file):
            return Original(url: file, filename: file.lastPathComponent, asset: nil, temporary: false)
        }
    }

    /// Own each Original before cloud admission, without a second provider read or the photo-media 25-MiB cap.
    static func loadForCloud(_ source: Source) async throws -> Original {
        let original: Original
        switch source {
        case .picker(let item):
            guard let value = try await item.loadTransferable(type: Imported<DirectComposerCloudImport>.self) else { throw PreparationFailure.unavailable }
            original = Original(url: value.url, filename: value.filename, asset: nil, temporary: true)
        case .asset(let asset) where asset.mediaType == .image || asset.mediaType == .video:
            original = try await photo(asset, maximumBytes: DirectComposerCloudImport.bytes)
        default: original = try await load(source)
        }
        do {
            try Task.checkCancellation()
            if original.size > DirectComposerCloudImport.bytes { throw PreparationFailure.requiresDirect }
            if original.size < 0 { throw PreparationFailure.unavailable }
            return try await namedForCloud(original)
        } catch { await original.cleanup(); throw error }
    }

    private static func namedForCloud(_ original: Original) async throws -> Original {
        let name = DirectFileOffer.safeFilename(original.filename)
        guard original.url.lastPathComponent != name else { return original }
        let source = original.url
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("unyri-original-" + UUID().uuidString.lowercased(), isDirectory: true)
        let destination = directory.appendingPathComponent(name)
        let operation = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            do { try FileManager.default.linkItem(at: source, to: destination) }
            catch { try AttachmentFileCopy.DocumentRead().copy(from: source, to: destination, maxBytes: DirectComposerCloudImport.bytes) }
            try Task.checkCancellation()
        }
        do {
            try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
            await original.cleanup()
            return Original(url: destination, filename: name, asset: nil, temporary: true, cleanupDirectory: directory)
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    }

    /// Transport never picks quality. The already captured media kind does.
    static func prepared(_ source: Source, kind: String) async throws -> Original {
        guard let plan = DirectMediaPreparation.resolve(kind: kind) else { throw PreparationFailure.unavailable }
        let original = try await load(source)
        let sourceLease = MediaCacheLease(path: original.url.path)
        defer { withExtendedLifetime(sourceLease) {} }
        if plan == .original { return original }
        let sourceURL = original.url, sourceName = original.filename
        let operation = Task.detached(priority: .userInitiated) { () async throws -> (URL, String) in
            try Task.checkCancellation()
            let base = FileManager.default.temporaryDirectory.appendingPathComponent("coco-direct-prepared-" + UUID().uuidString.lowercased())
            if case .image(let highQuality) = plan {
                do {
                    let result = try ImageProcessor.processImage(sourceURL: sourceURL, outputURL: base.appendingPathExtension("jpg"), highQuality: highQuality)
                    do { try Task.checkCancellation() }
                    catch { try? FileManager.default.removeItem(at: result.fileURL); throw error }
                    return (result.fileURL, (sourceName as NSString).deletingPathExtension + "." + result.fileURL.pathExtension)
                } catch {
                    for ext in ["jpg", "png", "gif", "webp", "bin"] { try? FileManager.default.removeItem(at: base.appendingPathExtension(ext)) }
                    throw error
                }
            }
            guard case .video(let highQuality) = plan else { throw PreparationFailure.unavailable }
            let output = base.appendingPathExtension("mp4")
            do {
                let asset = AVURLAsset(url: sourceURL)
                let compatible = AVAssetExportSession.exportPresets(compatibleWith: asset)
                let presets = VideoCompressionPolicy.ceilings(highQuality: highQuality).map { ceiling in
                    ceiling == 1080 ? AVAssetExportPreset1920x1080 : ceiling == 720 ? AVAssetExportPreset1280x720 : AVAssetExportPreset960x540
                }
                for preset in presets where compatible.contains(preset) {
                    try Task.checkCancellation(); try? FileManager.default.removeItem(at: output)
                    guard let exporter = AVAssetExportSession(asset: asset, presetName: preset), exporter.supportedFileTypes.contains(.mp4) else { continue }
                    exporter.outputURL = output; exporter.outputFileType = .mp4; exporter.shouldOptimizeForNetworkUse = true; exporter.metadata = []
                    try await withTaskCancellationHandler(operation: {
                        await withCheckedContinuation { continuation in exporter.exportAsynchronously { continuation.resume() } }
                        try Task.checkCancellation()
                        guard exporter.status == .completed else { throw exporter.error ?? CocoaError(.fileReadUnknown) }
                    }, onCancel: { exporter.cancelExport() })
                    let bytes = (try FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.int64Value ?? 0
                    // Same resolution policy/native exporter as normal sending; direct transport has its own 64-GiB limit.
                    if bytes > 0 && bytes <= DirectFileOffer.maximumBytes {
                        return (output, (sourceName as NSString).deletingPathExtension + ".mp4")
                    }
                }
                throw PreparationFailure.unavailable
            } catch { try? FileManager.default.removeItem(at: output); throw error }
        }
        do {
            let result = try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
            do { try Task.checkCancellation() }
            catch { try? FileManager.default.removeItem(at: result.0); throw error }
            await original.cleanup()
            return Original(url: result.0, filename: DirectFileOffer.safeFilename(result.1), asset: nil, temporary: true)
        } catch { await original.cleanup(); throw error }
    }

    private final class PhotoRequest: @unchecked Sendable {
        private let lock = NSLock()
        private var handle: FileHandle?
        private var waiter: CheckedContinuation<Void, Error>?
        private var requestID: PHAssetResourceDataRequestID?
        private var count: Int64 = 0
        private var finished = false
        private var cancelRequest = false
        private let maximumBytes: Int64
        init(handle: FileHandle, maximumBytes: Int64 = DirectFileOffer.maximumBytes) { self.handle = handle; self.maximumBytes = maximumBytes }
        func install(_ continuation: CheckedContinuation<Void, Error>) {
            lock.lock()
            guard !finished else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
            waiter = continuation; lock.unlock()
        }
        func identify(_ id: PHAssetResourceDataRequestID) {
            lock.lock(); requestID = id; let stop = cancelRequest; lock.unlock()
            if stop { PHAssetResourceManager.default().cancelDataRequest(id) }
        }
        func receive(_ data: Data) {
            lock.lock()
            guard !finished else { lock.unlock(); return }
            do {
                guard Int64(data.count) <= maximumBytes - count else {
                    throw maximumBytes == DirectComposerCloudImport.bytes ? PreparationFailure.requiresDirect : PreparationFailure.tooLarge
                }
                guard let handle else { throw CocoaError(.fileWriteUnknown) }
                try handle.write(contentsOf: data); count += Int64(data.count); lock.unlock()
            } catch { lock.unlock(); finish(error) }
        }
        func finish(_ error: Error?) {
            lock.lock()
            guard !finished else { lock.unlock(); return }
            finished = true
            let failure = error ?? (count > 0 ? nil : CocoaError(.fileReadUnknown))
            cancelRequest = failure != nil
            let continuation = waiter; waiter = nil; let id = requestID
            try? handle?.close(); handle = nil; lock.unlock()
            if failure != nil, let id { PHAssetResourceManager.default().cancelDataRequest(id) }
            if let failure { continuation?.resume(throwing: failure) } else { continuation?.resume() }
        }
    }

    private static func photo(_ asset: PHAsset, maximumBytes: Int64 = DirectFileOffer.maximumBytes) async throws -> Original {
        let resources = PHAssetResource.assetResources(for: asset)
        let video = asset.mediaType == .video
        guard let resource = resources.first(where: { $0.type == (video ? .video : .photo) }) ?? resources.first(where: { video ? $0.type == .fullSizeVideo : ($0.type == .fullSizePhoto || $0.type == .alternatePhoto) }) else { throw CocoaError(.fileReadUnknown) }
        let name = DirectFileOffer.safeFilename(resource.originalFilename)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("coco-direct-photo-" + UUID().uuidString.lowercased()).appendingPathExtension((name as NSString).pathExtension)
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let handle: FileHandle
        do { handle = try FileHandle(forWritingTo: url) }
        catch { try? FileManager.default.removeItem(at: url); throw error }
        let request = PhotoRequest(handle: handle, maximumBytes: maximumBytes)
        do {
            try await withTaskCancellationHandler(operation: {
                try Task.checkCancellation()
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    request.install(continuation)
                    let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = true
                    let id = PHAssetResourceManager.default().requestData(for: resource, options: options,
                        dataReceivedHandler: { request.receive($0) }, completionHandler: { request.finish($0) })
                    request.identify(id)
                }
                try Task.checkCancellation()
            }, onCancel: { request.finish(CancellationError()) })
            return Original(url: url, filename: name, asset: nil, temporary: true)
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }
}
