import Foundation

/// Server -> client WebSocket events, parsed from the versioned JSON envelope
/// (`server/src/websocket/protocol.rs`). Only the messaging events this stage
/// consumes are decoded here; `call.*` frames are intentionally ignored until
/// the call signaling port (the `CallsAPI` foundation exists but has no live
/// transport yet), and `PONG`/`ERROR` are protocol-noise.
enum RealtimeEvent: Equatable {
    case messageNew(MessageDto)
    case messageSent(MessageDto)
    case receiptUpdated(chatId: String, userId: String, deliveredSequence: Int64, readSequence: Int64)
    case sessionRevoked(reason: String)
    case chatManagementChanged
    case chatDeleted(chatId: String)
}

/// Pure JSON -> event parser. Never crashes the socket loop on a malformed
/// frame (returns nil), and reuses the same tolerant ISO-8601 date decoding
/// as `APIClient` so `MessageDto.createdAt` decodes identically on both the
/// REST and WebSocket paths.
enum RealtimeEventParser {
    private struct Envelope: Decodable {
        let type: String
    }

    private struct MessageEnvelope: Decodable {
        let message: MessageDto
    }

    private struct ReceiptEnvelope: Decodable {
        let chatId: String
        let userId: String
        let deliveredSequence: Int64
        let readSequence: Int64
        // No explicit CodingKeys: the parser's decoder uses convertFromSnakeCase,
        // so `chat_id` -> `chatId` etc. are handled by the strategy (explicit
        // snake_case raw values would double-mismatch under that strategy).
    }

    private struct SessionRevokedEnvelope: Decodable {
        let reason: String
    }
    private struct ChatDeletedEnvelope: Decodable { let chatId: String }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            guard let date = ISO8601Cocoa.parse(string) else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "Unrecognized date format: \(string)"
                )
            }
            return date
        }
        return decoder
    }()

    static func parse(_ data: Data) -> RealtimeEvent? {
        guard let envelope = try? decoder.decode(Envelope.self, from: data) else { return nil }
        switch envelope.type {
        case "message.new":
            return (try? decoder.decode(MessageEnvelope.self, from: data)).map { .messageNew($0.message) }
        case "message.sent":
            return (try? decoder.decode(MessageEnvelope.self, from: data)).map { .messageSent($0.message) }
        case "receipt.updated":
            return (try? decoder.decode(ReceiptEnvelope.self, from: data)).map {
                .receiptUpdated(
                    chatId: $0.chatId,
                    userId: $0.userId,
                    deliveredSequence: $0.deliveredSequence,
                    readSequence: $0.readSequence
                )
            }
        case "session.revoked":
            return (try? decoder.decode(SessionRevokedEnvelope.self, from: data)).map { .sessionRevoked(reason: $0.reason) }
        case "chat.management_changed": return .chatManagementChanged
        case "chat.deleted":
            guard let event = try? decoder.decode(ChatDeletedEnvelope.self, from: data), UUID(uuidString: event.chatId) != nil else { return nil }
            return .chatDeleted(chatId: event.chatId)
        default:
            return nil
        }
    }
}
