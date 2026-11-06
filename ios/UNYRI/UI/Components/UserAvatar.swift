import SwiftUI
import ImageIO

@MainActor
private enum AvatarImages {
    static var generation: UInt64 = 0
    static let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = 16 * 1024 * 1024
        return cache
    }()
}

/// Reusable avatar: shows a remote image when a resolved URL is available,
/// otherwise a violet circle with the first letter of the name. Never shows a
/// broken-image placeholder.
struct UserAvatar: View {
    static func clearDecodedCache() {
        AvatarImages.generation &+= 1
        AvatarImages.cache.removeAllObjects()
    }
    let url: URL?
    let name: String
    var size: CGFloat = 44
    var vividFallback = false
    @State private var loadedImage: UIImage?
    @State private var loadedURL: URL?

    var body: some View {
        Group {
            if let url, let image = AvatarImages.cache.object(forKey: url as NSURL) ?? (loadedURL == url ? loadedImage : nil) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                initials
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .task(id: url) {
            loadedImage = nil
            loadedURL = nil
            guard let url else { return }
            if let image = AvatarImages.cache.object(forKey: url as NSURL) { loadedURL = url; loadedImage = image; return }
            let generation = AvatarImages.generation
            let image = await Task.detached(priority: .utility) { () -> UIImage? in
                let data: Data
                if url.isFileURL {
                    guard let bytes = try? Data(contentsOf: url) else { return nil }
                    data = bytes
                } else {
                    guard let (bytes, response) = try? await URLSession.shared.data(from: url),
                          let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
                    data = bytes
                }
                guard data.count <= 3 * 1024 * 1024,
                      let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 512,
                        kCGImageSourceShouldCacheImmediately: true
                      ] as CFDictionary) else { return nil }
                return UIImage(cgImage: thumbnail)
            }.value
            guard !Task.isCancelled, generation == AvatarImages.generation, let image else { return }
            AvatarImages.cache.setObject(image, forKey: url as NSURL, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            loadedURL = url
            loadedImage = image
        }
    }

    private var initials: some View {
        ZStack {
            if vividFallback {
                Circle().fill(LinearGradient(colors: [Color(red: 0.714, green: 0.298, blue: 0.867), Color(red: 0.396, green: 0.22, blue: 0.725)], startPoint: .topLeading, endPoint: .bottomTrailing))
            } else { Circle().fill(UnyriColors.accent.opacity(0.14)) }
            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundColor(vividFallback ? .white : UnyriColors.accent)
        }
    }
}
