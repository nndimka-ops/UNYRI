import Foundation

/// One Signal Protocol ciphertext blob addressed to a single recipient
/// device — the backend's `EnvelopeInput` wire shape
/// (`server/src/messages/models.rs`).
struct EnvelopeInput: Codable, Equatable, Sendable {
    let recipientDeviceId: String
    let messageType: Int
    let ciphertext: String
}

/// UNYRI's wire tag for Signal message types (deliberately decoupled from
/// libsignal's internal constants): 1 = PreKeySignalMessage
/// (session-establishing), 2 = SignalMessage (established session).
enum SignalMessageType {
    static let preKey = 1
    static let whisper = 2
}

/// Incoming Signal message routing: the backend wire tag mapped to the exact
/// libsignal decrypt path. Pure — no libsignal import — so it can be exercised
/// on Windows.
enum SignalIncomingMessageType {
    case preKey
    case whisper

    /// Strict 1/2 mapping. Any other value throws; no fallback to another
    /// Signal type and no ciphertext inspection.
    static func parse(_ rawMessageType: Int) throws -> SignalIncomingMessageType {
        switch rawMessageType {
        case SignalMessageType.preKey: return .preKey
        case SignalMessageType.whisper: return .whisper
        default:
            throw SignalDecryptionError.unsupportedMessageType(rawMessageType)
        }
    }
}

/// Errors raised by the Signal decryption path. Never includes plaintext,
/// ciphertext, or key material in error text.
enum SignalDecryptionError: Error, LocalizedError {
    case unsupportedMessageType(Int)
    case unknownDevice(userId: String, deviceId: String)
    case malformedCiphertext

    var errorDescription: String? {
        switch self {
        case .unsupportedMessageType(let type):
            return "Unsupported Signal message_type: \(type)"
        case .unknownDevice(let userId, let deviceId):
            return "Unknown device \(deviceId) for user \(userId)"
        case .malformedCiphertext:
            return "Malformed ciphertext base64"
        }
    }
}
