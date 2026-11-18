import Foundation

enum PerformanceKind: String, Codable, CaseIterable, Sendable {
    case appActive = "app_active", appInactive = "app_inactive"
    case screenOpen = "screen_open", screenClose = "screen_close"
    case touch, action, frameSummary = "frame_summary", frameGap = "frame_gap", mainStall = "main_stall"
    case keyboardShow = "keyboard_show", keyboardHide = "keyboard_hide", memory, memoryWarning = "memory_warning"
    case network, thermal, lowPower = "low_power", chatOpen = "chat_open", historyLoad = "history_load"
    case mediaPage = "media_page", videoReady = "video_ready", previewDecode = "preview_decode", previewCache = "preview_cache"
    case mediaPrepare = "media_prepare", mediaCompress = "media_compress", mediaUpload = "media_upload", mediaDownload = "media_download"
    case messageSend = "message_send", networkRequest = "network_request", networkError = "network_error", queue, markProblem = "mark_problem"
    case callState = "call_state"
    case uiUpdate = "ui_update"
    case appLaunch = "app_launch", auth, realtime, push, sync, decrypt, errorShown = "error_shown"
}
enum PerformanceScreen: String, Codable, Sendable { case unknown, app, login, chats, chat, search, settings, media, gallery, call, report }

struct PerformanceEvent: Codable, Sendable {
    let sequence: UInt64
    let atMs: Int64
    let kind: PerformanceKind
    let screen: PerformanceScreen
    let metrics: [String: Double]
}
struct PerformanceSnapshot: Codable, Sendable {
    let schemaVersion: Int
    let recordingId: UUID
    let platform: String
    let appVersion: String
    let build: String
    let osVersion: String
    let hardware: String
    let dropped: UInt64
    var omitted: UInt64 = 0
    var events: [PerformanceEvent]
    func encoded() throws -> Data {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        return try encoder.encode(self)
    }
    static func decode(_ data: Data) throws -> Self {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Self.self, from: data)
    }
}

/// No strings supplied by message models, views or errors enter the event log.
/// Foundation-only so bounds, account retirement and encoding run on the PC.
final class PerformanceRecorder: @unchecked Sendable {
    @TaskLocal static var trace: Double?
    static let shared = PerformanceRecorder()
    static let enabledKey = "performanceDiagnosticsEnabled"
    static let onlineKey = "performanceDiagnosticsOnline"
    static let allowedMetrics: Set<String> = ["duration_ms", "gap_ms", "budget_ms", "frames", "late_frames", "max_gap_ms", "memory_mb", "thermal", "low_power", "network", "count", "bytes", "cache_hit", "status", "success", "cancelled", "queue_count", "visible_count", "items", "fps", "maximum_fps", "phase", "direction", "width", "height", "span", "operation", "trace", "skew_ms", "code", "attempt", "interface", "reason"]
    private let lock = NSLock()
    private var enabled = false
    private var recording = UUID()
    private var sequence: UInt64 = 0
    private var acknowledged: UInt64 = 0
    private var spanCounter: UInt64 = 0
    private var operationCounter: UInt64 = 0
    private var operationTokens: [String: Double] = [:]
    private var operationOrder: [String] = []
    private var dropped: UInt64 = 0
    private var events: [PerformanceEvent] = []
    private var problemEvents: [PerformanceEvent] = []
    private var problemAt: Int64?
    private var problemAfter = 0
    private var activeNetwork = 0
    private var cacheHits = 0
    private var lastScrollSample: TimeInterval = -.infinity
    func recordScroll(visibleCount: Int) {
        locked {
            guard enabled else { return }
            let now = ProcessInfo.processInfo.systemUptime
            guard now - lastScrollSample >= 0.5 else { return }
            lastScrollSample = now
            appendLocked(.action, ["phase": 3, "visible_count": Double(max(0, visibleCount))], screen: screens.last?.1 ?? .app, at: Date())
        }
    }

    /// Frequent memory-cache hits are counters, not one event per body/task.
    func recordMemoryCacheHit() {
        locked {
            guard enabled else { return }
            cacheHits += 1
            if cacheHits >= 16 { flushCacheHitsLocked() }
        }
    }
    private func flushCacheHitsLocked() {
        guard cacheHits > 0 else { return }
        let count = cacheHits; cacheHits = 0
        appendLocked(.previewCache, ["cache_hit": 1, "count": Double(count)], screen: screens.last?.1 ?? .app, at: Date())
    }
    private var screens: [(UUID, PerformanceScreen)] = []
    private var metadata = (app: "unknown", build: "unknown", os: "unknown", hardware: "unknown")
    private(set) var maximumEvents: Int
    init(maximumEvents: Int = 1200) { self.maximumEvents = max(1, min(1200, maximumEvents)) }
    var isEnabled: Bool { locked { enabled } }
    var recordingId: UUID { locked { recording } }
    var eventCount: Int { locked { events.count } }
    func configure(enabled: Bool, app: String, build: String, os: String, hardware: String) {
        locked {
            self.enabled = enabled
            metadata = (Self.safeVersion(app), Self.safeVersion(build), Self.safeVersion(os), Self.safeVersion(hardware))
            if !enabled { resetLocked() }
        }
    }
    func reset() { locked { resetLocked() } }
    private func resetLocked() {
        recording = UUID(); sequence = 0; acknowledged = 0; spanCounter = 0; dropped = 0; events.removeAll(keepingCapacity: false)
        problemEvents.removeAll(); problemAt = nil; problemAfter = 0
        activeNetwork = 0; cacheHits = 0
        lastScrollSample = -.infinity
        operationTokens.removeAll(keepingCapacity: false); operationCounter = 0
        operationOrder.removeAll()
    }
    private static func safeVersion(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,_-+")
        guard !value.isEmpty, value.utf8.count <= 64, value.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return "unknown" }
        return value
    }
    func pushScreen(_ screen: PerformanceScreen, token: UUID) {
        locked {
            screens.removeAll { $0.0 == token }; screens.append((token, screen))
            appendLocked(.screenOpen, [:], screen: screen, at: Date())
        }
    }
    func popScreen(token: UUID) {
        locked {
            guard let closing = screens.first(where: { $0.0 == token }) else { return }
            appendLocked(.screenClose, [:], screen: closing.1, at: Date())
            screens.removeAll { $0.0 == token }
        }
    }
    func record(_ kind: PerformanceKind, _ metrics: [String: Double] = [:], at: Date = Date(), screen: PerformanceScreen? = nil) {
        locked { appendLocked(kind, metrics, screen: screen ?? screens.last?.1 ?? .app, at: at) }
    }
    /// Converts an internal correlation token to a recording-local number.
    /// Tokens are never encoded, hashed or exported; at most 1024 are retained.
    func operation(_ internalToken: String) -> Double {
        locked {
            guard enabled else { return 0 }
            renewSequenceIfNeededLocked()
            if let existing = operationTokens[internalToken] { return existing }
            operationCounter += 1
            let number = Double(operationCounter)
            operationTokens[internalToken] = number; operationOrder.append(internalToken)
            if operationOrder.count > 1024 { operationTokens[operationOrder.removeFirst()] = nil }
            return number
        }
    }
    private func appendLocked(_ kind: PerformanceKind, _ metrics: [String: Double], screen: PerformanceScreen, at: Date) {
        guard enabled else { return }
        renewSequenceIfNeededLocked()
        var input = metrics
        if input["trace"] == nil, let trace = Self.trace { input["trace"] = trace }
        let safe = input.filter { Self.allowedMetrics.contains($0.key) && $0.value.isFinite && $0.value >= 0 && $0.value <= 1_000_000_000_000 }
        guard safe.count <= 16 else { return }
        sequence += 1
        events.append(PerformanceEvent(sequence: sequence, atMs: Int64(at.timeIntervalSince1970 * 1000), kind: kind, screen: screen, metrics: safe))
        if kind == .markProblem {
            problemAt = Int64(at.timeIntervalSince1970 * 1000)
            problemEvents = Array(events.suffix(min(180, maximumEvents)))
            problemAfter = min(120, max(0, maximumEvents - problemEvents.count))
        } else if problemAfter > 0, let last = events.last {
            problemEvents.append(last); problemAfter -= 1
        }
        let cutoff = Int64(at.timeIntervalSince1970 * 1000) - 15 * 60 * 1000
        let expired = events.prefix { $0.atMs < cutoff }.count
        let trim = max(expired, events.count - maximumEvents)
        if trim > 0 { events.removeFirst(trim); dropped += UInt64(trim) }
    }
    struct Span {
        fileprivate let recording: UUID
        fileprivate let id: UInt64
        fileprivate let start: TimeInterval
        fileprivate let kind: PerformanceKind
        fileprivate let screen: PerformanceScreen
        fileprivate let metrics: [String: Double]
    }
    func begin(_ kind: PerformanceKind, _ metrics: [String: Double] = [:]) -> Span? {
        locked {
            guard enabled else { return nil }
            renewSequenceIfNeededLocked()
            spanCounter += 1
            var context = metrics
            if kind == .networkRequest { activeNetwork += 1; context["queue_count"] = Double(activeNetwork) }
            if context["trace"] == nil, let trace = Self.trace { context["trace"] = trace }
            let span = Span(recording: recording, id: spanCounter, start: ProcessInfo.processInfo.systemUptime, kind: kind, screen: screens.last?.1 ?? .app, metrics: context)
            var values = context; values["phase"] = 0; values["span"] = Double(span.id)
            // Request starts are noise next to their end events (which carry the
            // duration); they filled most of the report window (report 0.3.27).
            if kind != .networkRequest { appendLocked(kind, values, screen: span.screen, at: Date()) }
            return span
        }
    }
    func end(_ span: Span?, success: Bool = true, metrics: [String: Double] = [:]) {
        guard let span else { return }
        locked {
            guard enabled else { return }
            renewSequenceIfNeededLocked()
            guard span.recording == recording else { return }
            var values = span.metrics.merging(metrics, uniquingKeysWith: { _, latest in latest })
            if span.kind == .networkRequest { activeNetwork = max(0, activeNetwork - 1); values["queue_count"] = Double(activeNetwork) }
            values["phase"] = 1; values["span"] = Double(span.id); values["success"] = success ? 1 : 0
            values["cancelled"] = Task.isCancelled ? 1 : 0
            values["duration_ms"] = max(0, (ProcessInfo.processInfo.systemUptime - span.start) * 1000)
            appendLocked(span.kind, values, screen: span.screen, at: Date())
        }
    }
    /// The newest ~560 events, keeping at most 120 network requests and 150 UI updates
    /// so touches, keyboard, frames and screens are not crowded out, plus up to 40
    /// older milestones: a report sent a few minutes after opening a chat lost the
    /// opening itself (report 0.4.1 held only the last 160 s).
    static func reportWindow(_ events: [PerformanceEvent]) -> [PerformanceEvent] {
        var picked: [PerformanceEvent] = []
        var network = 0, updates = 0
        for event in events.reversed() {
            if event.kind == .networkRequest {
                guard network < 120 else { continue }
                network += 1
            }
            if event.kind == .uiUpdate {
                guard updates < 150 else { continue }
                updates += 1
            }
            picked.append(event)
            if picked.count == 560 { break }
        }
        let milestones: Set<PerformanceKind> = [.chatOpen, .mainStall, .screenOpen, .screenClose, .historyLoad, .memoryWarning]
        let kept = Set(picked.map(\.sequence))
        var older: [PerformanceEvent] = []
        for event in events.reversed() where older.count < 40 && milestones.contains(event.kind) && !kept.contains(event.sequence) {
            older.append(event)
        }
        return (picked + older).sorted { $0.sequence < $1.sequence }
    }
    func acknowledge(_ snapshot: PerformanceSnapshot) {
        locked { if snapshot.recordingId == recording { acknowledged = max(acknowledged, snapshot.events.last?.sequence ?? 0) } }
    }
    func snapshot(online: Bool = false, maximumBytes: Int = 240 * 1024, preferProblemWindow: Bool = true) -> PerformanceSnapshot? {
        var value: PerformanceSnapshot? = locked {
            guard enabled else { return nil }
            flushCacheHitsLocked()
            let cutoff = Int64(Date().timeIntervalSince1970 * 1000) - 15 * 60 * 1000
            let expired = events.prefix { $0.atMs < cutoff }.count
            if expired > 0 { events.removeFirst(expired); dropped += UInt64(expired) }
            if let markedAt = problemAt, markedAt < cutoff { problemEvents.removeAll(); problemAt = nil; problemAfter = 0 }
            if !problemEvents.isEmpty { problemEvents.removeAll { $0.atMs < cutoff } }
            let selected = online ? Array(events.filter { $0.sequence > acknowledged }.prefix(64)) : problemEvents.isEmpty || !preferProblemWindow ? Self.reportWindow(events) : problemEvents
            guard !selected.isEmpty else { return nil }
            return PerformanceSnapshot(schemaVersion: 1, recordingId: recording, platform: "ios", appVersion: metadata.app, build: metadata.build, osVersion: metadata.os, hardware: metadata.hardware, dropped: dropped, omitted: online ? 0 : UInt64(max(0, events.count - selected.count)), events: selected)
        }
        let limit = max(1024, min(256 * 1024, maximumBytes))
        while let current = value, ((try? current.encoded().count) ?? Int.max) > limit {
            guard current.events.count > 1 else { return nil }
            if online { value?.events.removeLast() } else { value?.events.removeFirst(); value?.omitted += 1 }
        }
        return value
    }
    /// Restore only a locally owner-checked snapshot. The owner itself is never exported.
    func restore(_ snapshot: PerformanceSnapshot, now: Date = Date()) {
        locked {
            guard enabled, snapshot.schemaVersion == 1, snapshot.platform == "ios",
                  snapshot.appVersion == metadata.app, snapshot.build == metadata.build,
                  snapshot.osVersion == metadata.os, snapshot.hardware == metadata.hardware else { return }
            let cutoff = Int64(now.timeIntervalSince1970 * 1000) - 15 * 60 * 1000
            // Validate legacy rows before dropping the retired metric. Keep
            // event identities and valid companion metrics for server dedup.
            let restorable = snapshot.events.filter { $0.atMs >= cutoff && $0.atMs <= Int64(now.timeIntervalSince1970 * 1000) + 1000 && $0.metrics.count <= 16 && $0.metrics.allSatisfy { (Self.allowedMetrics.contains($0.key) || $0.key == "disk_free_mb") && $0.value.isFinite && $0.value >= 0 && $0.value <= 1_000_000_000_000 } }
            let valid = restorable.map { event in
                PerformanceEvent(sequence: event.sequence, atMs: event.atMs, kind: event.kind,
                    screen: event.screen, metrics: event.metrics.filter { $0.key != "disk_free_mb" })
            }
            guard !valid.isEmpty else { return }
            guard valid.first!.sequence > 0, valid.last!.sequence < 1_000_000_000_000,
                  zip(valid, valid.dropFirst()).allSatisfy({ $0.sequence < $1.sequence }) else { return }
            recording = snapshot.recordingId; events = Array(valid.suffix(maximumEvents))
            problemEvents.removeAll(); problemAt = nil; problemAfter = 0; activeNetwork = 0; cacheHits = 0
            operationTokens.removeAll(); operationOrder.removeAll()
            sequence = events.map(\.sequence).max() ?? 0; acknowledged = 0
            dropped = min(1_000_000_000_000, snapshot.dropped) + min(1200, snapshot.omitted)
            spanCounter = UInt64(events.compactMap { $0.metrics["span"] }.max() ?? 0)
            operationCounter = UInt64(events.flatMap { [$0.metrics["operation"], $0.metrics["trace"]].compactMap { $0 } }.max() ?? 0)
        }
    }
    private func renewSequenceIfNeededLocked() {
        if sequence >= 999_999_999_999 { resetLocked() }
    }
    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }
}


/// Whole-app diagnostics for people who turned diagnostics on. Numbers only: no
/// message text, names, chat or user IDs ever enter these events (the server
/// rejects anything else). They explain cases like a session stuck on
/// "Authentication required" or a socket that never connects.
enum UnyriDiag {
    enum AuthPhase: Double { case refreshStart = 1, refreshOK, refreshFailed, bearerRejected, terminalLogout, clockSkew }
    enum RealtimePhase: Double { case connect = 1, opened, ready, disconnected, handshakeRejected, pathChanged, reconnectScheduled, transportError }
    enum PushPhase: Double { case voipReceived = 1, voipReported, voipUnmatched, apnsRegistered, apnsFailed, messageWake }

    private static func put(_ kind: PerformanceKind, _ values: [String: Double?]) {
        guard PerformanceRecorder.shared.isEnabled else { return }
        var metrics: [String: Double] = [:]
        for (key, value) in values { if let value, value.isFinite, value >= 0 { metrics[key] = value } }
        PerformanceRecorder.shared.record(kind, metrics)
    }
    static func auth(_ phase: AuthPhase, status: Int? = nil, code: Int? = nil, durationMs: Double? = nil) {
        put(.auth, ["phase": phase.rawValue, "status": status.map(Double.init), "code": code.map(Double.init), "duration_ms": durationMs])
    }
    /// 1 invalid_refresh_token, 2 session_revoked, 3 unauthorized, 4 network, 5 other server, 6 other.
    static func code(for error: Error) -> Int {
        if let api = error as? APIError {
            switch api {
            case .server(let code, _):
                switch code {
                case "invalid_refresh_token": return 1
                case "session_revoked": return 2
                case "unauthorized": return 3
                default: return 5
                }
            case .network: return 4
            default: return 6
            }
        }
        if error is URLError { return 4 }
        return 6
    }
    static func realtime(_ phase: RealtimePhase, attempt: Int? = nil, status: Int? = nil, code: Int? = nil,
                         durationMs: Double? = nil, interface: Int? = nil, reason: Int? = nil) {
        put(.realtime, ["phase": phase.rawValue, "attempt": attempt.map(Double.init), "status": status.map(Double.init),
                        "code": code.map(Double.init), "duration_ms": durationMs, "interface": interface.map(Double.init),
                        "reason": reason.map(Double.init)])
    }
    static func push(_ phase: PushPhase, reason: Int? = nil, durationMs: Double? = nil) {
        put(.push, ["phase": phase.rawValue, "reason": reason.map(Double.init), "duration_ms": durationMs])
    }
    static func sync(finished: Bool, items: Int? = nil, durationMs: Double? = nil, success: Bool? = nil) {
        put(.sync, ["phase": finished ? 2 : 1, "items": items.map(Double.init), "duration_ms": durationMs,
                    "success": success.map { $0 ? 1 : 0 }])
    }
    static func decryptFailed(identityChanged: Bool) {
        put(.decrypt, ["phase": 1, "code": identityChanged ? 2 : 1])
    }
    /// 1 network (code = URLError), 2 server rejection, 3 auth, 4 other.
    static func errorShown(_ error: Error) {
        var kind = 4.0, code: Double? = nil
        if let url = error as? URLError { kind = 1; code = Double(abs(url.code.rawValue)) }
        else if let api = error as? APIError {
            switch api {
            case .network: kind = 1
            case .server(let value, _): kind = ["unauthorized", "session_revoked", "invalid_refresh_token"].contains(value) ? 3 : 2
            case .http(let status): kind = status == 401 ? 3 : 2; code = Double(status)
            default: kind = 4
            }
        }
        put(.errorShown, ["phase": kind, "code": code])
    }
    static func appLaunch(background: Bool, durationMs: Double?) {
        put(.appLaunch, ["phase": background ? 1 : 0, "duration_ms": durationMs])
    }

    /// Difference between this phone's clock and the server's (HTTP Date), in ms.
    /// Kept even without diagnostics: the connection check shows it.
    private static let skewLock = NSLock()
    private static var skew: (ms: Double, at: TimeInterval)?
    private static var lastSkewRecord: TimeInterval = 0
    static var clockSkewMs: Double? { skewLock.lock(); defer { skewLock.unlock() }; return skew?.ms }
    private static let httpDate: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter
    }()
    static func observeServerDate(_ header: String?) {
        let now = ProcessInfo.processInfo.systemUptime
        skewLock.lock()
        if let last = skew, now - last.at < 60 { skewLock.unlock(); return }
        skewLock.unlock()
        guard let header, let server = httpDate.date(from: header) else { return }
        // HTTP dates have one-second resolution; ignore differences under 30 s.
        let difference = Date().timeIntervalSince(server) * 1000
        skewLock.lock()
        skew = (difference, now)
        let shouldRecord = abs(difference) >= 30_000 && now - lastSkewRecord > 600
        if shouldRecord { lastSkewRecord = now }
        skewLock.unlock()
        if shouldRecord {
            put(.auth, ["phase": AuthPhase.clockSkew.rawValue, "skew_ms": abs(difference), "direction": difference >= 0 ? 1 : 0])
        }
    }
}
