import Foundation

/// Export admission is deliberately independent of thumbnail/preview availability.
/// The caller supplies availability of the decrypted file, never an object-store URL.
enum UnyriMessageMediaCopyPolicy {
    enum Kind: Equatable { case image, gif, movie }

    static func kind(messageKind: String, status: String, serverID: String?, hidden: Bool, deleted: Bool,
                     directFile: Bool, mediaType: String?, mime: String?, localAvailable: Bool) -> Kind? {
        // Same confirmed-message boundary as the menu's Forward/Save actions.
        guard messageKind == "content", serverID != nil, ["sent", "delivered", "read"].contains(status),
              !hidden, !deleted, !directFile, localAvailable else { return nil }
        let mime = mime?.lowercased() ?? ""
        if mime.hasPrefix("video/") { return .movie }
        guard mediaType == "image", mime.hasPrefix("image/") else { return nil }
        return mime == "image/gif" ? .gif : .image
    }
}

