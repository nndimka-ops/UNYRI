import Foundation

/// NSItemProvider metadata is adapted to this Foundation-only contract. A Live
/// Photo's still and movie are representations of ONE item, not two items.
enum ShareItemClassification {
    static let maximumItems = 10
    enum Category: Equatable { case image, video, livePhoto, file, genericData, fileURL }
    struct Representation {
        let identifier: String
        let category: Category
        var filenameExtension: String? = nil
    }
    struct Selection: Equatable {
        let identifier: String
        let kind: ShareJob.Kind?
        let filenameExtension: String?
        let livePhotoStill: Bool
        var usesFileURL: Bool { identifier == "public.file-url" }
    }
    static func isLivePhotoIdentifier(_ identifier: String) -> Bool {
        identifier == "com.apple.live-photo" || identifier.hasPrefix("com.apple.live-photo-")
    }
    static func select(_ representations: [Representation], livePhoto: Bool = false) -> Selection? {
        let live = livePhoto || representations.contains { $0.category == .livePhoto }
        func selection(_ representation: Representation, kind: ShareJob.Kind?) -> Selection {
            .init(identifier: representation.identifier, kind: kind, filenameExtension: representation.filenameExtension, livePhotoStill: live)
        }
        if live {
            // Never send the Live Photo bundle or its paired MOV, even if that
            // representation is registered first. An absent still is an error.
            return representations.first { $0.category == .image }.map { selection($0, kind: .image) }
        }
        // A movie may advertise a poster image too. Its video representation is
        // authoritative; neither a poster nor a misleading filename wins.
        if let video = representations.first(where: { $0.category == .video }) { return selection(video, kind: .video) }
        if let image = representations.first(where: { $0.category == .image }) { return selection(image, kind: .image) }
        if let file = representations.first(where: { $0.category == .file }) { return selection(file, kind: .file) }
        if let data = representations.first(where: { $0.category == .genericData }) { return selection(data, kind: nil) }
        return representations.first { $0.category == .fileURL }.map { selection($0, kind: nil) }
    }
    static func kind(_ selection: Selection, filename: String) -> ShareJob.Kind {
        if let kind = selection.kind { return kind }
        return UnyriShareStore.isVideo(filename) ? .video : UnyriShareStore.isImage(filename) ? .image : .file
    }
    static func name(_ suggested: String?, providerName: String, selection: Selection) -> String {
        var name = UnyriShareStore.safeName(suggested ?? providerName)
        // Media UTI wins over a temporary suffix or a misleading suggestedName.
        let ext = selection.filenameExtension ?? (selection.kind == .video ? "mov" : selection.kind == .image ? "jpg" : nil)
        if let ext, !ext.isEmpty, ext.count <= 16, ext.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) }), selection.kind != nil {
            name = String((name as NSString).deletingPathExtension.prefix(140)) + "." + ext
        } else if (name as NSString).pathExtension.isEmpty, !(providerName as NSString).pathExtension.isEmpty {
            name = String(name.prefix(140)) + "." + (providerName as NSString).pathExtension
        }
        return UnyriShareStore.safeName(name)
    }
    static func initialSendAs(kind: ShareJob.Kind) -> ShareJob.SendAs {
        // A remembered gallery document mode must not silently turn a Photos
        // video/HEIC/screenshot into a file. The share's toggle is explicit.
        kind == .file ? .file : .media
    }
    static let loadingFailure = "Could not load this photo, video or file. If the original is in iCloud, check your connection and try again. Nothing was sent."
    static let loadingCancelled = "Loading was cancelled. Nothing was sent. Share again when the original is available."
    static func providerFailure(_ error: Error) -> String {
        var value = error as NSError
        for _ in 0..<8 {
            if value.domain == NSCocoaErrorDomain && value.code == CocoaError.fileWriteOutOfSpace.rawValue {
                return "Not enough space on this iPhone. Free some storage and try again."
            }
            if (value.domain == NSURLErrorDomain && value.code == URLError.cancelled.rawValue) ||
               (value.domain == NSCocoaErrorDomain && value.code == CocoaError.userCancelled.rawValue) { return loadingCancelled }
            guard let underlying = value.userInfo[NSUnderlyingErrorKey] as? NSError else { break }
            value = underlying
        }
        return loadingFailure
    }
}

/// KVO may arrive on any provider thread. Coalesce to whole percentages before
/// hopping to MainActor, without polling or accumulating per-byte UI tasks.
final class ShareProviderProgressGate: @unchecked Sendable {
    enum Value: Equatable { case indeterminate, percent(Int) }
    private let lock = NSLock()
    private var previous: Value?
    func take(fraction: Double, total: Int64) -> Value? {
        let value: Value = total > 0 && fraction.isFinite && fraction >= 0
            ? .percent(Int((min(1, fraction) * 100).rounded(.down))) : .indeterminate
        lock.lock(); defer { lock.unlock() }
        guard value != previous else { return nil }
        previous = value; return value
    }
}
