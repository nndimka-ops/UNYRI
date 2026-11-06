import Foundation

/// App-level abstraction over the concrete Signal Protocol binding.
/// No concrete implementation exists yet — this isolates the rest of UNYRI
/// from the future LibSignalClient binding. Any implementation MUST NOT
/// fall back to plaintext or invent its own crypto.
protocol SignalCryptoProviding {
    func bootstrapIfNeeded() async throws
    func ensureSessions(forUserId userId: String) async throws
    func encrypt(plaintext: Data, forRecipientDeviceIds deviceIds: [String]) async throws -> [EnvelopeInput]
    func decrypt(
        ciphertextBase64: String,
        messageType: Int,
        senderUserId: String,
        senderSignalDeviceId: Int
    ) async throws -> Data
}

/// Explicit sentinel used until the real LibSignalClient-backed provider is
/// implemented. Fails loudly so a missing implementation can never be
/// mistaken for working encryption.
struct UnimplementedSignalCryptoProvider: SignalCryptoProviding {
    enum SignalCryptoError: Error, LocalizedError {
        case notImplemented
        var errorDescription: String? { "Signal encryption is not implemented yet." }
    }

    func bootstrapIfNeeded() async throws { throw SignalCryptoError.notImplemented }
    func ensureSessions(forUserId userId: String) async throws { throw SignalCryptoError.notImplemented }
    func encrypt(plaintext: Data, forRecipientDeviceIds deviceIds: [String]) async throws -> [EnvelopeInput] {
        throw SignalCryptoError.notImplemented
    }
    func decrypt(
        ciphertextBase64: String,
        messageType: Int,
        senderUserId: String,
        senderSignalDeviceId: Int
    ) async throws -> Data {
        throw SignalCryptoError.notImplemented
    }
}
