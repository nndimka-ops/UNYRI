import Foundation

@main
struct AttachmentDownloadContracts {
    @MainActor static func main() async throws {
        let flights = AttachmentDownloadFlights()
        let result = URL(fileURLWithPath: "/isolated/photo")
        var starts = 0
        var release: CheckedContinuation<URL, Never>?
        let first = Task {
            try await flights.run("photo") {
                starts += 1
                return await withCheckedContinuation { release = $0 }
            }
        }
        while release == nil { await Task.yield() }
        let second = Task { try await flights.run("photo") { starts += 1; return result } }
        while flights.activeConsumerCount < 2 { await Task.yield() }
        precondition(starts == 1, "Duplicate callers must share the transfer")
        let unrelated = try await flights.run("file") { result }
        precondition(unrelated == result, "Unrelated downloads remain independent")
        first.cancel()
        do { _ = try await first.value; fatalError("Cancelled caller must not update its screen") }
        catch is CancellationError {}
        precondition(flights.activeConsumerCount == 1, "Cancelling the automatic lease must leave the manual consumer")
        release?.resume(returning: result)
        let secondResult = try await second.value
        precondition(secondResult == result, "Cancelling one caller must not cancel another")

        do { _ = try await flights.run("failure") { throw CocoaError(.fileReadUnknown) }; fatalError("Expected failure") }
        catch is CocoaError {}
        let retry = try await flights.run("failure") { result }
        precondition(retry == result, "Failure must permit retry")

        var soleNetworkCancelled = false
        let started = AsyncStream<Void>.makeStream()
        var startedEvents = started.stream.makeAsyncIterator()
        let sole = Task {
            try await flights.run("automatic-only") {
                started.continuation.yield(())
                do { try await Task.sleep(nanoseconds: 1_000_000_000) }
                catch { soleNetworkCancelled = Task.isCancelled; throw error }
                return result
            }
        }
        _ = await startedEvents.next(); sole.cancel()
        do { _ = try await sole.value; fatalError("Cancelled automatic consumer returned a file") }
        catch is CancellationError {}
        while flights.activeFlightCount > 0 { await Task.yield() }
        precondition(soleNetworkCancelled, "The last cancelled consumer must cancel the actual network task")

        // A network/decrypt worker can take time to observe cancellation. Its
        // abandoned caller returns promptly, but a new writer must not start
        // until that old worker has really stopped touching the cache.
        var drainRelease: CheckedContinuation<URL, Never>?
        var drainStarts = 0
        let abandoned = Task {
            try await flights.run("slow-drain") {
                drainStarts += 1
                return await withCheckedContinuation { drainRelease = $0 }
            }
        }
        while drainRelease == nil { await Task.yield() }
        abandoned.cancel()
        do { _ = try await abandoned.value; fatalError("Abandoned caller waited for an uncooperative worker") }
        catch is CancellationError {}
        precondition(flights.activeConsumerCount == 0 && flights.activeFlightCount == 1,
            "An abandoned worker must retain its identity until it drains")
        var replacementEntered = false
        let replacement = Task {
            replacementEntered = true
            return try await flights.run("slow-drain") { drainStarts += 1; return result }
        }
        while !replacementEntered { await Task.yield() }
        for _ in 0..<32 { await Task.yield() }
        precondition(drainStarts == 1, "A replacement must not overlap the abandoned cache writer")
        drainRelease?.resume(returning: result)
        let replaced = try await replacement.value
        precondition(replaced == result && drainStarts == 2 && flights.activeFlightCount == 0 && flights.activeConsumerCount == 0,
            "A drained flight must allow exactly one clean replacement")

        var cancelledBeforeStart = false
        var forbiddenStarts = 0
        let early = Task {
            await Task.yield()
            do { _ = try await flights.run("cancelled-before-start") { forbiddenStarts += 1; return result } }
            catch is CancellationError { cancelledBeforeStart = true }
        }
        early.cancel(); try await early.value
        precondition(cancelledBeforeStart && forbiddenStarts == 0, "Pre-start cancellation must not create a transfer")

        // Repeated off/on/off cycles drain each abandoned worker before a new
        // writer for the same attachment may begin.
        var liveWorkers = 0
        var peakWorkers = 0
        for _ in 0..<8 {
            let entered = AsyncStream<Void>.makeStream()
            var entering = entered.stream.makeAsyncIterator()
            let automatic = Task {
                try await flights.run("toggle") {
                    liveWorkers += 1; peakWorkers = max(peakWorkers, liveWorkers)
                    defer { liveWorkers -= 1 }
                    entered.continuation.yield(())
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                    return result
                }
            }
            _ = await entering.next(); automatic.cancel()
            do { _ = try await automatic.value; fatalError("Off toggle returned an automatic result") }
            catch is CancellationError {}
        }
        await flights.cancelAndWait(["toggle"])
        precondition(liveWorkers == 0 && peakWorkers == 1 && flights.activeFlightCount == 0 && flights.activeConsumerCount == 0,
            "Off/on/off must not leak workers, waiters or concurrent cache writers")

        let explicitStart = AsyncStream<Void>.makeStream()
        var explicitEvents = explicitStart.stream.makeAsyncIterator()
        let allFirst = Task { try await flights.run("explicit") {
            explicitStart.continuation.yield(())
            try await Task.sleep(nanoseconds: 1_000_000_000); return result
        } }
        _ = await explicitEvents.next()
        let allSecond = Task { try await flights.run("explicit") { result } }
        while flights.activeConsumerCount < 2 { await Task.yield() }
        await flights.cancelAndWait(["explicit"])
        for caller in [allFirst, allSecond] {
            do { _ = try await caller.value; fatalError("Explicit cancel-all retained a consumer") }
            catch is CancellationError {}
        }
        precondition(flights.activeFlightCount == 0 && flights.activeConsumerCount == 0, "Cancel-all must drain flight ownership")

        let mib: Int64 = 1024 * 1024
        func permitted(wifi: Bool = true, wifiEnabled: Bool = true, mobileEnabled: Bool = false,
                       bytes: Int64 = 1, work: Bool = true, connected: Bool = true,
                       local: Bool = false, failed: Bool = false) -> Bool {
            AttachmentAutomaticDownloadPolicy.allows(workAllowed: work, connected: connected, wifi: wifi,
                wifiEnabled: wifiEnabled, mobileEnabled: mobileEnabled,
                hasLocalFile: local, failed: failed, bytes: bytes)
        }
        precondition(!permitted(wifiEnabled: false, mobileEnabled: true), "Wi-Fi off must not borrow the mobile preference")
        precondition(!permitted(wifi: false, wifiEnabled: true), "Mobile off must not borrow the Wi-Fi preference")
        precondition(permitted(bytes: 50 * mib) && !permitted(bytes: 50 * mib + 1), "Wi-Fi size boundary changed")
        precondition(permitted(wifi: false, mobileEnabled: true, bytes: 5 * mib)
            && !permitted(wifi: false, mobileEnabled: true, bytes: 5 * mib + 1), "Mobile size boundary changed")
        precondition(!permitted(bytes: -1) && !permitted(work: false) && !permitted(connected: false)
            && !permitted(local: true) && !permitted(failed: true), "Ineligible rows must never start automatic downloads")
        precondition([AttachmentDownloadOrigin.unspecified.rawValue, AttachmentDownloadOrigin.automatic.rawValue,
            AttachmentDownloadOrigin.messageTap.rawValue, AttachmentDownloadOrigin.mediaViewer.rawValue,
            AttachmentDownloadOrigin.forward.rawValue] == [0, 1, 2, 3, 4], "Numeric download diagnostic mapping changed")
        print("attachment download contracts passed")
    }
}
