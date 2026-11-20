import Foundation

enum ConnectionSyncPhase { case idle, running, failed }
enum UnyriDiag { static func sync(finished: Bool, items: Int = 0, durationMs: Double = 0, success: Bool = false) {} }
struct SyncMessage { let sequence: Int64 }
struct SyncResponse {
    var messages: [SyncMessage] = []
    var nextSequence: Int64 = 0
    var hasMore = false
    var historyClearedBeforeMs: Int64? = nil
    var historyClearedThroughSequence: Int64? = nil
    var deletedChatIds: [String] = []
}
@MainActor final class SyncAPI {
    var calls = 0
    var blocked = false
    var page: (Int64, Int) -> SyncResponse = { _, _ in SyncResponse() }
    func sync(accessToken: String, afterSequence: Int64, limit: Int) async throws -> SyncResponse {
        calls += 1
        while blocked { try Task.checkCancellation(); await Task.yield() }
        try Task.checkCancellation()
        return page(afterSequence, calls)
    }
}
@MainActor final class MessageIngestor {
    var count = 0
    func ingest(_ messages: [SyncMessage], advanceCursor: Bool) async -> Bool { count += 1; return !Task.isCancelled }
    func retryPendingDecrypts(automatic: Bool) async {}
}
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    func currentAccessToken() async throws -> String { "fixture" }
}
final class SessionStore { func lastSyncedSequence() -> Int64? { 0 } }
private func rejects(_ body: () throws -> Void) { do { try body(); fatalError("Expected budget rejection") } catch {} }

@main struct Main {
    @MainActor static func main() async throws {
        var now: TimeInterval = 100
        func fixture() -> (SyncAPI, MessageIngestor, AuthRepository, SyncManager) {
            let api = SyncAPI(), ingestor = MessageIngestor(), auth = AuthRepository()
            return (api, ingestor, auth, SyncManager(api: api, ingestor: ingestor, auth: auth, sessionStore: SessionStore(), clock: { now }))
        }
        let empty = fixture()
        empty.0.page = { _, _ in SyncResponse(hasMore: true) }
        await empty.3.runCatchUpSync()
        for _ in 0..<100 { await empty.3.runCatchUpSync() }
        precondition(empty.0.calls == 1 && empty.1.count == 0)
        now += 31; await empty.3.runCatchUpSync(); precondition(empty.0.calls == 2)

        let repeated = fixture()
        repeated.0.page = { _, _ in SyncResponse(messages: [.init(sequence: 1)], nextSequence: 1, hasMore: true) }
        await repeated.3.runCatchUpSync()
        precondition(repeated.0.calls == 2 && repeated.1.count == 1)

        let increasing = fixture()
        increasing.0.page = { after, _ in SyncResponse(messages: [.init(sequence: after + 1)], nextSequence: after + 1, hasMore: true) }
        await increasing.3.runCatchUpSync()
        precondition(increasing.0.calls == 20 && increasing.1.count == 20)

        let timed = fixture()
        timed.0.page = { after, _ in now += 6; return SyncResponse(messages: [.init(sequence: after + 1)], nextSequence: after + 1, hasMore: true) }
        await timed.3.runCatchUpSync()
        precondition(timed.0.calls == 2 && timed.1.count == 1)

        let cancelled = fixture(); cancelled.0.blocked = true
        let running = Task { await cancelled.3.runCatchUpSync() }
        while cancelled.0.calls == 0 { await Task.yield() }
        running.cancel(); await running.value
        precondition(cancelled.0.calls == 1 && cancelled.1.count == 0)

        let switched = fixture()
        switched.0.page = { _, _ in switched.2.sessionGeneration += 1; return SyncResponse() }
        await switched.3.runCatchUpSync(); precondition(switched.1.count == 0)

        let deleted = fixture(); var purges = 0
        deleted.0.page = { _, _ in SyncResponse(deletedChatIds: ["11111111-1111-4111-8111-111111111111"]) }
        deleted.3.isDeletionApplied = { _ in true }; deleted.3.onDeletedChat = { _ in purges += 1 }
        await deleted.3.runCatchUpSync(); precondition(purges == 0)

        var budget = ServerWorkBudget(now: 0)
        rejects { try budget.accept(after: 0, next: Int64.max, sequences: [], hasMore: true, deletions: 0, now: 0) }
        rejects { try budget.accept(after: 0, next: -1, sequences: [], hasMore: false, deletions: 0, now: 0) }
        rejects { try budget.accept(after: 0, next: 2, sequences: [2, 1], hasMore: false, deletions: 0, now: 0) }
        rejects { try budget.accept(after: 0, next: 1, sequences: [1], hasMore: false, deletions: 1001, now: 0) }
        rejects { try budget.accept(after: 0, next: 1, sequences: [1], hasMore: false, deletions: 0, now: 10) }
        var events = ServerEventBudget(), admitted = 0
        for _ in 0..<100_000 { if events.admit(now: 0) { admitted += 1 } }
        precondition(admitted == 8 && events.admit(now: 30))
        let profile = ServerProfileReadBudget()
        precondition(profile.admit(now: 0) && profile.admit(now: 30) && profile.admit(now: 60))
        precondition(!profile.admit(now: 90) && profile.admit(now: 120))
        try ServerWorkDeadline.$uptime.withValue(10) {
            try ServerWorkDeadline.check(now: 9)
            rejects { try ServerWorkDeadline.check(now: 10) }
        }
        print("Hostile F12 production catch-up, cancellation and flood contracts passed")
    }
}
