import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@main struct Main {
    @MainActor static func main() async {
        var now: TimeInterval = ProcessInfo.processInfo.systemUptime
        let api=SyncAPI(), ingestor=MessageIngestor(), auth=AuthRepository(), session=SessionStore()
        let manager=SyncManager(api: api, ingestor: ingestor, auth: auth, sessionStore: session, clock: { now })
        var clears=0; manager.onHistoryClear = { _, _ in clears += 1 }
        for watermark in [Int64(11), Int64.max, Int64(11)] {
            api.page = { _, _ in SyncResponse(messages: [.init(sequence: 11)], nextSequence: 100, historyClearedBeforeMs: 1, historyClearedThroughSequence: watermark) }
            await manager.runCatchUpSync(); now += 3
        }
        check(clears == 0 && ingestor.count == 3)
        api.page = { _, _ in SyncResponse(messages: [.init(sequence: 11)], nextSequence: 11, historyClearedBeforeMs: 1, historyClearedThroughSequence: 10) }
        await manager.runCatchUpSync(); check(clears == 1)
        api.page = { _, _ in SyncResponse(messages: [.init(sequence: 11)], nextSequence: 100, historyClearedBeforeMs: 1, historyClearedThroughSequence: Int64.max) }
        do { try await manager.prepareHistoryBackup() } catch { preconditionFailure("Future clear interrupted backup ingest: \(error)") }
        check(clears == 1 && ingestor.count == 5)
        manager.onHistoryClear = nil
        do { try await manager.prepareHistoryBackup() } catch { preconditionFailure("Ignored clear required a handler: \(error)") }
        check(ingestor.count == 6)
        print("PASS F7 guard: future clears cannot use next_sequence, delete history, or suppress page messages")
    }
}
