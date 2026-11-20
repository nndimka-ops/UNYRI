import Foundation

// PRODUCTION_MODELS
@MainActor final class Auth { var sessionGeneration: UInt64 = 1 }
enum FixtureError: Error { case write }
@MainActor final class HistoryStore {
    var state = CallDeletionState()
    var writes = 0
    var failWrite = false
    func deletions() async throws -> CallDeletionState { state }
    func remove(ids: Set<UUID>, cursor: Int64) async throws -> CallDeletionState {
        if failWrite { throw FixtureError.write }
        state.ids.formUnion(ids); state.cursor = cursor; writes += 1
        return state
    }
}
@MainActor final class API {
    var calls = 0
    var blocked = false
    var page: (Int64, Int) -> CallDeletionPage = { _, _ in .init(items: [], hasMore: false) }
    func historyDeletions(accessToken: String, after: Int64) async throws -> CallDeletionPage {
        calls += 1
        while blocked { try Task.checkCancellation(); await Task.yield() }
        return page(after, calls)
    }
}
@MainActor final class Subject {
    let api = API(), auth = Auth()
    let generation: UInt64 = 1
    var ownerId: UUID? = UUID()
    var deletionServerOrigin: String? = nil
    var currentDeletionServer: (() -> String)? = nil
    var currentDeletionOwner: (() -> UUID?)? = nil
    var historyStore: HistoryStore? = HistoryStore()
    var deletedHistory = CallDeletionState()
    private var historyRefreshes: [String: SharedHistoryRefresh] = [:]
    var prunes = 0
    var now: TimeInterval = 100
    func pruneDeletedHistory() { prunes += 1 }
    func performHistoryRefresh(peer: UUID?, before: UUID?) async throws -> UUID? {
        try await run(); return nil
    }
    func run() async throws { try await syncHistoryDeletions(token: "fixture", clock: { self.now }) }
    // PRODUCTION_METHODS
}

@main struct Main {
    @MainActor static func rejects(_ body: () async throws -> Void) async {
        do { try await body(); preconditionFailure("Expected rejection") } catch {}
    }
    static func item(_ sequence: Int64) -> CallDeletion { .init(sequence: sequence, callId: UUID()) }
    @MainActor static func main() async throws {
        let repeated = Subject()
        repeated.api.page = { _, _ in .init(items: [item(1)], hasMore: true) }
        await rejects { try await repeated.run() }
        precondition(repeated.api.calls == 2 && repeated.historyStore?.writes == 1 && repeated.deletedHistory.cursor == 1)

        let malformedPages: [[CallDeletion]] = [[], [item(0)], [item(-1)], [item(2), item(1)],
            [item(1), item(1)], (1...201).map { item(Int64($0)) }]
        for items in malformedPages {
            let malformed = Subject()
            malformed.api.page = { _, _ in .init(items: items, hasMore: true) }
            await rejects { try await malformed.run() }
            precondition(malformed.api.calls == 1 && malformed.historyStore?.writes == 0 && malformed.deletedHistory.cursor == 0)
        }
        let valid = Subject()
        valid.api.page = { after, count in
            precondition(after == Int64(count - 1) * 200)
            return .init(items: (1...200).map { item(after + Int64($0)) }, hasMore: count < 3)
        }
        try await valid.run()
        precondition(valid.api.calls == 3 && valid.deletedHistory.cursor == 600 && valid.historyStore?.writes == 3)
        valid.api.page = { after, _ in precondition(after == 600); return .init(items: [], hasMore: false) }
        try await valid.run()

        let endless = Subject()
        endless.api.page = { after, _ in .init(items: (1...200).map { item(after + Int64($0)) }, hasMore: true) }
        await rejects { try await endless.run() }
        precondition(endless.api.calls == 20 && endless.deletedHistory.cursor == 4000 && endless.historyStore?.writes == 20)
        endless.api.page = { after, _ in precondition(after == 4000); return .init(items: [item(4001)], hasMore: false) }
        try await endless.run()
        precondition(endless.deletedHistory.cursor == 4001)

        let timed = Subject()
        timed.api.page = { after, _ in timed.now += 6; return .init(items: [item(after + 1)], hasMore: true) }
        await rejects { try await timed.run() }
        precondition(timed.api.calls == 2 && timed.historyStore?.writes == 1 && timed.deletedHistory.cursor == 1)

        let failedWrite = Subject(); failedWrite.historyStore?.failWrite = true
        failedWrite.api.page = { _, _ in .init(items: [item(1)], hasMore: false) }
        await rejects { try await failedWrite.run() }
        precondition(failedWrite.deletedHistory.cursor == 0 && failedWrite.deletedHistory.ids.isEmpty)

        let switched = Subject()
        switched.api.page = { _, _ in switched.auth.sessionGeneration += 1; return .init(items: [item(1)], hasMore: false) }
        await rejects { try await switched.run() }
        precondition(switched.historyStore?.writes == 0)

        let cancelled = Subject(); cancelled.api.blocked = true
        let task = Task { try await cancelled.refreshHistory() }
        while cancelled.api.calls == 0 { await Task.yield() }
        task.cancel()
        await rejects { _ = try await task.value }
        precondition(cancelled.api.calls == 1 && cancelled.historyStore?.writes == 0)

        // One cancelled waiter must not fail another waiter's shared refresh.
        let shared = Subject(); shared.api.blocked = true
        let leaving = Task { try await shared.refreshHistory() }
        while shared.api.calls == 0 { await Task.yield() }
        let staying = Task { try await shared.refreshHistory() }
        for _ in 0..<100 { await Task.yield() }
        leaving.cancel()
        for _ in 0..<100 { await Task.yield() }
        shared.api.blocked = false
        _ = try await staying.value
        _ = try? await leaving.value
        precondition(shared.api.calls == 1 && shared.prunes == 1)

        var budget = ServerWorkBudget(now: 0, maxPages: 10, maxRecords: 3, pageLimit: 2)
        try budget.accept(after: 0, next: 2, sequences: [1, 2], hasMore: true, deletions: 0, now: 0)
        do {
            try budget.accept(after: 2, next: 4, sequences: [3, 4], hasMore: true, deletions: 0, now: 0)
            preconditionFailure("Record budget must reject before application")
        } catch {}
        precondition(budget.records == 2 && budget.pages == 1)
        print("Call deletion paging, durable progress, budgets and cancellation contracts passed")
    }
}
