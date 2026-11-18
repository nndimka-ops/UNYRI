import Foundation

/// A transport default for one account on one server, independent of media quality.
enum DirectSendPreference {
    static let prefix = "unyri.directSend.v1."
    static let explanation = "Send photos, videos or files after the recipient accepts. Offline offers wait in the chat. You can pause, continue or cancel in Transfers. The file itself is not kept in cloud storage; encrypted offers and small previews still use the server. On iPhone, transfer may pause while UNYRI is in the background. Quality is selected separately."

    static func scope(server: URL, owner: String?) -> String? {
        guard let owner, let identifier = UUID(uuidString: owner),
              var parts = URLComponents(url: server, resolvingAgainstBaseURL: false),
              let scheme = parts.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil else { return nil }
        parts.scheme = scheme; parts.host = host
        if (scheme == "https" && parts.port == 443) || (scheme == "http" && parts.port == 80) { parts.port = nil }
        while parts.percentEncodedPath.hasSuffix("/") { parts.percentEncodedPath.removeLast() }
        guard let canonical = parts.string else { return nil }
        let identity = canonical + "\n" + identifier.uuidString.lowercased()
        return prefix + Data(identity.utf8).base64EncodedString()
    }

    static func enabled(server: URL, owner: String?, defaults: UserDefaults = .standard) -> Bool {
        guard let key = scope(server: server, owner: owner) else { return false }
        return defaults.object(forKey: key) as? Bool ?? false
    }

    static func set(_ enabled: Bool, server: URL, owner: String?, defaults: UserDefaults = .standard) {
        guard let key = scope(server: server, owner: owner) else { return }
        // Persist false explicitly too. Never migrate a global preference across accounts.
        defaults.set(enabled, forKey: key)
    }
}

/// Captured once for a picker import or Send, never reread midway through that batch.
struct DirectSendSelectionContext: Equatable {
    let scope: String?
    let generation: UInt64
    let requested: Bool
    init(server: URL, owner: String?, generation: UInt64, defaults: UserDefaults = .standard) {
        scope = DirectSendPreference.scope(server: server, owner: owner)
        self.generation = generation
        requested = DirectSendPreference.enabled(server: server, owner: owner, defaults: defaults)
    }
    func isCurrent(server: URL, owner: String?, generation: UInt64) -> Bool {
        scope != nil && self.generation == generation && scope == DirectSendPreference.scope(server: server, owner: owner)
    }
}

enum DirectMediaPreparation: Equatable {
    case original, image(highQuality: Bool), video(highQuality: Bool)
    static func resolve(kind: String) -> Self? {
        switch kind {
        case "file": return .original
        case "image": return .image(highQuality: false)
        case "image_high": return .image(highQuality: true)
        case "video": return .video(highQuality: false)
        case "video_high": return .video(highQuality: true)
        default: return nil
        }
    }
}
