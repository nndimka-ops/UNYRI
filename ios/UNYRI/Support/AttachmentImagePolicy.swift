import Foundation

/// Pixel provenance, not UIImage identity or arrival order, determines an upgrade.
enum AttachmentImagePolicy {
    enum Origin: Int, Equatable { case tiny, inline, localImage, videoFrame, notePoster, viewerFrame }
    struct Quality: Equatable {
        let origin: Origin
        let pixels: Int
        var sharp: Bool { origin.rawValue >= Origin.localImage.rawValue }
    }
    static func prefers(_ candidate: Quality, over current: Quality?) -> Bool {
        guard let current else { return true }
        if candidate.sharp != current.sharp { return candidate.sharp }
        if candidate.pixels != current.pixels { return candidate.pixels > current.pixels }
        return candidate.origin.rawValue > current.origin.rawValue
    }

    /// A finite recovery window, owned by visible consumers. No polling after it.
    static func retryDelay(failures: Int, persistent: Bool) -> TimeInterval? {
        guard !persistent, failures > 0 else { return nil }
        return [0.5, 2.0, 8.0].indices.contains(failures - 1) ? [0.5, 2.0, 8.0][failures - 1] : nil
    }
    enum Failure { case unsupported, invalidFile, protectedContent, interrupted, resourceUnavailable, unknown }
    static func persistent(_ failure: Failure, steady: Bool) -> Bool {
        guard steady else { return false }
        switch failure {
        case .unsupported, .invalidFile, .protectedContent: return true
        case .interrupted, .resourceUnavailable, .unknown: return false
        }
    }
    static func allowsWork(visible: Bool, menu: Bool, active: Bool, viewerOpen: Bool, viewerSurface: Bool) -> Bool {
        (visible || menu) && active && (!viewerOpen || viewerSurface)
    }

    /// Failure memos belong to a specific file version, never just an attachment ID.
    struct FileRevision: Hashable {
        let path: String
        let bytes: UInt64
        let modified: TimeInterval
        let inode: UInt64
        static func read(_ url: URL) throws -> Self {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            return Self(path: url.standardizedFileURL.path,
                bytes: (attributes[.size] as? NSNumber)?.uint64Value ?? 0,
                modified: (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0,
                inode: (attributes[.systemFileNumber] as? NSNumber)?.uint64Value ?? 0)
        }
    }
}
