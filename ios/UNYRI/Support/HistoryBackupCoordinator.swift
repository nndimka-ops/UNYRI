import Foundation

/// Retry transport failures without discarding a live transfer's private key.
/// Malformed transcripts, authorization failures and expired requests never retry.
enum HistoryTransferRetryPolicy {
    static func delay(status: Int?, networkFailure: Bool, attempt: Int, now: Int64, expires: Int64) -> UInt64? {
        guard now < expires, networkFailure || status == 429 || status.map({ (500...599).contains($0) }) == true else { return nil }
        let seconds: Int64 = status == 429 ? 30 : min(30, 3 * (1 << min(4, max(0, attempt))))
        return UInt64(min(seconds * 1000, expires - now)) * 1_000_000
    }
}

struct HistoryBackupInfo: Codable, Equatable {
    let backupId: String
    let formatVersion: Int
    let sha256: String
    let bytes: Int
    let createdAt: String
    let verifiedAt: String?
}
struct HistoryBackupState: Codable {
    let serverId: String
    let revision: Int64
    let desiredEnabled: Bool
    let active: HistoryBackupInfo?
    let staged: HistoryBackupInfo?
}
struct HistoryBackupObject: Codable {
    let backupId: String
    let formatVersion: Int
    let sha256: String
    let ciphertext: String
}
struct HistoryBackupUpload: Encodable {
    let expectedRevision: Int64
    let backupId: String
    var formatVersion: Int = 1
    let ciphertext: String
    let sha256: String
    var mediaIds: [String] = []
}
struct HistoryBackupConfirm: Encodable {
    let expectedRevision: Int64
    let backupId: String
    let sha256: String
}
struct HistoryBackupPreference: Encodable { let expectedRevision: Int64; let desiredEnabled: Bool }
struct HistoryBackupDelete: Encodable { let expectedRevision: Int64 }
protocol HistoryBackupTransport {
    func status() async throws -> HistoryBackupState
    func download(_ id: String) async throws -> HistoryBackupObject
    func stage(_ request: HistoryBackupUpload) async throws -> HistoryBackupState
    func confirm(_ request: HistoryBackupConfirm) async throws -> HistoryBackupState
    func preference(_ request: HistoryBackupPreference) async throws -> HistoryBackupState
    func delete(_ request: HistoryBackupDelete) async throws -> HistoryBackupState
}
/// The native implementation must verify source completeness/durable media and
/// fence the final transaction. Raw MessageStore import alone is insufficient.
protocol HistoryBackupLocal {
    func assertCurrent() async throws
    func export(snapshot: String) async throws -> HistoryArchive
    func prepareBackup(_ archive: HistoryArchive) async throws -> HistoryArchive
    /// Recheck source revision and durable media before upload AND confirmation.
    func verifyBackupReady(_ archive: HistoryArchive) async throws
    func restoreVerified(_ archive: HistoryArchive) async throws
}
extension HistoryBackupLocal {
    func prepareBackup(_ archive: HistoryArchive) async throws -> HistoryArchive { archive }
}
/// Injectable only so state-machine contracts run without CryptoKit on Windows.
/// Production uses platformCrypto; never select a cipher from server input.
struct HistoryBackupCipher {
    let seal: (Data, Data, String, String, String) throws -> Data
    let open: (Data, Data, String, String, String) throws -> Data
    let sha256: (Data) -> String
}

/// Account-scoped actor keeps encoding/crypto off the main UI actor. Re-entrancy
/// is explicitly rejected while an operation is suspended on network/disk I/O.
actor HistoryBackupCoordinator {
    enum Failure: Error { case busy, invalidResponse, disabled, noBackup, changed, unsupportedServer }
    private let server: String
    private let owner: String
    private let remote: HistoryBackupTransport
    private let local: HistoryBackupLocal
    private let cipher: HistoryBackupCipher
    private var running = false
    private static let maxPacket = 8 * 1024 * 1024

    init(server: String, owner: String, remote: HistoryBackupTransport, local: HistoryBackupLocal, cipher: HistoryBackupCipher) {
        self.server = server; self.owner = owner; self.remote = remote; self.local = local; self.cipher = cipher
    }
    func status() async throws -> HistoryBackupState {
        guard !running else { throw Failure.busy }
        running = true; defer { running = false }
        try await fence()
        let value = try await remote.status()
        try validate(value); try await fence()
        return value
    }
    /// Key setup does not import messages or replace a server copy.
    func verifyRecoveryKey(_ key: Data) async throws -> HistoryBackupInfo {
        guard !running else { throw Failure.busy }
        running = true; defer { running = false }
        guard key.count == 32 else { throw Failure.invalidResponse }
        try await fence()
        let state = try await remote.status(); try validate(state)
        guard let info = state.active else { throw Failure.noBackup }
        _ = try await read(info, key: key)
        let latest = try await remote.status(); try validate(latest); try await fence()
        guard latest.revision == state.revision, latest.active == info else { throw Failure.changed }
        return info
    }
    /// Pause future copying without erasing the last restorable copy.
    func setEnabled(expectedRevision: Int64, enabled: Bool) async throws -> HistoryBackupState {
        guard !running else { throw Failure.busy }
        running = true; defer { running = false }
        try await fence()
        let before = try await remote.status(); try validate(before)
        guard before.revision == expectedRevision else { throw Failure.changed }
        try await fence()
        let result = try await remote.preference(.init(expectedRevision: expectedRevision, desiredEnabled: enabled))
        try validate(result); try await fence()
        guard result.revision > expectedRevision, result.desiredEnabled == enabled,
              result.active == before.active, enabled || result.staged == nil else { throw Failure.invalidResponse }
        return result
    }
    /// Revision must come from the explicit deletion confirmation shown to the
    /// owner; the server rejects it if another phone replaced the copy meanwhile.
    func deleteConfirmed(expectedRevision: Int64) async throws -> HistoryBackupState {
        guard !running else { throw Failure.busy }
        running = true; defer { running = false }
        guard expectedRevision >= 0 else { throw Failure.invalidResponse }
        try await fence()
        let before = try await remote.status(); try validate(before)
        guard before.revision == expectedRevision else { throw Failure.changed }
        try await fence()
        let result = try await remote.delete(.init(expectedRevision: expectedRevision))
        try validate(result); try await fence()
        guard result.revision > expectedRevision, !result.desiredEnabled,
              result.active == nil, result.staged == nil else { throw Failure.invalidResponse }
        return result
    }
    func backup(recoveryKey: Data) async throws -> HistoryBackupInfo {
        guard !running else { throw Failure.busy }
        running = true
        defer { running = false }
        guard recoveryKey.count == 32 else { throw Failure.invalidResponse }
        try await fence()
        let state = try await remote.status(); try validate(state)
        try await fence()
        guard state.desiredEnabled else { throw Failure.disabled }
        var previous: HistoryArchive?
        if let info = state.active { previous = try await read(info, key: recoveryKey) }
        let id = UUID().uuidString.lowercased()
        var archive = try await local.export(snapshot: id)
        try await fence(); try archive.validate(server: server, owner: owner)
        guard archive.snapshot == id else { throw Failure.invalidResponse }
        if let previous { archive = try HistoryArchiveMerge.merge(local: archive, restored: previous) }
        archive.snapshot = id
        let merged = archive
        archive = try await local.prepareBackup(merged)
        try await fence(); try archive.validate(server: server, owner: owner)
        guard archive.format >= merged.format, archive.withoutMedia() == merged.withoutMedia(),
              archive.calls == merged.calls, archive.callDeletedIds == merged.callDeletedIds,
              archive.callClearedBeforeMs == merged.callClearedBeforeMs,
              archive.callChatClears == merged.callChatClears else { throw Failure.invalidResponse }
        if archive.format == 2 { try archive.validateBackupChatDescriptors() }
        try await local.verifyBackupReady(archive)
        try await fence()
        let packet = try cipher.seal(archive.encoded(), recoveryKey, server, owner, id)
        guard (29...Self.maxPacket).contains(packet.count) else { throw Failure.invalidResponse }
        let digest = cipher.sha256(packet)
        let staged = try await remote.stage(HistoryBackupUpload(expectedRevision: state.revision, backupId: id, formatVersion: archive.format,
            ciphertext: packet.base64EncodedString(), sha256: digest, mediaIds: archive.media?.map { $0.blob } ?? []))
        try validate(staged); try await fence()
        guard staged.desiredEnabled, staged.revision > state.revision, let info = staged.staged,
              info.backupId == id, info.sha256 == digest, info.bytes == packet.count else { throw Failure.invalidResponse }
        let readBack = try await read(info, key: recoveryKey)
        guard readBack == archive else { throw Failure.invalidResponse }
        try await local.verifyBackupReady(archive)
        try await fence()
        let confirmed = try await remote.confirm(HistoryBackupConfirm(expectedRevision: staged.revision, backupId: id, sha256: digest))
        try validate(confirmed); try await fence()
        guard confirmed.desiredEnabled, confirmed.revision > staged.revision, let active = confirmed.active,
              active.backupId == id, active.sha256 == digest, active.bytes == packet.count,
              active.verifiedAt != nil else { throw Failure.invalidResponse }
        return active
    }
    func restore(recoveryKey: Data) async throws -> HistoryBackupInfo {
        guard !running else { throw Failure.busy }
        running = true
        defer { running = false }
        guard recoveryKey.count == 32 else { throw Failure.invalidResponse }
        try await fence()
        let state = try await remote.status(); try validate(state)
        try await fence()
        guard let info = state.active else { throw Failure.noBackup }
        let archive = try await read(info, key: recoveryKey)
        let latest = try await remote.status(); try validate(latest)
        try await fence()
        guard latest.revision == state.revision, latest.active == info else { throw Failure.changed }
        try await local.restoreVerified(archive)
        try await fence()
        return info
    }
    private func read(_ info: HistoryBackupInfo, key: Data) async throws -> HistoryArchive {
        try validateInfo(info); try await fence()
        let object = try await remote.download(info.backupId)
        try await fence()
        guard object.backupId == info.backupId, object.formatVersion == info.formatVersion, object.sha256 == info.sha256,
              object.ciphertext.utf8.count <= ((Self.maxPacket + 2) / 3) * 4,
              let packet = Data(base64Encoded: object.ciphertext), packet.count == info.bytes,
              cipher.sha256(packet) == info.sha256 else { throw Failure.invalidResponse }
        let plain = try cipher.open(packet, key, server, owner, info.backupId)
        let archive = try HistoryArchive.decode(plain, server: server, owner: owner)
        guard archive.snapshot == info.backupId, archive.format == info.formatVersion else { throw Failure.invalidResponse }
        return archive
    }
    private func fence() async throws { try Task.checkCancellation(); try await local.assertCurrent() }
    private func validate(_ state: HistoryBackupState) throws {
        try HistoryArchive.validateId(server); try HistoryArchive.validateId(owner)
        guard state.serverId == server, state.revision >= 0 else { throw Failure.invalidResponse }
        if let active = state.active { try validateInfo(active); guard active.verifiedAt != nil else { throw Failure.invalidResponse } }
        if let staged = state.staged { try validateInfo(staged); guard staged.verifiedAt == nil else { throw Failure.invalidResponse } }
        if let active = state.active, active.backupId == state.staged?.backupId { throw Failure.invalidResponse }
    }
    private func validateInfo(_ info: HistoryBackupInfo) throws {
        try HistoryArchive.validateId(info.backupId)
        guard (1...2).contains(info.formatVersion), (29...Self.maxPacket).contains(info.bytes),
              info.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw Failure.invalidResponse }
    }
}
