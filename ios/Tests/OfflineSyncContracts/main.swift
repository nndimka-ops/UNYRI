import Foundation

// Transport/store fakes; the scheduler under test is the production SyncManager.
enum ConnectionSyncPhase { case idle, running, failed }
struct SyncResponse {
    var messages: [Int] = []
    var nextSequence: Int64 = 0
    var hasMore = false
    var historyClearedBeforeMs: Int64? = nil
    var historyClearedThroughSequence: Int64? = nil
    var deletedChatIds: [String] = []
}
@MainActor final class SyncAPI {
    var calls = 0
    var response = SyncResponse()
    var failure: Error?
    var release: CheckedContinuation<Void, Never>?
    func sync(accessToken: String, afterSequence: Int64, limit: Int) async throws -> SyncResponse {
        calls += 1
        if calls == 1 {
            await withCheckedContinuation { release = $0 }
            throw URLError(.networkConnectionLost)
        }
        if let failure { throw failure }
        return response
    }
}
@MainActor final class MessageIngestor {
    var count = 0
    var success = true
    var advances: [Bool] = []
    func ingest(_ messages: [Int], advanceCursor: Bool) async -> Bool { count += 1; advances.append(advanceCursor); return success }
    func retryPendingDecrypts(automatic: Bool) async { }
}
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    func currentAccessToken() async throws -> String { "test" }
}
final class SessionStore { func lastSyncedSequence() -> Int64? { 0 } }

@main struct OfflineSyncContracts {
    @MainActor static func main() async {
        let api = SyncAPI(), ingestor = MessageIngestor(), auth = AuthRepository()
        let manager = SyncManager(api: api, ingestor: ingestor, auth: auth, sessionStore: SessionStore())
        let old = Task { await manager.runCatchUpSync() }
        while api.release == nil { await Task.yield() }
        for _ in 0..<10 { await manager.runCatchUpSync() }
        precondition(api.calls == 1, "Requests must remain serialized")
        api.release?.resume()
        await old.value
        precondition(api.calls == 2, "Reconnect after a failed request must run once")
        precondition(ingestor.count == 1, "Failed request must not ingest or duplicate messages")
        await manager.runCatchUpSync()
        precondition(api.calls == 3, "Scheduler must remain reusable")
        func incomplete() async {
            do { try await manager.prepareHistoryBackup(); fatalError("Accepted incomplete history preparation") }
            catch SyncManager.HistoryPreparationFailure.incompleteSource {}
            catch { fatalError("Unexpected failure: \(error)") }
        }
        api.response = SyncResponse(historyClearedBeforeMs: 1, historyClearedThroughSequence: 10)
        await incomplete()
        manager.onHistoryClear = { _, _ in }
        api.response.historyClearedThroughSequence = nil
        await incomplete()
        api.response = SyncResponse(deletedChatIds: ["deleted"])
        await incomplete()
        manager.onDeletedChat = { _ in }
        api.response = SyncResponse(nextSequence: 0, hasMore: true)
        await incomplete()
        api.response = SyncResponse()
        ingestor.success = false
        await incomplete()
        ingestor.success = true
        api.failure = URLError(.networkConnectionLost)
        do { try await manager.prepareHistoryBackup(); fatalError("Swallowed preparation transport failure") }
        catch let error as URLError { precondition(error.code == .networkConnectionLost) }
        catch { fatalError("Unexpected transport error") }
        api.failure = nil
        do { try await manager.prepareHistoryBackup() } catch { fatalError("Preparation could not recover: \(error)") }
        precondition(ingestor.advances.last == false, "Full traversal must not regress incremental cursor")
        print("PASS: serialization, reconnect after failure, trigger coalescing, and scheduler reuse")
    }
}
