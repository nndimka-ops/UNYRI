import Foundation

/// Async orchestration only; the synchronous core owns all admission facts.
actor DirectFileHTTPGate {
    enum Priority: Int { case control, live, queued, passive }
    enum Failure: Error { case full, clock }
    struct Context {
        let key: String?
        let priority: Priority
        let current: @MainActor () -> Bool
    }
    struct Started<Value> {
        let result: () async throws -> Value
        let cancel: () -> Void
    }
    struct Prepared<Value> {
        let start: @MainActor () throws -> Started<Value>
        let cancel: () -> Void
    }
    private nonisolated let core: Core
    init(clock: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }) { core = Core(clock) }
    nonisolated func promote(_ key: String) -> UUID { core.promote(key) }
    nonisolated func demote(_ key: String, lease: UUID) { core.demote(key, lease) }
    nonisolated func observe429(_ value: String?) { core.backOff(value) }
    nonisolated func cancelScope(_ prefix: String) { core.cancelScope(prefix) }

    func withPermit<Value>(context: Context, read: Bool,
        prepare: () async throws -> Prepared<Value>,
        beforeStart: @MainActor () throws -> Void) async throws -> Value {
        let id = UUID()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            try core.insert(id, context, read)
            defer { core.finish(id) }
            while true {
                try Task.checkCancellation() // cancellation may precede insertion
                try await withCheckedThrowingContinuation { core.park(id, $0) }
                try Task.checkCancellation()
                let request = try await prepare() // token/storage/request preparation, no lock
                do {
                    try Task.checkCancellation()
                    try core.prepared(id)
                    let started = try await MainActor.run {
                        try core.start(id, beforeStart, request.start)
                    }
                    if let started {
                        let result = try await started.result()
                        try Task.checkCancellation(); return result
                    }
                    request.cancel() // final reclassification/rate check requeued this lease
                } catch { request.cancel(); throw error }
            }
        }, onCancel: { self.core.cancel(id) })
    }

    private final class Core: @unchecked Sendable {
        enum Phase { case waiting, preparing, prepared, started }
        final class Entry {
            let id: UUID; let context: Context; let read: Bool
            var phase = Phase.waiting
            var cancelled = false
            var continuation: CheckedContinuation<Void, Error>?
            var cancelStarted: (() -> Void)?
            init(_ id: UUID, _ context: Context, _ read: Bool) { self.id=id; self.context=context; self.read=read }
        }
        let lock = NSLock()
        let clock: () -> Int64
        var entries: [Entry] = []
        var promotions: [String: UUID] = [:]
        var budget = DirectFileRequestBudget()
        var clockFailed = false
        var timer: DispatchWorkItem?
        var timerGeneration: UInt64 = 0
        init(_ clock: @escaping () -> Int64) { self.clock=clock }
        // No continuation or task cancellation is invoked while holding the core.
        func changing<T>(_ body: (inout [() -> Void]) throws -> T) rethrows -> T {
            lock.lock(); var callbacks: [() -> Void] = []
            defer { lock.unlock(); callbacks.forEach { $0() } }
            return try body(&callbacks)
        }
        func entry(_ id: UUID) -> Entry? { entries.first { $0.id == id } }
        func urgent(_ e: Entry) -> Bool { e.context.priority.rawValue <= 1 || e.context.key.map { promotions[$0] != nil } == true }
        func rank(_ e: Entry) -> Int { urgent(e) ? min(e.context.priority.rawValue, 1) : e.context.priority.rawValue }
        func background(_ e: Entry) -> Bool { !urgent(e) }
        func active(_ e: Entry) -> Bool { e.phase != .waiting }
        func kind(_ e: Entry) -> DirectFileRequestBudget.Kind { .init(read:e.read, normal:!urgent(e)) }
        func pendingHigherRead(_ e: Entry) -> Bool {
            e.read && entries.contains { other in
                other.id != e.id && other.read && !other.cancelled && other.phase != .started && rank(other) < rank(e)
            }
        }
        func insert(_ id: UUID, _ context: Context, _ read: Bool) throws {
            try changing { callbacks in
                guard !clockFailed else { throw Failure.clock }
                guard entries.filter({ $0.phase != .started }).count < 64 else { throw Failure.full }
                entries.append(Entry(id,context,read)); drain(&callbacks)
            }
        }
        func park(_ id: UUID, _ continuation: CheckedContinuation<Void, Error>) {
            changing { callbacks in
                guard let e=entry(id), !e.cancelled else { callbacks.append { continuation.resume(throwing:CancellationError()) }; return }
                if clockFailed { callbacks.append { continuation.resume(throwing:Failure.clock) } }
                else if e.phase == .preparing { callbacks.append { continuation.resume() } }
                else { e.continuation=continuation; drain(&callbacks) }
            }
        }
        func prepared(_ id: UUID) throws {
            try changing { _ in
                guard let e=entry(id), !e.cancelled, e.phase == .preparing else { throw CancellationError() }
                e.phase = .prepared
            }
        }
        @MainActor func start<T>(_ id: UUID, _ guardStart: () throws -> Void,
            _ start: () throws -> Started<T>) throws -> Started<T>? {
            try changing { callbacks in
                guard let e=entry(id), !e.cancelled, e.phase == .prepared else { throw CancellationError() }
                guard !clockFailed else { throw Failure.clock }
                try Task.checkCancellation()
                guard e.context.current() else { throw CancellationError() }
                let now=clock(), k=kind(e)
                let backgroundBusy=background(e) && entries.contains { $0.id != id && active($0) && background($0) }
                // Prepared priority is explicit, not NSLock/MainActor arrival order.
                let higherReady=try entries.contains { other in
                    guard other.id != id, other.read == e.read, other.phase == .prepared,
                        !other.cancelled, rank(other) < rank(e), other.context.current() else { return false }
                    return try budget.delay(now,kind(other)) == 0
                }
                let headroom = pendingHigherRead(e)
                let rateDelay=try budget.delay(now,k,headroom:headroom)
                if backgroundBusy || higherReady || rateDelay > 0 {
                    e.phase = .waiting; drain(&callbacks); return nil
                }
                var debited=false
                do {
                    try guardStart() // synchronous memory-only owner/producer marker
                    try Task.checkCancellation()
                    try budget.consume(clock(),k,headroom:headroom); debited=true
                    let value=try start() // synchronous URLSessionTask.resume
                    e.phase = .started; e.cancelStarted=value.cancel
                    drain(&callbacks); return value
                } catch {
                    // An entered final guard consumes conservatively, never refunds.
                    if !debited {
                        do { try budget.consume(clock(),k,headroom:headroom) } catch { clockFailed=true }
                    }
                    throw error
                }
            }
        }
        func cancel(_ id: UUID) {
            changing { callbacks in
                guard let e=entry(id) else { return }
                e.cancelled=true
                if let cancel=e.cancelStarted { callbacks.append(cancel) }
                if let c=e.continuation { e.continuation=nil; callbacks.append { c.resume(throwing:CancellationError()) } }
                // A cancelled preparing worker retains its slot until it exits.
                if e.phase == .waiting { entries.removeAll { $0.id == id } }
                drain(&callbacks)
            }
        }
        func finish(_ id: UUID) { changing { c in entries.removeAll { $0.id == id }; drain(&c) } }
        func cancelScope(_ prefix: String) {
            changing { callbacks in
                // Mark the complete epoch atomically before waking any successor.
                let matches = entries.filter { $0.context.key?.hasPrefix(prefix) == true }
                for e in matches {
                    e.cancelled = true
                    if let cancel = e.cancelStarted { callbacks.append(cancel) }
                    if let c = e.continuation { e.continuation = nil; callbacks.append { c.resume(throwing: CancellationError()) } }
                }
                entries.removeAll { $0.cancelled && $0.phase == .waiting }
                for key in promotions.keys.filter({ $0.hasPrefix(prefix) }) { promotions[key] = nil }
                drain(&callbacks)
            }
        }
        func promote(_ key: String) -> UUID {
            changing { c in let lease=UUID(); promotions[key]=lease; drain(&c); return lease }
        }
        func demote(_ key: String, _ lease: UUID) {
            changing { c in if promotions[key] == lease { promotions[key]=nil }; drain(&c) }
        }
        func backOff(_ value: String?) {
            changing { c in
                do { try budget.backOff(clock(),value) } catch { clockFailed=true }
                drain(&c)
            }
        }
        func drain(_ callbacks: inout [() -> Void]) {
            timer?.cancel(); timer=nil; timerGeneration &+= 1
            if clockFailed {
                for e in entries where e.phase == .waiting {
                    if let c=e.continuation { e.continuation=nil; callbacks.append { c.resume(throwing:Failure.clock) } }
                }
                return
            }
            let now=clock(); var earliest: Int64?
            // Requeues change only phase, never original Entry position. Equal
            // ranks retain original request order across every lost admission.
            let waiting=entries.enumerated().filter { $0.element.phase == .waiting && !$0.element.cancelled }
                .sorted { rank($0.element) == rank($1.element) ? $0.offset < $1.offset : rank($0.element) < rank($1.element) }
            for (_,e) in waiting {
                guard entries.filter({ active($0) }).count < 2 else { break }
                if background(e) && entries.contains(where:{ active($0) && background($0) }) { continue }
                let delay: Int64
                do { delay=try budget.delay(now,kind(e),headroom:pendingHigherRead(e)) }
                catch { clockFailed=true; drain(&callbacks); return }
                if delay > 0 { earliest=min(earliest ?? delay,delay); continue }
                e.phase = .preparing
                if let c=e.continuation { e.continuation=nil; callbacks.append { c.resume() } }
            }
            if let delay=earliest {
                let generation=timerGeneration
                let item=DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.changing { c in if self.timerGeneration == generation { self.drain(&c) } }
                }
                timer=item
                DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now()+Double(delay)/1000,execute:item)
            }
        }
    }
}

/// Runtime credits only; mailbox sequence and account epoch never create credits.
struct DirectFileRequestBudget {
    struct Kind { let read: Bool; let normal: Bool }
    private var reads: [(Int64,Bool)] = [], writes: [(Int64,Bool)] = []
    private var last: Int64?, refilledAt: Int64?
    private var readCreditMillis: Int64=1000 // two500ms credits
    private var cooldown: Int64=0
    private mutating func advance(_ now: Int64) throws {
        guard now >= 0, last.map({ now >= $0 }) != false else { throw DirectFileHTTPGate.Failure.clock }
        last=now
        reads.removeAll { now-$0.0 >= 60_000 }; writes.removeAll { now-$0.0 >= 60_000 }
        if let base=refilledAt { readCreditMillis=min(1000,readCreditMillis+min(1000,now-base)) }
        // Always advance, including while capped: idle fractional time cannot
        // be borrowed after consuming a previously full burst.
        refilledAt=now
    }
    mutating func delay(_ now: Int64, _ kind: Kind, headroom: Bool = false) throws -> Int64 {
        try advance(now)
        var result=max(0,cooldown-now)
        let samples=kind.read ? reads:writes, hard=kind.read ? 144:96, normal=kind.read ? 84:80
        if samples.count >= hard { result=max(result,60_000-(now-samples[samples.count-hard].0)) }
        let ordinary=samples.filter { $0.1 }
        if kind.normal, ordinary.count >= normal { result=max(result,60_000-(now-ordinary[ordinary.count-normal].0)) }
        if kind.read { result=max(result,max(0,500-readCreditMillis)) }
        // Reserve at most one upcoming credit for a higher pending LIVE read.
        // Normal work uses surplus or the next refill; an unprepared peer is
        // never an unbounded head-of-line barrier.
        if kind.read, kind.normal, headroom { result=max(result,max(0,1000-readCreditMillis)) }
        return result
    }
    mutating func consume(_ now: Int64, _ kind: Kind, headroom: Bool = false) throws {
        guard try delay(now,kind,headroom:headroom) == 0 else { throw DirectFileHTTPGate.Failure.clock }
        if kind.read { readCreditMillis -= 500; reads.append((now,kind.normal)) } else { writes.append((now,kind.normal)) }
    }
    mutating func backOff(_ now: Int64, _ value: String?) throws {
        try advance(now)
        let seconds=value.flatMap { Int64($0.trimmingCharacters(in:.whitespacesAndNewlines)) }
        let duration=(seconds.map { $0 > 0 ? min(3600,$0):120 } ?? 120)*1000
        cooldown=max(cooldown,now > Int64.max-duration ? Int64.max:now+duration)
    }
}
