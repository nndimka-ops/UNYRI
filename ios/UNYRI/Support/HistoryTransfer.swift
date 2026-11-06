import Foundation
import CryptoKit

struct HistoryTransferChallenge: Codable, Equatable {
    let v: Int
    let type: String
    let server: String
    let owner: String
    let transfer: String
    let recipient: String
    let expires: Int64
    func validate(server: String, owner: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        guard v == 1, type == "coco-history-transfer", expires <= now + 660_000 else { throw HistoryTransferFailure.invalid }
        for id in [server, owner, transfer] { try HistoryArchive.validateId(id); guard id != "00000000-0000-0000-0000-000000000000" else { throw HistoryTransferFailure.invalid } }
        guard self.server == server else { throw HistoryTransferFailure.wrongServer }
        guard self.owner == owner else { throw HistoryTransferFailure.wrongAccount }
        guard expires > now else { throw HistoryTransferFailure.expired }
        _ = try HistoryTransferCrypto.base64(recipient, count: 32)
    }
    static func decode(_ text: String, server: String, owner: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> Self {
        let bytes = Data(text.utf8)
        guard bytes.count <= 1024,
              let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              Set(object.keys) == Set(["v", "type", "server", "owner", "transfer", "recipient", "expires"]) else { throw HistoryTransferFailure.invalid }
        let value = try JSONDecoder().decode(Self.self, from: bytes)
        try value.validate(server: server, owner: owner, now: now)
        return value
    }
    func encoded() throws -> String { String(decoding: try JSONEncoder().encode(self), as: UTF8.self) }
}
enum HistoryTransferFailure: Error { case invalid, changed, comparisonRequired, wrongServer, wrongAccount, expired, recoveryKeyMissing, deviceApprovalRequired }
struct HistoryTransferRecord: Decodable {
    let transferId: String; let serverId: String; let ownerId: String
    let recipientDeviceId: String; let recipientPublicKey: String; let expiresAtMs: Int64
    let status: String; let senderPublicKey: String?; let backupId: String?; let ciphertext: String?
    var challenge: HistoryTransferChallenge {
        .init(v: 1, type: "coco-history-transfer", server: serverId, owner: ownerId,
              transfer: transferId, recipient: recipientPublicKey, expires: expiresAtMs)
    }
    func pin(_ expected: HistoryTransferChallenge, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        try expected.validate(server: serverId, owner: ownerId, now: now)
        guard challenge == expected, ["waiting", "ready"].contains(status) else { throw HistoryTransferFailure.changed }
    }
}
enum HistoryTransferCrypto {
    static let domain = "COCO-HISTORY-TRANSFER-V1"
    static func base64(_ text: String, count: Int) throws -> Data {
        guard let bytes = Data(base64Encoded: text), bytes.count == count, bytes.base64EncodedString() == text else { throw HistoryTransferFailure.invalid }
        return bytes
    }
    static func aad(_ challenge: HistoryTransferChallenge, sender: String, backup: String) throws -> Data {
        try HistoryArchive.validateId(backup)
        guard backup != "00000000-0000-0000-0000-000000000000" else { throw HistoryTransferFailure.invalid }
        _ = try base64(sender, count: 32)
        return Data([domain, challenge.server, challenge.owner, challenge.transfer, String(challenge.expires),
                     challenge.recipient, sender, backup].joined(separator: "\n").utf8)
    }
    private static func key(_ privateKey: Curve25519.KeyAgreement.PrivateKey, publicKey: String, aad: Data) throws -> SymmetricKey {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: base64(publicKey, count: 32))
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
        guard shared.withUnsafeBytes({ $0.contains { $0 != 0 } }) else { throw HistoryTransferFailure.invalid }
        return shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(SHA256.hash(data: aad)),
            sharedInfo: Data(domain.utf8), outputByteCount: 32)
    }
    static func seal(_ recoveryKey: Data, challenge: HistoryTransferChallenge, backup: String,
                     sender: Curve25519.KeyAgreement.PrivateKey = .init(), nonce: AES.GCM.Nonce = .init()) throws -> (sender: String, ciphertext: String, comparison: String) {
        guard recoveryKey.count == 32 else { throw HistoryTransferFailure.invalid }
        let publicKey = sender.publicKey.rawRepresentation.base64EncodedString()
        let authenticated = try aad(challenge, sender: publicKey, backup: backup)
        let sealed = try AES.GCM.seal(recoveryKey, using: key(sender, publicKey: challenge.recipient, aad: authenticated), nonce: nonce, authenticating: authenticated)
        guard let combined = sealed.combined, combined.count == 60 else { throw HistoryTransferFailure.invalid }
        return (publicKey, combined.base64EncodedString(), comparison(aad: authenticated, combined: combined))
    }
    static func open(_ record: HistoryTransferRecord, challenge: HistoryTransferChallenge,
                     recipient: Curve25519.KeyAgreement.PrivateKey, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> (key: Data, comparison: String) {
        try record.pin(challenge, now: now)
        guard record.status == "ready", recipient.publicKey.rawRepresentation.base64EncodedString() == challenge.recipient,
              let sender = record.senderPublicKey, let backup = record.backupId, let ciphertext = record.ciphertext else { throw HistoryTransferFailure.invalid }
        let authenticated = try aad(challenge, sender: sender, backup: backup)
        let combined = try base64(ciphertext, count: 60)
        let clear = try AES.GCM.open(AES.GCM.SealedBox(combined: combined), using: key(recipient, publicKey: sender, aad: authenticated), authenticating: authenticated)
        guard clear.count == 32 else { throw HistoryTransferFailure.invalid }
        return (clear, comparison(aad: authenticated, combined: combined))
    }
    static func comparison(aad: Data, combined: Data) -> String {
        let hex = SHA256.hash(data: aad + combined).prefix(8).map { String(format: "%02X", $0) }.joined()
        return stride(from: 0, to: 16, by: 4).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            return String(hex[start..<hex.index(start, offsetBy: 4)])
        }.joined(separator: "-")
    }
}
