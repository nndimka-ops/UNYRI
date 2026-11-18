import Foundation

@MainActor
final class Gate {
    private var opened = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func open() {
        opened = true
        let pending = waiters
        waiters.removeAll()
        pending.forEach { $0.resume() }
    }
}

@main
struct CallQueueContracts {
    @MainActor
    static func main() async {
        let queue = CallEffectSerialQueue()
        var events: [String] = []
        let started = Gate(), release = Gate()
        queue.enqueue {
            events.append("initialize-start")
            started.open()
            await release.wait()
            events.append("initialize-finish")
        }
        await started.wait()
        let offer = queue.enqueue { events.append("offer") }
        await Task.yield()
        precondition(events == ["initialize-start"], "Offer overtook initialization")
        release.open()
        await offer.value
        precondition(events == ["initialize-start", "initialize-finish", "offer"])

        events = []
        let oldStarted = Gate(), oldRelease = Gate()
        queue.enqueue {
            events.append("old-start")
            oldStarted.open()
            await oldRelease.wait() // Deliberately non-cancellable SDK callback.
            events.append("old-return")
        }
        await oldStarted.wait()
        queue.enqueue { events.append("stale-offer") }
        queue.cancel()
        let nextCall = queue.enqueue { events.append("new-call") }
        await Task.yield()
        precondition(events == ["old-start"], "New call overlapped old SDK work")
        oldRelease.open()
        await nextCall.value
        precondition(events == ["old-start", "old-return", "new-call"], "Cancelled offer executed")
        print("Call effect queue contracts passed")
    }
}
