"""Exercise production archive repository/queue/API with in-memory adapters.

Uses no real accounts, devices, network addresses or persistent user settings.
The production local history visibility policy is tested separately below.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
repository = (root / "ios/UNYRI/Chats/ChatsRepository.swift").read_text(encoding="utf-8")
prefix = repository[repository.index("    private let api:"):repository.index("    /// Best-effort local chat-list cache")]
archive = repository[repository.index("    // MARK: - Account-scoped durable archive preferences"):repository.index("    // MARK: - End archive preferences")]
api = (root / "ios/UNYRI/Networking/ChatsAPI.swift").read_text(encoding="utf-8")
api = api[api.index("    struct Archives:"):api.index("    struct DeleteBody:")]
view = (root / "ios/UNYRI/UI/Chats/ChatsListView.swift").read_text(encoding="utf-8")
policy = view[view.index("enum ChatListArchivePolicy {"):]

support = r'''
import Foundation
@propertyWrapper struct Published<Value> { var wrappedValue: Value }
enum APIError: Error { case http(Int); case server(code: String, message: String); case network(String) }
enum UnyriL10n { static func string(_ value: String) -> String { value } }
@MainActor enum AppConfig { static var storageOrigin = "synthetic-server-A" }
struct User { var id: String }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var currentUser: User? = User(id: "owner")
}
struct Peer { var id = "peer" }
struct ChatSummary {
    var chatId: String; var chatType = "direct"; var otherUser: Peer? = Peer()
    var isMuted: Bool? = true; var mutedUntil: String?; var isBlocked: Bool?; var blockedUserId: String?
}
@MainActor final class MessageStore {
    var values: [String: [String: String]] = [:]
    var failWrites = false
    func workspaceValues(chatId: String) throws -> [String: String] { values[chatId] ?? [:] }
    func chatValue(chatId: String, name: String) throws -> String? { values[chatId]?[name] }
    func saveChatValue(chatId: String, name: String, value: String?) throws {
        if failWrites { throw APIError.http(507) }
        var row = values[chatId] ?? [:]; row[name] = value; values[chatId] = row
    }
}
@MainActor final class ChatStore { func upsertChats(_ rows: [ChatSummary]) throws {} }
@MainActor final class Gate {
    var entered = false
    var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class Backend {
    var online = false
    var ids = Set<String>()
    var reads = 0
    var writes: [(Set<String>, Bool)] = []
    var failure: APIError?
    var getGate: Gate?
    var putGate: Gate?
    func get() async throws -> ChatsAPI.Archives {
        reads += 1
        guard online else { throw APIError.network("offline") }
        let result = ids
        if let gate = getGate { getGate = nil; await gate.wait() }
        return .init(chatIds: result.sorted())
    }
    func put(_ targets: Set<String>, archived: Bool) async throws -> ChatsAPI.Archives {
        writes.append((targets, archived))
        guard online else { throw APIError.network("offline") }
        if let gate = putGate { putGate = nil; await gate.wait() }
        if let failure { throw failure }
        if archived { ids.formUnion(targets) } else { ids.subtract(targets) }
        return .init(chatIds: ids.sorted())
    }
}
@MainActor struct APIClient {
    let backend: Backend
    func get<Response: Decodable>(_ path: String, accessToken: String, as: Response.Type) async throws -> Response {
        precondition(path == "/chats/archives" && accessToken == "synthetic-token")
        return try await backend.get() as! Response
    }
    func put<Body: Encodable, Response: Decodable>(_ path: String, accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        precondition(path == "/chats/archives" && accessToken == "synthetic-token")
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let object = try JSONSerialization.jsonObject(with: encoder.encode(body)) as! [String: Any]
        precondition(Set(object.keys) == ["chat_ids", "archived"], "Exact server contract")
        let targets = object["chat_ids"] as! [String]
        precondition(targets == targets.sorted(), "Deterministic wire ordering")
        return try await backend.put(Set(targets), archived: object["archived"] as! Bool) as! Response
    }
}
@MainActor struct ChatsAPI {
    let client: APIClient
    struct Pins { let chatIds: [String] }
    func setPins(accessToken: String, ids: Set<String>, pinned: Bool) async throws -> Pins { .init(chatIds: []) }
    func updatePreference(accessToken: String, chatId: String, action: String) async throws { preconditionFailure("Archive must never toggle mute") }
'''
fixture = r'''
    var messageStore: MessageStore? { didSet { archivedChatIDs = cachedArchives() } }
    var store: ChatStore? = ChatStore()
    var preferenceCache: [String: ChatSummary] = [:]
    var cacheRevision: UInt64 = 0
    var deleted = Set<String>()
    init(auth: AuthRepository, backend: Backend, database: MessageStore, known: [ChatSummary]) {
        self.auth = auth; generation = auth.sessionGeneration
        api = ChatsAPI(client: APIClient(backend: backend)); messageStore = database
        preferenceCache = Dictionary(uniqueKeysWithValues: known.map { ($0.chatId, $0) })
        archivedChatIDs = cachedArchives()
    }
    func isDeleted(_ id: String) -> Bool { deleted.contains(id) }
    func currentToken() async throws -> String { try preferenceIdentity(); return "synthetic-token" }
    func loadCachedChats(includePreviews: Bool = true) -> [ChatSummary] { Array(preferenceCache.values) }
    func settle() async { for _ in 0..<100 { await Task.yield() } }
    var pendingCount: Int { (try? preferenceIntents().count) ?? 0 }
    var archivePendingCount: Int { ((try? preferenceIntents()) ?? [:]).values.filter { $0.kind == "archive" }.count }
}
'''
tests = r'''
@main struct Run {
    @MainActor static var count = 0
    @MainActor static func check(_ value: Bool, _ message: String) { precondition(value, message); count += 1 }
    @MainActor static func awaitGate(_ gate: Gate) async {
        for _ in 0..<1000 { if gate.entered { return }; await Task.yield() }
        preconditionFailure("Expected deterministic request gate")
    }
    @MainActor static func main() async throws {
        let a = "00000000-0000-4000-8000-000000000001"
        let b = "00000000-0000-4000-8000-000000000002"
        let hidden = "00000000-0000-4000-8000-000000000099"
        let rows = [ChatSummary(chatId: a), ChatSummary(chatId: b)]

        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            check(repo.cachedArchives().isEmpty, "Existing accounts default to no archives")
            let local = try await repo.setArchived([a], archived: true)
            check(local == [a] && repo.archivedChatIDs == [a], "Offline archive publishes immediately after durable enqueue")
            await repo.settle()
            check(repo.archivePendingCount == 1 && repo.cachedArchives() == [a], "Network failure retains durable intent and local archive")
            let restarted = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            check(restarted.archivedChatIDs == [a], "Restart recovers pending archive from canonical account DB")
            backend.online = true
            await restarted.replayPreferences(); await restarted.settle()
            check(backend.ids == [a] && restarted.archivePendingCount == 0 && restarted.cachedArchives() == [a], "Successful replay persists server snapshot before removing queue")
            check(restarted.preferenceCache[a]?.isMuted == true, "Archive never changes notification setting")
            backend.ids.insert(hidden)
            _ = try await restarted.archives()
            check(restarted.preferenceCache[hidden] == nil, "Server archive IDs must not hydrate unknown chat metadata")
            check(restarted.preferenceCache.count == 2, "Archive refresh cannot restore absent device history")
            _ = try await restarted.setArchived([a], archived: false)
            await restarted.settle()
            check(!backend.ids.contains(a) && !restarted.cachedArchives().contains(a), "Manual restore removes archive membership")
            check(restarted.preferenceCache[a]?.isMuted == true, "Restore also leaves mute unchanged")
            for invalid in [Set<String>(), [hidden], ["not-a-uuid"]] {
                do { _ = try await restarted.setArchived(invalid, archived: true); preconditionFailure("Invalid/unknown target accepted") }
                catch APIError.http(400) { count += 1 }
            }
        }
        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            backend.online = true; backend.ids = [a]
            let gate = Gate(); backend.getGate = gate
            let read = Task { @MainActor in try await repo.archives() }
            await awaitGate(gate)
            _ = try await repo.setArchived([b], archived: true)
            await repo.settle()
            gate.open(); _ = try await read.value
            check(repo.cachedArchives().contains(b), "Stale GET cannot overwrite local/replayed change made while awaiting it")
        }
        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            backend.online = true
            let gate = Gate(); backend.putGate = gate
            _ = try await repo.setArchived([a], archived: true)
            await awaitGate(gate)
            _ = try await repo.setArchived([a], archived: false)
            gate.open(); await repo.settle()
            check(!repo.cachedArchives().contains(a) && !backend.ids.contains(a), "A newer restore survives an older archive response")
            check(repo.archivePendingCount == 0 && backend.writes.count == 2, "Coalesced worker replays replacement intent without reconnect")
        }
        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            backend.online = true; backend.failure = .server(code: "not_found", message: "unsupported route")
            _ = try await repo.setArchived([a], archived: true); await repo.settle()
            check(repo.archivePendingCount == 1 && repo.cachedArchives() == [a], "Older unsupported endpoint retains offline archive intent")
            backend.failure = .server(code: "chat_not_found", message: "not a current member")
            await repo.replayPreferences()
            check(repo.archivePendingCount == 0 && repo.cachedArchives().isEmpty && repo.archiveReplayError != nil, "Typed membership rejection removes only stale archive change and reports it")
        }
        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            db.failWrites = true
            do { _ = try await repo.setArchived([a], archived: true); preconditionFailure("Disk failure accepted") }
            catch APIError.http(507) { count += 1 }
            check(repo.archivedChatIDs.isEmpty, "Failed durable enqueue never claims archive success")
        }
        do {
            let auth = AuthRepository(), backend = Backend(), db = MessageStore()
            let repo = ChatsRepository(auth: auth, backend: backend, database: db, known: rows)
            _ = try await repo.setArchived([a], archived: true); await repo.settle()
            AppConfig.storageOrigin = "synthetic-server-B"
            check(repo.cachedArchives().isEmpty, "Archive cache is rejected after server change")
            do { _ = try await repo.setArchived([b], archived: true); preconditionFailure("Stale origin write accepted") }
            catch is CancellationError { count += 1 }
            let otherOrigin = ChatsRepository(auth: auth, backend: backend, database: MessageStore(), known: [])
            check(otherOrigin.cachedArchives().isEmpty && otherOrigin.preferenceCache.isEmpty, "Another origin/empty device cannot inherit archive metadata or history")
            AppConfig.storageOrigin = "synthetic-server-A"
            auth.sessionGeneration += 1
            check(repo.cachedArchives().isEmpty, "Old session cannot expose archive after logout/login")
            do { _ = try await repo.archives(); preconditionFailure("Stale session GET accepted") }
            catch is CancellationError { count += 1 }
        }
        check(ChatListArchivePolicy.includes(chatID: a, type: "direct", archived: [a], showingArchive: true), "Known archived direct chat appears in archive")
        check(!ChatListArchivePolicy.includes(chatID: a, type: "direct", archived: [a], showingArchive: false), "Archived direct chat stays out of main list even after new messages")
        check(!ChatListArchivePolicy.includes(chatID: a, type: "saved", archived: [a], showingArchive: true), "Saved cannot appear in archive")
        check(ChatListArchivePolicy.includes(chatID: a, type: "group", archived: [a], showingArchive: false), "Non-direct chat is unaffected by malformed archive IDs")
        let visibleLocal = [a, b].filter { ChatListArchivePolicy.includes(chatID: $0, type: "direct", archived: [a, hidden], showingArchive: true) }
        check(visibleLocal == [a], "Unknown server archive ID cannot create a displayed history row")
        print("PASS: \(count) production archive repository/API/privacy/race contracts; no live writes")
    }
}
'''
source = support + api + "\n}\n@MainActor final class ChatsRepository {\n" + prefix + archive + fixture + policy + tests
with tempfile.TemporaryDirectory(prefix="coco-archive-contracts-") as directory:
    folder = Path(directory)
    swift = folder / "Archive.swift"
    executable = folder / "archive.exe"
    swift.write_text(source, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
