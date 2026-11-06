import Foundation

/// Pure mapping from a locally-persisted message to the exact bytes + wire
/// discriminator the outbox POSTs. Extracted from the outbox so the "one
/// Signal encryption per logical send / same `client_message_id` / same
/// plaintext on transport retry" invariant is testable on macOS without
/// libsignal (the outbox itself imports libsignal).
///
/// The *logical* content is fixed at enqueue time: a transport retry reuses
/// the same `client_message_id` and re-derives the SAME plaintext from the
/// same `message.content` — it never mutates the logical message. (Signal's
/// ratchet means the ciphertext bytes are re-encrypted per transport attempt,
/// exactly as Android's `attemptSendEntity` does; the server dedups on
/// `(sender_device_id, client_message_id)`.)
enum MessageSendComposer {
    struct Payload: Equatable {
        let kind: String
        let targetMessageId: String?
        let attachmentIds: [String]
        let plaintext: Data
    }

    static func payload(for message: DecryptedChatMessage) -> Payload {
        switch message.kind {
        case "edit":
            return Payload(
                kind: "edit",
                targetMessageId: message.targetMessageId,
                attachmentIds: [],
                plaintext: MessageContentCodec.encodeEdit(message.content.text ?? "")
            )
        case "delete":
            return Payload(
                kind: "delete",
                targetMessageId: message.targetMessageId,
                attachmentIds: [],
                plaintext: MessageContentCodec.encodeDelete()
            )
        case "reaction":
            let plaintext: Data
            if message.content.reactionAction == MessageContent.reactionActionRemove {
                plaintext = MessageContentCodec.encodeReactionRemoval()
            } else {
                plaintext = MessageContentCodec.encodeReaction(message.content.reaction ?? "")
            }
            return Payload(
                kind: "reaction",
                targetMessageId: message.targetMessageId,
                attachmentIds: [],
                plaintext: plaintext
            )
        default:
            // "content": a plain text message, an attachment message, or a
            // reply (reply-to reference in `targetMessageId`, snapshot inside
            // the Signal-encrypted content). The attachment's crypto metadata
            // lives inside the content; only its `attachment_id` is sent
            // server-side as a storage-authorization reference.
            if let attachment = message.content.attachment {
                return Payload(
                    kind: "content",
                    targetMessageId: message.targetMessageId,
                    attachmentIds: [attachment.attachmentId],
                    plaintext: MessageContentCodec.encodeAttachment(
                        attachment,
                        replySnapshot: message.content.replySnapshot,
                        forwarded: message.content.forwarded,
                        caption: message.content.text,
                        albumId: message.content.albumId
                    )
                )
            }
            return Payload(
                kind: "content",
                targetMessageId: message.targetMessageId,
                attachmentIds: [],
                plaintext: MessageContentCodec.encodeText(
                    message.content.text ?? "",
                    replySnapshot: message.content.replySnapshot,
                    forwarded: message.content.forwarded,
                    conferenceInvite: message.content.conferenceInvite,
                    directFile: message.content.directFile
                )
            )
        }
    }
}
