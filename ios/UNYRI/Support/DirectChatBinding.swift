import Foundation

enum DirectChatBindingError: Error, LocalizedError {
    case changed, unavailable
    var errorDescription: String? {
        #if canImport(UIKit)
        return UnyriL10n.string("Chat identity changed. Sending is blocked. Contact this person through a trusted channel.")
        #else
        return "Chat identity changed. Sending is blocked. Contact this person through a trusted channel."
        #endif
    }
}

/// The server may update presentation, never the locally pinned conversation identity.
enum DirectChatBinding {
    static func identity(type: String, peer: String?) throws -> String {
        switch type {
        case "direct":
            guard let peer, !peer.isEmpty else { throw DirectChatBindingError.unavailable }
            return "direct:" + peer.lowercased()
        case "group":
            guard peer == nil else { throw DirectChatBindingError.changed }
            return "group:"
        case "saved":
            guard peer == nil else { throw DirectChatBindingError.changed }
            return "saved:"
        default: throw DirectChatBindingError.changed
        }
    }
    static func validate(pinned: String?, type: String, peer: String?, requestedPeer: String? = nil) throws -> String {
        // A legacy direct cache may have no peer because its first summary
        // was redacted. Retain its type, but do not invent a peer or authorize
        // ingress/sending until the first non-redacted identity is supplied.
        if pinned == "direct:" {
            guard type == "direct" else { throw DirectChatBindingError.changed }
            guard let peer, !peer.isEmpty else { throw DirectChatBindingError.unavailable }
            let value = try identity(type: type, peer: peer)
            if let requestedPeer, value != "direct:" + requestedPeer.lowercased() { throw DirectChatBindingError.changed }
            return value
        }
        // Privacy/block responses redact the peer. Absence cannot replace a pin
        // or prove a contradiction. Never pin a requested peer from absence.
        if type == "direct", peer == nil {
            guard let pinned else { throw DirectChatBindingError.unavailable }
            guard pinned.hasPrefix("direct:") else { throw DirectChatBindingError.changed }
            if let requestedPeer, pinned != "direct:" + requestedPeer.lowercased() { throw DirectChatBindingError.changed }
            return pinned
        }
        let value = try identity(type: type, peer: peer)
        guard pinned == nil || pinned == value else { throw DirectChatBindingError.changed }
        if let requestedPeer, value != "direct:" + requestedPeer.lowercased() { throw DirectChatBindingError.changed }
        return value
    }
}
