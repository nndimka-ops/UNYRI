import AVFoundation
import Foundation
import Photos

/// Public PhotoKit original-video URL. No private KVC fileSize lookup and no
/// AVAssetExportSession: these are the original container bytes, not a remux.
enum DirectPhotoOriginal {
    struct Source { let url: URL; let asset: AVAsset }
    enum Failure: Error { case unavailable }
    private final class Request: @unchecked Sendable {
        private let lock = NSLock()
        private var requestID: PHImageRequestID?
        private var waiter: CheckedContinuation<Source, Error>?
        private var cancelled = false
        private var finished = false
        func install(_ continuation: CheckedContinuation<Source, Error>) {
            lock.lock()
            if cancelled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
            waiter = continuation; lock.unlock()
        }
        func identify(_ id: PHImageRequestID) {
            lock.lock(); requestID = id; let stop = cancelled; lock.unlock()
            if stop { PHImageManager.default().cancelImageRequest(id) }
        }
        func complete(asset: AVAsset?, info: [AnyHashable: Any]?) {
            lock.lock()
            guard !finished, !cancelled else { lock.unlock(); return }
            finished = true; let continuation = waiter; waiter = nil; lock.unlock()
            if let error = info?[PHImageErrorKey] as? Error { continuation?.resume(throwing: error) }
            else if let asset = asset as? AVURLAsset, asset.url.isFileURL {
                continuation?.resume(returning: Source(url: asset.url, asset: asset))
            } else { continuation?.resume(throwing: Failure.unavailable) }
        }
        func cancel() {
            lock.lock(); cancelled = true; let id = requestID; let continuation = waiter; waiter = nil; lock.unlock()
            if let id { PHImageManager.default().cancelImageRequest(id) }
            continuation?.resume(throwing: CancellationError())
        }
    }

    static func video(_ asset: PHAsset) async throws -> Source {
        let request = Request()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                request.install(continuation)
                let options = PHVideoRequestOptions()
                options.version = .original; options.deliveryMode = .highQualityFormat
                options.isNetworkAccessAllowed = true
                let id = PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { value, _, info in
                    request.complete(asset: value, info: info)
                }
                request.identify(id)
            }
        }, onCancel: { request.cancel() })
    }
}
