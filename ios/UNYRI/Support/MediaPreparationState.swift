import Foundation

enum AttachmentSendStage: String {
    case queued, preparing, compressing, encrypting, uploading, sent
    var label: String {
        switch self {
        case .queued: return "Waiting to send"
        case .preparing: return "Preparing…"
        case .compressing: return "Compressing video…"
        case .encrypting: return "Preparing to send…"
        case .uploading, .sent: return "Sending…"
        }
    }
    var isLocalPreparation: Bool { self == .preparing || self == .compressing || self == .encrypting }
}

/// Captured when Send is tapped; later preference changes cannot alter a batch.
struct MediaSendIntent: Codable, Equatable {
    enum Mode: String, Codable { case media, file }
    let mode: Mode
    let highQuality: Bool
    let caption: String
    let albumId: String?
    init(mode: Mode = .media, highQuality: Bool = false, caption: String = "", albumId: String? = UUID().uuidString.lowercased()) {
        self.mode = mode; self.highQuality = highQuality
        // Preserve the raw intent; admission validates without silently truncating.
        self.caption = caption
        self.albumId = albumId
    }
    func kind(isVideo: Bool) -> String {
        if mode == .file { return "file" }
        return isVideo ? (highQuality ? "video_high" : "video") : (highQuality ? "image_high" : "image")
    }
    func caption(at index: Int) -> String { index == 0 ? caption : "" }
}

/// A gallery default, distinct from an individual batch's immutable send intent.
/// Old quality preferences never implicitly opt the user into sending documents.
enum MediaGallerySendChoice: String, CaseIterable {
    case standard = "image", high = "image_high", original = "file"
    static let preferenceKey = "mediaGallerySendChoiceV1"

    static func resolve(_ raw: String?, legacyHighQuality: Bool) -> Self {
        raw.flatMap(Self.init(rawValue:)) ?? (legacyHighQuality ? .high : .standard)
    }
    static func saved(in defaults: UserDefaults = .standard) -> Self {
        resolve(defaults.string(forKey: preferenceKey), legacyHighQuality: defaults.bool(forKey: "mediaSendHighQuality"))
    }
    func save(in defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.preferenceKey)
        if self != .original { defaults.set(self == .high, forKey: "mediaSendHighQuality") }
    }
    func intent(caption: String = "") -> MediaSendIntent {
        MediaSendIntent(mode: self == .original ? .file : .media, highQuality: self == .high, caption: caption)
    }
}

/// Ordered resolution ceilings. An oversized result may fall back once, without
/// replacing the original draft or silently sending an uncompressed source.
enum VideoCompressionPolicy {
    static let maxOutputBytes: Int64 = 100 * 1024 * 1024
    static func ceilings(highQuality: Bool) -> [Int] { highQuality ? [1080, 720, 540] : [720, 540] }
    static func accepts(bytes: Int64) -> Bool { bytes > 0 && bytes <= maxOutputBytes }
}

enum MediaPreparationFailure {
    static func messageKey(_ error: Error) -> String {
        var current = error as NSError
        for _ in 0..<8 {
            if current.domain == "COCO.AttachmentCopy", current.code == 2 {
                return "The source video exceeds 500 MB. Choose a shorter video. Saved items remain in the draft."
            }
            if current.domain == "COCO.AttachmentCopy", current.code == 1 {
                return "This video or file exceeds 100 MB. Choose a shorter video or reduce its size. Saved items remain in the draft."
            }
            if current.domain == NSCocoaErrorDomain, current.code == CocoaError.fileWriteOutOfSpace.rawValue {
                return "Not enough free space to prepare this attachment. Free up space on your phone and try again. Saved items remain in the draft."
            }
            if current.domain == NSURLErrorDomain {
                return "Could not download the original. Check your connection and open the file in its source app, then try again. Saved items remain in the draft."
            }
            guard let underlying = current.userInfo[NSUnderlyingErrorKey] as? NSError else { break }
            current = underlying
        }
        return "Could not prepare all media. Saved items are still available; retry the remaining files."
    }

    // Only a system domain/code, never a filename, URL or localized error description.
    static func diagnosticCode(_ error: Error) -> String {
        let value = error as NSError
        let allowed = [NSCocoaErrorDomain, NSURLErrorDomain, "PHPhotosErrorDomain", "NSItemProviderErrorDomain", "COCO.AttachmentCopy"]
        return "\(allowed.contains(value.domain) ? value.domain : "Import"):\(value.code)"
    }
}

struct StoredFileDraft: Codable {
    let id: String
    let url: URL
    let size: Int64
}

struct StoredMediaDraft: Codable {
    var id: String
    var url: URL
    var bytes: Int
    var isVideo: Bool
}

/// Account-owned operation tokens: a cancelled/completed old task cannot finish a newer import.
struct MediaPreparationOwnership {
    private var operations: [String: UUID] = [:]
    mutating func begin(chat: String) -> UUID? {
        guard operations[chat] == nil else { return nil }
        let token = UUID(); operations[chat] = token; return token
    }
    func owns(chat: String, token: UUID) -> Bool { operations[chat] == token }
    @discardableResult mutating func finish(chat: String, token: UUID) -> Bool {
        guard owns(chat: chat, token: token) else { return false }
        operations[chat] = nil; return true
    }
    mutating func cancel(chat: String) { operations[chat] = nil }
    mutating func clear() { operations.removeAll() }
}

/// Local outbox presentation only; never serialized into an encrypted message.
struct QueuedMediaVisual: Codable, Equatable {
    let width: Int
    let height: Int
    let durationMs: Int64
    var valid: Bool { width > 0 && height > 0 && width <= 100_000 && height <= 100_000 && durationMs >= 0 }
    static func oriented(width: Int, height: Int, orientation: Int, durationMs: Int64 = 0) -> Self {
        let swap = (5...8).contains(orientation)
        return Self(width: swap ? height : width, height: swap ? width : height, durationMs: durationMs)
    }
    func displaySize(cellWidth: Double? = nil) -> (width: Double, height: Double) {
        if let cellWidth { let side = max(1, min(220, cellWidth)); return (side, side) }
        let ratio = min(5, max(0.2, Double(max(1, width)) / Double(max(1, height))))
        let h = min(260, 210 / ratio)
        return (h * ratio, h)
    }
}

/// Decoded together with the timeline snapshot, never from a row's render pass.
struct QueuedMediaPreview: Decodable, Equatable {
    let url: URL
    let kind: String
    let duration: Int64
    let caption: String?
    let visual: QueuedMediaVisual?
    var isVisual: Bool { ["image", "image_high", "video", "video_high"].contains(kind) && visual?.valid == true }
    static func decodeWorkspace(_ values: [String: String]) -> [String: Self] {
        var result: [String: Self] = [:]
        for (name, raw) in values where name.hasPrefix("upload:") {
            guard let value = try? JSONDecoder().decode(Self.self, from: Data(raw.utf8)) else { continue }
            result[String(name.dropFirst(7))] = value
        }
        return result
    }
}
