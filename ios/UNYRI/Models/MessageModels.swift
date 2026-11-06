import Foundation

/// Device-local call activity, separate from encrypted message/receipt rows.
struct CallActivityProjection: Equatable {
    let callId: String
    let chatId: String?
    let peerId: String
    let createdAt: Date
    let mediaKind: String
    let outcome: String
    let visible: Bool
    let unread: Bool
}
struct PendingCallDeletion: Equatable {
    let callId: String
    let everyone: Bool
}
struct ChatClearCommand: Equatable {
    let requestId: String
    let chatId: String
    let forEveryone: Bool
    let throughSequence: Int64
    let callsBefore: Date?

    var callsBeforeISO: String? {
        guard let callsBefore else { return nil }
        let epoch = callsBefore.timeIntervalSince1970
        let micros = Int64((epoch * 1_000_000).rounded())
        let seconds = micros / 1_000_000
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let base = formatter.string(from: Date(timeIntervalSince1970: Double(seconds)))
        return String(base.dropLast()) + String(format: ".%06lldZ", micros % 1_000_000)
    }
}

// MARK: - Backend DTOs (mirror server/src/messages/models.rs)

/// One encrypted message addressed to the requester's own device.
/// `senderDeviceId` is the backend device UUID — resolved to a Signal device id
/// separately (never used as `ProtocolAddress.deviceId`).
struct MessageDto: Decodable, Equatable {
    let id: String
    let chatId: String
    let clientMessageId: String
    let senderId: String
    let senderDeviceId: String
    let sequence: Int64
    let createdAt: Date
    let messageType: Int
    let ciphertext: String
    let kind: String
    let targetMessageId: String?
}

/// P1-4: the durable, account-local record of an encrypted envelope whose
/// decryption failed. Opaque ciphertext + addressing only — never plaintext,
/// never Signal private keys. Account-scoped by MessageStore's per-account
/// database file (`COCO/<server>/<userId>/messages.sqlite3`), mirroring
/// Android's `PendingDecryptEntity`. Retention is what makes it safe to
/// advance the durable `/sync` cursor past an undecryptable message.
struct PendingDecrypt: Equatable {
    let clientMessageId: String
    let serverId: String
    let chatId: String
    let senderId: String
    let senderDeviceId: String
    let sequence: Int64
    let createdAt: Date
    let messageType: Int
    let ciphertext: String
    let kind: String
    let targetMessageId: String?
    var retryCount: Int = 0
    var lastErrorClass: String?

    /// Reconstructs the original wire DTO so `ingestOne` can decrypt the
    /// envelope again with the same semantics as first ingress.
    func toDto() -> MessageDto {
        MessageDto(
            id: serverId,
            chatId: chatId,
            clientMessageId: clientMessageId,
            senderId: senderId,
            senderDeviceId: senderDeviceId,
            sequence: sequence,
            createdAt: createdAt,
            messageType: messageType,
            ciphertext: ciphertext,
            kind: kind,
            targetMessageId: targetMessageId
        )
    }
}

/// `GET /chats/{id}/messages` — newest-first by sequence DESC.
struct MessageListResponse: Decodable, Equatable {
    let messages: [MessageDto]
    let hasMore: Bool
    let nextBeforeSequence: Int64?
}

/// `POST /chats/{id}/messages` — carries only ciphertext; never plaintext.
struct SendMessageRequest: Encodable, Equatable {
    let clientMessageId: String
    let envelopes: [EnvelopeInput]
    let attachmentIds: [String]
    let kind: String
    let targetMessageId: String?
}

/// Server receipt for a sent message.
struct SendMessageResponse: Decodable, Equatable {
    let id: String
    let chatId: String
    let clientMessageId: String
    let senderId: String
    let sequence: Int64
    let createdAt: Date
    let status: String
}

// MARK: - App-level decrypted message

enum MessageStatus: String {
    case pending
    case sent
    case failed
    case delivered
    case read
    case decryptFailed = "decrypt_failed"
    case identityChanged = "identity_changed"
}

// MARK: - Foreground banner policy (pure, Runtime Correction D)

/// A presentation-only boundary, never a sync cursor or permission to delete.
func hasCurrentDecryptionFailures(_ messages: [DecryptedChatMessage], chatId: String, loadedChatId: String?, cutoff: Int64) -> Bool {
    guard loadedChatId == chatId else { return false }
    return messages.contains {
        $0.chatId == chatId && !$0.hiddenLocally && !$0.isDeletedForEveryone &&
        $0.status == .decryptFailed && !isUnavailableHistory($0, cutoff: cutoff)
    }
}

func isUnavailableHistory(_ message: DecryptedChatMessage, cutoff: Int64) -> Bool {
    guard cutoff > 0, let sequence = message.sequence else { return false }
    return sequence > 0 && sequence <= cutoff && message.status == .decryptFailed
}

func collapseUnavailableHistory(_ messages: [DecryptedChatMessage], cutoff: Int64, collapseRecentFailures: Bool = false) -> [DecryptedChatMessage] {
    let recentMarker = messages.filter { $0.status == .decryptFailed && !isUnavailableHistory($0, cutoff: cutoff) }
        .max { ($0.sequence ?? 0) < ($1.sequence ?? 0) }
    return messages.filter {
        if isUnavailableHistory($0, cutoff: cutoff) { return false }
        return !collapseRecentFailures || $0.status != .decryptFailed || $0.id == recentMarker?.id
    }
}

/// Self-contained typed preview for the foreground message banner (mirrors
/// Android's `LastMessagePreview`; mapped to the UI `ChatPreview` in the app
/// layer, never on the wire).
enum ForegroundPreview: Equatable {
    case text(String)
    case photo
    case voice
    case video
    case file(String?)
    case deleted
}

/// Pure decision + preview policy for the foreground message banner. No
/// SwiftUI/Combine types — contract-tested on macOS CI. This is the inverse of
/// the system-notification suppression: a banner shows exactly when the app is
/// foreground and a DIFFERENT chat received the message.
enum NotificationRecipientPolicy {
    static func matches(owner: String?, current: String?) -> Bool {
        guard let owner, !owner.isEmpty, let current, !current.isEmpty else { return false }
        return owner == current
    }
}

enum ForegroundBannerPolicy {
    static func shouldShow(
        isForeground: Bool,
        activeChatId: String?,
        eventChatId: String,
        isOwnMessage: Bool,
        activeCall: Bool,
        navigationChatId: String? = nil
    ) -> Bool {
        // The call screen also presents banners. A chat behind an expanded
        // call is not visible, so it must not suppress an incoming message.
        isForeground && !isOwnMessage && (activeCall || (eventChatId != activeChatId && eventChatId != navigationChatId))
    }

    /// Maps raw local-message state to a typed preview. Returns nil when no
    /// safe, human-readable preview exists (never raw JSON / object keys /
    /// UUIDs) — callers must then skip the banner.
    static func preview(
        kind: String,
        status: String,
        isDeleted: Bool,
        text: String?,
        mediaType: String?,
        filename: String?,
        mimeType: String? = nil
    ) -> ForegroundPreview? {
        guard kind == "content" else { return nil }
        guard status != "decrypt_failed" && status != "identity_changed" else { return nil }
        if isDeleted { return .deleted }
        if mimeType?.lowercased().hasPrefix("video/") == true { return .video }
        switch mediaType {
        case "image": return .photo
        case "voice": return .voice
        case "file": return .file(filename)
        default: break
        }
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty, !trimmed.hasPrefix("{") else { return nil }
        return .text(trimmed)
    }
}

/// Aggregated reaction state for one message — mirrors Android's
/// `ReactionSummary` (emoji, count of users, whether the current account
/// is among them). Built from the per-user `ReactionRow`s in the store.
struct ReactionSummary: Equatable {
    let emoji: String
    let count: Int
    let isMine: Bool
}

/// A usable message for the UI. Ciphertext never leaves the crypto layer —
/// only the decoded `MessageContent` (or a safe placeholder) is exposed.
struct DecryptedChatMessage: Identifiable, Equatable {
    let clientMessageId: String
    var serverId: String?
    let chatId: String
    let senderId: String
    var sequence: Int64?
    var createdAt: Date
    /// Server-visible kind: "content" | "edit" | "delete" | "reaction".
    let kind: String
    var targetMessageId: String?
    var status: MessageStatus
    /// Decrypted content (its own `kind`: text/attachment/reaction/delete).
    var content: MessageContent

    /// Guards a stale/reordered edit from resurrecting older text — an edit
    /// only applies when its global sequence is higher (mirrors Android's
    /// `MessageEntity.lastEditSequence`, Этап I п.43).
    var lastEditSequence: Int64 = 0
    /// Guards a stale edit from un-deleting a tombstoned message, and records
    /// the delete event's sequence (Android's `MessageEntity.deleteSequence`,
    /// Этап I п.44).
    var deleteSequence: Int64 = 0
    /// "Delete for me" — purely local, never sent to the backend, survives
    /// restart/re-sync (Android's `MessageEntity.hiddenLocally`, Этап I п.15).
    var hiddenLocally: Bool = false
    /// Text that belongs to the current server idempotency key. It is frozen
    /// before the first POST; later edits must not silently replace it.
    var payloadText: String? = nil
    /// Latest edit that could not be applied before the original POST was
    /// attempted. It is sent as an ordinary `edit` lifecycle row after the
    /// server returns the target's server id.
    var pendingEditText: String? = nil
    var pendingEditRevision: Int64 = 0
    /// True once an attempt may have reached the server under this key.
    var sendAttempted: Bool = false

    var id: String { clientMessageId }

    /// Android keeps `isEdited`/`isDeletedForEveryone` as columns; here they
    /// are derived from the sequence guards that already enforce them.
    var isEdited: Bool { lastEditSequence > 0 }
    var isDeletedForEveryone: Bool { deleteSequence > 0 }

    var displayText: String {
        switch content.kind {
        case MessageContent.kindText:
            if content.conferenceInvite != nil {
                #if os(iOS)
                return UnyriL10n.string(ConferenceInvitationMetadata.fallbackText)
                #else
                return ConferenceInvitationMetadata.fallbackText
                #endif
            }
            if let raw = content.text, raw.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
                let recovered = MessageContentCodec.decode(Data(raw.utf8))
                if recovered.kind == MessageContent.kindAttachment {
                    return recovered.attachment?.mediaType == AttachmentMetadata.mediaTypeVoice
                        ? "Voice message" : "Attachment"
                }
            }
            return content.text ?? ""
        case MessageContent.kindAttachment:
            return content.text ?? "Attachment"
        case MessageContent.kindReaction:
            return "[reaction]"
        case MessageContent.kindDelete:
            return "[delete]"
        default:
            return content.text ?? ""
        }
    }
}

/// Chronological ordering: server-assigned sequence ascending, with
/// not-yet-sequenced (pending) messages last (newest), tie-broken by created
/// time. Pure — exercised on Windows.
enum MessageOrdering {
    static func chronological(_ messages: [DecryptedChatMessage]) -> [DecryptedChatMessage] {
        messages.sorted { lhs, rhs in
            switch (lhs.sequence, rhs.sequence) {
            case let (l?, r?):
                return l < r
            case (nil, .some):
                return false
            case (.some, nil):
                return true
            case (nil, nil):
                return lhs.createdAt < rhs.createdAt
            }
        }
    }

    /// Oldest server sequence in a page (the keyset for the next older page),
    /// nil when the list is empty or has no sequenced messages.
    static func oldestSequence(_ messages: [DecryptedChatMessage]) -> Int64? {
        messages.compactMap { $0.sequence }.min()
    }
}
