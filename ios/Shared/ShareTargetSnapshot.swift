import Foundation

enum ShareTargetSnapshot {
    static func canInclude(type: String, blocked: Bool, deleted: Bool, hidden: Bool, canPost: Bool = true, hasPeer: Bool) -> Bool {
        ["direct", "group", "saved"].contains(type) && !blocked && !deleted && !hidden && canPost && (type != "direct" || hasPeer)
    }
    struct Account: Codable, Equatable, Identifiable {
        let key: String
        let binding: UnyriShareStore.AccountBinding
        let displayName: String
        var state: String = "active"
        var appLock = false
        var colorStyle = "monochrome"
        var language = "en"
        var mediaChoice = "image"
        var id: String { key }
    }
    struct Index: Codable, Equatable {
        var version = 1
        var activeAccountKey: String?
        var accounts: [Account]
    }
    struct Target: Codable, Equatable, Identifiable {
        let chatId: String
        let type: String
        let title: String
        var publicHandle = ""
        let peerUserId: String?
        var pinned = false
        var archived = false
        var lastActivity: TimeInterval = 0
        var avatar: String? = nil
        var canPost = true
        var id: String { chatId }
    }
    struct Snapshot: Codable, Equatable {
        var version = 1
        let accountKey: String
        var writtenAt: TimeInterval
        var targets: [Target]
    }
    static func ordered(_ targets: [Target], query: String = "") -> [Target] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return targets.filter { target in
            target.canPost && (query.isEmpty ? !target.archived :
                target.title.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil ||
                target.publicHandle.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil)
        }.sorted {
            if ($0.type == "saved") != ($1.type == "saved") { return $0.type == "saved" }
            if $0.pinned != $1.pinned { return $0.pinned }
            return $0.lastActivity == $1.lastActivity ? $0.chatId < $1.chatId : $0.lastActivity > $1.lastActivity
        }
    }
    static func readIndex(root: URL) throws -> Index {
        let data = try ShareContainer.read(ShareContainer.targets(root).appendingPathComponent("index.json"), maximum: 64 * 1024)
        let index = try JSONDecoder().decode(Index.self, from: data)
        guard index.version == 1, index.accounts.count <= 10,
              Set(index.accounts.map(\.key)).count == index.accounts.count,
              index.accounts.allSatisfy({ $0.binding.valid && $0.binding.accountKey == $0.key }),
              index.activeAccountKey.map({ key in index.accounts.contains { $0.key == key } }) ?? true else { throw CocoaError(.fileReadCorruptFile) }
        return index
    }
    static func read(account: Account, root: URL) throws -> Snapshot {
        let data = try ShareContainer.read(ShareContainer.targets(root).appendingPathComponent(account.key + "/targets.json"), maximum: 4 * 1024 * 1024)
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: data)
        guard snapshot.version == 1, snapshot.accountKey == account.key, snapshot.targets.count <= 10000,
              snapshot.writtenAt.isFinite, Set(snapshot.targets.map(\.chatId)).count == snapshot.targets.count,
              snapshot.targets.allSatisfy({ target in
                  ShareJob.uuid(target.chatId) && ["direct", "group", "saved"].contains(target.type) &&
                  (target.peerUserId.map(ShareJob.uuid) ?? true) && target.publicHandle.isEmpty &&
                  (target.avatar.map { $0 == UnyriShareStore.safeName($0) && !$0.contains("\0") } ?? true)
              }) else { throw CocoaError(.fileReadCorruptFile) }
        return snapshot
    }
}

/// Event-driven debounce, with an injectable monotonic clock for contracts. The
/// writer calls changed only for revisions/preferences, never from a timer.
struct ShareSnapshotGate {
    private(set) var revision: UInt64 = 0
    private var due: TimeInterval = 0
    private var published: Data?
    mutating func changed(at now: TimeInterval) -> UInt64 {
        revision &+= 1; due = now + 2; return revision
    }
    func ready(_ ticket: UInt64, at now: TimeInterval) -> Bool { ticket == revision && now >= due }
    func differs(_ content: Data) -> Bool { content != published }
    mutating func didWrite(_ content: Data) { published = content }
}
