import Foundation

/// One authenticated application-level PING. Transport control-PONG alone
/// cannot confirm that JSON events are still being processed. No payload or
/// account identifiers are exported by this policy.
struct RealtimeApplicationProbe {
    static let timeout: TimeInterval = 10
    static let delayedWakeGrace: TimeInterval = 2
    enum Deadline: Equatable { case ignore, wait(TimeInterval), retryFresh, disconnect }
    let id: String
    let attempt: UUID
    let generation: UInt64
    let startedAt: TimeInterval
    private(set) var completed = false
    private(set) var measuredMilliseconds: Int?

    func owns(attempt: UUID, generation: UInt64) -> Bool {
        self.attempt == attempt && self.generation == generation
    }

    mutating func receivePong(requestID: String?, uptime: TimeInterval,
                              attempt: UUID, generation: UInt64) -> Bool {
        guard !completed, owns(attempt: attempt, generation: generation),
              uptime.isFinite, uptime >= startedAt,
              requestID == nil || requestID == id else { return false }
        completed = true
        // Older servers omit request_id. That proves liveness, not a
        // correlated round trip: never manufacture a ping measurement.
        if requestID == id {
            measuredMilliseconds = max(1, Int(min(60_000, (uptime - startedAt) * 1000)))
        }
        return true
    }

    func deadline(uptime: TimeInterval, attempt: UUID, generation: UInt64) -> Deadline {
        guard !completed, owns(attempt: attempt, generation: generation),
              uptime.isFinite, uptime >= startedAt else { return .ignore }
        let elapsed = uptime - startedAt
        if elapsed < Self.timeout { return .wait(Self.timeout - elapsed) }
        // A suspended process/main loop must first get a fresh chance to
        // receive a response; a delayed timer must not drop an ongoing call.
        if elapsed > Self.timeout + Self.delayedWakeGrace { return .retryFresh }
        return .disconnect
    }
}
