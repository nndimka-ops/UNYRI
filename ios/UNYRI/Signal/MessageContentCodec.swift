import Foundation

/// iOS mirror of Android's `MessageContentDto` family (kotlinx.serialization
/// JSON). These are the bytes that actually get Signal-encrypted, so the field
/// names and omission rules must match Android exactly for cross-platform
/// interop.
///
/// kotlinx.serialization semantics mirrored here:
/// - fields equal to their default value are omitted (`encodeDefaults = false`);
/// - null optionals are omitted;
/// - `@SerialName` fields use snake_case keys.

// MARK: - Nested DTOs

struct ReplySnapshot: Codable, Equatable {
    static let contentTypeText = "text"
    static let contentTypeImage = "image"
    static let contentTypeVoice = "voice"
    static let contentTypeFile = "file"

    var senderId: String
    var contentType: String
    var preview: String
    var sequenceHint: Int64?

    enum CodingKeys: String, CodingKey {
        case senderId = "sender_id"
        case contentType = "content_type"
        case preview
        case sequenceHint = "sequence_hint"
    }
}

struct ImageInfo: Codable, Equatable {
    var width: Int
    var height: Int
}

struct VideoInfo: Codable, Equatable {
    var durationMs: Int64
    enum CodingKeys: String, CodingKey { case durationMs = "duration_ms" }
}

struct VoiceInfo: Codable, Equatable {
    var durationMs: Int64

    enum CodingKeys: String, CodingKey {
        case durationMs
        case legacyDurationMs = "duration_ms"
    }

    init(durationMs: Int64) { self.durationMs = durationMs }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        durationMs = try values.decodeIfPresent(Int64.self, forKey: .durationMs)
            ?? values.decode(Int64.self, forKey: .legacyDurationMs)
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(durationMs, forKey: .durationMs)
    }
}

struct EncryptionInfo: Codable, Equatable {
    static let versionV1AesGcm = 1
    static let versionV2StreamingAead = 2

    var version: Int
    var key: String?
    var nonce: String?
    var keyset: String?
    var sha256: String

    init(
        version: Int = EncryptionInfo.versionV1AesGcm,
        key: String? = nil,
        nonce: String? = nil,
        keyset: String? = nil,
        sha256: String
    ) {
        self.version = version
        self.key = key
        self.nonce = nonce
        self.keyset = keyset
        self.sha256 = sha256
    }

    enum CodingKeys: String, CodingKey {
        case version, key, nonce, keyset, sha256
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        if version != EncryptionInfo.versionV1AesGcm {
            try container.encode(version, forKey: .version)
        }
        try container.encodeIfPresent(key, forKey: .key)
        try container.encodeIfPresent(nonce, forKey: .nonce)
        try container.encodeIfPresent(keyset, forKey: .keyset)
        try container.encode(sha256, forKey: .sha256)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? EncryptionInfo.versionV1AesGcm
        key = try container.decodeIfPresent(String.self, forKey: .key)
        nonce = try container.decodeIfPresent(String.self, forKey: .nonce)
        keyset = try container.decodeIfPresent(String.self, forKey: .keyset)
        sha256 = try container.decode(String.self, forKey: .sha256)
    }
}

struct AttachmentMetadata: Codable, Equatable {
    static let mediaTypeImage = "image"
    static let mediaTypeFile = "file"
    static let mediaTypeVoice = "voice"

    var attachmentId: String
    var mediaType: String
    var objectKey: String
    var size: Int64
    var encryptedSize: Int64
    var mimeType: String
    var encryption: EncryptionInfo
    var filename: String?
    var image: ImageInfo?
    var voice: VoiceInfo?
    var thumbnail: String?
    var video: VideoInfo? = nil

    enum CodingKeys: String, CodingKey {
        case attachmentId = "attachment_id"
        case mediaType = "media_type"
        case objectKey = "object_key"
        case size
        case encryptedSize = "encrypted_size"
        case mimeType = "mime_type"
        case encryption
        case filename
        case image
        case voice
        case thumbnail
        case video
    }
}

// MARK: - MessageContent

struct MessageAuthentication: Codable, Equatable {
    var version: Int = 1
    var chatId: String
    var clientMessageId: String
    var operation: String
    var targetMessageId: String?
    var targetClientMessageId: String?
    enum CodingKeys: String, CodingKey {
        case version, operation
        case chatId = "chat_id", clientMessageId = "client_message_id"
        case targetMessageId = "target_message_id", targetClientMessageId = "target_client_message_id"
    }
    init(chatId: String, clientMessageId: String, operation: String, targetMessageId: String?, targetClientMessageId: String?) {
        self.chatId = chatId; self.clientMessageId = clientMessageId; self.operation = operation
        self.targetMessageId = targetMessageId; self.targetClientMessageId = targetClientMessageId
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Kotlin omits its default version; explicit null is malformed, not a default.
        version = c.contains(.version) ? try c.decode(Int.self, forKey: .version) : 1
        chatId = try c.decode(String.self, forKey: .chatId)
        clientMessageId = try c.decode(String.self, forKey: .clientMessageId)
        operation = try c.decode(String.self, forKey: .operation)
        targetMessageId = try c.decodeIfPresent(String.self, forKey: .targetMessageId)
        targetClientMessageId = try c.decodeIfPresent(String.self, forKey: .targetClientMessageId)
    }
}

/// Optional text-message metadata. Only the existing Signal envelope transports
/// the capability; generic previews use the harmless text fallback. Descriptions
/// are deliberately redacted because the URL contains a media key.
struct ConferenceInvitationMetadata: Codable, Equatable, Identifiable, CustomStringConvertible, CustomDebugStringConvertible {
    static let fallbackText = "Conference invitation"
    let version: Int
    let id: String
    let title: String
    let server: String
    let roomID: String
    let invite: String
    let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case version, id, title, server, invite
        case roomID = "room_id"
        case expiresAt = "expires_at"
    }
    var description: String { "ConferenceInvitationMetadata(<redacted>)" }
    var debugDescription: String { description }

    init(version: Int = 1, id: String, title: String, server: String, roomID: String, invite: String, expiresAt: Int64) {
        self.version = version; self.id = id; self.title = title; self.server = server
        self.roomID = roomID; self.invite = invite; self.expiresAt = expiresAt
    }
    var hasValidShape: Bool {
        guard version == 1, (1...253402300799).contains(expiresAt), title == title.trimmingCharacters(in: .whitespacesAndNewlines),
              (1...80).contains(title.unicodeScalars.count), title.utf8.count <= 320,
              !title.unicodeScalars.contains(where: { $0.properties.generalCategory == .control || $0.value == 0x2028 || $0.value == 0x2029 }),
              UUID(uuidString: id)?.uuidString.lowercased() == id,
              UUID(uuidString: roomID)?.uuidString.lowercased() == roomID,
              (1...256).contains(server.utf8.count), server.utf8.allSatisfy({ $0 < 128 }),
              (1...2048).contains(invite.utf8.count),
              let origin = URLComponents(string: server), origin.scheme == "https", let host = origin.host,
              !host.isEmpty, host == host.lowercased(), !host.contains("%"),
              origin.user == nil, origin.password == nil, origin.path.isEmpty,
              origin.query == nil, origin.fragment == nil, origin.port != 443,
              (1...65535).contains(origin.port ?? 443), origin.url?.absoluteString == server else { return false }
        return true
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(version: try c.decode(Int.self, forKey: .version), id: try c.decode(String.self, forKey: .id),
            title: try c.decode(String.self, forKey: .title), server: try c.decode(String.self, forKey: .server),
            roomID: try c.decode(String.self, forKey: .roomID), invite: try c.decode(String.self, forKey: .invite),
            expiresAt: try c.decode(Int64.self, forKey: .expiresAt))
        guard hasValidShape else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid conference metadata"))
        }
    }
}

/// Versioned Signal-protocol plaintext content (mirrors `MessageContentDto`).
struct MessageContent: Equatable {
    static let kindText = "text"
    static let kindAttachment = "attachment"
    static let kindReaction = "reaction"
    static let kindDelete = "delete"

    static let reactionActionSet = "set"
    static let reactionActionRemove = "remove"

    var version: Int
    var kind: String
    var text: String?
    var attachment: AttachmentMetadata?
    var replySnapshot: ReplySnapshot?
    var reaction: String?
    var reactionAction: String?
    var forwarded: Bool
    var albumId: String?
    var conferenceInvite: ConferenceInvitationMetadata?
    var directFile: DirectFileOffer?
    var authentication: MessageAuthentication? = nil

    init(
        version: Int = 1,
        kind: String,
        text: String? = nil,
        attachment: AttachmentMetadata? = nil,
        replySnapshot: ReplySnapshot? = nil,
        reaction: String? = nil,
        reactionAction: String? = nil,
        forwarded: Bool = false,
        albumId: String? = nil,
        conferenceInvite: ConferenceInvitationMetadata? = nil,
        directFile: DirectFileOffer? = nil
    ) {
        self.version = version
        self.kind = kind
        self.text = conferenceInvite == nil ? text : ConferenceInvitationMetadata.fallbackText
        self.attachment = attachment
        self.replySnapshot = replySnapshot
        self.reaction = reaction
        self.reactionAction = reactionAction
        self.forwarded = forwarded
        self.albumId = albumId
        self.conferenceInvite = conferenceInvite
        self.directFile = directFile
    }
}

extension MessageContent: Codable {
    private enum CodingKeys: String, CodingKey {
        case version
        case kind
        case text
        case attachment
        case replySnapshot = "reply_snapshot"
        case reaction
        case reactionAction = "reaction_action"
        case forwarded
        case albumId = "album_id"
        case authentication
        case conferenceInvite = "conference_invite"
        case directFile = "direct_file"
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        if version != 1 {
            try container.encode(version, forKey: .version)
        }
        try container.encode(kind, forKey: .kind)
        try container.encodeIfPresent(albumId, forKey: .albumId)
        try container.encodeIfPresent(authentication, forKey: .authentication)
        if kind == Self.kindText, attachment == nil, let conferenceInvite, conferenceInvite.hasValidShape {
            try container.encode(conferenceInvite, forKey: .conferenceInvite)
        }
        if kind == Self.kindText, attachment == nil, conferenceInvite == nil,
           let directFile, directFile.hasValidShape { try container.encode(directFile, forKey: .directFile) }
        try container.encodeIfPresent(conferenceInvite == nil ? text : ConferenceInvitationMetadata.fallbackText, forKey: .text)
        try container.encodeIfPresent(attachment, forKey: .attachment)
        try container.encodeIfPresent(replySnapshot, forKey: .replySnapshot)
        try container.encodeIfPresent(reaction, forKey: .reaction)
        try container.encodeIfPresent(reactionAction, forKey: .reactionAction)
        if forwarded {
            try container.encode(forwarded, forKey: .forwarded)
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? 1
        kind = try container.decode(String.self, forKey: .kind)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        attachment = try container.decodeIfPresent(AttachmentMetadata.self, forKey: .attachment)
        replySnapshot = try container.decodeIfPresent(ReplySnapshot.self, forKey: .replySnapshot)
        reaction = try container.decodeIfPresent(String.self, forKey: .reaction)
        reactionAction = try container.decodeIfPresent(String.self, forKey: .reactionAction)
        forwarded = try container.decodeIfPresent(Bool.self, forKey: .forwarded) ?? false
        albumId = try container.decodeIfPresent(String.self, forKey: .albumId)
        authentication = try container.decodeIfPresent(MessageAuthentication.self, forKey: .authentication)
        if container.contains(.conferenceInvite) { text = ConferenceInvitationMetadata.fallbackText }
        // An invalid optional extension never turns the whole encrypted message
        // into a raw-JSON fallback exposing capability bytes in generic previews.
        conferenceInvite = kind == Self.kindText && attachment == nil
            ? (try? container.decode(ConferenceInvitationMetadata.self, forKey: .conferenceInvite)) : nil
        if container.contains(.directFile) {
            if kind == Self.kindText, attachment == nil, conferenceInvite == nil,
               let offer = try? container.decode(DirectFileOffer.self, forKey: .directFile), offer.hasValidShape {
                directFile = offer
                text = text ?? DirectFileOffer.fallbackText
            } else { directFile = nil; text = DirectFileOffer.fallbackText }
        } else { directFile = nil }
    }
}

// MARK: - Codec

/// Encodes the exact bytes Android Signal-encrypts for each message kind.
enum MessageContentCodec {
    static func authenticate(_ plaintext: Data, chatId: String, clientMessageId: String,
                             operation: String, targetMessageId: String?, targetClientMessageId: String? = nil) throws -> Data {
        guard MessageTextPolicy.allowsPlaintext(plaintext) else { throw MessageTextPolicyError.invalid }
        var content = decode(plaintext)
        try validateSendingText(content)
        content.authentication = MessageAuthentication(chatId: chatId, clientMessageId: clientMessageId,
            operation: operation, targetMessageId: targetMessageId, targetClientMessageId: targetClientMessageId)
        guard authenticates(content, chatId: chatId, clientMessageId: clientMessageId,
                            operation: operation, targetMessageId: targetMessageId) else {
            throw MessageAuthenticationError.invalidContext
        }
        return encode(content)
    }

    static func authenticates(_ content: MessageContent, chatId: String, clientMessageId: String,
                              operation: String, targetMessageId: String?) -> Bool {
        let correctKind: Bool
        switch operation {
        case "content": correctKind = content.kind == MessageContent.kindText || content.kind == MessageContent.kindAttachment
        case "edit": correctKind = content.kind == MessageContent.kindText
        case "delete": correctKind = content.kind == MessageContent.kindDelete
        case "reaction": correctKind = content.kind == MessageContent.kindReaction
        default: return false
        }
        guard correctKind else { return false }
        if let offer = content.directFile {
            guard content.authentication != nil, !content.forwarded, operation == "content", targetMessageId == nil,
                  offer.offerClientMessageId == clientMessageId else { return false }
        }
        guard let b = content.authentication else { return operation == "content" }
        return b.version == 1 && b.chatId == chatId && b.clientMessageId == clientMessageId &&
            b.operation == operation && b.targetMessageId == targetMessageId &&
            (operation == "content" || (!(targetMessageId ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                !(b.targetClientMessageId ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
    }
    static func encodeText(_ text: String, replySnapshot: ReplySnapshot? = nil, forwarded: Bool = false,
                           conferenceInvite: ConferenceInvitationMetadata? = nil,
                           directFile: DirectFileOffer? = nil) -> Data {
        encode(MessageContent(kind: MessageContent.kindText, text: text, replySnapshot: replySnapshot,
            forwarded: forwarded, conferenceInvite: conferenceInvite, directFile: directFile))
    }

    static func encodeAttachment(_ attachment: AttachmentMetadata, replySnapshot: ReplySnapshot? = nil, forwarded: Bool = false,
                                 caption: String? = nil, albumId: String? = nil) -> Data {
        encode(MessageContent(kind: MessageContent.kindAttachment, text: caption, attachment: attachment,
                              replySnapshot: replySnapshot, forwarded: forwarded, albumId: albumId))
    }

    /// An edit's encrypted payload is shaped exactly like a plain text message.
    static func encodeEdit(_ newText: String) -> Data {
        encodeText(newText)
    }

    static func encodeReaction(_ emoji: String) -> Data {
        encode(MessageContent(kind: MessageContent.kindReaction, reaction: emoji, reactionAction: MessageContent.reactionActionSet))
    }

    static func encodeReactionRemoval() -> Data {
        encode(MessageContent(kind: MessageContent.kindReaction, reactionAction: MessageContent.reactionActionRemove))
    }

    static func encodeDelete() -> Data {
        encode(MessageContent(kind: MessageContent.kindDelete))
    }

    /// Mirrors Android's `MessageContentCodec.decode`: AFTER a successful
    /// Signal decrypt, bytes that parse as a `MessageContent` with a
    /// recognized `kind` are decoded as-is; anything else (legacy raw UTF-8,
    /// unknown kind, malformed JSON, arbitrary binary) becomes a legacy
    /// plain-text message. Invalid UTF-8 is lossily replaced with U+FFFD,
    /// matching Java's `String(bytes, UTF_8)`.
    ///
    /// This is NOT a crypto fallback — it only runs on successfully
    /// decrypted plaintext.
    static func decode(_ plaintext: Data) -> MessageContent {
        guard MessageTextPolicy.allowsPlaintext(plaintext) else { return MessageContent(kind: "invalid_authenticated_content") }
        let parsed = try? JSONDecoder().decode(MessageContent.self, from: plaintext)
        let object = (try? JSONSerialization.jsonObject(with: plaintext)) as? [String: Any]
        if object?.keys.contains("authentication") == true,
           parsed?.authentication == nil || !isRecognizedKind(parsed?.kind ?? "") {
            return MessageContent(kind: "invalid_authenticated_content")
        }
        if let content = parsed,
           isRecognizedKind(content.kind) {
            return content
        }
        if object?.keys.contains("conference_invite") == true {
            return MessageContent(kind: MessageContent.kindText, text: ConferenceInvitationMetadata.fallbackText)
        }
        if object?.keys.contains("direct_file") == true {
            return MessageContent(kind: MessageContent.kindText, text: DirectFileOffer.fallbackText)
        }
        if object?["kind"] as? String == MessageContent.kindAttachment {
            return MessageContent(kind: MessageContent.kindAttachment, text: "Unable to read attachment. Please ask the sender to resend it.")
        }
        return MessageContent(kind: MessageContent.kindText, text: String(decoding: plaintext, as: UTF8.self))
    }

    /// New logical sends only; never applied to an attempted retry's frozen bytes.
    static func admittedText(_ source: MessageContent) throws -> MessageContent {
        var content = source
        if content.kind == MessageContent.kindText, content.conferenceInvite == nil {
            content.text = try MessageTextPolicy.outgoing(content.text ?? "")
        } else if content.kind == MessageContent.kindAttachment {
            content.text = try MessageTextPolicy.caption(content.text)
        }
        try validateReceivedText(content)
        return content
    }

    /// Invoke only after authentication context verification on receive.
    static func validateReceivedText(_ content: MessageContent) throws {
        try MessageTextPolicy.received(content.text)
        try MessageTextPolicy.received(content.replySnapshot?.preview)
        try MessageTextPolicy.receivedReaction(content.reaction)
        try MessageTextPolicy.received(content.attachment?.filename)
    }

    static func validateSendingText(_ content: MessageContent, frozenText: String? = nil) throws {
        if content.kind == MessageContent.kindText, content.conferenceInvite == nil {
            try MessageTextPolicy.retry(frozenText ?? content.text)
        } else if content.kind == MessageContent.kindAttachment {
            try MessageTextPolicy.retry(content.text, optional: true)
        }
        try validateReceivedText(content)
    }

    private static func isRecognizedKind(_ kind: String) -> Bool {
        switch kind {
        case MessageContent.kindText, MessageContent.kindAttachment,
             MessageContent.kindReaction, MessageContent.kindDelete:
            return true
        default:
            return false
        }
    }

    /// Encodes a value of plain `String`/`Int`/`Bool`/optional fields, which
    /// cannot throw — kept non-throwing to mirror Android's codec API.
    /// `sortedKeys` makes the byte output deterministic so the "same logical
    /// plaintext on every transport retry" invariant is literally byte-stable
    /// (JSON key order is irrelevant to kotlinx.serialization on the other
    /// side, which parses by name).
    private static func encode(_ content: MessageContent) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try! encoder.encode(content)
    }
}

enum MessageAuthenticationError: Error { case invalidContext }
