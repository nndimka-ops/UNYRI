import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// The runner builds the real ImageProcessor on macOS (the UIKit renderer is
// conditional). These are real ImageIO representations, not filename fixtures.
var shareCopyChunkHook: (() -> Void)?
@main struct NativeShareImageContracts {
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("share-images-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let image = context.makeImage()!
        func encode(_ type: UTType, frames: Int, name: String) -> URL {
            let url = root.appendingPathComponent(name)
            let destination = CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, frames, nil)!
            for _ in 0..<frames { CGImageDestinationAddImage(destination, image, nil) }
            precondition(CGImageDestinationFinalize(destination))
            return url
        }
        func pad(_ url: URL, to bytes: Int) throws {
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            try handle.truncate(atOffset: UInt64(bytes))
        }
        func kind(_ url: URL, _ expected: String) throws {
            let actual = try ImageProcessor.shareSendKind(sourceURL: url)
            precondition(actual == expected, "ImageIO route: \(url.lastPathComponent) => \(actual), expected \(expected)")
        }
        let ceiling = UnyriShareStore.maxPreservedImageBytes
        let gif = encode(.gif, frames: 1, name: "mislabelled-photo.png")
        try kind(gif, "image")
        try pad(gif, to: ceiling)
        try kind(gif, "image")
        try pad(gif, to: ceiling + 1)
        try kind(gif, "file")
        let webp = root.appendingPathComponent("photo.webp")
        let webpBytes = Data(base64Encoded: "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")!
        try webpBytes.write(to: webp)
        precondition(CGImageSourceCreateWithURL(webp as CFURL, nil) != nil, "valid WebP fixture required")
        try kind(webp, "image")
        try pad(webp, to: ceiling + 1)
        try kind(webp, "file")
        let multiFrame = encode(.tiff, frames: 2, name: "animated.tiff")
        try kind(multiFrame, "file")
        try pad(multiFrame, to: ceiling + 1)
        try kind(multiFrame, "file")
        let still = encode(.png, frames: 1, name: "still.png")
        try pad(still, to: ceiling + 1)
        try kind(still, "image")
        try pad(gif, to: UnyriShareStore.maxFileBytes)
        try kind(gif, "file")
        print("Native ImageIO share preflight contracts passed: GIF/WebP, mislabeled GIF, rejected multi-frame TIFF, still PNG, 25/100 MB boundaries")
    }
}
