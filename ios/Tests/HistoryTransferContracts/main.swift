import Foundation
import CryptoKit

@main struct HistoryTransferContracts {
    static func rejects(_ action: () throws -> Void) {
        do { try action(); fatalError("Accepted invalid transfer") } catch {}
    }
    static func main() throws {
        struct Vector: Decodable {
            let qr: HistoryTransferChallenge
            let recipientPrivate: String; let senderPrivate: String; let senderPublicKey: String
            let backupId: String; let recoveryKey: String; let nonce: String
            let aad: String; let ciphertext: String; let comparisonCode: String
        }
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let v = try decoder.decode(Vector.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        let now = v.qr.expires - 600_000
        let sender = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(base64Encoded: v.senderPrivate)!)
        let recipient = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(base64Encoded: v.recipientPrivate)!)
        let key = Data(base64Encoded: v.recoveryKey)!
        let sealed = try HistoryTransferCrypto.seal(key, challenge: v.qr, backup: v.backupId, sender: sender,
            nonce: AES.GCM.Nonce(data: Data(base64Encoded: v.nonce)!))
        precondition(sealed.sender == v.senderPublicKey && sealed.ciphertext == v.ciphertext && sealed.comparison == v.comparisonCode)
        let aad = try HistoryTransferCrypto.aad(v.qr, sender: v.senderPublicKey, backup: v.backupId)
        precondition(aad == Data(v.aad.utf8))
        let record = HistoryTransferRecord(transferId: v.qr.transfer, serverId: v.qr.server, ownerId: v.qr.owner,
            recipientDeviceId: v.backupId, recipientPublicKey: v.qr.recipient, expiresAtMs: v.qr.expires,
            status: "ready", senderPublicKey: v.senderPublicKey, backupId: v.backupId, ciphertext: v.ciphertext)
        let opened = try HistoryTransferCrypto.open(record, challenge: v.qr, recipient: recipient, now: now)
        precondition(opened.key == key && opened.comparison == v.comparisonCode)
        let json = try v.qr.encoded()
        _ = try HistoryTransferChallenge.decode(json, server: v.qr.server, owner: v.qr.owner, now: now)
        rejects { _ = try HistoryTransferChallenge.decode(json, server: v.qr.server, owner: v.backupId, now: now) }
        rejects { _ = try HistoryTransferChallenge.decode(json, server: v.qr.server, owner: v.qr.owner, now: v.qr.expires) }
        rejects { _ = try HistoryTransferChallenge.decode(json, server: v.qr.server, owner: v.qr.owner, now: now - 61_000) }
        rejects { _ = try HistoryTransferChallenge.decode(String(json.dropLast()) + ",\"extra\":1}", server: v.qr.server, owner: v.qr.owner, now: now) }
        rejects { _ = try HistoryTransferChallenge.decode(json.replacingOccurrences(of: v.qr.transfer, with: "00000000-0000-0000-0000-000000000000"), server: v.qr.server, owner: v.qr.owner, now: now) }
        rejects { _ = try HistoryTransferCrypto.open(record, challenge: v.qr, recipient: .init(), now: now) }
        var bad = Data(base64Encoded: v.ciphertext)!; bad[20] ^= 1
        let tampered = HistoryTransferRecord(transferId: record.transferId, serverId: record.serverId, ownerId: record.ownerId,
            recipientDeviceId: record.recipientDeviceId, recipientPublicKey: record.recipientPublicKey, expiresAtMs: record.expiresAtMs,
            status: "ready", senderPublicKey: record.senderPublicKey, backupId: record.backupId, ciphertext: bad.base64EncodedString())
        rejects { _ = try HistoryTransferCrypto.open(tampered, challenge: v.qr, recipient: recipient, now: now) }
        print("History transfer CryptoKit contracts passed: shared vector, SAS, AEAD tamper, account/key pins, strict fields, expiry")
    }
}
