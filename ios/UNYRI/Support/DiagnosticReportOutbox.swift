import Foundation

/// Disk IO is actor-owned, bounded, and only happens on an incident or retry.
/// No credentials, user text, screenshots, or sampled media enter this store.
actor DiagnosticReportOutbox {
    struct Pending: Codable, Sendable {
        let report: FeedbackRequest
        let created: Date
        let crashID: String?
        var attempts = 0
        var retryAt: Date
    }
    private struct Scope: Codable {
        var budget = DiagnosticReportBudget()
        var uploads = DiagnosticUploadBudget()
        var pending: [Pending] = []
    }
    struct Failure: Codable, Sendable {
        let kind: DiagnosticTrigger
        let performance: PerformanceSnapshot?
        let created: Date
    }
    private struct State: Codable {
        var scopes: [String: Scope] = [:]
        var failures: [String: [Failure]] = [:]
    }
    private var state: State?
    private let load: @Sendable () throws -> Data?
    private let save: @Sendable (Data) throws -> Void
    init(load: @escaping @Sendable () throws -> Data?, save: @escaping @Sendable (Data) throws -> Void) {
        self.load = load; self.save = save
    }
    private func loaded() throws -> State {
        if let state { return state }
        guard let data = try load() else { let fresh = State(); state = fresh; return fresh }
        guard data.count <= 4 * 1024 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        let value = try JSONDecoder().decode(State.self, from: data)
        guard value.scopes.count <= 64, value.failures.count <= 8,
              value.failures.values.allSatisfy({ $0.count <= 2 && $0.allSatisfy { $0.kind == .signInFailure || $0.kind == .cleanupFailure } }),
              value.scopes.values.allSatisfy({ $0.pending.count <= 5 && $0.budget.reservations.count <= 5 && $0.uploads.reservations.count <= 5 }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        state = value; return value
    }
    private func persist(_ value: State) throws {
        let data = try JSONEncoder().encode(value)
        guard data.count <= 4 * 1024 * 1024 else { throw CocoaError(.fileWriteOutOfSpace) }
        try save(data); state = value
    }
    @discardableResult
    func enqueue(_ report: FeedbackRequest, owner: String, kind: DiagnosticTrigger,
                 crashID: String? = nil, now: Date, optedIn: Bool) throws -> Bool {
        guard optedIn, report.automatic, report.trigger == kind,
              report.description.isEmpty, report.screenshots.isEmpty else { return false }
        var value = try loaded()
        guard value.scopes[owner] != nil || value.scopes.count < 64 else { return false }
        var scope = value.scopes[owner] ?? Scope()
        scope.pending.removeAll { now.timeIntervalSince($0.created) >= 7 * 86_400 }
        guard scope.pending.count < 5,
              crashID == nil || !scope.pending.contains(where: { $0.crashID == crashID }),
              scope.budget.reserve(kind, at: now, optedIn: optedIn) else { return false }
        scope.pending.append(Pending(report: report, created: now, crashID: crashID, retryAt: now))
        value.scopes[owner] = scope; try persist(value); return true
    }
    func due(owner: String, now: Date) throws -> [Pending] {
        let value = try loaded()
        return (value.scopes[owner]?.pending ?? []).filter {
            $0.retryAt <= now && now.timeIntervalSince($0.created) < 7 * 86_400
        }
    }
    func admitUpload(id: String, owner: String, now: Date) throws -> Bool {
        var value = try loaded()
        guard var scope = value.scopes[owner],
              let kind = scope.pending.first(where: { $0.report.id == id })?.report.trigger,
              scope.uploads.admit(id: id, kind: kind, at: now) else { return false }
        value.scopes[owner] = scope; try persist(value); return true
    }
    func completed(id: String, owner: String) throws {
        var value = try loaded(); value.scopes[owner]?.pending.removeAll { $0.report.id == id }; try persist(value)
    }
    func failed(id: String, owner: String, now: Date, rateLimited: Bool) throws {
        var value = try loaded()
        guard var scope = value.scopes[owner], let index = scope.pending.firstIndex(where: { $0.report.id == id }) else { return }
        scope.pending[index].attempts = min(10, scope.pending[index].attempts + 1)
        let delay = rateLimited ? 86_400.0 : min(3600.0, 30 * pow(2, Double(scope.pending[index].attempts - 1)))
        scope.pending[index].retryAt = now.addingTimeInterval(delay)
        value.scopes[owner] = scope; try persist(value)
    }
    func holdFailure(origin: String, kind: DiagnosticTrigger, performance: PerformanceSnapshot?, now: Date) throws {
        guard kind == .signInFailure || kind == .cleanupFailure else { return }
        var value = try loaded()
        guard value.failures[origin] != nil || value.failures.count < 8 else { return }
        var failures = value.failures[origin] ?? []
        failures.removeAll { $0.kind == kind || now.timeIntervalSince($0.created) >= 86_400 }
        failures.append(Failure(kind: kind, performance: performance, created: now))
        value.failures[origin] = failures; try persist(value)
    }
    func pendingFailures(origin: String, now: Date) throws -> [Failure] {
        let value = try loaded()
        return (value.failures[origin] ?? []).filter { now.timeIntervalSince($0.created) < 86_400 }
    }
    func clearFailure(origin: String, kind: DiagnosticTrigger) throws {
        var value = try loaded(); value.failures[origin]?.removeAll { $0.kind == kind }
        if value.failures[origin]?.isEmpty == true { value.failures[origin] = nil }
        try persist(value)
    }
    /// Keep the quota across logout; discard pending payloads. Turning off
    /// automatic reports discards all payloads, including pre-auth failures.
    func discard(owner: String? = nil) throws {
        var value = try loaded()
        if let owner { value.scopes[owner]?.pending = [] }
        else {
            for key in Array(value.scopes.keys) { value.scopes[key]?.pending = [] }
            value.failures = [:]
        }
        try persist(value)
    }
}
