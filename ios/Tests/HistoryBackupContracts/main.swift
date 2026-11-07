import Foundation
import CryptoKit

func expectFailure(_ action: () throws -> Void) {
    do { try action(); fatalError("Expected authentication/validation failure") } catch { }
}
let key = Data((0..<32).map(UInt8.init))
let context = HistoryBackupCrypto.Context(
    serverId: UUID(uuidString: "11111111-1111-4111-8111-111111111111")!,
    userId: UUID(uuidString: "22222222-2222-4222-8222-222222222222")!,
    backupId: UUID(uuidString: "33333333-3333-4333-8333-333333333333")!)
let plaintext = Data("{\"format\":1,\"messages\":[]}".utf8)
let hex = Array("000102030405060708090a0b3c20b074b788a36faf7ba6a793841d1ef0b7e05183596527651ac3ed4f26b5fb3e91ea1fe511cc67e0ec")
let packet = Data(stride(from: 0, to: hex.count, by: 2).map { UInt8(String(hex[$0..<($0+2)]), radix: 16)! })
let sealed = try HistoryBackupCrypto.seal(plaintext, key: key, context: context, nonce: AES.GCM.Nonce(data: Data((0..<12).map(UInt8.init))))
precondition(sealed == packet)
let opened = try HistoryBackupCrypto.decrypt(packet, key: key, context: context)
precondition(opened == plaintext)
for index in packet.indices {
    var damaged = packet; damaged[index] ^= 1
    expectFailure { _ = try HistoryBackupCrypto.decrypt(damaged, key: key, context: context) }
}
for changed in [
    HistoryBackupCrypto.Context(serverId: UUID(), userId: context.userId, backupId: context.backupId),
    HistoryBackupCrypto.Context(serverId: context.serverId, userId: UUID(), backupId: context.backupId),
    HistoryBackupCrypto.Context(serverId: context.serverId, userId: context.userId, backupId: UUID())
] { expectFailure { _ = try HistoryBackupCrypto.decrypt(packet, key: key, context: changed) } }
expectFailure { _ = try HistoryBackupCrypto.decrypt(packet, key: Data(repeating: 9, count: 32), context: context) }
expectFailure { _ = try HistoryBackupCrypto.decrypt(Data(packet.prefix(28)), key: key, context: context) }
let encoded = try HistoryBackupCrypto.formatRecoveryKey(key)
let parsed = try HistoryBackupCrypto.parseRecoveryKey(encoded)
precondition(parsed == key)
expectFailure { _ = try HistoryBackupCrypto.parseRecoveryKey("123456") }
let randomA = try HistoryBackupCrypto.encrypt(plaintext, key: key, context: context)
let randomB = try HistoryBackupCrypto.encrypt(plaintext, key: key, context: context)
precondition(randomA != randomB)
let randomOpened = try HistoryBackupCrypto.decrypt(randomA, key: key, context: context)
precondition(randomOpened == plaintext)
print("PASS: history AES-GCM cross-platform fixture, mutation rejection, account/server/backup binding, key format and fresh nonces")
