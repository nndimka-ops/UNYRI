import Foundation

/// Phase 1 admits cloud routes only. The app's existing image/video preparers
/// enforce output limits. No preference is modified and nothing routes direct.
enum ShareRoutePolicy {
    static let cloudBytes: Int64 = 100 * 1024 * 1024
    static let videoSourceBytes: Int64 = 500 * 1024 * 1024
    static func issue(kind: ShareJob.Kind, bytes: Int64, sendAs: ShareJob.SendAs) -> String? {
        guard bytes >= 0 else { return "Could not read this file. Try again or share it from another app." }
        if kind == .image && sendAs == .media { return nil } // downsampled by the app, never decoded at full size here
        if kind == .video && sendAs == .media {
            return bytes <= videoSourceBytes ? nil : "Videos up to 500 MB can be queued. Choose a smaller video."
        }
        return bytes <= cloudBytes ? nil : "Files up to 100 MB can be sent. Choose a smaller file."
    }
    static func sendKind(_ item: ShareJob.Item) -> String {
        if item.sendAs == .file || item.kind == .file { return "file" }
        return item.kind.rawValue + (item.quality == .high ? "_high" : "")
    }
}
