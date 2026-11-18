import Foundation
// Apple platforms only; the rest of this file still builds without it.
#if canImport(CryptoKit)
import CryptoKit
#endif

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

/// When the full-screen call cover is up. Ringing in, the system banner is
/// the screen only when the system hosts it; otherwise use the in-app cover.
/// Before the call exists, show the instant "Calling…" screen. An
/// ended call keeps the cover only when it was raised for that very call
/// (`Claim`), until `acknowledgeEnded` (1.5 s), so its reason ("Declined",
/// "No answer") is seen; it never raises one. Minimized, never.
enum CallCoverPolicy {
    /// What the cover is up for. It follows the binding's own answer at once
    /// (`claim(_:presented:)`), not the cover's appearance: the cover
    /// disappears only after its dismissal slide, and a call that rings in and
    /// ends during that slide must not inherit the cover that is going away.
    enum Claim: Equatable {
        case none
        /// The instant "Calling…" screen: the call being placed has no id yet.
        case starting
        case call(String)
    }

    static func presented(_ state: CallUiState, starting: Bool, minimized: Bool, claim: Claim,
                          systemHostsIncoming: Bool) -> Bool {
        guard !minimized else { return false }
        switch state {
        case .incomingRinging: return !systemHostsIncoming
        case .idle: return starting
        case .ended(let call, _):
            switch claim {
            case .call(let id): return id == call.callId
            // Placed and ended before "Calling…" gave way to its ringing screen.
            case .starting: return call.direction == .outgoing
            case .none: return false
            }
        case .outgoingRinging, .connecting, .connected, .reconnecting, .failed: return true
        }
    }

    /// A presented cover claims its call as soon as an id exists, even if it
    /// ended before its ringing screen rendered. "Calling…" and an unbound
    /// failed start claim the pending start; a dismissed cover claims nothing.
    static func claim(_ state: CallUiState, presented: Bool) -> Claim {
        guard presented else { return .none }
        if let call = state.callInfo { return .call(call.callId) }
        return .starting
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
    var peerRingingExpected: Bool? = nil
    var peerRinging: Bool? = nil

    private enum CodingKeys: String, CodingKey {
        case id, callerUserId, calleeUserId, mediaKind, state, endReason, acceptedDeviceId
        case createdAt, acceptedAt, connectedAt, endedAt, chatId, chatVisible, unread, peer
        case peerRingingExpected, peerRinging
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
        peerRingingExpected = try c.decodeIfPresent(Bool.self, forKey: .peerRingingExpected)
        peerRinging = try c.decodeIfPresent(Bool.self, forKey: .peerRinging)
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

/// `GET /calls/ice-servers`: the servers and how long their TURN credentials
/// stay valid (`ttl_seconds`; nil from a server that predates the field).
struct CallICEServerSet: Equatable {
    let servers: [CallICEServer]
    let ttlSeconds: Int?
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
    case generic, network, busy, rateLimited
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
    /// Not a timed toast: shown for as long as outgoing video stays paused.
    case videoPausedWeakConnection
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
/// `uid` is the AVAudioSession port uid of an accessory: it names one device
/// when several of a kind are connected (two "AirPods" are two devices). The
/// receiver and the speaker exist once each and need none.
struct AudioRoute: Equatable {
    let kind: AudioRouteKind
    let label: String
    var uid: String? = nil

    /// Whether two reports name the same output. Accessories compare by port
    /// uid, never by name; one accessory exposes a port per Bluetooth profile
    /// ("<address>-tsco" for HFP, "<address>-tacl" for A2DP), so ports compare
    /// by the device they belong to.
    func isSameOutput(as other: AudioRoute) -> Bool {
        guard kind == other.kind else { return false }
        switch kind {
        case .earpiece, .speaker:
            return true
        case .bluetooth, .wiredHeadset:
            guard let uid, let otherUid = other.uid else {
                // Only two reports that both lack a uid fall back to the name.
                return uid == nil && other.uid == nil && label == other.label
            }
            return Self.deviceID(uid) == Self.deviceID(otherUid)
        }
    }

    /// The device part of a port uid. Bluetooth ports are "<address>-<profile>"
    /// with a six-pair hex address; any other uid already names its device.
    static func deviceID(_ uid: String) -> String {
        guard let dash = uid.lastIndex(of: "-") else { return uid }
        let address = uid[..<dash]
        let pairs = address.split(separator: ":", omittingEmptySubsequences: false)
        guard pairs.count == 6, pairs.allSatisfy({ $0.count == 2 && $0.allSatisfy(\.isHexDigit) }) else { return uid }
        return String(address)
    }
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
    /// The link cannot carry video: the camera keeps running for the preview,
    /// but nothing is encoded or sent, and the peer sees the avatar.
    var outgoingVideoPaused: Bool = false

    /// The toast on screen: a timed notice first, otherwise the lasting
    /// "Video paused: weak connection" while it applies.
    var visibleNotice: CallNotice? {
        notice ?? (outgoingVideoPaused && localVideoEnabled && videoMode == .video ? .videoPausedWeakConnection : nil)
    }
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

    /// An established call (media connected or being restored).
    var isInCallMedia: Bool {
        switch self { case .connected, .reconnecting: return true; default: return false }
    }
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
/// minimized active-call banner (Android `formatCallDuration` parity);
/// h:mm:ss from the first hour, never three minute digits.
func formatCallDuration(_ interval: TimeInterval) -> String {
    let total = max(0, Int(interval))
    if total >= 3600 {
        return String(format: "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }
    return String(format: "%02d:%02d", total / 60, total % 60)
}

// MARK: - Call layout policy (pure, deterministic — Phase 4 §41/§42)

/// Single source of truth for the responsive call UI sizes. No SwiftUI types;
/// all values in points. Mirrors Android `CallLayoutPolicy` exactly so the two
/// platforms scale identically, and is directly contract-tested on macOS CI.
enum CallLayoutPolicy {
    /// Measured stage size, never the sensor. Portrait uses the original
    /// functions below unchanged, including the original spacing constants.
    static func isLandscape(availableWidth: CGFloat, availableHeight: CGFloat) -> Bool { availableWidth > availableHeight }

    struct Landscape: Equatable {
        let avatar: CGFloat
        let identityGap: CGFloat
        let identityTextWidth: CGFloat
        let control: CGFloat
        let controlSpacing: CGFloat
        let controlsHeight: CGFloat
        // Scalars deliberately avoid CoreGraphics overlay conformances and
        // initializers: Foundation-only contract targets use minimal geometry.
        let freeX: CGFloat
        let freeY: CGFloat
        let freeWidth: CGFloat
        let freeHeight: CGFloat
        let previewWidth: CGFloat
        let previewHeight: CGFloat
        let previewX: CGFloat
        let previewY: CGFloat
    }

    static func landscape(availableWidth: CGFloat, availableHeight: CGFloat) -> Landscape {
        // Controls keep the portrait short-side size and order. Safe-area
        // insets have already been removed by the stage's proposal.
        let control: CGFloat = controlSize(availableWidth: availableHeight, controlCount: 4)
        let spacing: CGFloat = controlSpacing(availableWidth: availableHeight, controlCount: 4)
        // Explicit steps: one mixed-literal expression here exceeded the type checker's budget.
        let controlsHeight: CGFloat = control + 40          // 2 x 12 padding + 16 bottom
        let freeWidth: CGFloat = max(0, availableWidth - 48)
        let freeHeight: CGFloat = max(0, availableHeight - 72 - controlsHeight)   // 64 top + 8 gap
        let avatarLimit: CGFloat = min(availableHeight * 0.28, freeHeight - 24)
        let avatar: CGFloat = min(136, max(64, avatarLimit))
        let regular = localPreviewSize(availableWidth: availableHeight)
        // The OUTER tile follows the interface, never the capture tag. Only
        // the renderer's inner bounds swap when compensation is necessary.
        let previewWidth: CGFloat = min(regular.height, max(44, availableHeight * 0.30))
        let previewHeight: CGFloat = previewWidth / previewAspect
        let previewX: CGFloat = availableWidth - 12 - previewWidth
        // The identity is centred in the stage. Its right edge must leave
        // 12 pt before the wide default tile, including on SE landscape.
        let identityLimit: CGFloat = 2 * (previewX - 12) - availableWidth - avatar - 24
        let textWidth: CGFloat = min(340, max(0, identityLimit))
        return Landscape(avatar: avatar, identityGap: 24, identityTextWidth: textWidth,
                         control: control, controlSpacing: spacing,
                         controlsHeight: controlsHeight, freeX: 24, freeY: 64,
                         freeWidth: freeWidth, freeHeight: freeHeight,
                         previewWidth: previewWidth, previewHeight: previewHeight,
                         previewX: previewX, previewY: 64)
    }

    static func previewSize(availableWidth: CGFloat, availableHeight: CGFloat) -> (width: CGFloat, height: CGFloat) {
        let regular = localPreviewSize(availableWidth: availableWidth)
        let height = min(regular.height, max(40, availableHeight * 0.30))
        let base: (width: CGFloat, height: CGFloat)
        if isLandscape(availableWidth: availableWidth, availableHeight: availableHeight) {
            let layout = landscape(availableWidth: availableWidth, availableHeight: availableHeight)
            base = (layout.previewWidth, layout.previewHeight)
        } else { base = (height / previewAspect, height) }
        return base
    }

    static func failureTopInset(availableWidth: CGFloat, availableHeight: CGFloat) -> CGFloat {
        isLandscape(availableWidth: availableWidth, availableHeight: availableHeight) ? 56 : 0
    }
    static func encryptionColumns(availableWidth: CGFloat, availableHeight: CGFloat) -> Int {
        isLandscape(availableWidth: availableWidth, availableHeight: availableHeight) ? 2 : 1
    }
    static func encryptionContentWidth(availableWidth: CGFloat, availableHeight: CGFloat) -> CGFloat {
        isLandscape(availableWidth: availableWidth, availableHeight: availableHeight) ? min(900, availableWidth) : availableWidth
    }

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

    /// Video always has four control slots (camera, mute, end, output); the
    /// flip button sits on the self-view tile and exists only with the camera on.
    static let videoControlCount = 4
    /// Audio always has four (output, mute, video, end) in every phase.
    static let audioControlCount = 4

    /// Rows for `count` controls, decided from the width alone (no measuring,
    /// so the tray never flips between layouts while the call runs).
    static func controlRows(availableWidth: CGFloat, controlCount: Int) -> Int {
        let size = controlSize(availableWidth: availableWidth, controlCount: controlCount)
        let spacing = controlSpacing(availableWidth: availableWidth, controlCount: controlCount)
        let inner = trayWidth(availableWidth: availableWidth) - 2 * trayPadding(availableWidth: availableWidth)
        return size * CGFloat(controlCount) + spacing * CGFloat(controlCount - 1) <= inner + 0.5 ? 1 : 2
    }
}

/// Which layout the 1:1 call screen uses. A call placed or answered as video
/// keeps the video layout (the same five slots, End in the same place) from
/// its first frame — the instant "Calling…" screen, ringing, connecting —
/// through connected; the media comes from the call itself, never from a
/// connected-state default. After connect the negotiated mode decides (an
/// upgrade or a switch to audio is a deliberate change), and an ended call
/// keeps the layout it ended in for its last seconds on screen.
enum CallPresentationPolicy {
    static func showsVideoLayout(state: CallUiState, controls: ConnectedState?, startingVideo: Bool?,
                                 endedInVideo: Bool) -> Bool {
        switch state {
        case .idle:
            return startingVideo ?? false
        case .outgoingRinging(let call), .incomingRinging(let call), .connecting(let call):
            return call.mediaKind == .video
        case .connected(let c):
            return c.videoMode == .video
        case .reconnecting(let call):
            if let controls, controls.call.callId == call.callId { return controls.videoMode == .video }
            return call.mediaKind == .video
        case .ended:
            return endedInVideo
        case .failed:
            return false
        }
    }
}

/// PiP follows negotiated media, not the original audio/video invitation.
enum CallPipFramePolicy {
    static func acceptsFrame(valid: Bool, running: Bool, primed: Bool, visible: Bool, busy: Bool) -> Bool {
        valid && visible && !busy && (running || !primed)
    }
}

/// System PiP follows the negotiated video call, not the cameras. With the peer's
/// camera paused (or both cameras off) the window stays and shows the peer's avatar
/// and name instead of a frame; closing it while the person is in another app would
/// leave no way back but the call banner. Only a downgrade to audio or the end of
/// the call removes it.
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

/// Remote video geometry for the system PiP window.
enum CallPipGeometry {
    /// Upright size of a frame: a 90/270 degree rotation swaps the axes.
    static func orientedSize(_ size: CGSize, rotationDegrees: Int) -> CGSize {
        rotationDegrees % 180 == 0 ? size : CGSize(width: size.height, height: size.width)
    }

    /// AVKit uses only the aspect ratio of the preferred size. Extreme frames are
    /// kept within 21:9 either way.
    static func preferredContentSize(for video: CGSize) -> CGSize? {
        guard video.width.isFinite, video.height.isFinite, video.width > 0, video.height > 0 else { return nil }
        let aspect = min(max(video.height / video.width, 9 / 21), 21 / 9)
        return CGSize(width: 300, height: (300 * aspect).rounded())
    }
}

// MARK: - Outgoing ringing acknowledgement (Foundation-only)

/// Caller-local value. No remote device identity, count or timestamp is stored.
struct CallRingIndicator: Equatable {
    enum Phase: CaseIterable, Equatable {
        case sending, ringing, notOnline, answered, busy, declined, cancelled, noAnswer, failed
        var isTerminal: Bool {
            switch self {
            case .sending, .ringing, .notOnline: return false
            default: return true
            }
        }
        var titleKey: String {
            switch self {
            case .sending: return "Calling…"
            case .ringing: return "Ringing…"
            case .notOnline: return "Not online yet"
            case .answered: return "Connecting call…"
            case .busy: return "Line busy"
            case .declined: return "Declined"
            case .cancelled: return "Cancelled"
            case .noAnswer: return "No answer"
            case .failed: return "Failed"
            }
        }
    }
    var phase: Phase = .sending
    var expect = false
    var created = false
    var rang = false
    var ringbackAllowed: Bool { phase == .ringing || (phase == .sending && created && !expect) }
    var awaitsConfirmation: Bool { (phase == .sending || phase == .notOnline) && created && expect }
}

/// Ordered transition table from CALL_RINGING_SPEC §1.4. The controller drops
/// foreign call ids and stamps GETs when each request is issued.
enum CallRingingIndicatorPolicy {
    static let confirmDeadline: TimeInterval = 6
    static let recheckInterval: TimeInterval = 5
    enum Event: Equatable {
        case started
        case created(expect: Bool)
        case createFailed(busy: Bool)
        case peerRinging
        case serverRow(state: String, peerRinging: Bool?, expected: Bool?, afterDeadline: Bool)
        case accepted
        case ended(reason: String)
        case cancelledHere
    }
    enum Output: Equatable {
        case haptic
        case announce(String)
    }
    static func reduce(_ current: CallRingIndicator?, _ event: Event) -> (CallRingIndicator?, [Output]) {
        guard var next = current else {
            switch event {
            case .started: return (CallRingIndicator(), [])
            case .created(let expect): return (CallRingIndicator(expect: expect, created: true), [])
            default: return (nil, [])
            }
        }
        guard !next.phase.isTerminal else { return (next, []) }
        let previous = next
        switch event {
        case .started: break
        case .created(let expect):
            if next.phase == .sending && !next.created {
                next.created = true
                next.expect = expect
            }
        case .createFailed(let busy):
            if next.phase == .sending && !next.created { next.phase = busy ? .busy : .failed }
        case .peerRinging:
            next.phase = .ringing
            next.rang = true
        case .serverRow(let state, let peerRinging, let expected, let afterDeadline):
            guard state == "ringing" else { return (next, []) }
            if peerRinging == true && (next.phase == .sending || next.phase == .notOnline) {
                next.phase = .ringing
                next.rang = true
            } else if expected == false {
                next.expect = false
                if next.phase == .notOnline { next.phase = .sending }
            } else if next.phase == .sending && afterDeadline && next.expect && next.created {
                next.phase = .notOnline
            }
        case .accepted: next.phase = .answered
        case .ended(let reason):
            switch reason {
            case "rejected", "hangup": next.phase = .declined
            case "missed": next.phase = .noAnswer
            case "cancelled": next.phase = .cancelled
            default: next.phase = .failed
            }
        case .cancelledHere: next.phase = .cancelled
        }
        guard next.phase != previous.phase else { return (next, []) }
        switch next.phase {
        case .ringing:
            var outputs: [Output] = previous.rang ? [] : [.haptic]
            outputs.append(.announce(next.phase.titleKey))
            return (next, outputs)
        case .notOnline:
            return (next, [.announce("Not online yet. Waiting for them to come online")])
        case .busy, .declined, .noAnswer, .failed:
            return (next, [.announce(next.phase.titleKey)])
        default: return (next, [])
        }
    }
}

typealias CallRingIndicatorPolicy = CallRingingIndicatorPolicy

/// The caller's reads of its own call while it rings out (`GET /calls/{id}`,
/// CALL_RINGING_SPEC §3.2), one value per call from its create response.
/// - The post-create read, the deadline check and the re-checks poll, so
///   together they stop after `pollBudget` reads, issued or not answered.
/// - A socket reconnect or a foreground refresh (`.refresh`) always reads at
///   once, outside that budget: only such a read brings back a call.accepted
///   or call.ended the socket lost (race 8), and the socket's backoff and
///   the foreground's own cadence bound it. A re-check is spaced from it.
/// - One `.backstop` read past the server's missed sweep, whatever the
///   indicator shows (confirmed ringing and legacy calls too), so a lost
///   call.ended never leaves the call ringing out with CallKit and the
///   busy state held.
struct CallOutgoingReadPolicy: Equatable {
    enum Read: Equatable { case created, check, refresh, backstop }
    struct Due: Equatable {
        let read: Read
        let at: TimeInterval
    }
    static let pollBudget = 8
    /// The server's ringing timeout (40 s) and sweep interval (5 s), plus 1 s.
    static let backstopAfter: TimeInterval = 46

    let createdAt: TimeInterval
    private(set) var polls = 0
    /// When the last read of any kind was issued (monotonic).
    private(set) var lastReadAt: TimeInterval?
    private(set) var backstopIssued = false

    init(createdAt: TimeInterval) { self.createdAt = createdAt }

    /// A row from a read issued before this never shows notOnline.
    var deadline: TimeInterval { createdAt + CallRingingIndicatorPolicy.confirmDeadline }

    mutating func issued(_ read: Read, at now: TimeInterval) {
        lastReadAt = now
        switch read {
        case .created, .check: polls += 1
        case .backstop: backstopIssued = true
        case .refresh: break
        }
    }

    /// The next timed read, nil when none is left. Checks run only while the
    /// indicator awaits confirmation: the deadline first, then
    /// `recheckInterval` after the last read issued at or after it.
    func next(awaitingConfirmation: Bool) -> Due? {
        var due: Due? = backstopIssued ? nil : Due(read: .backstop, at: createdAt + Self.backstopAfter)
        if awaitingConfirmation && polls < Self.pollBudget {
            let check = lastReadAt.flatMap { $0 >= deadline ? $0 + CallRingingIndicatorPolicy.recheckInterval : nil } ?? deadline
            if due.map({ check < $0.at }) ?? true { due = Due(read: .check, at: check) }
        }
        return due
    }
}

/// CallKit reporting and incoming admission can complete in either order.
/// Error classification is supplied by the controller, without network types.
enum CallRingConfirmPolicy {
    enum Report: Equatable { case pending, succeeded, failed }
    enum Failure: Equatable { case transport, http(Int), other }
    static let retryDelay: TimeInterval = 1
    static let requestTimeout: TimeInterval = 5
    static func shouldSend(ui: CallUiState, callId: String, report: Report, alreadySent: Bool) -> Bool {
        guard !alreadySent, report == .succeeded, case .incomingRinging(let call) = ui else { return false }
        return call.callId == callId
    }
    static func retry(after failure: Failure, attempt: Int, stillRinging: Bool) -> Bool {
        guard attempt == 1 && stillRinging else { return false }
        switch failure {
        case .transport: return true
        case .http(let status): return (500...599).contains(status)
        case .other: return false
        }
    }
}

/// Local feedback only; it does not prove the remote device is ringing.
enum CallRingbackPolicy {
    static func shouldPlay(_ state: CallUiState, audioActive: Bool, ringbackAllowed: Bool = true) -> Bool {
        guard audioActive && ringbackAllowed else { return false }
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

// MARK: - Call screen: verification code and status capsule (pure, contract-tested)

/// The DTLS certificate fingerprints of this call's two sides, each as
/// "alg hex" lowercased from `a=fingerprint`. Fixed for the peer connection,
/// ICE restarts included, so read once.
struct CallDtlsFingerprints: Equatable {
    let local: String
    let remote: String

    /// The description's fingerprint. Nil when there is none, when any line is
    /// malformed, or when the session-level and per-media lines disagree
    /// (the same rule as the direct-transfer description check).
    static func fingerprint(inSdp sdp: String) -> String? {
        var found: String?
        // "\r\n" is one Character, so CRLF and LF descriptions split alike.
        for raw in sdp.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let prefix = line.range(of: "a=fingerprint:", options: [.anchored, .caseInsensitive]) else { continue }
            let parts = line[prefix.upperBound...].split(separator: " ", omittingEmptySubsequences: true)
            guard parts.count == 2 else { return nil }
            let alg = parts[0].lowercased(), hex = parts[1].lowercased()
            guard wellFormed(alg: alg, hex: hex) else { return nil }
            let value = alg + " " + hex
            if let found, found != value { return nil }
            found = value
        }
        return found
    }

    private static func wellFormed(alg: String, hex: String) -> Bool {
        guard !alg.isEmpty, alg.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }) else { return false }
        let pairs = hex.split(separator: ":", omittingEmptySubsequences: false)
        return pairs.count >= 16 && pairs.allSatisfy { pair in
            pair.utf8.count == 2 && pair.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
        }
    }
}

/// The code's inputs for one peer connection, in offer/answer roles. The
/// fingerprints alone are not enough: whoever relays the SDP could swap in
/// its own certificates and grind them (2^24 hashes, seconds) until both
/// phones show the same symbols. So the offerer commits to a random nonce in
/// its offer, the answerer sends its own nonce with the answer, and only then
/// does the offerer reveal its nonce: a relay must fix its side before it can
/// know the result, which leaves it one blind 2^-24 guess (ZRTP's rule).
struct CallVerificationTranscript: Equatable {
    let offererFingerprint: String
    let answererFingerprint: String
    /// Sent with the offer: `CallVerificationCode.commitment(fingerprint:nonce:)`.
    let offererCommitment: String
    /// Revealed by the offerer only after the answer arrived.
    let offererNonce: String
    /// Sent with the answer.
    let answererNonce: String

    init(fingerprints: CallDtlsFingerprints, localIsOfferer: Bool, offererCommitment: String,
         offererNonce: String, answererNonce: String) {
        offererFingerprint = localIsOfferer ? fingerprints.local : fingerprints.remote
        answererFingerprint = localIsOfferer ? fingerprints.remote : fingerprints.local
        self.offererCommitment = offererCommitment
        self.offererNonce = offererNonce
        self.answererNonce = answererNonce
    }
}

/// Four symbols both people compare aloud: equal on both phones means the
/// DTLS keys are the two phones' own, with nobody in between. Android uses the
/// same domain, the same 64-entry list in the same order and the same names.
enum CallVerificationCode {
    /// SAS-1 hashed the fingerprints alone (grindable) and never shipped.
    static let domain = "UNYRI-CALL-SAS-2"
    static let length = 4
    static let nonceBytes = 32
    /// SF Symbols 1-3 only (iOS 16), and none with a meaning in the call UI
    /// (no lock, phone, camera, microphone or arrows).
    static let symbols: [(symbol: String, nameKey: String)] = [
        ("star.fill", "call.code.star"), ("heart.fill", "call.code.heart"),
        ("moon.fill", "call.code.moon"), ("sun.max.fill", "call.code.sun"),
        ("cloud.fill", "call.code.cloud"), ("bolt.fill", "call.code.lightning"),
        ("snowflake", "call.code.snowflake"), ("drop.fill", "call.code.drop"),
        ("flame.fill", "call.code.fire"), ("leaf.fill", "call.code.leaf"),
        ("globe", "call.code.globe"), ("bell.fill", "call.code.bell"),
        ("key.fill", "call.code.key"), ("house.fill", "call.code.house"),
        ("car.fill", "call.code.car"), ("airplane", "call.code.airplane"),
        ("bicycle", "call.code.bicycle"), ("ticket.fill", "call.code.ticket"),
        ("bus.fill", "call.code.bus"), ("umbrella.fill", "call.code.umbrella"),
        ("gift.fill", "call.code.gift"), ("crown.fill", "call.code.crown"),
        ("flag.fill", "call.code.flag"), ("book.fill", "call.code.book"),
        ("bookmark.fill", "call.code.bookmark"), ("paperclip", "call.code.paperclip"),
        ("pencil", "call.code.pencil"), ("scissors", "call.code.scissors"),
        ("paintbrush.fill", "call.code.brush"), ("hammer.fill", "call.code.hammer"),
        ("magnifyingglass", "call.code.magnifier"), ("eye.fill", "call.code.eye"),
        ("hand.raised.fill", "call.code.palm"), ("music.note", "call.code.note"),
        ("guitars.fill", "call.code.guitar"), ("gamecontroller.fill", "call.code.gamepad"),
        ("puzzlepiece.fill", "call.code.puzzle"), ("dice.fill", "call.code.dice"),
        ("tortoise.fill", "call.code.turtle"), ("hare.fill", "call.code.hare"),
        ("ant.fill", "call.code.ant"), ("ladybug.fill", "call.code.ladybug"),
        ("pawprint.fill", "call.code.paw"), ("cup.and.saucer.fill", "call.code.cup"),
        ("fork.knife", "call.code.cutlery"), ("cart.fill", "call.code.cart"),
        ("bag.fill", "call.code.bag"), ("tag.fill", "call.code.tag"),
        ("lightbulb.fill", "call.code.bulb"), ("envelope.fill", "call.code.envelope"),
        ("binoculars.fill", "call.code.binoculars"), ("map.fill", "call.code.map"),
        ("mountain.2.fill", "call.code.mountains"), ("sparkles", "call.code.sparkles"),
        ("hourglass", "call.code.hourglass"), ("alarm.fill", "call.code.alarm-clock"),
        ("megaphone.fill", "call.code.megaphone"), ("theatermasks.fill", "call.code.masks"),
        ("film.fill", "call.code.film"), ("atom", "call.code.atom"),
        ("gearshape.fill", "call.code.gear"), ("suit.spade.fill", "call.code.spade"),
        ("tshirt.fill", "call.code.t-shirt"), ("eyeglasses", "call.code.glasses"),
    ]

    /// A fresh nonce for one offer or answer, lowercase hex. The default
    /// generator is the system CSPRNG.
    static func newNonce() -> String {
        (0..<nonceBytes).map { _ in String(format: "%02x", UInt8.random(in: .min ... .max)) }.joined()
    }

    /// Nonces and commitments are exactly 32 bytes of lowercase hex.
    static func isHex32(_ value: String) -> Bool {
        value.utf8.count == 2 * nonceBytes && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    #if canImport(CryptoKit)
    /// What the offer carries: SHA-256 over the offerer's fingerprint and its
    /// nonce, lowercase hex. Nil for a malformed nonce.
    static func commitment(fingerprint: String, nonce: String) -> String? {
        guard isHex32(nonce) else { return nil }
        return SHA256.hash(data: Data((domain + " commit\n" + fingerprint + "\n" + nonce).utf8))
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Indices into `symbols`, or nil (no code to show) when the revealed
    /// nonce does not open the commitment or a nonce is malformed. Roles fix
    /// the order, so both phones get the same code.
    static func indices(_ t: CallVerificationTranscript) -> [Int]? {
        guard isHex32(t.answererNonce),
              commitment(fingerprint: t.offererFingerprint, nonce: t.offererNonce) == t.offererCommitment else { return nil }
        let input = [domain, t.offererFingerprint, t.answererFingerprint, t.offererNonce, t.answererNonce].joined(separator: "\n")
        let digest = Array(SHA256.hash(data: Data(input.utf8)))
        let bits = Int(digest[0]) << 16 | Int(digest[1]) << 8 | Int(digest[2])
        return [(bits >> 18) & 63, (bits >> 12) & 63, (bits >> 6) & 63, bits & 63]
    }
    #endif
}

/// The call's phase as the status capsule sees it.
enum CallStatusPhase: Equatable {
    case starting, calling, incoming(video: Bool), connecting, connected, reconnecting, ended(CallEndReason)
    var isEnded: Bool { if case .ended = self { return true }; return false }
}

/// What the one status capsule at the top of the call screen says.
enum CallStatusCapsuleKind: Equatable {
    case calling
    /// A callee device successfully reported its incoming call to CallKit.
    case peerRinging
    case peerNotOnline
    case outgoingEnded(CallRingIndicator.Phase)
    case incoming(video: Bool)
    case connecting
    case encrypted
    case reconnecting
    case waitingForNetwork(inCall: Bool)
    case ended(CallEndReason, duration: Int?)
    case hidden

    /// A localization key.
    var titleKey: String {
        switch self {
        case .calling: return "Calling…"
        case .peerRinging: return "Ringing…"
        case .peerNotOnline: return "Not online yet"
        case .outgoingEnded(let phase): return phase.titleKey
        case .incoming(let video): return video ? "Incoming video call" : "Incoming audio call"
        case .connecting: return "Connecting call…"
        case .encrypted: return "Encrypted"
        case .reconnecting: return "Reconnecting…"
        case .waitingForNetwork: return "Waiting for network…"
        case .ended(let reason, _): return CallStatusCapsulePolicy.endedTitleKey(reason)
        case .hidden: return ""
        }
    }

    /// Stays on screen with the video chrome hidden, and pulses once on change.
    var isAlert: Bool {
        switch self { case .reconnecting, .waitingForNetwork: return true; default: return false }
    }

    /// Media runs (or ran a moment ago) under the call's DTLS keys.
    var isSecured: Bool {
        switch self {
        case .encrypted, .reconnecting, .waitingForNetwork(inCall: true): return true
        default: return false
        }
    }

    var showsTimer: Bool { self == .encrypted }

    var showsBars: Bool { isSecured }
}

enum CallStatusCapsulePolicy {
    /// `reconnectingShown`: the drop has lasted the shared 1.2 s, so a shorter
    /// blip keeps "Encrypted" and the timer. `offline`: the device has had no
    /// network for 1.2 s before media ran.
    static func kind(phase: CallStatusPhase?, ring: CallRingIndicator.Phase?, reconnectingShown: Bool, waitingForNetwork: Bool,
                     offline: Bool, endedDuration: Int?) -> CallStatusCapsuleKind {
        guard let phase else { return .hidden }
        switch phase {
        case .starting, .calling:
            if offline { return .waitingForNetwork(inCall: false) }
            if ring == .ringing { return .peerRinging }
            return ring == .notOnline ? .peerNotOnline : .calling
        case .incoming(let video): return .incoming(video: video)
        case .connecting: return offline ? .waitingForNetwork(inCall: false) : .connecting
        case .connected: return .encrypted
        case .reconnecting:
            guard reconnectingShown else { return .encrypted }
            return waitingForNetwork ? .waitingForNetwork(inCall: true) : .reconnecting
        case .ended(let reason):
            if let ring, ring.isTerminal && ring != .answered { return .outgoingEnded(ring) }
            return .ended(reason, duration: reason == .hangup || reason == .remoteEnded ? endedDuration : nil)
        }
    }

    static func endedTitleKey(_ reason: CallEndReason) -> String {
        switch reason {
        case .hangup, .remoteEnded: return "Call ended"
        case .rejected: return "Declined"
        case .cancelled: return "Cancelled"
        case .missed: return "Missed"
        case .timeout: return "No answer"
        case .failed: return "Failed"
        case .answeredElsewhere: return "Answered on another device"
        }
    }

    /// The capsule's second line, encrypted only: a poor link while it lasts,
    /// a weak one for its first seconds, otherwise the route. Primitives,
    /// because the route type is not compiled into the contract target.
    static func subtitleKey(kind: CallStatusCapsuleKind, routeIsRelay: Bool?, privateRelay: Bool,
                            caption: String?, weakExpired: Bool) -> String? {
        if kind == .peerNotOnline { return "Waiting for them to come online" }
        guard kind == .encrypted else { return nil }
        if caption == "Poor connection" { return "Poor connection" }
        if caption == "Weak connection", !weakExpired { return "Weak connection" }
        // Owner, 14 Nov: the status must not get in the way. The route ("Direct",
        // "Via server") lives in the details sheet; the line speaks only for problems.
        return nil
    }

    /// Bars only when something is wrong; a good call shows the lock and the timer alone.
    /// Owner, 14 Nov: the route as two tiny phones (direct) or phone, server, phone
    /// (via the server), instead of the words. Only once the call is encrypted.
    enum RouteGlyph: Equatable { case direct, relay }
    static func routeGlyph(kind: CallStatusCapsuleKind, routeIsRelay: Bool?) -> RouteGlyph? {
        guard kind == .encrypted, let routeIsRelay else { return nil }
        return routeIsRelay ? .relay : .direct
    }

    static func showsBars(kind: CallStatusCapsuleKind, linkHasProblem: Bool) -> Bool {
        kind.showsBars && linkHasProblem
    }
}
