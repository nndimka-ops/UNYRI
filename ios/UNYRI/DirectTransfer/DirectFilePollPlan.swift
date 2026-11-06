import Foundation

/// Separate bounded mailboxes keep a slow peer/hash from owning a whole round.
/// Passive offers get one pending GET, while live and completion work retain
/// independent capacity. Existing job IDs are never dispatched a second time.
enum DirectFilePollPlan {
    enum Kind: CaseIterable, Hashable { case active, bookkeeping, passive }
    struct Entry: Equatable {
        let id: String
        let kind: Kind
    }
    static let maximumJobs = 32

    /// Requested cadence only. The HTTP gate owns actual credits and priority;
    /// none of these classifications grants consent or a transport lease.
    enum Cadence: Equatable { case passive, reserved, urgent, held, queued }

    static func heldInterval(_ cadences: [Cadence], extraReads: Int = 0) -> TimeInterval {
        let held = cadences.filter { $0 == .held }.count
        guard held > 0 else { return 2 }
        let reserved = 30 * cadences.filter { $0 == .reserved }.count
        let normal = 30 * cadences.filter { $0 == .urgent }.count +
            3 * cadences.filter { $0 == .queued }.count +
            6 * cadences.filter { $0 == .passive }.count
        let available = max(1, min(84, max(0, 120 - reserved)) - normal - max(0, extraReads))
        let milliseconds = ceil(60_000 * Double(held) / Double(available))
        return min(60, max(2, ceil(milliseconds / 1_000)))
    }

    static func interval(_ cadence: Cadence, heldInterval: TimeInterval) -> TimeInterval {
        switch cadence {
        case .held: return heldInterval
        case .queued: return 20
        case .passive: return 10
        case .reserved, .urgent: return 2
        }
    }

    static func shortenedHeldDue(_ old: TimeInterval, now: TimeInterval,
                                 interval: TimeInterval) -> TimeInterval {
        min(old, now + interval)
    }

    static func select(_ ordered: [Entry], running: [String: Kind],
                       after: [Kind: String]) -> [Entry] {
        var selected: [Entry] = []
        for kind in Kind.allCases {
            let maximum = kind == .active ? 24 : kind == .bookkeeping ? 7 : 1
            let occupied = running.values.filter { $0 == kind }.count
            let capacity = min(maximum - occupied, maximumJobs - running.count - selected.count)
            guard capacity > 0 else { continue }
            let entries = ordered.filter { $0.kind == kind }
            let start = after[kind].flatMap { id in entries.firstIndex { $0.id == id } }.map { $0 + 1 } ?? 0
            let rotated = Array(entries.dropFirst(start)) + entries.prefix(start)
            for entry in rotated where running[entry.id] == nil && !selected.contains(where: { $0.id == entry.id }) {
                selected.append(entry)
                if selected.filter({ $0.kind == kind }).count == capacity { break }
            }
        }
        return selected
    }
}
