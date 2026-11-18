import Foundation

/// PerformanceDiagnostics.swift classifies networking errors; this Foundation-only
/// target compiles it without the networking layer, so it gets the same cases here.
enum APIError: Error {
    case invalidURL
    case network(String)
    case http(Int)
    case server(code: String, message: String)
    case decoding(String)
    case missingSession
}

func expect(_ condition: Bool, _ message: String = "contract failed") {
    precondition(condition, message)
}
final class MemoryFile: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Data?
    func load() -> Data? { lock.lock(); defer { lock.unlock() }; return bytes }
    func save(_ data: Data) { lock.lock(); defer { lock.unlock() }; bytes = data }
    func outbox() -> DiagnosticReportOutbox {
        DiagnosticReportOutbox(load: { self.load() }, save: { self.save($0) })
    }
}
@main struct DiagnosticReportContracts {
    static func main() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        expect(!DiagnosticReportPolicy.defaultEnabled(debugOrQA: false, receiptName: "receipt"))
        expect(!DiagnosticReportPolicy.defaultEnabled(debugOrQA: false, receiptName: nil))
        expect(DiagnosticReportPolicy.defaultEnabled(debugOrQA: false, receiptName: "sandboxReceipt"))
        expect(DiagnosticReportPolicy.defaultEnabled(debugOrQA: true, receiptName: "receipt"))
        expect(DiagnosticReportPolicy.defaultEnabled(debugOrQA: true, receiptName: nil))
        for connected in [false, true] {
            for cellular in [false, true] {
                for restricted in [false, true] {
                    expect(DiagnosticReportPolicy.allowsNetwork(connected: connected, cellular: cellular, restricted: restricted)
                           == (connected && !(cellular && restricted)))
                }
            }
        }
        expect(DiagnosticReportPolicy.kind(for: .crash) == .crash)
        expect(DiagnosticReportPolicy.kind(for: .cleanupFailure) == .cleanupFailure)
        expect(DiagnosticReportPolicy.kind(for: .signInFailure) == .signInFailure)
        for seconds in [0.0, 1.999, 2, .nan, .infinity] {
            expect(DiagnosticReportPolicy.kind(for: .mainStall(seconds: seconds)) == nil)
        }
        expect(DiagnosticReportPolicy.kind(for: .mainStall(seconds: 2.001)) == .mainStall)
        expect(DiagnosticReportPolicy.kind(for: .callEnded(failedToConnect: true, connectedSeconds: nil, mediaProblem: false)) == .callFailed)
        for seconds in [0.0, 9.999, 10] {
            expect(DiagnosticReportPolicy.kind(for: .callEnded(failedToConnect: false, connectedSeconds: seconds, mediaProblem: true)) == .callMedia)
        }
        let invalidDurations: [TimeInterval?] = [nil, -1, 10.001, .infinity, .nan]
        for seconds in invalidDurations {
            expect(DiagnosticReportPolicy.kind(for: .callEnded(failedToConnect: false, connectedSeconds: seconds, mediaProblem: true)) == nil)
        }
        expect(DiagnosticReportPolicy.kind(for: .callEnded(failedToConnect: false, connectedSeconds: 5, mediaProblem: false)) == nil)
        expect(DiagnosticReportPolicy.kind(for: .blackVideo(local: true, seconds: 3, trackLive: true)) == nil)
        expect(DiagnosticReportPolicy.kind(for: .blackVideo(local: true, seconds: 3.001, trackLive: false)) == nil)
        expect(DiagnosticReportPolicy.kind(for: .blackVideo(local: true, seconds: 3.001, trackLive: true)) == .selfVideoBlack)
        expect(DiagnosticReportPolicy.kind(for: .blackVideo(local: false, seconds: 3.001, trackLive: true)) == .peerVideoBlack)
        expect(DiagnosticTrigger.allCases.count == 8)
        expect(DiagnosticTrigger.allCases.allSatisfy { $0.rawValue.utf8.count <= 32 && $0.rawValue.utf8.allSatisfy { $0 < 128 } })

        var budget = DiagnosticReportBudget()
        expect(!budget.reserve(.crash, at: now, optedIn: false)); expect(budget.reservations.isEmpty)
        expect(budget.reserve(.crash, at: now, optedIn: true))
        expect(!budget.reserve(.crash, at: now.addingTimeInterval(3599.999), optedIn: true))
        expect(budget.reserve(.mainStall, at: now, optedIn: true), "different kind has its own hour")
        expect(budget.reserve(.crash, at: now.addingTimeInterval(3600), optedIn: true))
        expect(budget.reserve(.callFailed, at: now, optedIn: true))
        expect(budget.reserve(.selfVideoBlack, at: now, optedIn: true))
        expect(!budget.reserve(.peerVideoBlack, at: now, optedIn: true), "sixth report rejected")
        let encoded = try JSONEncoder().encode(budget)
        var restored = try JSONDecoder().decode(DiagnosticReportBudget.self, from: encoded)
        expect(!restored.reserve(.cleanupFailure, at: now.addingTimeInterval(86_399), optedIn: true))
        expect(restored.reserve(.cleanupFailure, at: now.addingTimeInterval(86_400), optedIn: true))
        expect(!restored.reserve(.cleanupFailure, at: now, optedIn: true), "clock rollback cannot reset quota")

        var delivery = DiagnosticUploadBudget()
        for (index, kind) in DiagnosticTrigger.allCases.prefix(5).enumerated() {
            expect(delivery.admit(id: "report-" + String(index), kind: kind, at: now))
        }
        expect(!delivery.admit(id: "sixth", kind: .cleanupFailure, at: now))
        expect(delivery.admit(id: "report-0", kind: .crash, at: now.addingTimeInterval(30)), "retry uses the same delivery slot")
        var deliveryAfterRestart = try JSONDecoder().decode(DiagnosticUploadBudget.self, from: JSONEncoder().encode(delivery))
        expect(!deliveryAfterRestart.admit(id: "another-crash", kind: .crash, at: now.addingTimeInterval(3599)))
        expect(!deliveryAfterRestart.admit(id: "backlog", kind: .cleanupFailure, at: now.addingTimeInterval(86_399)))
        expect(deliveryAfterRestart.admit(id: "backlog", kind: .cleanupFailure, at: now.addingTimeInterval(86_400)))

        var video = DiagnosticBlackVideoWindow()
        for i in 0...7 {
            let elapsed = Double(i) / 2
            expect(video.observe(luma: 16, at: elapsed, live: true) == elapsed)
        }
        expect(video.observe(luma: 100, at: 4, live: true) == nil)
        expect(video.observe(luma: 0, at: 4.5, live: true) == 0)
        expect(video.observe(luma: nil, at: 5, live: true) == nil)
        expect(video.observe(luma: 0, at: 6, live: true) == 0)
        expect(video.observe(luma: 0, at: 8, live: true) == 0, "frame gap is not continuous black")
        expect(video.observe(luma: 0, at: 8.5, live: false) == nil)

        func report(_ kind: DiagnosticTrigger, id: String = UUID().uuidString) -> FeedbackRequest {
            FeedbackRequest(id: id, description: "", appVersion: "1", osVersion: "18", screenshots: [],
                            performance: nil, automatic: true, trigger: kind)
        }
        let file = MemoryFile(), owner = "https://one.example|alice", other = "https://two.example|alice"
        let store = file.outbox()
        expect(try await store.enqueue(report(.crash), owner: owner, kind: .crash, now: now, optedIn: false) == false)
        let first = report(.crash)
        expect(try await store.enqueue(first, owner: owner, kind: .crash, crashID: "crash1", now: now, optedIn: true))
        let restart = file.outbox()
        expect(try await restart.enqueue(report(.crash), owner: owner, kind: .crash, now: now, optedIn: true) == false)
        expect(try await restart.due(owner: other, now: now).isEmpty, "origins and accounts cannot share payloads")
        expect(try await restart.due(owner: owner, now: now).first?.report.id == first.id)
        expect(try await restart.admitUpload(id: first.id, owner: owner, now: now))
        expect(try await restart.admitUpload(id: first.id, owner: other, now: now) == false)
        try await restart.failed(id: first.id, owner: owner, now: now, rateLimited: false)
        expect(try await restart.due(owner: owner, now: now.addingTimeInterval(29)).isEmpty)
        let retry = try await file.outbox().due(owner: owner, now: now.addingTimeInterval(30))
        expect(retry.first?.report.id == first.id, "retry UUID is stable across launch")
        expect(retry.first?.report.description == "" && retry.first?.report.screenshots.isEmpty == true)
        try await restart.failed(id: first.id, owner: owner, now: now, rateLimited: true)
        expect(try await restart.due(owner: owner, now: now.addingTimeInterval(86_399)).isEmpty)
        try await restart.completed(id: first.id, owner: owner)
        expect(try await restart.due(owner: owner, now: now.addingTimeInterval(86_400)).isEmpty)
        expect(try await restart.enqueue(report(.crash), owner: owner, kind: .crash, now: now, optedIn: true) == false,
               "success does not refund quota")
        expect(try await restart.enqueue(report(.mainStall), owner: owner, kind: .mainStall, now: now, optedIn: true))
        try await restart.holdFailure(origin: "https://one.example", kind: .signInFailure, performance: nil, now: now)
        expect(try await restart.pendingFailures(origin: "https://two.example", now: now).isEmpty)
        expect(try await restart.pendingFailures(origin: "https://one.example", now: now).first?.kind == .signInFailure)
        try await restart.holdFailure(origin: "https://one.example", kind: .cleanupFailure, performance: nil, now: now)
        expect(try await restart.pendingFailures(origin: "https://one.example", now: now).count == 2, "pre-auth failure kinds are independent")
        try await restart.clearFailure(origin: "https://one.example", kind: .signInFailure)
        expect(try await restart.pendingFailures(origin: "https://one.example", now: now).first?.kind == .cleanupFailure)
        try await restart.discard()
        expect(try await restart.due(owner: owner, now: now).isEmpty, "opt-out removes payloads")
        expect(try await restart.pendingFailures(origin: "https://one.example", now: now).isEmpty)
        expect(try await restart.enqueue(report(.mainStall), owner: owner, kind: .mainStall, now: now, optedIn: true) == false,
               "opt-out and logout preserve quotas")
        var invalid = report(.callFailed); invalid = FeedbackRequest(id: invalid.id, description: "draft", appVersion: "1", osVersion: "18", screenshots: [], performance: nil, automatic: true, trigger: .callFailed)
        expect(try await restart.enqueue(invalid, owner: owner, kind: .callFailed, now: now, optedIn: true) == false)
        let failing = DiagnosticReportOutbox(load: { nil }, save: { _ in throw CocoaError(.fileWriteOutOfSpace) })
        do {
            _ = try await failing.enqueue(report(.crash), owner: owner, kind: .crash, now: now, optedIn: true)
            preconditionFailure("a failed disk write must not admit an automatic report")
        } catch { }
        print("Diagnostic report contracts passed: defaults, triggers, opt-in, network, persisted quotas, ownership and retries")
    }
}
