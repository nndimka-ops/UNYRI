import SwiftUI
import ImageIO
import UniformTypeIdentifiers

enum AccountAvatarImage {
    static let changed = Notification.Name("UnyriAccountAvatarChanged")
    private static let cache = AccountAvatarMemory<UIImage>()

    /// ImageIO downsamples and applies EXIF orientation on the caller's worker.
    static func jpeg(file: URL) -> Data? {
        guard file.isFileURL, let source = PeerImagePolicy.source(url: file),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 128,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { return nil }
        let bytes = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(bytes, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? bytes as Data : nil
    }

    static func cached(slot: String, owner: String) -> UIImage? {
        cache.peek(slot: slot, owner: owner)
    }

    static func load(slot: String, owner: String) -> UIImage? {
        try? cache.load(slot: slot, owner: owner, read: {
            try AccountAvatarStore.device.read(slot: slot, owner: owner)
        }, decode: { jpeg in
        guard let source = PeerImagePolicy.source(data: jpeg, jpegOnly: true),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 128,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
        })
    }

    @MainActor static func didChange(slot: String? = nil) {
        cache.clear()
        NotificationCenter.default.post(name: changed, object: slot)
    }
}

/// Local-only account row. Identity changes discard SwiftUI state before rendering.
struct UnyriAccountAvatar: View {
    let record: UnyriAccountRecord
    var size: CGFloat
    var neutralFallback = false
    var onImageChange: ((Bool) -> Void)? = nil
    var body: some View {
        AccountAvatarContent(slot: record.slot, owner: AccountAvatarStore.owner(origin: record.origin, user: record.userId),
            name: record.displayName, size: size, neutralFallback: neutralFallback, onImageChange: onImageChange)
            .id(record.slot + "|" + record.origin + "|" + record.userId)
    }
}

private struct AccountAvatarContent: View {
    let slot: String
    let owner: String
    let name: String
    let size: CGFloat
    let neutralFallback: Bool
    let onImageChange: ((Bool) -> Void)?
    @State private var image: UIImage?
    @State private var revision = UUID()

    init(slot: String, owner: String, name: String, size: CGFloat, neutralFallback: Bool,
         onImageChange: ((Bool) -> Void)?) {
        self.slot = slot; self.owner = owner; self.name = name; self.size = size
        self.neutralFallback = neutralFallback; self.onImageChange = onImageChange
        _image = State(initialValue: AccountAvatarImage.cached(slot: slot, owner: owner))
    }

    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else if neutralFallback {
                Text(String(name.prefix(1)).uppercased()).font(.system(size: 14, weight: .semibold))
                    .foregroundColor(UnyriColors.primaryText).frame(width: size, height: size)
                    .background(Circle().fill(Color.white.opacity(0.1)))
            }
            else { UserAvatar(url: nil, name: name, size: size) }
        }.frame(width: size, height: size).clipShape(Circle())
            .task(id: revision) {
                if image != nil { onImageChange?(true); return }
                let loaded = await Task.detached(priority: .utility) { AccountAvatarImage.load(slot: slot, owner: owner) }.value
                guard !Task.isCancelled else { return }
                image = loaded
                onImageChange?(loaded != nil)
            }
            .onReceive(NotificationCenter.default.publisher(for: AccountAvatarImage.changed)) { event in
                guard event.object == nil || (event.object as? String) == slot else { return }
                image = AccountAvatarImage.cached(slot: slot, owner: owner)
                onImageChange?(image != nil)
                revision = UUID()
            }
    }
}
