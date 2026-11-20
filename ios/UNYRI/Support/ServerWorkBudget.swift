import Foundation

enum ServerWorkDeadline {
    @TaskLocal static var uptime: TimeInterval?
    static func check(now: TimeInterval = ProcessInfo.processInfo.systemUptime) throws {
        try Task.checkCancellation()
        if let uptime, now >= uptime { throw CancellationError() }
    }
    static var allowsWork: Bool { (try? check()) != nil }
}

/// Monotonic, demand-driven budgets. No scheduled retry or idle work.
struct ServerWorkBudget {
    enum Failure: Error { case nonProgress, exhausted, invalidPage }
    let started: TimeInterval
    var pages = 0
    var records = 0
    let maxPages: Int
    let maxRecords: Int
    let pageLimit: Int
    let seconds: TimeInterval
    init(now: TimeInterval, maxPages: Int = 20, seconds: TimeInterval = 10,
         maxRecords: Int = Int.max, pageLimit: Int = 100) {
        started = now; self.maxPages = maxPages; self.seconds = seconds
        self.maxRecords = maxRecords; self.pageLimit = pageLimit
    }
    func check(now: TimeInterval) throws {
        guard now.isFinite, now >= started, now - started < seconds,
              pages < maxPages, records < maxRecords else { throw Failure.exhausted }
    }
    mutating func accept(after: Int64, next: Int64, sequences: [Int64], hasMore: Bool, deletions: Int, now: TimeInterval) throws {
        try check(now: now)
        guard after >= 0, next >= after, sequences.count <= pageLimit, deletions >= 0, deletions <= 1000,
              sequences.allSatisfy({ $0 > after && $0 <= next }),
              zip(sequences, sequences.dropFirst()).allSatisfy({ pair in pair.0 < pair.1 }) else { throw Failure.invalidPage }
        guard !hasMore || (!sequences.isEmpty && next > after) else { throw Failure.nonProgress }
        guard sequences.count <= maxRecords - records else { throw Failure.exhausted }
        pages += 1; records += sequences.count
    }
}

/// Fixed storage, shared by all invalidation types of one account connection.
struct ServerEventBudget {
    private var started: TimeInterval = -1
    private var count = 0
    mutating func admit(now: TimeInterval, limit: Int = 8, window: TimeInterval = 30) -> Bool {
        guard now.isFinite, now >= 0, limit > 0, window > 0 else { return false }
        if started < 0 || now < started || now - started >= window { started = now; count = 0 }
        guard count < limit else { return false }
        count += 1; return true
    }
}

/// Copies of one origin/account API client share this small gate. A slow
/// /users/me response is bounded to 30 s by URLSession; three reads per 120 s
/// also terminate an invalidation-driven fetch loop with changing revisions.
final class ServerProfileReadBudget: @unchecked Sendable {
    private let lock = NSLock()
    private var budget = ServerEventBudget()
    func admit(now: TimeInterval) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return budget.admit(now: now, limit: 3, window: 120)
    }
}
