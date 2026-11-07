import Foundation

enum FloatingVideoPosition {
    static func normalized(origin: CGFloat, translation: CGFloat, range: CGFloat) -> CGFloat {
        guard range > 0 else { return min(1, max(0, origin)) }
        return min(1, max(0, origin + translation / range))
    }
}

enum CallVideoWindowPolicy {
    static func showsInline(videoCall: Bool, minimized: Bool, fullScreenVisible: Bool,
                            appActive: Bool, systemPipVisible: Bool) -> Bool {
        videoCall && minimized && !fullScreenVisible && appActive && !systemPipVisible
    }
}

enum CallMinimizePolicy {
    static func callID(_ state: CallUiState) -> String? {
        switch state {
        case .outgoingRinging(let call), .connecting(let call), .reconnecting(let call): return call.callId
        case .connected(let value): return value.call.callId
        default: return nil
        }
    }

    static func canComplete(requested: String?, state: CallUiState) -> Bool {
        guard let requested else { return false }
        return requested == callID(state)
    }

    static func isDownwardSwipe(x: CGFloat, y: CGFloat) -> Bool {
        x.isFinite && y.isFinite && y >= 64 && y > abs(x) * 1.5
    }
}

// MARK: - Backend call DTOs (mirror server/src/calls/models.rs)

enum CallMediaKind: String, Codable, Equatable {
    case audio, video
}

/// The optional public identity bundled with history. Kept independent of
/// profile services so call transport/persistence contracts remain standalone.
struct CallPeerProfile: Codable, Equatable {
    let id: UUID
    let username: String
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let profileVersion: Int64
    let avatarVersion: Int64
    let hasAvatar: Bool
}

struct CallDTO: Codable, Identifiable, Equatable {
    let id: UUID
    let callerUserId: UUID
    let calleeUserId: UUID
    let mediaKind: CallMediaKind
    let state: String
    let endReason: String?
    let acceptedDeviceId: UUID?
    let createdAt: Date
    let acceptedAt: Date?
    let connectedAt: Date?
    let endedAt: Date?
    var chatId: UUID? = nil
    var chatVisible: Bool? = nil
    var unread: Bool = false
    var peer: CallPeerProfile? = nil

    private enum CodingKeys: String, CodingKey {
        case id, callerUserId, calleeUserId, mediaKind, state, endReason, acceptedDeviceId
        case createdAt, acceptedAt, connectedAt, endedAt, chatId, chatVisible, unread, peer
    }

    var isEnded: Bool { state == "ended" || endedAt != nil }
    func validatedPeer(owner: UUID?) -> CallPeerProfile? {
        guard let owner, owner == callerUserId || owner == calleeUserId,
              let peer, peer.id == (callerUserId == owner ? calleeUserId : callerUserId) else { return nil }
        return peer
    }
    func isMissedIncoming(owner: UUID?) -> Bool {
        calleeUserId == owner && isEnded && acceptedAt == nil && ["missed", "cancelled", "busy", "failed", "timeout"].contains(endReason ?? "")
    }

    func historyLabel(owner: UUID?) -> String {
        if !isEnded { return "Call in progress" }
        if connectedAt != nil { return callerUserId == owner ? "Outgoing call" : "Incoming call" }
        if endReason == "rejected" || endReason == "busy" { return "Declined call" }
        if endReason == "failed" || endReason == "timeout" { return "Failed call" }
        if callerUserId == owner { return endReason == "missed" ? "No answer" : "Cancelled call" }
        return "Missed call"
    }
    var durationSeconds: Int? {
        guard let connectedAt, let endedAt else { return nil }
        return max(0, Int(endedAt.timeIntervalSince(connectedAt)))
    }
}

extension CallDTO {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        callerUserId = try c.decode(UUID.self, forKey: .callerUserId)
        calleeUserId = try c.decode(UUID.self, forKey: .calleeUserId)
        mediaKind = try c.decode(CallMediaKind.self, forKey: .mediaKind)
        state = try c.decode(String.self, forKey: .state)
        endReason = try c.decodeIfPresent(String.self, forKey: .endReason)
        acceptedDeviceId = try c.decodeIfPresent(UUID.self, forKey: .acceptedDeviceId)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        acceptedAt = try c.decodeIfPresent(Date.self, forKey: .acceptedAt)
        connectedAt = try c.decodeIfPresent(Date.self, forKey: .connectedAt)
        endedAt = try c.decodeIfPresent(Date.self, forKey: .endedAt)
        chatId = try c.decodeIfPresent(UUID.self, forKey: .chatId)
        chatVisible = try c.decodeIfPresent(Bool.self, forKey: .chatVisible)
        unread = try c.decodeIfPresent(Bool.self, forKey: .unread) ?? false
        peer = try? c.decodeIfPresent(CallPeerProfile.self, forKey: .peer)
    }
}

enum CallActivityPolicy {
    static func matchesChat(_ call: CallDTO, chatId: String) -> Bool {
        call.chatVisible != false && call.chatId == UUID(uuidString: chatId)
    }
    static func eligible(_ call: CallDTO, owner: UUID?, since: Date, deleted: Bool = false) -> Bool {
        guard let owner else { return false }
        return !deleted && (call.callerUserId == owner || call.calleeUserId == owner) && call.createdAt >= since
    }
    static func merged(_ old: CallDTO?, _ next: CallDTO) -> CallDTO {
        guard let old else { return next }
        var result = next
        if (old.isEnded && !next.isEnded) ||
            (old.connectedAt != nil && next.connectedAt == nil && !next.isEnded) ||
            (old.acceptedAt != nil && next.acceptedAt == nil && !next.isEnded) { result = old }
        if next.chatVisible == false || old.chatVisible == false { result.chatVisible = false; result.unread = false }
        if result.peer == nil, old.callerUserId == result.callerUserId, old.calleeUserId == result.calleeUserId { result.peer = old.peer }
        return result
    }
}

enum CallPeerTitlePolicy {
    static func title(displayName: String?, username: String?, unknown: String) -> String {
        if let name = displayName?.trimmingCharacters(in:.whitespacesAndNewlines), !name.isEmpty { return name }
        if let handle = username?.trimmingCharacters(in:.whitespacesAndNewlines), !handle.isEmpty {
            return handle.hasPrefix("@") ? handle : "@" + handle
        }
        return unknown
    }
}

enum ChatCallActivity {
    static func latestByChat(_ calls: [CallDTO], owner: UUID?) -> [String: CallDTO] {
        guard let owner else { return [:] }
        var result: [String: CallDTO] = [:]
        for call in calls where call.chatVisible != false && (call.callerUserId == owner || call.calleeUserId == owner) {
            guard let chat = call.chatId?.uuidString.lowercased() else { continue }
            if let old = result[chat], old.createdAt > call.createdAt || (old.createdAt == call.createdAt && old.id.uuidString >= call.id.uuidString) { continue }
            result[chat] = call
        }
        return result
    }
    static func latestByPeer(_ calls: [CallDTO], owner: UUID?) -> [UUID: CallDTO] {
        guard let owner else { return [:] }
        var result: [UUID: CallDTO] = [:]
        for call in calls {
            guard call.callerUserId == owner || call.calleeUserId == owner else { continue }
            let peer = call.callerUserId == owner ? call.calleeUserId : call.callerUserId
            if let previous = result[peer], previous.createdAt > call.createdAt ||
                (previous.createdAt == call.createdAt && previous.id.uuidString >= call.id.uuidString) { continue }
            result[peer] = call
        }
        return result
    }
    static func latestDate(message: Date?, call: CallDTO?) -> Date {
        max(message ?? .distantPast, call?.createdAt ?? .distantPast)
    }
}

struct CallHistoryPage: Decodable { let items: [CallDTO]; let nextCursor: UUID?; let historyRestored: Bool?; let visibleSince: Date? }

struct CallDeletion: Codable { let sequence: Int64; let callId: UUID }
struct CallDeletionPage: Decodable { let items: [CallDeletion]; let hasMore: Bool }
struct CallDeletionState: Codable { var cursor: Int64 = 0; var ids = Set<UUID>() }

enum CallArchiveClock {
    /// Match Android Instant.toEpochMilli (floor), while removing only Date's
    /// floating-point representation noise around an exact integer millisecond.
    /// Date stores seconds from Apple's reference epoch, so both reference and
    /// Unix-epoch ULPs matter, particularly for dates close to January 1970.
    static func milliseconds(_ date: Date) -> Int64 {
        let seconds = date.timeIntervalSince1970
        let scaled = seconds * 1000
        let nearest = scaled.rounded()
        let tolerance = max(max(seconds.ulp, date.timeIntervalSinceReferenceDate.ulp) * 1000, scaled.ulp)
        return Int64(abs(scaled - nearest) <= tolerance ? nearest : scaled.rounded(.down))
    }
}

extension CallDTO {
    func archived(owner: UUID) -> HistoryArchiveCall? {
        guard isEnded, callerUserId == owner || calleeUserId == owner else { return nil }
        func ms(_ value: Date?) -> Int64? { value.map(CallArchiveClock.milliseconds) }
        return HistoryArchiveCall(id: id.uuidString.lowercased(), caller: callerUserId.uuidString.lowercased(),
            callee: calleeUserId.uuidString.lowercased(), mediaKind: mediaKind.rawValue, createdAtMs: ms(createdAt)!,
            acceptedAtMs: ms(acceptedAt), connectedAtMs: ms(connectedAt), endedAtMs: ms(endedAt), endReason: endReason,
            chat: chatId?.uuidString.lowercased(), chatVisible: chatVisible != false,
            peer: validatedPeer(owner: owner).map { HistoryArchivePeer(id: $0.id.uuidString.lowercased(), username: $0.username, displayName: $0.displayName) })
    }
    init(restored row: HistoryArchiveCall) throws {
        func date(_ value: Int64?) -> Date? { value.map { Date(timeIntervalSince1970: Double($0) / 1000) } }
        guard let id = UUID(uuidString: row.id), let caller = UUID(uuidString: row.caller),
              let callee = UUID(uuidString: row.callee), let kind = CallMediaKind(rawValue: row.mediaKind) else { throw HistoryArchive.Failure.invalidArchive }
        self.init(id: id, callerUserId: caller, calleeUserId: callee, mediaKind: kind, state: "ended", endReason: row.endReason,
            acceptedDeviceId: nil, createdAt: date(row.createdAtMs)!, acceptedAt: date(row.acceptedAtMs),
            connectedAt: date(row.connectedAtMs), endedAt: date(row.endedAtMs), chatId: row.chat.flatMap(UUID.init(uuidString:)),
            chatVisible: row.chatVisible, unread: false, peer: row.peer.map { CallPeerProfile(id: UUID(uuidString: $0.id)!,
                username: $0.username, displayName: $0.displayName, bio: nil, avatarUrl: nil,
                profileVersion: 0, avatarVersion: 0, hasAvatar: false) })
    }
}

/// Account-scoped durable history. Actor isolation serializes page merges;
/// atomic replacement never exposes a partially written JSON file.
actor CallHistoryStore {
    private struct Snapshot: Codable { let rows: [CallDTO]; let restoredIds: Set<UUID> }
    private let file: URL
    init(file: URL) { self.file = file }
    func barrier() {}
    private func snapshot() throws -> Snapshot {
        guard FileManager.default.fileExists(atPath: file.path) else { return Snapshot(rows: [], restoredIds: []) }
        let data = try Data(contentsOf: file)
        if let value = try? JSONDecoder().decode(Snapshot.self, from: data) { return value }
        return Snapshot(rows: try JSONDecoder().decode([CallDTO].self, from: data), restoredIds: [])
    }
    func restoredIDs() throws -> Set<UUID> {
        try snapshot().restoredIds
    }
    /// Only invoked after authenticated archive validation and native restore.
    /// A server historyRestored boolean never authorizes an old row locally.
    func restore(_ rows: [CallDTO]) throws {
        _ = try merge(rows, restoring: Set(rows.map(\.id)))
    }
    private var deletionFile: URL { file.appendingPathExtension("deleted") }
    func deletions() throws -> CallDeletionState {
        guard FileManager.default.fileExists(atPath: deletionFile.path) else { return CallDeletionState() }
        return try JSONDecoder().decode(CallDeletionState.self, from: Data(contentsOf: deletionFile))
    }
    func remove(ids: Set<UUID>, cursor: Int64 = 0) throws -> CallDeletionState {
        var state = try deletions()
        if ids.isSubset(of: state.ids) && cursor <= state.cursor { return state }
        state.ids.formUnion(ids); state.cursor = max(state.cursor, cursor)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(state).write(to: deletionFile, options: .atomic)
        return state
    }
    private var cutoffFile: URL { file.appendingPathExtension("cleared-before") }
    func cutoff() throws -> Date {
        guard FileManager.default.fileExists(atPath: cutoffFile.path) else { return .distantPast }
        let milliseconds = try JSONDecoder().decode(Int64.self, from: Data(contentsOf: cutoffFile))
        return Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }
    func clear(beforeMs: Int64) throws -> [CallDTO] {
        guard beforeMs >= 0 else { throw CocoaError(.coderInvalidValue) }
        let previous = try cutoff()
        let boundary = max(Date(timeIntervalSince1970: Double(beforeMs) / 1000), previous)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(Int64((boundary.timeIntervalSince1970 * 1000).rounded())).write(to: cutoffFile, options: .atomic)
        return try merge([])
    }
    func load() throws -> [CallDTO] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let boundary = try cutoff()
        let removed = try deletions().ids
        return try snapshot().rows.filter { $0.createdAt >= boundary && !removed.contains($0.id) }
    }
    func merge(_ incoming: [CallDTO], restoring restored: Set<UUID> = []) throws -> [CallDTO] {
        var rows = Dictionary(uniqueKeysWithValues: try load().map { ($0.id, $0) })
        let boundary = try cutoff()
        let removed = try deletions().ids
        for call in incoming where call.createdAt >= boundary && !removed.contains(call.id) { rows[call.id] = CallActivityPolicy.merged(rows[call.id], call) }
        let result = rows.values.sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString > $1.id.uuidString : $0.createdAt > $1.createdAt }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let liveIds = Set(result.map(\.id))
        let authorized = try restoredIDs().union(restored).intersection(liveIds)
        try JSONEncoder().encode(Snapshot(rows: result, restoredIds: authorized)).write(to: file, options: .atomic)
        return result
    }
}

struct CallICEServer: Decodable, Equatable {
    let urls: [String]
    let username: String?
    let credential: String?
}

// MARK: - Call domain models (mirror Android domain/call + domain/model/Call)

enum CallDirection: Equatable {
    case outgoing, incoming
}

enum CallEndReason: String, Equatable {
    case hangup, rejected, cancelled, missed, timeout, failed
    case answeredElsewhere = "answered_elsewhere"
    case remoteEnded = "remote_ended"
}

enum CallFailureReason: Equatable {
    case generic, network, busy
}

/// Immutable identity of "the current call" as the UI needs it.
struct CallInfo: Equatable, Identifiable {
    let callId: String
    let remoteUserId: String
    let remoteDisplayName: String
    let mediaKind: CallMediaKind
    let direction: CallDirection

    var id: String { callId }
}

enum VideoMode: Equatable {
    case audio, video
}

/// Explicit audio->video upgrade state machine (Android `VideoUpgradeState`).
enum VideoUpgradeState: Equatable {
    case idle, requesting, incomingRequest, upgrading, rejected, failed
}

enum CallNotice: Equatable {
    case videoRequestDeclined, videoRequestTimeout, videoFailed, cameraDenied, cameraFailed
}

/// Tri-state remote video presentation (signaling "on" is NOT frame evidence).
enum RemoteVideoState: Equatable {
    case off, waiting, on
}

enum LocalCameraState: Equatable {
    case off, starting, on, failed
}

enum AudioRouteKind: Hashable {
    case earpiece, speaker, bluetooth, wiredHeadset
}

/// An actual, selectable audio output route (Android `AudioRoute`).
struct AudioRoute: Equatable {
    let kind: AudioRouteKind
    let label: String
}

enum PeerConnectionState: Equatable {
    case new, checking, connected, disconnected, failed, closed
}

/// Live in-call state carried by `.connected`.
struct ConnectedState: Equatable {
    var call: CallInfo
    var isMuted: Bool = false
    var isSpeakerOn: Bool = false
    var localVideoEnabled: Bool = false
    var remoteVideoEnabled: Bool = false
    var videoMode: VideoMode = .audio
    var videoUpgrade: VideoUpgradeState = .idle
    var notice: CallNotice? = nil
    var remoteVideoState: RemoteVideoState = .off
    var localCameraState: LocalCameraState = .off
    var localCameraIsFrontFacing: Bool = true
    var audioRoute: AudioRouteKind = .earpiece
}

/// Client-side call state machine — a strict superset of the backend DB
/// states (Reconnecting/Failed are client-local). The UI observes this only.
enum CallUiState: Equatable {
    case idle
    case outgoingRinging(CallInfo)
    case incomingRinging(CallInfo)
    case connecting(CallInfo)
    case connected(ConnectedState)
    case reconnecting(CallInfo)
    case ended(CallInfo, reason: CallEndReason)
    case failed(CallInfo?, reason: CallFailureReason)

    var callInfo: CallInfo? {
        switch self {
        case .outgoingRinging(let c), .incomingRinging(let c), .connecting(let c),
             .reconnecting(let c), .ended(let c, _):
            return c
        case .connected(let s): return s.call
        case .failed(let c, _): return c
        case .idle: return nil
        }
    }
}

/// mm:ss call duration formatting shared by the in-call timer and the
/// minimized active-call banner (Android `formatCallDuration` parity).
func formatCallDuration(_ interval: TimeInterval) -> String {
    let total = max(0, Int(interval))
    return String(format: "%02d:%02d", total / 60, total % 60)
}

// MARK: - Call layout policy (pure, deterministic — Phase 4 §41/§42)

/// Single source of truth for the responsive call UI sizes. No SwiftUI types;
/// all values in points. Mirrors Android `CallLayoutPolicy` exactly so the two
/// platforms scale identically, and is directly contract-tested on macOS CI.
enum CallLayoutPolicy {
    static let minAvatar: CGFloat = 104
    static let maxAvatar: CGFloat = 136
    static let videoAvatar: CGFloat = 96

    static let controlMin: CGFloat = 52
    static let controlMax: CGFloat = 64
    static let controlTouchMin: CGFloat = 44

    static let previewMin: CGFloat = 96
    static let previewMax: CGFloat = 150
    static let previewWidthRatio: CGFloat = 0.28
    static let previewAspect: CGFloat = 4.0 / 3.0

    static let trayMaxWidth: CGFloat = 420
    static let trayMarginNarrow: CGFloat = 8
    static let trayMarginStandard: CGFloat = 16
    static let trayPaddingNarrow: CGFloat = 4
    static let trayPaddingStandard: CGFloat = 16

    static let callNameMaxLines = 2
    static let usernameMaxLines = 1

    /// Avatar diameter: scales with available height (104–136); video
    /// placeholders use a fixed compact 96 so they never crowd the video.
    static func avatarSize(availableHeight: CGFloat, isVideo: Bool) -> CGFloat {
        if isVideo { return videoAvatar }
        return min(max(availableHeight * 0.22, minAvatar), maxAvatar)
    }

    /// Largest control circle that fits `controlCount` controls plus spacing
    /// inside the floating tray's inner width. Prefer 52–64; narrow windows
    /// retain a native 44-point target and let the tray wrap when necessary.
    static func controlSize(availableWidth: CGFloat, controlCount: Int) -> CGFloat {
        let spacing = controlSpacing(availableWidth: availableWidth, controlCount: controlCount)
        let inner = trayWidth(availableWidth: availableWidth) - 2 * trayPadding(availableWidth: availableWidth)
        let fit = (inner - spacing * CGFloat(controlCount - 1)) / CGFloat(controlCount)
        return min(max(fit, controlTouchMin), controlMax)
    }

    static func controlSpacing(availableWidth: CGFloat, controlCount: Int) -> CGFloat {
        availableWidth < 300 ? 4 : availableWidth < 400 ? 6 : 12
    }

    static let controlCaptionMinWidth: CGFloat = 52

    /// Whether call controls render a visible caption under the icon. Captions
    /// are clamped to the control's own width (truncating, never overlapping),
    /// so they show whenever the control circle itself fits. Accessibility
    /// labels stay fully descriptive regardless.
    static func showCallCaptions(availableWidth: CGFloat, controlCount: Int) -> Bool {
        controlSize(availableWidth: availableWidth, controlCount: controlCount) >= controlCaptionMinWidth
    }

    /// Local preview size as (width, height): 26–31% of width, clamped 96–150,
    /// 4:3 camera aspect.
    static func localPreviewSize(availableWidth: CGFloat) -> (width: CGFloat, height: CGFloat) {
        let w = min(max(availableWidth * previewWidthRatio, previewMin), previewMax)
        return (w, w * previewAspect)
    }

    /// Scale both dimensions together. If controls leave no draggable target,
    /// the caller keeps its renderer mounted offscreen rather than squashing it.
    static func fittedPreviewSize(width: CGFloat, height: CGFloat, availableWidth: CGFloat,
                                  availableHeight: CGFloat) -> (width: CGFloat, height: CGFloat)? {
        guard [width, height, availableWidth, availableHeight].allSatisfy({ $0.isFinite && $0 > 0 }) else { return nil }
        let scale = min(1, min(availableWidth / width, availableHeight / height))
        let fitted = (width: width * scale, height: height * scale)
        guard fitted.width >= controlTouchMin, fitted.height >= controlTouchMin else { return nil }
        return fitted
    }

    /// Horizontal margin flanking the control tray (narrower on small screens).
    static func trayMargin(availableWidth: CGFloat) -> CGFloat {
        availableWidth < 400 ? trayMarginNarrow : trayMarginStandard
    }

    /// Internal horizontal padding of the control tray.
    static func trayPadding(availableWidth: CGFloat) -> CGFloat {
        availableWidth < 400 ? trayPaddingNarrow : trayPaddingStandard
    }

    /// Control tray width: never stretched beyond `trayMaxWidth` on tablets.
    static func trayWidth(availableWidth: CGFloat) -> CGFloat {
        min(availableWidth - 2 * trayMargin(availableWidth: availableWidth), trayMaxWidth)
    }
}

/// PiP follows negotiated media, not the original audio/video invitation.
enum CallPipFramePolicy {
    static func acceptsFrame(valid: Bool, running: Bool, primed: Bool, visible: Bool, busy: Bool) -> Bool {
        valid && visible && !busy && (running || !primed)
    }
}

struct CallPipPresentation {
    private(set) var videoCallID: String?
    mutating func update(_ state: CallUiState) -> (call: CallInfo, showVideo: Bool)? {
        switch state {
        case .connected(let c) where c.videoMode == .video:
            videoCallID = c.call.callId
            return (c.call, c.remoteVideoState == .on)
        case .reconnecting(let call) where call.callId == videoCallID:
            return (call, false)
        default:
            videoCallID = nil
            return nil
        }
    }
}

/// Local feedback only; it does not prove the remote device is ringing.
enum CallRingbackPolicy {
    static func shouldPlay(_ state: CallUiState, audioActive: Bool) -> Bool {
        guard audioActive else { return false }
        if case .outgoingRinging = state { return true }
        return false
    }

    // 1.5 seconds of a soft 425 Hz tone, then 1.5 seconds of silence.
    static func waveData() -> Data {
        let rate = 8000
        let samples = rate * 3
        var data = Data()
        func word(_ value: UInt32, bytes: Int) {
            for index in 0..<bytes { data.append(UInt8(truncatingIfNeeded: value >> (8 * index))) }
        }
        data.append(contentsOf: "RIFF".utf8); word(UInt32(36 + samples * 2), bytes: 4)
        data.append(contentsOf: "WAVEfmt ".utf8); word(16, bytes: 4)
        word(1, bytes: 2); word(1, bytes: 2); word(UInt32(rate), bytes: 4)
        word(UInt32(rate * 2), bytes: 4); word(2, bytes: 2); word(16, bytes: 2)
        data.append(contentsOf: "data".utf8); word(UInt32(samples * 2), bytes: 4)
        let toneSamples = rate * 3 / 2
        for index in 0..<samples {
            let envelope = max(0, min(1, min(Double(index) / 80, Double(toneSamples - index) / 80)))
            let value = Int16(6000 * envelope * sin(2 * Double.pi * 425 * Double(index) / Double(rate)))
            word(UInt32(UInt16(bitPattern: value)), bytes: 2)
        }
        return data
    }
}
