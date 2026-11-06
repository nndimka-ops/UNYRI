import UIKit
import ImageIO
import UniformTypeIdentifiers

struct ProcessedImage: Sendable {
    let fileURL: URL
    let width: Int
    let height: Int
    let thumbnailBase64: String
    let mimeType: String
}

enum ImageProcessor {
    static func processImage(sourceURL: URL, outputURL: URL, highQuality: Bool = false) throws -> ProcessedImage {
        guard let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, Double(width) * Double(height) <= 150_000_000 else {
            throw AttachmentError.tooLarge("Could not read the selected image.")
        }
        let type = CGImageSourceGetType(source).map { $0 as String } ?? ""
        let preserve = type == UTType.gif.identifier || type == UTType.webP.identifier || CGImageSourceGetCount(source) > 1
        guard let pixels = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: highQuality ? 4096 : 2048,
            kCGImageSourceShouldCacheImmediately: true
        ] as CFDictionary) else { throw AttachmentError.tooLarge("Could not read the selected image.") }
        let alpha = [.first, .last, .premultipliedFirst, .premultipliedLast].contains(pixels.alphaInfo)
        let mime = preserve ? (UTType(type)?.preferredMIMEType ?? "application/octet-stream") : (alpha ? "image/png" : "image/jpeg")
        let ext = preserve ? (UTType(type)?.preferredFilenameExtension ?? "bin") : (alpha ? "png" : "jpg")
        let target = outputURL.deletingPathExtension().appendingPathExtension(ext)
        if preserve {
            try AttachmentFileCopy.copy(from: sourceURL, to: target, maxBytes: 25 * 1024 * 1024)
        } else {
            let image = UIImage(cgImage: pixels)
            guard let data = alpha ? image.pngData() : image.jpegData(compressionQuality: highQuality ? 0.92 : 0.85) else { throw AttachmentError.tooLarge("Could not encode the selected image.") }
            try data.write(to: target, options: .atomic)
        }
        let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary)
        let preview = thumbnail.flatMap { previewJPEG(UIImage(cgImage: $0)) }?.base64EncodedString() ?? ""
        return ProcessedImage(fileURL: target, width: preserve ? width : pixels.width, height: preserve ? height : pixels.height, thumbnailBase64: preview, mimeType: mime)
    }
    // Preserve the existing video wire budget; prefer detail over severe JPEG artifacts.
    static func previewJPEG(_ image: UIImage) -> Data? {
        for edge in [512.0, 384.0, 256.0, 128.0] {
            let scale = min(1, edge / max(image.size.width, image.size.height))
            let size = CGSize(width: max(1, floor(image.size.width * scale)), height: max(1, floor(image.size.height * scale)))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let preview = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                UIColor.black.setFill()
                UIRectFill(CGRect(origin: .zero, size: size))
                image.draw(in: CGRect(origin: .zero, size: size))
            }
            for quality in [0.82, 0.70, 0.55] {
                if let data = preview.jpegData(compressionQuality: quality), data.count <= 16 * 1024 { return data }
            }
        }
        return nil
    }

}
