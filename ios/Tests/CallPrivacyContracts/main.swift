import Foundation

// Transport and projection doubles only. Production repository, archive and
// atomic call-history store are compiled unchanged into this executable.
enum APIError: Error { case http(Int), missingSession, server(code: String, message: String) }
struct HistoryContext { let beforeDate: Date }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var historyContext: HistoryContext? = HistoryContext(beforeDate: Date(timeIntervalSince1970: 10))
    private var screens: [String: Any] = [:]
    func cachedScreen<T: Decodable>(_ key: String, as type: T.Type) -> T? { screens[key] as? T }
    func saveScreen<T: Encodable>(_ key: String, generation: UInt64, value: T) { if generation == sessionGeneration { screens[key] = value } }
    func currentAccessToken() async throws -> String { "test-only" }
}
final class APIClient {
    var items: [CallDTO] = []
    var deleted = Set<UUID>()
    var requestedOldRead = false
    func get<T: Decodable>(_ path: String, accessToken: String, query: [URLQueryItem] = [], as type: T.Type) async throws -> T {
        if path == "/calls/history" { return CallHistoryPage(items: items, nextCursor: nil, historyRestored: true, visibleSince: Date(timeIntervalSince1970: 10)) as! T }
        if path == "/calls/history/deletions" { return CallDeletionPage(items: [], hasMore: false) as! T }
        throw APIError.http(404)
    }
    func post<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as type: T.Type) async throws -> T {
        let object: [String: Any]
        if path == "/history-backup/restore-check" { object = ["deletedCallIds": deleted.map { $0.uuidString }] }
        else if path.hasSuffix("/read") { requestedOldRead = true; throw APIError.http(404) }
        else { object = ["ok": true] }
        return try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: object))
    }
}
struct PendingCallDeletion { let callId: String; let everyone: Bool }
final class MessageStore {
    var deleted = Set<String>()
    func suspendCallActivity(owner: String) throws {}
    func clearCallActivity(before: Date) throws {}
    func clearCallActivity(chat: String, before: Date) throws {}
    func exportCallChatClears() throws -> [String: Int64] { [:] }
    func isChatDeleted(_ id: String) throws -> Bool { false }
    func callChatCutoff(_ id: String) throws -> Date { .distantPast }
    func callVisible(owner: String, id: String) throws -> Bool { true }
    func callUnread(owner: String, id: String) throws -> Bool { false }
    func markCallsViewed(owner: String, ids: Set<String>) throws {}
    func pendingCallReads(owner: String, since: Date) throws -> [String] { [] }
    func completeCallRead(owner: String, id: String) throws {}
    func removeCallActivity(owner: String, ids: Set<String>) throws {}
    func callDeletionIDs(owner: String, server: String) throws -> Set<String> { deleted }
    func pendingCallDeletions(owner: String, server: String) throws -> [PendingCallDeletion] { [] }
    func completeCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws {}
    func enqueueCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws { deleted.insert(id) }
}

func id(_ n: Int) -> UUID { UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", n))! }
func call(_ n: Int, time: TimeInterval) -> CallDTO {
    CallDTO(id: id(n), callerUserId: id(2), calleeUserId: id(3), mediaKind: .audio, state: "ended",
        endReason: "missed", acceptedDeviceId: nil, createdAt: Date(timeIntervalSince1970: time),
        acceptedAt: nil, connectedAt: nil, endedAt: Date(timeIntervalSince1970: time + 1))
}

@main struct CallPrivacyContracts {
    @MainActor static func main() async throws {
        precondition(CallArchiveClock.milliseconds(Date(timeIntervalSince1970: 0.0019)) == 1,
            "Call timestamps must floor fractional milliseconds like Android")
        precondition(CallArchiveClock.milliseconds(Date(timeIntervalSince1970: 1_791_664_200.0019)) == 1_791_664_200_001)
        for base: Int64 in [0, 1_791_664_200_000] {
            for offset: Int64 in 0..<2000 {
                let ms = base + offset
                let date = Date(timeIntervalSince1970: Double(ms) / 1000)
                precondition(CallArchiveClock.milliseconds(date) == ms, "Integer archive milliseconds must roundtrip without Date noise")
                let fractional = Date(timeIntervalSince1970: Double(ms) / 1000 + 0.000999)
                precondition(CallArchiveClock.milliseconds(fractional) == ms, "True sub-ms fraction must not round up")
            }
        }
        let fractionalCall = call(20, time: 1_791_664_200.0019)
        let fractionalArchive = fractionalCall.archived(owner: id(2))!
        precondition(fractionalArchive.createdAtMs == 1_791_664_200_001)
        let fractionalRestored = try CallDTO(restored: fractionalArchive)
        precondition(fractionalRestored.archived(owner: id(2)) == fractionalArchive,
            "Cross-platform call archive time must remain stable after import/export")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("coco-call-privacy-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("calls.json")
        let old = call(4, time: 1), new = call(5, time: 12), unverified = call(6, time: 2)
        let store = CallHistoryStore(file: file)
        _ = try await store.merge([old, new, unverified])
        let auth = AuthRepository(), api = APIClient(), messageStore = MessageStore()
        api.items = [old, new, unverified]
        let repository = CallsRepository(api: CallsAPI(client: api), auth: auth, historyFile: file,
            ownerId: id(2), deletionServerOrigin: "test-server")
        repository.messageStore = messageStore
        await repository.loadCachedHistory()
        precondition(repository.history == [new], "Old cache must not grant visibility after password login")
        _ = try await repository.refreshHistory()
        precondition(repository.history == [new], "Server restore boolean must not grant old calls")
        var backup = HistoryArchive(server: id(1).uuidString.lowercased(), owner: id(2).uuidString.lowercased(), snapshot: id(9).uuidString.lowercased(), messages: [])
        backup.format = 2; backup.media = []; backup.chats = []; backup.calls = [old.archived(owner: id(2))!]
        try await repository.restoreBackupCalls(backup)
        _ = try await repository.refreshHistory()
        precondition(repository.history.map(\.id) == [new.id, old.id], "Explicit imported call survives refresh, unrelated old row remains hidden")
        let reopened = CallsRepository(api: CallsAPI(client: api), auth: auth, historyFile: file, ownerId: id(2), deletionServerOrigin: "test-server")
        reopened.messageStore = messageStore
        await reopened.loadCachedHistory()
        precondition(reopened.history.map(\.id) == [new.id, old.id], "Atomic authorization survives restart")
        let export = try await reopened.exportBackupCalls(HistoryArchive(server: backup.server, owner: backup.owner, snapshot: backup.snapshot, messages: []))
        precondition(Set(export.calls?.map(\.id) ?? []) == Set([old.id, new.id].map { $0.uuidString.lowercased() }))
        api.deleted = [old.id]
        _ = try await reopened.refreshHistory()
        precondition(reopened.history.map(\.id) == [new.id], "Cross-device deleted restored call is removed")
        try await reopened.restoreBackupCalls(backup)
        precondition(reopened.history.map(\.id) == [new.id], "Restoring stale backup cannot undo deletion")
        auth.sessionGeneration += 1
        do { _ = try await reopened.exportBackupCalls(backup); fatalError("Retired repository exported data") } catch is CancellationError {}
        print("Call privacy contracts passed: login floor, forged restore boolean, explicit restore, restart, server tombstones, replay, account retirement")
    }
}
