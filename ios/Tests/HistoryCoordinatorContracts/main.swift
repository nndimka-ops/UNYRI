import Foundation

private let server = "11111111-1111-4111-8111-111111111111"
private let owner = "22222222-2222-4222-8222-222222222222"
private let key = Data((0..<32).map(UInt8.init))
private func id() -> String { UUID().uuidString.lowercased() }
private func archive() -> HistoryArchive {
    HistoryArchive(server: server, owner: owner, snapshot: id(), messages: [
        HistoryArchiveMessage(client: id(), id: id(), chat: id(), sender: owner, sequence: 1, createdAtMs: 1000, content: "{\"text\":\"hello\"}")
    ])
}
private enum TestFailure: Error { case injected }
#if canImport(CryptoKit)
private let cipher = HistoryBackupCipher.platformCrypto
#else
// ONLY a state-machine test double, not encryption and never in the app target.
// macOS CI uses real CryptoKit via the branch above.
private let cipher = HistoryBackupCipher(
    seal: { data, key, server, owner, id in key + Data((server + owner + id).utf8) + data },
    open: { packet, key, server, owner, id in
        let prefix = key + Data((server + owner + id).utf8)
        guard packet.starts(with: prefix) else { throw TestFailure.injected }
        return Data(packet.dropFirst(prefix.count))
    },
    sha256: { data in
        var h: UInt64 = 14695981039346656037
        for byte in data { h = (h ^ UInt64(byte)) &* 1099511628211 }
        return String(repeating: String(format: "%016llx", h), count: 4)
    })
#endif

private final class Local: HistoryBackupLocal {
    var data = archive()
    var current = true
    var ready = true
    var imports = 0
    var prepare: (HistoryArchive) throws -> HistoryArchive = { $0 }
    func assertCurrent() async throws { guard current else { throw TestFailure.injected } }
    func export(snapshot: String) async throws -> HistoryArchive { var result = data; result.snapshot = snapshot; return result }
    func prepareBackup(_ archive: HistoryArchive) async throws -> HistoryArchive { try prepare(archive) }
    func verifyBackupReady(_ archive: HistoryArchive) async throws { guard ready else { throw TestFailure.injected } }
    func restoreVerified(_ archive: HistoryArchive) async throws { try await assertCurrent(); data = archive; imports += 1 }
}
private final class Remote: HistoryBackupTransport {
    var revision: Int64 = 0
    var enabled = true
    var serverId = server
    var active: HistoryBackupInfo?
    var staged: HistoryBackupInfo?
    var objects: [String: HistoryBackupObject] = [:]
    var stages = 0, confirms = 0, statuses = 0
    var lastStage: HistoryBackupUpload?
    var onDownload: (() -> Void)?
    var onStage: (() -> Void)?
    var onStatus: (() -> Void)?
    var corrupt = false, loseStageReply = false, loseConfirmReply = false
    var state: HistoryBackupState { .init(serverId: serverId, revision: revision, desiredEnabled: enabled, active: active, staged: staged) }
    func seed(_ archive: HistoryArchive) throws {
        let packet = try cipher.seal(archive.encoded(), key, server, owner, archive.snapshot)
        let hash = cipher.sha256(packet)
        objects[archive.snapshot] = .init(backupId: archive.snapshot, formatVersion: archive.format, sha256: hash, ciphertext: packet.base64EncodedString())
        active = .init(backupId: archive.snapshot, formatVersion: archive.format, sha256: hash, bytes: packet.count, createdAt: "date", verifiedAt: "verified")
    }
    func status() async throws -> HistoryBackupState { statuses += 1; onStatus?(); return state }
    func download(_ id: String) async throws -> HistoryBackupObject {
        onDownload?()
        guard let object = objects[id] else { throw TestFailure.injected }
        if corrupt { return .init(backupId: object.backupId, formatVersion: 1, sha256: object.sha256, ciphertext: "AAAA") }
        return object
    }
    func stage(_ request: HistoryBackupUpload) async throws -> HistoryBackupState {
        guard request.expectedRevision == revision, enabled, let packet = Data(base64Encoded: request.ciphertext) else { throw TestFailure.injected }
        stages += 1; revision += 1
        lastStage = request
        objects[request.backupId] = .init(backupId: request.backupId, formatVersion: request.formatVersion, sha256: request.sha256, ciphertext: request.ciphertext)
        staged = .init(backupId: request.backupId, formatVersion: request.formatVersion, sha256: request.sha256, bytes: packet.count, createdAt: "date", verifiedAt: nil)
        onStage?()
        if loseStageReply { throw TestFailure.injected }
        return state
    }
    func confirm(_ request: HistoryBackupConfirm) async throws -> HistoryBackupState {
        guard request.expectedRevision == revision, let staged, staged.backupId == request.backupId, enabled else { throw TestFailure.injected }
        confirms += 1; revision += 1
        active = .init(backupId: staged.backupId, formatVersion: staged.formatVersion, sha256: staged.sha256, bytes: staged.bytes, createdAt: staged.createdAt, verifiedAt: "verified")
        self.staged = nil
        if loseConfirmReply { throw TestFailure.injected }
        return state
    }
    func preference(_ request: HistoryBackupPreference) async throws -> HistoryBackupState {
        guard request.expectedRevision == revision else { throw TestFailure.injected }
        enabled = request.desiredEnabled; revision += 1
        if !enabled { staged = nil }
        return state
    }
    func delete(_ request: HistoryBackupDelete) async throws -> HistoryBackupState {
        guard request.expectedRevision == revision else { throw TestFailure.injected }
        revision += 1; enabled = false; active = nil; staged = nil; objects.removeAll()
        return state
    }
}
private func coordinator(_ remote: Remote, _ local: Local) -> HistoryBackupCoordinator {
    .init(server: server, owner: owner, remote: remote, local: local, cipher: cipher)
}
private func rejects(_ block: () async throws -> Void) async {
    do { try await block(); fatalError("Expected rejection") } catch {}
}
@main struct Main {
    static func main() async throws {
        do {
            let r = Remote(), l = Local(); let expected = l.data.messages
            let result = try await coordinator(r, l).backup(recoveryKey: key)
            precondition(result == r.active && r.confirms == 1)
            l.data.messages = []
            _ = try await coordinator(r, l).restore(recoveryKey: key)
            precondition(l.data.messages == expected && l.imports == 1)
        }
        do {
            let r = Remote(), l = Local(); let old = archive(); try r.seed(old); l.data.messages = []
            _ = try await coordinator(r, l).backup(recoveryKey: key)
            _ = try await coordinator(r, l).restore(recoveryKey: key)
            precondition(l.data.messages == old.messages)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: Data(repeating: 9, count: 32)) }
            precondition(r.active == old && r.stages == 0)
        }
        do {
            let r = Remote(), l = Local(); l.ready = false
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.stages == 0)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active
            r.onStage = { r.corrupt = true }
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.confirms == 0 && r.active == old)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); r.onDownload = { l.current = false }
            await rejects { _ = try await coordinator(r, l).restore(recoveryKey: key) }
            precondition(l.imports == 0)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active; r.onStage = { l.current = false }
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.confirms == 0 && r.active == old)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active; r.loseStageReply = true
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.active == old)
            r.loseStageReply = false
            _ = try await coordinator(r, l).backup(recoveryKey: key)
            precondition(r.confirms == 1)
        }
        do {
            let r = Remote(), l = Local(); r.loseConfirmReply = true
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.active != nil)
            r.loseConfirmReply = false
            _ = try await coordinator(r, l).backup(recoveryKey: key)
            _ = try await coordinator(r, l).restore(recoveryKey: key)
            precondition(l.data.messages.count == 1)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive())
            r.onStatus = { if r.statuses == 2 { r.revision += 1 } }
            await rejects { _ = try await coordinator(r, l).restore(recoveryKey: key) }
            precondition(l.imports == 0)
        }
        do {
            let r = Remote(), l = Local(); r.enabled = false
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.stages == 0)
        }
        do {
            let r = Remote(), l = Local(); r.serverId = id()
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.stages == 0)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active
            r.onStage = { l.ready = false }
            await rejects { _ = try await coordinator(r, l).backup(recoveryKey: key) }
            precondition(r.confirms == 0 && r.active == old)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active
            let c = coordinator(r, l)
            _ = try await c.setEnabled(expectedRevision: 0, enabled: false)
            precondition(r.active == old)
            _ = try await c.restore(recoveryKey: key)
            await rejects { _ = try await c.backup(recoveryKey: key) }
            _ = try await c.deleteConfirmed(expectedRevision: 1)
            precondition(r.active == nil && !r.enabled && l.imports == 1)
        }
        do {
            let r = Remote(), l = Local(); let c = coordinator(r, l)
            let state = try await c.status()
            _ = try await c.backup(recoveryKey: key); let old = r.active
            await rejects { _ = try await c.deleteConfirmed(expectedRevision: state.revision) }
            precondition(r.active == old)
        }
        do {
            let r = Remote(), l = Local(); try r.seed(archive()); let old = r.active
            let c = coordinator(r, l)
            let result = try await c.verifyRecoveryKey(key)
            precondition(result == old && l.imports == 0 && r.stages == 0 && r.confirms == 0)
            await rejects { _ = try await c.verifyRecoveryKey(Data(repeating: 9, count: 32)) }
            precondition(l.imports == 0 && r.active == old)
        }
        do {
            let r = Remote(), l = Local()
            l.prepare = { source in
                var result = source; result.format = 2; result.media = []
                result.chats = source.messages.map { HistoryArchiveChat(id: $0.chat, type: "saved", createdAtMs: 1000, peer: nil) }
                return result
            }
            _ = try await coordinator(r,l).backup(recoveryKey: key)
            precondition(r.lastStage?.formatVersion == 2 && r.lastStage?.mediaIds.isEmpty == true)
            let receiver = Local(); receiver.data.messages = []
            _ = try await coordinator(r,receiver).restore(recoveryKey: key)
            precondition(receiver.data.format == 2 && receiver.data.messages == l.data.messages && receiver.data.chats?.isEmpty == false)
        }
        do {
            let r = Remote(), l = Local()
            l.prepare = { source in var changed = source; changed.messages = []; return changed }
            await rejects { _ = try await coordinator(r,l).backup(recoveryKey: key) }
            precondition(r.stages == 0)
            var full = l.data; full.format = 2; full.media = []
            full.chats = full.messages.map { HistoryArchiveChat(id: $0.chat, type: "saved", createdAtMs: 1000, peer: nil) }
            try r.seed(full); l.prepare = { $0.withoutMedia() }
            await rejects { _ = try await coordinator(r,l).backup(recoveryKey: key) }
            precondition(r.stages == 0)
        }
        for field in 0..<4 {
            let r = Remote(), l = Local()
            l.data.format = 2; l.data.media = []
            l.data.chats = l.data.messages.map { HistoryArchiveChat(id: $0.chat, type: "saved", createdAtMs: 1000, peer: nil) }
            l.data.calls = [HistoryArchiveCall(id: id(), caller: owner, callee: id(), mediaKind: "audio", createdAtMs: 1000,
                acceptedAtMs: nil, connectedAtMs: nil, endedAtMs: 2000, endReason: "missed", chat: nil, chatVisible: false, peer: nil)]
            l.data.callDeletedIds = [id()]; l.data.callClearedBeforeMs = 500
            l.data.callChatClears = [id(): 600]
            l.prepare = { source in
                var changed = source
                switch field {
                case 0: changed.calls = nil
                case 1: changed.callDeletedIds = nil
                case 2: changed.callClearedBeforeMs = nil
                default: changed.callChatClears = nil
                }
                return changed
            }
            await rejects { _ = try await coordinator(r,l).backup(recoveryKey: key) }
            precondition(r.stages == 0, "Media preparation must preserve calls and call deletion policy")
        }
        print("History coordinator contracts passed (22 scenarios)")
    }
}
