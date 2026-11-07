import AVFoundation
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// One optional reduced poster on an owned local source. File bytes and hashes
/// are unchanged. No attachment upload, external media references or full-file
/// Data read is part of this path.
enum DirectFileMediaPreviewGenerator {
    static func read(_ file: URL, mimeType: String, sourceBytes: Int64) async -> DirectFileMediaPreview? {
        guard !Task.isCancelled, file.isFileURL, sourceBytes > 0, sourceBytes <= DirectFileOffer.maximumBytes,
              mimeType.hasPrefix("image/") || mimeType.hasPrefix("video/") else { return nil }
        let worker = Task.detached(priority: .utility) {
            await withTaskGroup(of: DirectFileMediaPreview?.self) { group in
                group.addTask {
                    if mimeType.hasPrefix("image/") {
                        return image(file, sourceBytes: sourceBytes)
                    }
                    return await video(file)
                }
                group.addTask {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    return nil
                }
                let value = await group.next() ?? nil
                group.cancelAll()
                return value
            }
        }
        let result = await withTaskCancellationHandler(operation: { await worker.value }, onCancel: { worker.cancel() })
        return Task.isCancelled ? nil : result
    }

    private static func image(_ file: URL, sourceBytes: Int64) -> DirectFileMediaPreview? {
        // Optional poster work has a tighter parsing budget than transferring
        // the original. Oversized/unsupported sources remain sendable as files.
        guard !Task.isCancelled, sourceBytes <= 256 * 1024 * 1024,
              let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 100_000, height <= 100_000,
              Int64(width) * Int64(height) <= 64_000_000,
              let frame = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: DirectFileMediaPreview.maximumPixelEdge,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary), !Task.isCancelled,
              let jpeg = ImageProcessor.previewJPEG(UIImage(cgImage: frame)),
              let dimensions = DirectFileMediaPreview.jpegDimensions(jpeg) else { return nil }
        // Use the oriented poster ratio; neither sender EXIF nor arbitrary
        // original dimensions control a receiver's allocation or chat geometry.
        let value = DirectFileMediaPreview(kind: .image, thumbnail: jpeg.base64EncodedString(), width: dimensions.width, height: dimensions.height)
        return value.valid ? value : nil
    }

    private static func video(_ file: URL) async -> DirectFileMediaPreview? {
        let asset = AVURLAsset(url: file, options: [
            AVURLAssetReferenceRestrictionsKey: NSNumber(value: AVAssetReferenceRestrictions.forbidAll.rawValue),
            AVURLAssetAllowsCellularAccessKey: false,
            AVURLAssetAllowsConstrainedNetworkAccessKey: false,
            AVURLAssetAllowsExpensiveNetworkAccessKey: false
        ])
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: CGFloat(DirectFileMediaPreview.maximumPixelEdge), height: CGFloat(DirectFileMediaPreview.maximumPixelEdge))
        return await withTaskCancellationHandler(operation: {
            guard !Task.isCancelled else { return nil }
            var durationMs: Int64?
            if let duration = try? await asset.load(.duration).seconds,
               duration.isFinite, duration >= 0, duration <= 31_536_000 {
                durationMs = Int64(duration * 1000)
            }
            do {
                try Task.checkCancellation()
                let frame = try await generator.image(at: CMTime(seconds: 0, preferredTimescale: 600)).image
                try Task.checkCancellation()
                guard frame.width > 0, frame.height > 0,
                      frame.width <= DirectFileMediaPreview.maximumPixelEdge, frame.height <= DirectFileMediaPreview.maximumPixelEdge,
                      let jpeg = ImageProcessor.previewJPEG(UIImage(cgImage: frame)),
                      let dimensions = DirectFileMediaPreview.jpegDimensions(jpeg) else { return nil }
                let value = DirectFileMediaPreview(kind: .video, thumbnail: jpeg.base64EncodedString(), width: dimensions.width, height: dimensions.height, durationMs: durationMs)
                return value.valid ? value : nil
            } catch { return nil }
        }, onCancel: { generator.cancelAllCGImageGeneration(); asset.cancelLoading() })
    }

    static func decode(_ preview: DirectFileMediaPreview) -> UIImage? {
        guard !Task.isCancelled, preview.valid, let bytes = preview.jpegData,
              let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              CGImageSourceGetType(source).map({ $0 as String }) == UTType.jpeg.identifier,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...DirectFileMediaPreview.maximumPixelEdge).contains(width), (1...DirectFileMediaPreview.maximumPixelEdge).contains(height),
              let frame = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 320,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary), !Task.isCancelled, frame.width <= 320, frame.height <= 320 else { return nil }
        return UIImage(cgImage: frame)
    }
}
