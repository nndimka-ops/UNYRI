import Foundation
import ImageIO
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

/// One process-wide parser policy, repeated at each extension's entry point.
/// Bounded signatures restrict parser entry before ImageIO inspects bytes.
enum PeerImagePolicy {
    static let identifiers = [UTType.jpeg.identifier, UTType.png.identifier, UTType.heic.identifier,
                              UTType.heif.identifier, UTType.gif.identifier, UTType.webP.identifier]
    private static let registration: Void = {
        if #available(iOS 17.2, macOS 14.2, *) {
            _ = CGImageSourceSetAllowableTypes(identifiers as CFArray)
        }
    }()
    static func install() { _ = registration }
    /// Restrict parser entry even on iOS 17.0/17.1 and older extension hosts.
    static func admitsHeader(_ data: Data, jpegOnly: Bool = false) -> Bool {
        let bytes = Array(data.prefix(512))
        if bytes.starts(with: [0xff, 0xd8, 0xff]) { return true }
        if jpegOnly { return false }
        if bytes.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) { return true }
        if bytes.starts(with: Array("GIF87a".utf8)) || bytes.starts(with: Array("GIF89a".utf8)) { return true }
        if bytes.count >= 12, Array(bytes[0..<4]) == Array("RIFF".utf8), Array(bytes[8..<12]) == Array("WEBP".utf8) { return true }
        guard bytes.count >= 16, Array(bytes[4..<8]) == Array("ftyp".utf8) else { return false }
        let length = bytes[0..<4].reduce(0) { ($0 << 8) | Int($1) }
        guard length >= 16, length <= bytes.count, length % 4 == 0 else { return false }
        var brands = [String(decoding: bytes[8..<12], as: UTF8.self)]
        if length > 16 {
            for offset in stride(from: 16, to: length, by: 4) { brands.append(String(decoding: bytes[offset..<(offset + 4)], as: UTF8.self)) }
        }
        guard !brands.contains("avif"), !brands.contains("avis") else { return false }
        let hevc = ["heic", "heix", "hevc", "hevx"]
        return hevc.contains(brands[0]) || (["mif1", "msf1"].contains(brands[0]) && brands.contains(where: { hevc.contains($0) }))
    }
    private static func admitted(_ source: CGImageSource?, jpegOnly: Bool) -> CGImageSource? {
        guard let source, let type = CGImageSourceGetType(source).map({ $0 as String }),
              (jpegOnly ? [UTType.jpeg.identifier] : identifiers).contains(type) else { return nil }
        return source
    }
    static func source(url: URL, jpegOnly: Bool = false) -> CGImageSource? {
        install()
        guard url.isFileURL, let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let header = try? handle.read(upToCount: 512), admitsHeader(header, jpegOnly: jpegOnly) else { return nil }
        return admitted(CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary), jpegOnly: jpegOnly)
    }
    static func source(data: Data, jpegOnly: Bool = false) -> CGImageSource? {
        install()
        guard admitsHeader(data, jpegOnly: jpegOnly) else { return nil }
        return admitted(CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary), jpegOnly: jpegOnly)
    }
    #if canImport(UIKit)
    static func image(data: Data, jpegOnly: Bool = false) -> UIImage? {
        guard source(data: data, jpegOnly: jpegOnly) != nil else { return nil }
        return UIImage(data: data)
    }
    static func image(path: String) -> UIImage? {
        guard source(url: URL(fileURLWithPath: path)) != nil else { return nil }
        return UIImage(contentsOfFile: path)
    }
    #endif
}
