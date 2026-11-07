import Foundation

enum UIApplicationState { case active, background }
@MainActor final class UIApplication { static let shared = UIApplication(); var applicationState = UIApplicationState.active }
@MainActor final class UnyriAppLock {
    static let shared = UnyriAppLock()
    var shielded = false
    func requiresShield(owner: String?) -> Bool { shielded }
}
struct ReadUser { let id = "owner" }
@MainActor final class ReadAuth {
    var sessionGeneration: UInt64 = 1
    var currentUser: ReadUser? = ReadUser()
    let authenticatedServerURL = URL(string: "https://synthetic.invalid")!
    var beforeToken: (() async -> Void)?
    func currentAccessToken() async throws -> String { if let hook = beforeToken { await hook() }; return "synthetic" }
}
@MainActor final class ReadStore {
    var cursors: [String: Int64] = ["chat": 5]
    var activity: [String: Int64] = ["chat": 10]
    var onRead: (() async -> Void)?
    func readAsync<T>(_ operation: (ReadStore) throws -> T) async throws -> T {
        await Task.yield(); if let hook = onRead { await hook() }; return try operation(self)
    }
    func markLocallyRead(chatId: String, sequence: Int64) throws -> Bool {
        guard sequence > (cursors[chatId] ?? 0) else { return false }
        cursors[chatId] = sequence; return true
    }
    func localReadWatermarks() throws -> [String: Int64] { cursors }
    func receiptSequence(chatId: String, ownerId: String, deliveredOnly: Bool, upperBound: Int64) throws -> Int64 { upperBound }
    func latestActivityByChat() throws -> [String: Int64] { activity }
}
@MainActor final class ReadAPI {
    var posted: [(String, Int64)] = []
    func markRead(accessToken: String, chatId: String, sequence: Int64) async throws -> Bool { posted.append((chatId,sequence)); return true }
}
@MainActor final class ReadHarness {
    let auth = ReadAuth(), store = ReadStore(), api = ReadAPI()
    let generation: UInt64 = 1
    let ownerId = "owner"
    let ownerServer = URL(string: "https://synthetic.invalid")!
    var visibleChatId: String? = "chat"
    var visibleReadSequences: [String: Int64] = ["chat": 5]
    var readWatermarks: [String: Int64] = ["chat": 5]
    var isCallExpanded = false
    var flushingReadReceipts = false
    var deliveries: [String] = []
    var onReadStateChange: (() -> Void)?
    func requireSession() throws { if generation != auth.sessionGeneration { throw CancellationError() } }
    func confirmDelivery(chatId: String) async { deliveries.append(chatId) }
    // PRODUCTION_READ_METHODS
}

@main struct AppLockReadContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ condition: @autoclosure () -> Bool, _ message: String) { precondition(condition(),message); checks += 1 }
        let lock = UnyriAppLock.shared
        let repository = ReadHarness()
        lock.shielded = true
        check(repository.activeChatId == nil, "A mounted locked chat is not an active visible chat")
        repository.setVisibleReadSequence(chatId: "chat", sequence: 10)
        await repository.markReadLatest(chatId: "chat")
        await repository.flushReadReceipts()
        check(repository.visibleReadSequences["chat"] == 5, "New layout behind a shield cannot advance visible evidence")
        check(repository.store.cursors["chat"] == 5, "Incoming message remains locally unread while locked")
        check(repository.api.posted.isEmpty, "No remote read POST is emitted while locked")
        check(repository.deliveries == ["chat"], "Delivery confirmation continues while READ is paused")
        lock.shielded = false
        check(repository.activeChatId == "chat", "Unlock restores an actually visible mounted chat")
        repository.setVisibleReadSequence(chatId: "chat", sequence: 10)
        await repository.markReadLatest(chatId: "chat")
        await repository.presentationVisibilityChanged()
        check(repository.store.cursors["chat"] == 10, "Fresh visible evidence after unlock advances own unread cursor")
        check(repository.api.posted.count == 1 && repository.api.posted.first?.1 == 10, "Unlock publishes the visible read exactly once")
        lock.shielded = true
        repository.store.cursors["chat"] = 20
        await repository.flushReadReceipts()
        check(repository.store.cursors["chat"] == 20 && repository.api.posted.count == 1, "Lock defers a durable own-read queue without deleting it")
        lock.shielded = false
        await repository.presentationVisibilityChanged()
        check(repository.api.posted.count == 2 && repository.api.posted.last?.1 == 20, "Unlock retries a previously queued durable cursor")
        repository.store.cursors["chat"] = 30
        repository.auth.beforeToken = { lock.shielded = true }
        await repository.flushReadReceipts()
        check(repository.api.posted.count == 2 && repository.store.cursors["chat"] == 30, "Lock arriving during token await fences the POST without losing the queue")
        check(repository.deliveries.count >= 4, "Token-boundary READ deferral still reaches delivery confirmation")
        repository.auth.beforeToken = nil; lock.shielded = false
        await repository.presentationVisibilityChanged()
        check(repository.api.posted.last?.1 == 30, "A token-boundary deferred read retries after unlock")
        let suspended = ReadHarness()
        suspended.store.onRead = { lock.shielded = true }
        suspended.store.cursors["chat"] = 40
        await suspended.flushReadReceipts()
        check(suspended.api.posted.isEmpty && suspended.store.cursors["chat"] == 40, "Lock during SQLite await defers queued reads")
        check(suspended.deliveries == ["chat"], "SQLite-boundary READ deferral retains delivery")
        lock.shielded = false
        UIApplication.shared.applicationState = .background
        repository.setVisibleReadSequence(chatId: "chat", sequence: 50)
        await repository.markReadLatest(chatId: "chat")
        check(repository.store.cursors["chat"] == 30, "Background cannot read a mounted chat")
        UIApplication.shared.applicationState = .active
        repository.isCallExpanded = true
        check(repository.activeChatId == nil, "An expanded call keeps the conversation unread")
        repository.isCallExpanded = false; repository.visibleChatId = nil
        await repository.markReadLatest(chatId: "chat")
        check(repository.store.cursors["chat"] == 30, "Unlock does not read a chat that is no longer visible")
        let retired = ReadHarness(); retired.store.cursors["chat"] = 60
        retired.auth.beforeToken = { retired.auth.sessionGeneration = 2 }
        await retired.flushReadReceipts()
        check(retired.api.posted.isEmpty && retired.store.cursors["chat"] == 60, "Account retirement during token await fences the read")
        print("PASS: \(checks) extracted production app-lock/read/durable-queue/delivery contracts")
    }
}
