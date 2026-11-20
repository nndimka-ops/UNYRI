import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@main struct Main {
    static func main() {
        let context = CGContext(data: nil, width: 64, height: 64, bitsPerComponent: 8, bytesPerRow: 256,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let image = context.makeImage()!
        func encoded(_ identifier: String) -> Data {
            let data = NSMutableData()
            let destination = CGImageDestinationCreateWithData(data, identifier as CFString, 1, nil)!
            CGImageDestinationAddImage(destination, image, nil)
            check(CGImageDestinationFinalize(destination)); return data as Data
        }
        PeerImagePolicy.install()
        check(Set(PeerImagePolicy.identifiers) == ["public.jpeg", "public.png", "public.heic", "public.heif", "com.compuserve.gif", "org.webmproject.webp"])
        for type in [UTType.jpeg.identifier, UTType.png.identifier, UTType.gif.identifier, UTType.heic.identifier] {
            let bytes = encoded(type)
            check(PeerImagePolicy.source(data: bytes) != nil)
            check((PeerImagePolicy.source(data: bytes, jpegOnly: true) != nil) == (type == UTType.jpeg.identifier))
        }
        let webp = Data(base64Encoded: "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")!
        check(PeerImagePolicy.source(data: webp) != nil)
        let tiff = encoded("public.tiff")
        check(PeerImagePolicy.source(data: tiff) == nil)
        check(PeerImagePolicy.source(data: tiff, jpegOnly: true) == nil)
        for bytes in [Data("<svg/>".utf8), Data("%PDF-1.7".utf8), Data("8BPS".utf8), Data([0xff, 0xd8]), Data([0x49, 0x49, 42, 0])] { check(PeerImagePolicy.source(data: bytes) == nil) }
        var avif = Data([0,0,0,24]); avif.append(Data("ftypavif".utf8)); avif.append(Data([0,0,0,0])); avif.append(Data("mif1avif".utf8))
        check(!PeerImagePolicy.admitsHeader(avif))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        do {
            try tiff.write(to: file); defer { try? FileManager.default.removeItem(at: file) }
            check(PeerImagePolicy.source(url: file) == nil)
            try encoded(UTType.jpeg.identifier).write(to: file)
            check(PeerImagePolicy.source(url: file, jpegOnly: true) != nil)
        } catch { preconditionFailure("Fixture I/O failed: \(error)") }
        print("PASS I2: real supported pixels; TIFF/unknown/AVIF rejected; inline JPEG only")
    }
}
