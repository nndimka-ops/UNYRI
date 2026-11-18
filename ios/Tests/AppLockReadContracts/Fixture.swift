import Foundation

enum UIApplicationState { case active, background }
@MainActor final class UIApplication { static let shared = UIApplication(); var applicationState = UIApplicationState.active }
@MainActor final class UnyriAppLock {
    static let shared = UnyriAppLock()
    var shielded = false
    func requiresShield(owner: String?) -> Bool { shielded }
}
enum APIError: Error { case http(Int); case server(code: String, message: String) }
typealias AuthRepository = ReadAuth
// PRODUCTION_RECEIPT_STATE
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
    /// Newest stored incoming message per chat that the delivery ACK may cover.
    var delivered: [String: Int64] = [:]
    var values: [String: [String: String]] = [:]
    var workspaceWrites = 0
    var onRead: (() async -> Void)?
    func readAsync<T>(_ operation: (ReadStore) throws -> T) async throws -> T {
        await Task.yield(); if let hook = onRead { await hook() }; return try operation(self)
    }
    func markLocallyRead(chatId: String, sequence: Int64) throws -> Bool {
        guard sequence > (cursors[chatId] ?? 0) else { return false }
        cursors[chatId] = sequence; return true
    }
    func localReadWatermarks() throws -> [String: Int64] { cursors }
    func receiptSequence(chatId: String, ownerId: String, deliveredOnly: Bool, upperBound: Int64 = .max) throws -> Int64 {
        deliveredOnly ? min(delivered[chatId] ?? 0, upperBound) : upperBound
    }
    func latestActivityByChat() throws -> [String: Int64] { activity }
    func chatValue(chatId: String, name: String) throws -> String? { values[chatId]?[name] }
    func saveChatValue(chatId: String, name: String, value: String?) throws { values[chatId, default: [:]][name] = value }
    func startWorkspaceWrite(_ batches: [String: [String: String]]) -> Task<Void, Error> {
        workspaceWrites += 1
        for (chat, batch) in batches { values[chat, default: [:]].merge(batch) { _, latest in latest } }
        return Task<Void, Error> {}
    }
}
@MainActor final class ReadAPI {
    var posted: [(String, Int64)] = []
    var attempts = 0
    var refusing = Set<String>()
    var beforeAnswer: (() -> Void)?
    var duringPost: (() async -> Void)?
    func markRead(accessToken: String, chatId: String, sequence: Int64) async throws -> Bool {
        attempts += 1
        beforeAnswer?()
        if refusing.contains(chatId) { throw APIError.server(code: "conflict", message: "Communication is unavailable for this chat.") }
        posted.append((chatId,sequence))
        if let hook = duringPost { await hook() }
        return true
    }
    struct Receipt { let sequence: Int64 }
    var delivered: [(String, Int64)] = []
    func markDelivered(accessToken: String, chatId: String, sequence: Int64) async throws -> Receipt {
        attempts += 1
        if refusing.contains(chatId) { throw APIError.server(code: "conflict", message: "Communication is unavailable for this chat.") }
        delivered.append((chatId, sequence))
        return Receipt(sequence: sequence)
    }
}
@MainActor final class ReadHarness {
    let auth = ReadAuth(), store = ReadStore(), api = ReadAPI()
    let generation: UInt64 = 1
    let ownerId = "owner"
    let ownerServer = URL(string: "https://synthetic.invalid")!
    var visibleChatId: String? = "chat"
    var visibleReadSequences: [String: Int64] = ["chat": 5]
    lazy var receipts = SessionReceipts.shared(auth: auth, generation: generation)
    var isCallExpanded = false
    var flushingReadReceipts = false
    var readFlushRequested = false
    var deliveries: [String] = []
    init() { receipts.serverHasRead(chatId: "chat", through: 5) }
    var onReadStateChange: (() -> Void)?
    func requireSession() throws { if generation != auth.sessionGeneration { throw CancellationError() } }
    func confirmDelivery(chatId: String) async { deliveries.append(chatId) }
    // PRODUCTION_READ_METHODS
}
/// MessageIngestor's delivery ACK. `store` stands for the account's own database,
/// so a harness built for another account gets an empty one.
@MainActor final class DeliveryHarness {
    let auth: ReadAuth, store = ReadStore(), messagesAPI = ReadAPI()
    let generation: UInt64
    let ownerId = "owner"
    let receipts: SessionReceipts
    var deliveryRequests = Set<String>()
    // Optional: a default ReadAuth() would be built outside the main actor.
    init(auth: ReadAuth? = nil, generation: UInt64 = 1) {
        let auth = auth ?? ReadAuth()
        self.auth = auth; self.generation = generation
        receipts = SessionReceipts.shared(auth: auth, generation: generation)
    }
    func requireSession() throws { if generation != auth.sessionGeneration { throw CancellationError() } }
    func deliver(_ chatId: String) async { await confirmDelivery(chatId: chatId, token: "synthetic") }
    // PRODUCTION_DELIVERY_METHODS
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
        let reader = ReadHarness()
        lock.shielded = true
        check(!reader.setVisibleReadSequence(chatId: "chat", sequence: 7), "A locked chat refuses visible evidence, so the screen offers it again")
        lock.shielded = false
        let accepted = reader.setVisibleReadSequence(chatId: "chat", sequence: 7)
        reader.visibleChatId = nil
        check(accepted && reader.store.cursors["chat"] == 7, "An accepted visible read is saved before the chat can close")
        reader.visibleChatId = "chat"
        check(reader.setVisibleReadSequence(chatId: "chat", sequence: 6) && reader.visibleReadSequences["chat"] == 7, "Visible evidence never moves back")
        let coalesced = ReadHarness()
        coalesced.store.cursors["chat"] = 6
        coalesced.api.duringPost = {
            coalesced.api.duringPost = nil
            coalesced.store.cursors["chat"] = 9
            await coalesced.flushReadReceipts()
        }
        await coalesced.flushReadReceipts()
        check(coalesced.api.posted.map { $0.1 } == [6, 9], "A read made during a running flush is posted before that flush ends")
        check(coalesced.store.values["chat"]?[SessionReceipts.serverReadKey] == "9", "An accepted read is saved for the next launch")
        let ordered = ReadHarness()
        for index in 0..<8 { ordered.store.cursors["queued-\(index)"] = 3 }
        ordered.store.cursors["chat"] = 8
        await ordered.flushReadReceipts()
        check(ordered.api.posted.count == 9 && ordered.api.posted.first?.0 == "chat", "The open chat's read is posted before the queued ones")
        let seeded = ReadHarness()
        seeded.store.cursors["known"] = 8
        seeded.receipts.serverHasRead(chatId: "known", through: 8)
        await seeded.flushReadReceipts()
        check(seeded.api.attempts == 0, "A read the server already reported in GET /chats is not posted again")
        check(seeded.store.values["known"]?[SessionReceipts.serverReadKey] == "8", "A cursor learned from GET /chats is saved for the next launch")
        let blocked = ReadHarness()
        blocked.store.cursors["blocked"] = 4
        blocked.api.refusing = ["blocked"]
        await blocked.flushReadReceipts()
        check(blocked.receipts.isRefused("blocked") && blocked.api.attempts == 1, "409 pauses the chat's receipts")
        await blocked.flushReadReceipts()
        check(blocked.api.attempts == 1, "A paused chat is not retried by the next flush")
        check(!blocked.receipts.isRefused("blocked", at: ContinuousClock.now + SessionReceipts.refusalPause), "The pause ends by itself: the peer's unblock is never announced")
        check(SessionReceipts.shared(auth: blocked.auth, generation: 1) === blocked.receipts, "One session's repositories share one receipt state")
        check(SessionReceipts.shared(auth: blocked.auth, generation: 2) !== blocked.receipts, "A new session starts with empty receipt state")
        blocked.api.refusing = []
        blocked.receipts.allow(["blocked"])
        await blocked.flushReadReceipts()
        check(blocked.api.posted.contains { $0.0 == "blocked" && $0.1 == 4 }, "New activity in the chat ends the pause and the queued read goes out")
        check(SessionReceipts.isRefusal(APIError.server(code: "conflict", message: "Communication is unavailable for this chat.")) &&
              !SessionReceipts.isRefusal(APIError.http(503)), "Only the server's conflict answer counts as a refusal")
        let late = ReadHarness()
        late.store.cursors["late"] = 4
        late.api.refusing = ["late"]
        late.api.beforeAnswer = { late.receipts.allow(["late"]) }
        await late.flushReadReceipts()
        check(late.api.attempts == 1 && !late.receipts.isRefused("late"), "A 409 to a request sent before the chat's last allow() is stale")
        let relaunched = ReadHarness()
        relaunched.store.cursors["saved"] = 8
        relaunched.store.values["saved"] = [SessionReceipts.serverReadKey: "8"]
        await relaunched.flushReadReceipts()
        check(relaunched.api.attempts == 0 && relaunched.receipts.serverRead["saved"] == 8, "A cursor saved by an earlier launch is not posted again")
        let deliveredKey = SessionReceipts.serverDeliveredKey
        let launch = DeliveryHarness()
        launch.store.delivered["chat"] = 7
        launch.store.values["chat"] = [deliveredKey: "7"]
        await launch.deliver("chat")
        check(launch.messagesAPI.attempts == 0 && launch.receipts.serverDelivered["chat"] == 7,
              "A delivered cursor saved by an earlier launch suppresses the launch POST")
        check(launch.store.workspaceWrites == 0, "A suppressed launch POST does not rewrite the saved cursor")
        launch.store.delivered["chat"] = 9
        await launch.deliver("chat")
        check(launch.messagesAPI.delivered.map { $0.1 } == [9], "A newer incoming message still posts")
        check(launch.store.values["chat"]?[deliveredKey] == "9", "An acknowledged delivery is saved for the next launch")
        await launch.deliver("chat")
        check(launch.messagesAPI.attempts == 1, "An acknowledged delivery is not posted again in the same session")
        launch.auth.sessionGeneration = 2
        launch.store.delivered["chat"] = 10
        await launch.deliver("chat")
        check(launch.messagesAPI.attempts == 1 && launch.store.values["chat"]?[deliveredKey] == "9",
              "A retired session neither posts nor saves a delivered cursor")
        let next = DeliveryHarness(auth: launch.auth, generation: 2)
        check(next.receipts !== launch.receipts && next.receipts.serverDelivered.isEmpty,
              "A new session starts with no acknowledged delivery")
        next.store.delivered["chat"] = 9
        await next.deliver("chat")
        check(next.messagesAPI.delivered.map { $0.1 } == [9] && next.store.values["chat"]?[deliveredKey] == "9",
              "Another account's store has no saved cursor, so its own delivery is posted and saved there")
        let refusedDelivery = DeliveryHarness()
        refusedDelivery.store.delivered["blocked"] = 4
        refusedDelivery.messagesAPI.refusing = ["blocked"]
        await refusedDelivery.deliver("blocked")
        check(refusedDelivery.receipts.isRefused("blocked") && refusedDelivery.store.values["blocked"]?[deliveredKey] == nil,
              "A refused delivery pauses the chat and saves nothing")
        print("PASS: \(checks) extracted production app-lock/read/coalescing/saved-cursor/refusal/delivery contracts")
    }
}
