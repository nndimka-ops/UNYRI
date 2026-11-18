"""Execute the actual iOS transfer model with real CryptoKit/coordinator.

Run on macOS. OS authentication/camera, HTTP relay and device-key storage are
deterministic test adapters. This is not physical QR or Face ID verification.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
ui = (root / "ios/UNYRI/UI/Profile/HistoryTransferView.swift").read_text(encoding="utf-8")
model = ui[ui.index("/// Shared history-tool errors."):ui.index("\nstruct HistoryTransferView: View")]
# The model and crypto remain production source. Only the OS keychain endpoint
# is redirected to separate source/destination test stores.
model = model.replace(
    "HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner)",
    "fixtureKeys(server: state.serverId, owner: context.owner, device: context.device)")
model = model.replace(
    "HistoryRecoveryKeys.deviceStore(server: challenge.server, owner: context.owner)",
    "fixtureKeys(server: challenge.server, owner: context.owner, device: context.device)")
assert "HistoryRecoveryKeys.deviceStore" not in model

fixture = r'''
import Foundation
import Combine
import CryptoKit

private let server = "11111111-1111-4111-8111-111111111111"
private let owner = "22222222-2222-4222-8222-222222222222"
private let peer = "33333333-3333-4333-8333-333333333333"
private let key = Data((0..<32).map(UInt8.init)) // Public synthetic test key.
private func id() -> String { UUID().uuidString.lowercased() }
enum APIError: Error { case invalidURL, network(String), http(Int), missingSession }
struct AttachmentIntegrityError: Error {}
enum HistoryNativeOwner { enum Failure: Error { case incompleteSource, unavailableMedia, changed, insufficientSpace } }
enum HistoryMediaSpool { enum Failure: Error { case integrity } }
enum UnyriL10n { static func string(_ value: String) -> String { value } }
enum ISO8601Cocoa { static func parse(_ value: String) -> Date? { ISO8601DateFormatter().date(from: value) } }
enum LAPolicy { case deviceOwnerAuthentication }
enum LAError {
    static let errorDomain = "com.apple.LocalAuthentication"
    enum Code: Int { case userCancel = -2, userFallback = -3, systemCancel = -4, passcodeNotSet = -5, appCancel = -9 }
}
final class LAContext {
    func invalidate() {}
    func evaluatePolicy(_ policy: LAPolicy, localizedReason: String) async throws -> Bool { true }
}
enum AVMediaType { case video }
enum AVCaptureDevice { static func requestAccess(for type: AVMediaType) async -> Bool { true } }
struct HistoryAutoPreferences {
    init(server: String, owner: String) {}
    var lastSuccess: Double { get { 0 } nonmutating set {} }
}
final class HistoryAccountLease {
    var operation = false
    func begin() throws { guard !operation else { throw HistoryBackupCoordinator.Failure.busy }; operation = true }
    func end() { operation = false }
    func check() throws { try Task.checkCancellation() }
}
final class KeyRows { var values: [String: Data] = [:] }
@MainActor private var stores: [String: KeyRows] = [:]
@MainActor func fixtureKeys(server: String, owner: String, device: String) throws -> HistoryRecoveryKeys {
    let rows = stores[device] ?? KeyRows(); stores[device] = rows
    return try HistoryRecoveryKeys(server: server, owner: owner, read: { rows.values[$0] }, insert: { name, value in
        guard rows.values[name] == nil else { throw HistoryRecoveryKeys.Failure.conflict }; rows.values[name] = value
    }, replace: { rows.values[$0] = $1 })
}
@MainActor final class HistoryBackupAccess { let lease = HistoryAccountLease(); var canUseCamera: () -> Bool = { true } }
@MainActor final class AuthRepository {
    let device: String; let local: Local; let registry: Registry
    var active = true
    init(device: String, local: Local, registry: Registry) { self.device = device; self.local = local; self.registry = registry }
}
@MainActor final class HistoryOperationContext {
    let owner = "22222222-2222-4222-8222-222222222222"
    let device: String; let access: HistoryBackupAccess; let remote: Remote
    let coordinator: HistoryBackupCoordinator?
    let fence: () throws -> Void
    init(auth: AuthRepository, access: HistoryBackupAccess) throws {
        device = auth.device; self.access = access
        remote = Remote(device: auth.device, registry: auth.registry)
        coordinator = HistoryBackupCoordinator(server: server, owner: owner, remote: remote, local: auth.local, cipher: .platformCrypto)
        fence = { try access.lease.check(); guard auth.active else { throw APIError.missingSession } }
    }
    func open() async throws -> HistoryBackupState { try fence(); return try await remote.status() }
    func cleanup() async {}
}
final class Local: HistoryBackupLocal {
    var value: HistoryArchive; var imports = 0
    init(_ value: HistoryArchive) { self.value = value }
    func assertCurrent() async throws {}
    func export(snapshot: String) async throws -> HistoryArchive { var copy = value; copy.snapshot = snapshot; return copy }
    func verifyBackupReady(_ archive: HistoryArchive) async throws {}
    func restoreVerified(_ archive: HistoryArchive) async throws { value = archive; imports += 1 }
}
@MainActor final class Registry {
    var state = HistoryBackupState(serverId: server, revision: 0, desiredEnabled: true, active: nil, staged: nil)
    var object: HistoryBackupObject?
    var record: HistoryTransferRecord?
    var stages = 0; var publications = 0
    var loseAck = false
}
@MainActor final class Remote: HistoryBackupTransport {
    let device: String; let registry: Registry
    init(device: String, registry: Registry) { self.device = device; self.registry = registry }
    func status() async throws -> HistoryBackupState { registry.state }
    func download(_ id: String) async throws -> HistoryBackupObject {
        guard let value = registry.object, value.backupId == id else { throw APIError.http(404) }; return value
    }
    func stage(_ request: HistoryBackupUpload) async throws -> HistoryBackupState {
        let state = registry.state
        guard state.desiredEnabled, state.revision == request.expectedRevision, let bytes = Data(base64Encoded: request.ciphertext) else { throw APIError.http(409) }
        registry.stages += 1
        registry.object = .init(backupId: request.backupId, formatVersion: request.formatVersion, sha256: request.sha256, ciphertext: request.ciphertext)
        registry.state = .init(serverId: server, revision: state.revision + 1, desiredEnabled: true, active: state.active,
            staged: .init(backupId: request.backupId, formatVersion: request.formatVersion, sha256: request.sha256, bytes: bytes.count,
                createdAt: "2026-11-01T12:00:00Z", verifiedAt: nil))
        return registry.state
    }
    func confirm(_ request: HistoryBackupConfirm) async throws -> HistoryBackupState {
        let state = registry.state
        guard state.revision == request.expectedRevision, let pending = state.staged, pending.backupId == request.backupId else { throw APIError.http(409) }
        registry.state = .init(serverId: server, revision: state.revision + 1, desiredEnabled: true,
            active: .init(backupId: pending.backupId, formatVersion: pending.formatVersion, sha256: pending.sha256, bytes: pending.bytes,
                createdAt: pending.createdAt, verifiedAt: "2026-11-01T12:00:01Z"), staged: nil)
        return registry.state
    }
    func preference(_ request: HistoryBackupPreference) async throws -> HistoryBackupState { throw APIError.http(409) }
    func delete(_ request: HistoryBackupDelete) async throws -> HistoryBackupState { throw APIError.http(409) }
    func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase; return try encoder.encode(value)
    }
    func request<T: Decodable>(_ path: String, body: Data? = nil) async throws -> T {
        var value: HistoryTransferRecord
        if path == "/history-transfer", let body {
            let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
            value = .init(transferId: id(), serverId: server, ownerId: owner, recipientDeviceId: device,
                recipientPublicKey: json["recipient_public_key"] as! String, expiresAtMs: Int64(Date().timeIntervalSince1970 * 1000) + 600_000,
                status: "waiting", senderPublicKey: nil, backupId: nil, ciphertext: nil)
        } else {
            guard let current = registry.record, path.contains(current.transferId) else { throw APIError.http(404) }
            value = current
            if path.hasSuffix("/publish"), let body {
                let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
                guard device != current.recipientDeviceId, current.status == "waiting", registry.state.active?.backupId == json["backup_id"] as? String,
                      registry.state.revision == (json["expected_revision"] as? NSNumber)?.int64Value else { throw APIError.http(409) }
                registry.publications += 1
                value = .init(transferId: current.transferId, serverId: server, ownerId: owner, recipientDeviceId: current.recipientDeviceId,
                    recipientPublicKey: current.recipientPublicKey, expiresAtMs: current.expiresAtMs, status: "ready",
                    senderPublicKey: json["sender_public_key"] as? String, backupId: json["backup_id"] as? String, ciphertext: json["ciphertext"] as? String)
            } else if path.hasSuffix("/ack") {
                guard device == current.recipientDeviceId else { throw APIError.http(403) }
                value = .init(transferId: current.transferId, serverId: server, ownerId: owner, recipientDeviceId: current.recipientDeviceId,
                    recipientPublicKey: current.recipientPublicKey, expiresAtMs: current.expiresAtMs, status: "consumed",
                    senderPublicKey: nil, backupId: current.backupId, ciphertext: nil)
                registry.record = value
                if registry.loseAck { throw APIError.network("Synthetic lost acknowledgement") }
            } else if path.hasSuffix("/cancel"), current.status != "consumed" {
                value = .init(transferId: current.transferId, serverId: server, ownerId: owner, recipientDeviceId: current.recipientDeviceId,
                    recipientPublicKey: current.recipientPublicKey, expiresAtMs: current.expiresAtMs, status: "cancelled",
                    senderPublicKey: nil, backupId: current.backupId, ciphertext: nil)
            }
        }
        registry.record = value
        let json: [String: Any] = ["transfer_id": value.transferId, "server_id": value.serverId, "owner_id": value.ownerId,
            "recipient_device_id": value.recipientDeviceId, "recipient_public_key": value.recipientPublicKey, "expires_at_ms": value.expiresAtMs,
            "status": value.status, "sender_public_key": value.senderPublicKey as Any? ?? NSNull(),
            "backup_id": value.backupId as Any? ?? NSNull(), "ciphertext": value.ciphertext as Any? ?? NSNull()]
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: JSONSerialization.data(withJSONObject: json))
    }
}
'''

checks = r'''
@main struct ModelContracts {
    @MainActor static var count = 0
    @MainActor static func check(_ value: Bool, _ description: String) { precondition(value, description); count += 1 }
    @MainActor static func settled(_ model: HistoryTransferModel) async throws {
        let deadline = Date().addingTimeInterval(10)
        while model.busy { precondition(Date() < deadline, "Model did not settle"); try await Task.sleep(nanoseconds: 10_000_000) }
    }
    static func archive() -> HistoryArchive {
        let chat = id(), message = id(), user = HistoryArchivePeer(id: peer, username: "fixture_peer", displayName: "Fixture peer")
        return HistoryArchive(format: 2, server: server, owner: owner, snapshot: id(), messages: [
            .init(client: id(), id: message, chat: chat, sender: owner, sequence: 1, createdAtMs: 1000, content: "{\"text\":\"transfer fixture\"}")],
            reactions: [.init(target: message, user: peer, sequence: 2, emoji: "👍")], media: [],
            chats: [.init(id: chat, type: "direct", createdAtMs: 500, peer: user)], calls: [
                .init(id: id(), caller: owner, callee: peer, mediaKind: "video", createdAtMs: 1100, acceptedAtMs: nil,
                    connectedAtMs: nil, endedAtMs: 1900, endReason: "rejected", chat: chat, chatVisible: true, peer: user)])
    }
    @MainActor static func flow(loseAck: Bool) async throws {
        let original = archive(), registry = Registry(); registry.loseAck = loseAck
        let sourceID = id(), recipientID = id()
        let oldLocal = Local(original), newLocal = Local(HistoryArchive(format: 2, server: server, owner: owner, snapshot: id(), messages: [], media: [], chats: [], calls: []))
        let oldAuth = AuthRepository(device: sourceID, local: oldLocal, registry: registry)
        let newAuth = AuthRepository(device: recipientID, local: newLocal, registry: registry)
        try fixtureKeys(server: server, owner: owner, device: sourceID).saveConfirmed(key)
        let old = try HistoryTransferModel(auth: oldAuth, access: .init()), new = try HistoryTransferModel(auth: newAuth, access: .init())
        defer { old.close(); new.close(); stores.removeValue(forKey: sourceID); stores.removeValue(forKey: recipientID) }
        old.prepareSource(); try await settled(old)
        check(old.error == nil && old.preparedAt != nil && registry.state.active?.verifiedAt != nil, "Source must confirm a real encrypted backup before scanning")
        new.receive(); try await settled(new)
        check(new.error == nil && new.qr != nil, "Recipient must create a valid same-account QR")
        let validQR = try HistoryTransferChallenge.decode(new.qr!, server: server, owner: owner)
        for (field, replacement, expected) in [
            ("owner", "44444444-4444-4444-8444-444444444444" as Any, HistoryTransferFailure.wrongAccount),
            ("server", "55555555-5555-4555-8555-555555555555" as Any, .wrongServer),
            ("expires", NSNumber(value: Int64(Date().timeIntervalSince1970 * 1000) - 1) as Any, .expired)] {
            var payload = try JSONSerialization.jsonObject(with: Data(new.qr!.utf8)) as! [String: Any]
            payload[field] = replacement
            old.scanned(String(decoding: try JSONSerialization.data(withJSONObject: payload), as: UTF8.self)); try await settled(old)
            check(old.error == HistoryOperationErrorPresentation.message(for: expected) && !old.needsSourceApproval && registry.publications == 0,
                  "Wrong account/server/expired code must stop with its own reason before publication")
        }
        check(validQR.owner == owner, "The subsequent valid QR still belongs to this account")
        old.scanned(new.qr!); try await settled(old)
        check(old.error == nil && old.needsSourceApproval && registry.publications == 0, "Scanning alone cannot release the key")
        old.approveSource(); try await settled(old)
        check(old.error == nil && registry.publications == 1, "Explicit OS-approved source publication succeeds")
        let deadline = Date().addingTimeInterval(8)
        while !new.readyToAccept { precondition(Date() < deadline, "Recipient failed to open the transfer"); try await Task.sleep(nanoseconds: 20_000_000) }
        check(old.comparison == new.comparison && old.comparison != nil, "Both phones display the exact same authenticated comparison")
        check(newLocal.imports == 0 && stores[recipientID]?.values.isEmpty != false, "History cannot import before matching-code confirmation")
        new.acceptMatchingCodes(); try await settled(new)
        if loseAck {
            check(new.adopted && !new.completed && new.error != nil, "A lost ACK retains verified key adoption for retry")
            new.restoreAdopted(); try await settled(new)
        }
        check(new.completed && new.error == nil && newLocal.imports == 1, "Actual production model restores after verified key adoption")
        check(newLocal.value.messages == original.messages && newLocal.value.reactions == original.reactions && newLocal.value.calls == original.calls && newLocal.value.chats == original.chats, "Restored messages, reactions, calls and chat metadata match byte-for-byte")
        check(try fixtureKeys(server: server, owner: owner, device: recipientID).load() == key, "Verified recovery key is durable on the recipient")
        check(oldLocal.value == original, "Source history is unchanged")
    }
    @MainActor static func main() async throws {
        try await flow(loseAck: false)
        try await flow(loseAck: true)
        let registry = Registry(), sourceID = id(), local = Local(archive())
        let model = try HistoryTransferModel(auth: AuthRepository(device: sourceID, local: local, registry: registry), access: .init())
        model.prepareSource(); try await settled(model)
        check(model.error == HistoryOperationErrorPresentation.message(for: HistoryTransferFailure.recoveryKeyMissing), "Missing-key preparation gets its actual next step, not a network error")
        check(registry.stages == 0 && stores[sourceID]?.values.isEmpty != false, "Missing key never creates another key or uploads history")
        model.close(); stores.removeValue(forKey: sourceID)
        for (failure, fragment) in [(HistoryTransferFailure.wrongAccount, "different accounts"), (.wrongServer, "another server"), (.expired, "expired")] {
            check(HistoryOperationErrorPresentation.message(for: failure).contains(fragment), "Transcript failures must not be mislabeled as connection problems")
        }
        let missingPasscode = NSError(domain: LAError.errorDomain, code: LAError.Code.passcodeNotSet.rawValue)
        check(HistoryOperationErrorPresentation.message(for: missingPasscode).contains("Set an iPhone passcode"),
              "A missing device passcode must explain the system setup step, not blame the network or recovery key")
        for code: LAError.Code in [.userCancel, .appCancel, .systemCancel, .userFallback] {
            check(HistoryOperationErrorPresentation.message(for: NSError(domain: LAError.errorDomain, code: code.rawValue)).contains("Device confirmation was cancelled"),
                  "Cancelled OS confirmation remains a retryable confirmation explanation")
        }
        let unrelated = NSError(domain: "SyntheticOtherDomain", code: LAError.Code.passcodeNotSet.rawValue)
        check(!HistoryOperationErrorPresentation.message(for: unrelated).contains("Set an iPhone passcode"),
              "An unrelated error with the same numeric code must not be mislabeled as a missing passcode")
        print("PASS: \(count) actual iOS transfer-model checks; real CryptoKit and coordinator, deterministic OS/HTTP/keychain adapters, no physical device claim")
    }
}
'''

with tempfile.TemporaryDirectory(prefix="coco-transfer-model-") as directory:
    folder = Path(directory)
    source = folder / "Model.swift"
    executable = folder / "model-contracts"
    # throwing expressions cannot enter the nonthrowing assertion helper.
    checks = checks.replace(
        'check(try fixtureKeys(server: server, owner: owner, device: recipientID).load() == key,',
        'let durable = try fixtureKeys(server: server, owner: owner, device: recipientID).load(); check(durable == key,')
    source.write_text(fixture + "\n" + model + "\n" + checks, encoding="utf-8")
    sources = ["HistoryArchive.swift", "HistoryBackupCoordinator.swift", "HistoryBackupCrypto.swift", "HistoryRecoveryKeys.swift", "HistoryTransfer.swift"]
    subprocess.run(["swiftc", "-parse-as-library", *[str(root / "ios/UNYRI/Support" / name) for name in sources], str(source), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=40)
