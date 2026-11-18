import Foundation

/// Stable wire kinds, shared with feedback.rs. No UIKit or background worker.
enum DiagnosticTrigger: String, Codable, CaseIterable, Sendable {
    case crash, mainStall = "main_stall", callFailed = "call_failed", callMedia = "call_media"
    case selfVideoBlack = "self_video_black", peerVideoBlack = "peer_video_black"
    case cleanupFailure = "cleanup_failure", signInFailure = "sign_in_failure"
}

enum DiagnosticIncident: Sendable {
    case crash
    case mainStall(seconds: TimeInterval)
    case callEnded(failedToConnect: Bool, connectedSeconds: TimeInterval?, mediaProblem: Bool)
    case blackVideo(local: Bool, seconds: TimeInterval, trackLive: Bool)
    case cleanupFailure, signInFailure
}

enum DiagnosticReportPolicy {
    static func defaultEnabled(debugOrQA: Bool, receiptName: String?) -> Bool {
        debugOrQA || receiptName == "sandboxReceipt"
    }
    static func kind(for incident: DiagnosticIncident) -> DiagnosticTrigger? {
        switch incident {
        case .crash: return .crash
        case .mainStall(let seconds): return seconds.isFinite && seconds > 2 ? .mainStall : nil
        case .callEnded(let failed, let seconds, let media):
            if failed { return .callFailed }
            if media, let seconds, seconds.isFinite, seconds >= 0, seconds <= 10 { return .callMedia }
            return nil
        case .blackVideo(let local, let seconds, let live):
            return live && seconds.isFinite && seconds > 3 ? (local ? .selfVideoBlack : .peerVideoBlack) : nil
        case .cleanupFailure: return .cleanupFailure
        case .signInFailure: return .signInFailure
        }
    }
    static func allowsNetwork(connected: Bool, cellular: Bool, restricted: Bool) -> Bool {
        connected && !(cellular && restricted)
    }
}

/// A rolling day/hour is stricter than calendar buckets, including at midnight.
/// Reservations count queued reports, not upload attempts. Keep future entries
/// on clock rollback so changing the clock cannot reset a quota.
struct DiagnosticReportBudget: Codable, Sendable {
    struct Reservation: Codable, Sendable { let kind: DiagnosticTrigger; let at: Date }
    private(set) var reservations: [Reservation] = []
    mutating func reserve(_ kind: DiagnosticTrigger, at now: Date, optedIn: Bool) -> Bool {
        guard optedIn, now.timeIntervalSince1970.isFinite else { return false }
        reservations.removeAll { now.timeIntervalSince($0.at) >= 86_400 }
        guard reservations.count < 5,
              !reservations.contains(where: { $0.kind == kind && now.timeIntervalSince($0.at) < 3600 }) else { return false }
        reservations.append(Reservation(kind: kind, at: now)); return true
    }
}

/// Fed by frames already arriving at a renderer, never by a new timer. Only
/// continuous observed black counts; a missing/unknown/bright sample resets it.
struct DiagnosticBlackVideoWindow {
    private var since: TimeInterval?
    private var previous: TimeInterval?
    mutating func observe(luma: Double?, at now: TimeInterval, live: Bool) -> TimeInterval? {
        guard live, let luma, luma.isFinite, luma <= 20, now.isFinite else {
            since = nil; previous = nil; return nil
        }
        if let previous, now < previous || now - previous > 1.5 { since = nil }
        previous = now
        if since == nil { since = now }
        return now - (since ?? now)
    }
}

/// Delivery also has a durable budget: a backlog becoming reachable tomorrow
/// cannot be sent alongside five new reports. Retrying the same UUID does not
/// spend another slot, but keeps its last-attempt time in the rolling window.
struct DiagnosticUploadBudget: Codable, Sendable {
    struct Reservation: Codable, Sendable { let id: String; let kind: DiagnosticTrigger; var at: Date }
    private(set) var reservations: [Reservation] = []
    mutating func admit(id: String, kind: DiagnosticTrigger, at now: Date) -> Bool {
        guard now.timeIntervalSince1970.isFinite else { return false }
        reservations.removeAll { now.timeIntervalSince($0.at) >= 86_400 }
        guard !reservations.contains(where: { $0.id != id && $0.kind == kind && now.timeIntervalSince($0.at) < 3600 }) else { return false }
        if let index = reservations.firstIndex(where: { $0.id == id }) {
            guard reservations[index].kind == kind else { return false }
            reservations[index].at = max(reservations[index].at, now); return true
        }
        guard reservations.count < 5 else { return false }
        reservations.append(Reservation(id: id, kind: kind, at: now)); return true
    }
}
