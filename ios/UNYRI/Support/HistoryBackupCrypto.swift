import Foundation
import CryptoKit

/// Format 1: nonce(12) || AES-256-GCM ciphertext || tag(16).
/// Shared with Android; does not export live Signal state or auth credentials.
enum HistoryBackupCrypto {
    static let maxPacketBytes = 8 * 1024 * 1024
    enum Failure: Error { case invalidKey, invalidSize }
    struct Context {
        let serverId: UUID
        let userId: UUID
        let backupId: UUID
        var aad: Data {
            Data("COCO-HISTORY-BACKUP\0v1\0\(serverId.uuidString.lowercased())\0\(userId.uuidString.lowercased())\0\(backupId.uuidString.lowercased())".utf8)
        }
    }
    static func generateRecoveryKey() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }
    static func formatRecoveryKey(_ key: Data) throws -> String {
        guard key.count == 32 else { throw Failure.invalidKey }
        let hex = key.map { String(format: "%02X", $0) }.joined()
        let characters = Array(hex)
        return "COCO-H1-" + stride(from: 0, to: 64, by: 8).map { String(characters[$0..<($0 + 8)]) }.joined(separator: "-")
    }
    static func parseRecoveryKey(_ value: String) throws -> Data {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard normalized.range(of: "^COCO-H1-[0-9A-F]{8}(-[0-9A-F]{8}){7}$", options: .regularExpression) != nil else { throw Failure.invalidKey }
        let hex = Array(normalized.dropFirst(8).filter { $0 != "-" })
        var key = Data()
        for index in stride(from: 0, to: hex.count, by: 2) {
            guard let byte = UInt8(String(hex[index..<(index + 2)]), radix: 16) else { throw Failure.invalidKey }
            key.append(byte)
        }
        guard key.count == 32 else { throw Failure.invalidKey }
        return key
    }
    static func encrypt(_ plaintext: Data, key: Data, context: Context) throws -> Data {
        try seal(plaintext, key: key, context: context, nonce: AES.GCM.Nonce())
    }
    static func seal(_ plaintext: Data, key: Data, context: Context, nonce: AES.GCM.Nonce) throws -> Data {
        guard key.count == 32 else { throw Failure.invalidKey }
        guard !plaintext.isEmpty, plaintext.count <= maxPacketBytes - 28 else { throw Failure.invalidSize }
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: nonce, authenticating: context.aad)
        return nonce.withUnsafeBytes { Data($0) } + box.ciphertext + box.tag
    }
    static func decrypt(_ packet: Data, key: Data, context: Context) throws -> Data {
        guard key.count == 32 else { throw Failure.invalidKey }
        guard (29...maxPacketBytes).contains(packet.count) else { throw Failure.invalidSize }
        let box = try AES.GCM.SealedBox(combined: packet)
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: context.aad)
    }
}

extension HistoryBackupCipher {
    static var platformCrypto: HistoryBackupCipher {
        func context(_ server: String, _ owner: String, _ backup: String) throws -> HistoryBackupCrypto.Context {
            guard let s = UUID(uuidString: server), let u = UUID(uuidString: owner), let b = UUID(uuidString: backup) else {
                throw HistoryBackupCrypto.Failure.invalidKey
            }
            return .init(serverId: s, userId: u, backupId: b)
        }
        return HistoryBackupCipher(
            seal: { try HistoryBackupCrypto.encrypt($0, key: $1, context: context($2, $3, $4)) },
            open: { try HistoryBackupCrypto.decrypt($0, key: $1, context: context($2, $3, $4)) },
            sha256: { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() }
        )
    }
}
