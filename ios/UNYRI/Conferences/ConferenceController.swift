import AVFoundation
import Combine
import Foundation
import LiveKit
import UIKit

/// Also covers the short asynchronous teardown between account generations.
@MainActor
enum ConferenceMediaOwnership {
    static var owner: UUID?
    static var isBusy: Bool { owner != nil }
}

/// Separate unstructured requests preserve a committed admission's response
/// when the UI operation is cancelled. The actor owns all mutable outcomes.
@MainActor
private final class ConferenceAdmission {
    var createdID: UUID?
    var roomID: UUID?
    var connected = false
    var endForEveryone = false
    var finishingRequest = false
    var createRequest: Task<ConferenceDTO, Error>?
    var joinRequest: Task<ConferenceJoinDTO, Error>?
    func cancelRequests() { createRequest?.cancel(); joinRequest?.cancel() }
    func settle() async {
        _ = try? await createRequest?.value
        _ = try? await joinRequest?.value
    }
}

/// Account-scoped media owner. Every awaited operation is tied to an attempt
/// and auth generation. Active SDK keys/tokens live in RAM; creator re-entry
/// capabilities have a separate, login-bound Keychain record.
@MainActor
final class ConferenceController: NSObject, ObservableObject, RoomDelegate {
    static let invitationNotification = Notification.Name("COCOConferenceInvitation")
    enum Phase: Equatable { case idle, connecting, connected, reconnecting, leaving }
    struct Tile: Identifiable {
        let id: String
        let participant: Participant
        var isLocal: Bool { participant is LocalParticipant }
        var name: String { let value = participant.name ?? ""; return value.isEmpty ? "Participant" : value }
    }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var conference: ConferenceDTO?
    @Published private(set) var tiles: [Tile] = []
    @Published private(set) var microphoneEnabled = false
    @Published private(set) var cameraEnabled = false
    @Published private(set) var mediaChanging = false
    @Published private(set) var connectedAt: Date?
    @Published var error: String?
    @Published var minimized = false {
        didSet { applyPresentedVideo() }
    }
    @Published var showLobby = false
    @Published var offeredInvite: ConferenceInvite?
    @Published var requestedOwnedRooms = false
    @Published var pinnedID: String?
    @Published var remotePageAnchorID: String?
    // Device presentation only: never changes room membership or encryption.
    @Published var selectedVisibleCount = 0
    @Published var fitEveryoneOnScreen = false
    @Published private(set) var hostControlsAvailable = false
    @Published private(set) var participantModerationAvailable = false
    @Published private(set) var moderatingParticipantID: String?
    @Published private(set) var microphoneBlockedByOwner = false
    @Published private(set) var changingRoomSettings = false
    @Published private(set) var directoryAvailable = false
    @Published private(set) var ownedRoomsAvailable = false
    @Published private(set) var accessSettingsAvailable = false
    @Published private(set) var joinApprovalAvailable = false
    @Published private(set) var approvalWaiting = false
    @Published private(set) var pendingJoinRequests: [ConferenceJoinRequest] = []
    @Published private(set) var decidingJoinRequestID: UUID?
    private var joinApprovalTask: Task<Void, Never>?
    private var approvalWaitTask: Task<Void, Error>?
    private var joinDecisionTask: Task<Void, Never>?
    private var joinApprovalRevision: UInt64 = 0
    @Published private(set) var locallyMutedParticipantIDs = Set<String>()
    @Published private(set) var locallyHiddenParticipantIDs = Set<String>()
    private var roomSettingsRevision: UInt64 = 0
    @Published private(set) var ownPreviewPosition = CGPoint(x: 0.96, y: 0.05)
    var isBusy: Bool { phase != .idle }
    var isOwner: Bool { conference?.ownerUserId.uuidString.lowercased() == auth?.currentUser?.id.lowercased() }
    var activeInvite: URL? { invitation?.link }
    // UI classification must survive recovery clearing crypto authority. SDK
    // participant type remains local even while expectedIdentity is nil.
    var remoteTiles: [Tile] { tiles.filter { !($0.participant is LocalParticipant) } }
    var ownTile: Tile? { tiles.first { $0.participant is LocalParticipant } }
    // Local video occupies a real grid cell; it never covers someone else's.
    // Keep the remaining order stable rather than sorting by speaking state.
    var stageTiles: [Tile] { (ownTile.map { [$0] } ?? []) + remoteTiles }
    var totalParticipants: Int { tiles.count }
    var presentationStatus: String {
        switch phase {
        case .connecting: return approvalWaiting ? "Waiting for the room creator…" : "Connecting encrypted media…"
        case .reconnecting: return "Reconnecting…"
        case .leaving: return "Leaving…"
        case .connected: return microphoneBlockedByOwner ? "Microphone paused by the creator" : "Encrypted conference"
        case .idle: return "Conference ended"
        }
    }
    var shouldKeepScreenOn: Bool {
        // The visible conversation owns the display, even while listening with
        // every camera off or looking at participants/settings. Video demand
        // is a separate resource policy and must not trigger auto-lock.
        isBusy && phase != .leaving && !minimized && videoPresentationOwner != nil
    }
    var canUseMedia: () -> Bool = { false }
    func allowNewRoomAction() -> Bool {
        guard !retired, api.isCurrentSession else { return false }
        guard !isBusy, !ConferenceMediaOwnership.isBusy, !NonCallCaptureOwnership.isBusy, canUseMedia() else {
            error = phase == .leaving || (!isBusy && ConferenceMediaOwnership.isBusy)
                ? "Finishing the previous conversation. Wait a moment and try again."
                : isBusy ? "You are already in a room. Leave it before starting another conversation." : "End the current call or recording before joining another room."
            return false
        }
        return true
    }
    var onMediaOwnershipChanged: (Bool) -> Void = { _ in }
    let api: ConferencesAPI
    let keyHandoff: ConferenceKeyHandoff
    private let signalManager: SignalSessionManager?
    private var supportsKeyHandoff = false
    private var credential: ConferenceAdmissionCredential?
    private var keyServiceTask: Task<Void, Never>?
    private var keyEnvelopes: [String: EnvelopeInput] = [:]
    private weak var auth: AuthRepository?
    private var room: Room?
    private var invitation: ConferenceInvite?
    private var provider: BaseKeyProvider?
    private var operation: Task<Void, Never>?
    private var connectionTask: Task<Void, Never>?
    private var recoveryTask: Task<Void, Never>?
    private var mediaTask: Task<Void, Never>?
    private var mediaRevision: UInt64 = 0
    private var microphoneConsentRevision: UInt64 = 0
    private var mediaFailure: Error?
    private var outageStartedAt: Date?
    private var recoveryAttempts = 0
    private var stableConnectionTask: Task<Void, Never>?
    private var foreground = true
    private var cameraPosition: AVCaptureDevice.Position = .front
    private var expectedIdentity: String?
    private var monitor: Task<Void, Never>?
    private var teardown: Task<Void, Never>?
    private var admission: ConferenceAdmission?
    private var cleaningAdmission: ConferenceAdmission?
    private var admissionCleanup: Task<Void, Never>?
    private var subscriptions = Set<String>()
    private var visibleVideoIDs = Set<String>()
    @Published private var videoPresentationOwner: UUID?
    private var desiredPresentationIDs = Set<String>()
    private var presentationActive = true
    private var previewViewport: CGSize?
    private var subscriptionRevision: UInt64 = 0
    private var subscriptionEpoch = UUID()
    private var subscriptionTask: Task<Void, Never>?
    private var locallySilencedAudioTracks = Set<ObjectIdentifier>()
    private var participantPermissionObservers: [ObjectIdentifier: AnyCancellable] = [:]
    private var attempt = UUID()
    private var retired = false
    private let mediaLease = UUID()
    private let ownerGeneration: UInt64
    private var ownedAccess: ConferenceOwnedAccess?

    init(auth: AuthRepository, signalManager: SignalSessionManager? = nil) {
        self.auth = auth; api = ConferencesAPI(auth: auth)
        ownerGeneration = auth.sessionGeneration
        self.signalManager = signalManager
        keyHandoff = ConferenceKeyHandoff(api: api, auth: auth, signal: signalManager)
        foreground = UIApplication.shared.applicationState == .active
        super.init()
        keyHandoff.onReady = { [weak self] value in self?.joinCatalog(value) }
        // SDK errors may otherwise include token-bearing URLs. No conference
        // URL, JWT, invite secret or key is sent to OS/performance logs.
        LiveKitSDK.disableLogging()
    }

    private func ownerAccess() -> ConferenceOwnedAccess? {
        guard !retired, api.isCurrentSession, let auth, auth.sessionGeneration == ownerGeneration else { return nil }
        if let ownedAccess { return ownedAccess }
        guard let snapshot = try? auth.ownedRoomAccessSnapshot(server: api.server, generation: ownerGeneration) else { return nil }
        let server = api.server, generation = ownerGeneration, binding = snapshot.binding
        let value = ConferenceOwnedAccess(server: server, owner: binding, raw: snapshot.raw) { [weak auth] raw in
            guard let auth else { throw APIError.missingSession }
            try auth.saveOwnedRoomAccess(raw, server: server, binding: binding, generation: generation)
        }
        ownedAccess = value; return value
    }
    func hasSavedAccess(to value: ConferenceDTO) -> Bool {
        guard value.state == "active", value.expiresAt > Date(), value.ownerUserId.uuidString.lowercased() == auth?.currentUser?.id.lowercased() else { return false }
        return ownerAccess()?.invitation(for: value.id) != nil
    }
    /// The same owner key is valid from either directory tab. Returning false
    /// leaves the normal invitation/password flow intact; no key is requested
    /// from the server or synthesized when this installation has none.
    func rejoinSavedDirectoryRoom(_ value: ConferenceDirectoryRoom) -> Bool {
        guard !retired, api.isCurrentSession, value.expiresAt > Date(), let access = value.access,
              value.ownerUserId.uuidString.lowercased() == auth?.currentUser?.id.lowercased(),
              let invite = ownerAccess()?.invitation(for: value.id) else { return false }
        guard !isBusy, !ConferenceMediaOwnership.isBusy, !NonCallCaptureOwnership.isBusy, canUseMedia() else {
            error = isBusy ? "You are already in a room. Leave it before starting another conversation." : ConferenceInvite.Failure.busy.localizedDescription
            return true
        }
        if access.needsPassword {
            offeredInvite = invite; showLobby = true
        } else { join(invite, microphone: false, camera: false) }
        return true
    }
    func rejoinOwnedRoom(_ value: ConferenceDTO) {
        guard !retired, api.isCurrentSession else { return }
        if conference?.id == value.id, isBusy { minimized = false; return }
        guard !isBusy, !ConferenceMediaOwnership.isBusy, !NonCallCaptureOwnership.isBusy, canUseMedia() else { error = isBusy ? "You are already in a room. Leave it before starting another conversation." : ConferenceInvite.Failure.busy.localizedDescription; return }
        guard hasSavedAccess(to: value), let invite = ownerAccess()?.invitation(for: value.id) else {
            offeredInvite = nil; showLobby = true; return
        }
        if ConferenceAccessMode(rawValue: value.accessMode ?? "invite")?.needsPassword == true {
            offeredInvite = invite; showLobby = true
        } else { join(invite, microphone: false, camera: false) }
    }
    func forgetOwnedRoom(_ id: UUID) { ownerAccess()?.remove(id) }

    func receive(_ url: URL) {
        guard !retired else { return }
        if isBusy {
            if let parsed = try? ConferenceInvite.parse(url.absoluteString, currentServer: api.server), invitation == parsed {
                minimized = false
            } else { error = ConferenceInvite.Failure.busy.localizedDescription }
            return
        }
        do {
            offeredInvite = try ConferenceInvite.parse(url.absoluteString, currentServer: api.server)
            showLobby = true
        } catch { self.error = error.localizedDescription }
    }

    func create(title: String, limit: Int, microphone: Bool, camera: Bool) {
        begin(microphone: microphone, camera: camera) { [api] admission in
            let credentials = ConferenceInvite.randomCredentials()
            let request = Task { @MainActor in
                let value = try await api.create(title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                    limit: min(30, max(2, limit)), hash: credentials.hash)
                admission.createdID = value.id
                return value
            }
            admission.createRequest = request
            let value = try await request.value
            return .invitation(ConferenceInvite(server: try ConferenceInvite.origin(api.server), roomID: value.id,
                secret: credentials.secret, mediaKey: credentials.mediaKey), password: nil)
        }
    }
    func create(configuration: ConferenceRoomConfiguration, limit: Int) {
        guard configuration.approvalRequired != true || joinApprovalAvailable else { error = ConferenceDirectoryFailure.unavailable.localizedDescription; return }
        guard accessSettingsAvailable else { create(title: configuration.title, limit: limit, microphone: false, camera: false); return }
        do { try configuration.validate(creating: true) } catch { self.error = error.localizedDescription; return }
        begin(microphone: false, camera: false) { [api] admission in
            let credentials = ConferenceInvite.randomCredentials()
            let request = Task { @MainActor in
                let value = try await api.create(configuration: configuration, limit: limit, hash: credentials.hash)
                admission.createdID = value.id; return value
            }
            admission.createRequest = request
            let value = try await request.value
            return .invitation(ConferenceInvite(server: try ConferenceInvite.origin(api.server), roomID: value.id,
                secret: credentials.secret, mediaKey: credentials.mediaKey), password: configuration.password)
        }
    }

    func join(_ invite: ConferenceInvite, microphone: Bool, camera: Bool) {
        if isBusy, invitation == invite { minimized = false; return }
        begin(microphone: microphone, camera: camera) { _ in .invitation(invite, password: nil) }
    }
    func join(_ invite: ConferenceInvite, password: String?) {
        begin(microphone: false, camera: false) { _ in .invitation(invite, password: password) }
    }
    private func joinCatalog(_ value: ConferenceCatalogCredential) {
        guard !retired, api.isCurrentSession else { return }
        guard !isBusy, !ConferenceMediaOwnership.isBusy, !NonCallCaptureOwnership.isBusy, canUseMedia() else {
            error = ConferenceInvite.Failure.busy.localizedDescription
            Task { try? await api.cancelKey(room: value.roomID, id: value.requestID) }
            return
        }
        begin(microphone: false, camera: false) { _ in .catalog(value) }
    }
    func refreshCapabilities() async {
        guard let caps = try? await api.capabilities(), api.isCurrentSession, !retired else { return }
        directoryAvailable = caps.roomDirectory == true && caps.roomKeyHandoff == true && signalManager != nil
        ownedRoomsAvailable = caps.ownerRooms == true
        accessSettingsAvailable = caps.roomAccessV2 == true
        joinApprovalAvailable = caps.roomJoinApproval == true
    }

    func accept(_ metadata: ConferenceInvitationMetadata) {
        do {
            try api.requireCurrent()
            let invite = try metadata.validated(currentServer: api.server)
            if isBusy, invitation == invite { minimized = false; return }
            join(invite, microphone: false, camera: false)
        } catch { self.error = error.localizedDescription }
    }

    func invitationMetadata() -> ConferenceInvitationMetadata? {
        guard api.isCurrentSession, let invitation, let conference, conference.state == "active" else { return nil }
        let metadata = ConferenceInvitationMetadata(id: UUID().uuidString.lowercased(), title: conference.title,
            server: invitation.server.absoluteString, roomID: invitation.roomID.uuidString.lowercased(),
            invite: invitation.wireLink.absoluteString, expiresAt: Int64(conference.expiresAt.timeIntervalSince1970))
        return metadata.hasValidShape ? metadata : nil
    }

    func remotePageIndex(size: Int) -> Int {
        guard size > 0, let anchor = remotePageAnchorID,
              let index = stageTiles.firstIndex(where: { $0.id == anchor }) else { return 0 }
        return index / size
    }

    func selectRemotePage(_ page: Int, size: Int) {
        let values = stageTiles
        guard size > 0, !values.isEmpty else { remotePageAnchorID = nil; return }
        let index = min(max(0, page) * size, values.count - 1)
        remotePageAnchorID = values[index].id
    }

    func focusParticipant(_ id: String) {
        guard remoteTiles.contains(where: { $0.id == id }) else { return }
        remotePageAnchorID = id; pinnedID = id
    }

    func recordScrollAnchor(_ ids: Set<String>, owner: UUID) {
        guard videoPresentationOwner == owner, foreground, presentationActive, !minimized,
              selectedVisibleCount > 0, !fitEveryoneOnScreen, pinnedID == nil,
              let first = stageTiles.first(where: { ids.contains($0.id) }) else { return }
        if remotePageAnchorID != first.id { remotePageAnchorID = first.id }
    }

    func toggleLocalAudio(_ id: String) {
        guard isBusy, remoteTiles.contains(where: { $0.id == id }) else { return }
        if locallyMutedParticipantIDs.contains(id) { locallyMutedParticipantIDs.remove(id) }
        else { locallyMutedParticipantIDs.insert(id) }
        applyLocalAudioPreference(id)
        if let room { updateSubscriptions(room) }
    }

    func toggleLocalVideo(_ id: String) {
        guard isBusy, remoteTiles.contains(where: { $0.id == id }) else { return }
        if locallyHiddenParticipantIDs.contains(id) { locallyHiddenParticipantIDs.remove(id) }
        else { locallyHiddenParticipantIDs.insert(id) }
        if let room { updateSubscriptions(room) }
    }

    private func applyLocalAudioPreference(_ id: String) {
        guard let room, let participant = room.remoteParticipants.values.first(where: { $0.identity?.stringValue == id }) else { return }
        for publication in participant.audioTracks {
            guard let track = publication.track as? RemoteAudioTrack else { continue }
            let object = ObjectIdentifier(track)
            if locallyMutedParticipantIDs.contains(id) || isParticipantAudioBlocked(id) {
                if locallySilencedAudioTracks.insert(object).inserted { track.volume = 0 }
            } else if locallySilencedAudioTracks.contains(object) {
                guard let remote = publication as? RemoteTrackPublication, remote.encryptionType == .gcm else {
                    failEncryption(room); return
                }
                locallySilencedAudioTracks.remove(object); track.volume = 1
            }
        }
    }

    func isParticipantAudioBlocked(_ id: String) -> Bool {
        if conference?.participants.first(where: { $0.participantIdentity == id })?.audioPublishBlocked == true { return true }
        guard let participant = room?.remoteParticipants.values.first(where: { $0.identity?.stringValue == id }) else { return false }
        return Self.permissionsDenyMicrophone(participant.permissions)
    }

    nonisolated private static func permissionsDenyMicrophone(_ permissions: ParticipantPermissions) -> Bool {
        let sources = permissions.canPublishSources
        return !permissions.canPublish || (!sources.isEmpty && !sources.contains(Track.Source.microphone.rawValue))
    }

    private func observeParticipantPermissions(_ room: Room) {
        let objects = Set(room.remoteParticipants.values.map(ObjectIdentifier.init))
        participantPermissionObservers = participantPermissionObservers.filter { objects.contains($0.key) }
        let epoch = subscriptionEpoch
        for participant in room.remoteParticipants.values {
            let object = ObjectIdentifier(participant)
            guard participantPermissionObservers[object] == nil else { continue }
            // The SDK emits objectWillChange after committing its thread-safe
            // state. Filter the permission bit before scheduling MainActor work;
            // audio-level/speaking changes never rebuild the whole grid here.
            participantPermissionObservers[object] = participant.objectWillChange
                .map { [weak participant] _ in
                    participant.map { Self.permissionsDenyMicrophone($0.permissions) } ?? true
                }
                .prepend(Self.permissionsDenyMicrophone(participant.permissions))
                .removeDuplicates().dropFirst()
                .sink { [weak self, weak room, weak participant] _ in
                    Task { @MainActor in
                        guard let self, let room, let participant,
                              self.room === room, self.subscriptionEpoch == epoch,
                              room.remoteParticipants.values.contains(where: { $0 === participant }) else { return }
                        self.refresh(room)
                    }
                }
        }
    }

    func moderateParticipant(_ identity: String, audioBlocked: Bool?) async -> Bool {
        guard participantModerationAvailable, isOwner, phase == .connected,
              moderatingParticipantID == nil, !changingRoomSettings, let value = conference,
              let member = value.participants.first(where: { $0.participantIdentity == identity }),
              member.userId != value.ownerUserId, identity != expectedIdentity else { return false }
        let ticket = attempt
        moderatingParticipantID = identity; roomSettingsRevision &+= 1
        defer { if attempt == ticket { moderatingParticipantID = nil } }
        do {
            let updated: ConferenceDTO
            if let audioBlocked {
                updated = try await api.moderateAudio(value.id, userID: member.userId, participantIdentity: identity, blocked: audioBlocked)
            } else {
                updated = try await api.removeParticipant(value.id, userID: member.userId, participantIdentity: identity)
            }
            try check(ticket)
            guard conference?.id == value.id, updated.id == value.id, updated.state == "active", isOwner, phase == .connected else { return false }
            roomSettingsRevision &+= 1; conference = updated
            if let room { refresh(room) }
            return true
        } catch {
            guard attempt == ticket, conference?.id == value.id, !retired, phase != .leaving else { return false }
            self.error = "Could not change participant access. Try again."
            return false
        }
    }

    private func applyOwnAudioRestriction(_ room: Room) {
        guard self.room === room else { return }
        let permissions = room.localParticipant.permissions
        let sources = permissions.canPublishSources
        let deniedBySFU = !permissions.canPublish || (!sources.isEmpty && !sources.contains(Track.Source.microphone.rawValue))
        let deniedByRoom = conference?.participants.first(where: { $0.participantIdentity == expectedIdentity })?.audioPublishBlocked ?? false
        let blocked = deniedBySFU || deniedByRoom
        // Revocation clears intent. A later permission grant never starts capture.
        if blocked && (!microphoneBlockedByOwner || microphoneEnabled) { revokeOwnMicrophoneIntent() }
        microphoneBlockedByOwner = blocked
    }

    private func revokeOwnMicrophoneIntent() {
        microphoneConsentRevision &+= 1
        let hadIntent = microphoneEnabled
        microphoneEnabled = false
        if hadIntent { reconcileMedia() }
    }

    func updateRoomSettings(title: String, admissionLocked: Bool) async -> Bool {
        guard hostControlsAvailable, isOwner, phase == .connected, !changingRoomSettings, moderatingParticipantID == nil,
              let id = conference?.id else { return false }
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.unicodeScalars.count <= 80, value.utf8.count <= 320,
              !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) || $0.value == 0x2028 || $0.value == 0x2029 }) else {
            error = "Use a room name of 1–80 characters."; return false
        }
        let ticket = attempt
        roomSettingsRevision &+= 1
        changingRoomSettings = true
        defer { if attempt == ticket { changingRoomSettings = false } }
        do {
            let updated = try await api.updateSettings(id, title: value, admissionLocked: admissionLocked)
            try check(ticket)
            guard conference?.id == id, updated.id == id, updated.state == "active", isOwner, phase == .connected else { return false }
            roomSettingsRevision &+= 1
            conference = updated
            return true
        } catch {
            guard attempt == ticket, conference?.id == id, !retired, phase != .leaving else { return false }
            self.error = "Could not save room settings. Try again."
            return false
        }
    }
    func updateRoomSettings(configuration: ConferenceRoomConfiguration, expectedRevision: Int64) async -> Bool {
        guard accessSettingsAvailable, hostControlsAvailable, isOwner, phase == .connected, !changingRoomSettings,
              moderatingParticipantID == nil, let id = conference?.id else { return false }
        do { try configuration.validate(creating: false) } catch { self.error = error.localizedDescription; return false }
        let ticket = attempt; roomSettingsRevision &+= 1; changingRoomSettings = true
        defer { if attempt == ticket { changingRoomSettings = false } }
        do {
            let value = try await api.updateSettings(id, configuration: configuration, expectedRevision: expectedRevision)
            try check(ticket)
            guard conference?.id == id, value.id == id, value.state == "active", isOwner, phase == .connected else { return false }
            roomSettingsRevision &+= 1; conference = value; return true
        } catch {
            guard attempt == ticket, conference?.id == id, !retired, phase != .leaving else { return false }
            if error as? ConferenceDirectoryFailure == .changed {
                if let latest = try? await api.room(id), attempt == ticket, conference?.id == id { conference = latest }
            }
            guard attempt == ticket, conference?.id == id, !retired, phase != .leaving, api.isCurrentSession else { return false }
            self.error = (error as? ConferenceDirectoryFailure)?.localizedDescription ?? "Could not save room settings. Try again."
            return false
        }
    }

    func beginVideoPresentation(_ owner: UUID) {
        if videoPresentationOwner != owner { previewViewport = nil; videoPresentationOwner = owner }
    }
    func registerPreviewViewport(_ size: CGSize, owner: UUID) {
        guard videoPresentationOwner == owner, size.width.isFinite, size.height.isFinite,
              size.width > 0, size.height > 0 else { return }
        previewViewport = size
    }
    func moveOwnPreview(_ position: CGPoint, owner: UUID, viewport: CGSize) {
        guard videoPresentationOwner == owner, previewViewport == viewport, foreground,
              presentationActive, !minimized, cameraEnabled, position.x.isFinite, position.y.isFinite else { return }
        ownPreviewPosition = CGPoint(x: min(1, max(0, position.x)), y: min(1, max(0, position.y)))
    }
    func endVideoPresentation(_ owner: UUID) {
        guard videoPresentationOwner == owner else { return }
        videoPresentationOwner = nil; previewViewport = nil; desiredPresentationIDs.removeAll(); showVideoParticipants([])
    }
    func presentVideo(_ ids: Set<String>, owner: UUID, active: Bool = true) {
        guard videoPresentationOwner == owner else { return }
        desiredPresentationIDs = ids; presentationActive = active; applyPresentedVideo()
    }
    private func applyPresentedVideo() {
        showVideoParticipants(videoPresentationOwner != nil && foreground && presentationActive && !minimized ? desiredPresentationIDs : [])
    }

    private func begin(microphone: Bool, camera: Bool, invitation makeInvite: @escaping (ConferenceAdmission) async throws -> ConferenceAdmissionCredential) {
        guard !retired, !isBusy, !ConferenceMediaOwnership.isBusy, !NonCallCaptureOwnership.isBusy, canUseMedia() else { error = isBusy ? "You are already in a room. Leave it before starting another conversation." : ConferenceInvite.Failure.busy.localizedDescription; return }
        keyHandoff.cancel()
        ConferenceMediaOwnership.owner = mediaLease
        phase = .connecting; error = nil; minimized = false
        onMediaOwnershipChanged(true)
        let ticket = UUID(); attempt = ticket
        let admission = ConferenceAdmission(); self.admission = admission
        connectionTask = Task { [weak self] in
            guard let self else { return }
            var inviteForPassword: ConferenceInvite?
            do {
                try self.check(ticket)
                let capabilities = try await self.api.capabilities()
                self.hostControlsAvailable = capabilities.hostControls ?? false
                self.participantModerationAvailable = capabilities.participantModeration ?? false
                self.accessSettingsAvailable = capabilities.roomAccessV2 == true
                self.joinApprovalAvailable = capabilities.roomJoinApproval == true
                self.supportsKeyHandoff = capabilities.roomKeyHandoff == true && self.signalManager != nil
                guard capabilities.enabled, capabilities.e2eeRequired, capabilities.maxParticipants >= 2 else {
                    throw ConferenceInvite.Failure.unavailable
                }
                // No local track/capturer exists before the explicit user's
                // permissions complete. Listen-only also requires no capture.
                try await self.requestPermissions(microphone: microphone, camera: camera)
                try self.check(ticket)
                let invite = try await makeInvite(admission)
                try self.check(ticket)
                inviteForPassword = invite.invitation
                if case .catalog = invite, !self.supportsKeyHandoff { throw ConferenceDirectoryFailure.unavailable }
                self.credential = invite; self.invitation = invite.invitation
                admission.roomID = invite.roomID
                let request = Task { @MainActor in
                    do { return try await self.api.joinCredential(invite, supportsKeyHandoff: self.supportsKeyHandoff) }
                    catch {
                        guard ConferencesAPI.needsApproval(error), case .invitation(let invitation, let password) = invite else { throw error }
                        try self.check(ticket)
                        defer { if self.attempt == ticket { self.approvalWaiting = false } }
                        let waiting = Task { @MainActor in
                            try await self.api.waitForJoinApproval(room: invitation.roomID, secret: invitation.secret, password: password) {
                                if self.attempt == ticket, !self.retired, self.api.isCurrentSession { self.approvalWaiting = true }
                            }
                        }
                        self.approvalWaitTask = waiting
                        defer { if self.attempt == ticket { self.approvalWaitTask = nil } }
                        try await waiting.value
                        try self.check(ticket)
                        return try await self.api.joinCredential(invite, supportsKeyHandoff: self.supportsKeyHandoff)
                    }
                }
                admission.joinRequest = request
                let joined = try await request.value
                try self.check(ticket)
                let room = try self.prepareRoom(joined, credential: invite)
                AudioManager.shared.isSpeakerOutputPreferred = true
                try await room.connect(url: joined.livekitUrl, token: joined.participantToken)
                try self.check(ticket)
                try self.verifyEncryption(room)
                self.microphoneEnabled = microphone; self.cameraEnabled = camera
                self.applyOwnAudioRestriction(room)
                self.reconcileMedia()
                await self.mediaTask?.value
                try self.check(ticket)
                if let failure = self.mediaFailure { throw failure }
                try self.verifyEncryption(room)
                self.phase = .connected; self.connectedAt = Date(); self.showLobby = false; self.offeredInvite = nil
                if joined.conference.ownerUserId.uuidString.lowercased() == self.auth?.currentUser?.id.lowercased(),
                   let invitation = invite.invitation {
                    do {
                        guard let access = self.ownerAccess() else { throw ConferenceOwnedAccess.Failure.persistence }
                        try access.remember(invitation, expiresAt: joined.conference.expiresAt)
                    } catch {
                        self.error = "The room is connected, but its re-entry key could not be saved on this device. Keep an invitation before leaving."
                    }
                }
                // A refresh of an existing admission does not need the access
                // password. Keep only the credential and media key in RAM.
                if let invitation = self.invitation { self.credential = .invitation(invitation, password: nil) }
                admission.connected = true
                self.refresh(room)
                self.startMonitor(ticket)
                self.startJoinApprovalMonitor(ticket)
                self.startKeyService(ticket)
                self.connectionTask = nil
            } catch {
                // Disconnect and stop capture before handing ownership back.
                if self.attempt == ticket {
                    self.connectionTask = nil
                    let failure = ConferencesAPI.accessError(error)
                    let message = (failure as? ConferenceDirectoryFailure)?.localizedDescription ?? (failure as? ConferenceInvite.Failure)?.localizedDescription
                        ?? (error as? CallMediaPermissions.PermissionError)?.localizedDescription
                        ?? (error as? ConferenceApprovalFailure)?.localizedDescription
                        ?? "Could not join the encrypted conference. It may be closed or unavailable."
                    await self.releaseMedia()
                    if !self.retired, self.admission == nil, self.room == nil {
                        self.error = message
                        if failure as? ConferenceDirectoryFailure == .passwordRequired || failure as? ConferenceDirectoryFailure == .incorrectPassword,
                           let invitation = inviteForPassword {
                            self.offeredInvite = invitation; self.showLobby = true
                        }
                    }
                }
            }
        }
    }

    private func prepareRoom(_ joined: ConferenceJoinDTO, credential: ConferenceAdmissionCredential) throws -> Room {
        guard joined.e2eeRequired, joined.conference.state == "active", joined.conference.id == credential.roomID,
              (2...30).contains(joined.conference.maxParticipants), joined.conference.expiresAt > Date(),
              joined.tokenExpiresAt > Date(), !joined.participantIdentity.isEmpty,
              let url = URLComponents(string: joined.livekitUrl), url.scheme == "wss", url.host?.isEmpty == false,
              (1...65535).contains(url.port ?? 443), url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, !joined.participantToken.isEmpty else {
            throw ConferenceInvite.Failure.encryption
        }
        let provider = BaseKeyProvider(options: KeyProviderOptions(sharedKey: true,
            ratchetSalt: Data("LKFrameEncryptionKey".utf8), ratchetWindowSize: 0,
            uncryptedMagicBytes: Data(), discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .pbkdf2))
        _ = try ConferenceInvite.decodeKey(credential.mediaKey)
        provider.setKey(key: credential.mediaKey, index: 0)
        // Recovery uses a fresh admission/Room/provider. SDK retry/republication
        // must not run concurrently with this controller's capture serializer.
        let room = Room(delegate: self, connectOptions: ConnectOptions(autoSubscribe: false, reconnectAttempts: 0),
            roomOptions: RoomOptions(defaultCameraCaptureOptions: CameraCaptureOptions(position: cameraPosition, dimensions: .h540_169, fps: 24),
                defaultVideoPublishOptions: VideoPublishOptions(preferredCodec: .vp8),
                adaptiveStream: true, dynacast: true, suspendLocalVideoTracksInBackground: false,
                e2eeOptions: E2EEOptions(keyProvider: provider, encryptionType: .gcm)))
        self.provider = provider; self.expectedIdentity = joined.participantIdentity
        self.room = room; self.conference = joined.conference
        return room
    }

    private func verifyEncryption(_ room: Room) throws {
        guard self.room === room, provider != nil, room.e2eeManager?.frameEncryptionType == .gcm,
              let expectedIdentity, room.localParticipant.identity?.stringValue == expectedIdentity else {
            throw ConferenceInvite.Failure.encryption
        }
    }

    private func requestPermissions(microphone: Bool, camera: Bool) async throws {
        try Task.checkCancellation()
        if microphone {
            guard await captureAuthorized(for: .audio) else { throw CallMediaPermissions.PermissionError.microphoneDenied }
        }
        try Task.checkCancellation()
        if camera {
            guard await captureAuthorized(for: .video) else { throw CallMediaPermissions.PermissionError.cameraDenied }
        }
        try Task.checkCancellation()
    }
    private func captureAuthorized(for mediaType: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: mediaType)
        case .denied, .restricted: return false
        @unknown default: return false
        }
    }

    private func check(_ ticket: UUID) throws {
        try Task.checkCancellation(); try api.requireCurrent()
        guard !retired, attempt == ticket, isBusy, phase != .leaving else { throw CancellationError() }
    }

    func toggleMicrophone() {
        if !retired, api.isCurrentSession, microphoneBlockedByOwner {
            error = "The room creator disabled your microphone. You can enable it yourself after the restriction is lifted."
            return
        }
        if phase == .reconnecting, microphoneEnabled {
            microphoneEnabled = false; reconcileMedia(); return
        }
        guard !microphoneBlockedByOwner else { return }
        let consent = microphoneConsentRevision
        changeMedia { room in
            let enabled = !self.microphoneEnabled
            if enabled { try await self.requestPermissions(microphone: true, camera: false) }
            try self.api.requireCurrent(); try Task.checkCancellation()
            guard self.room === room, self.phase == .connected, !self.microphoneBlockedByOwner,
                  consent == self.microphoneConsentRevision else { throw CancellationError() }
            self.microphoneEnabled = enabled
        }
    }
    func toggleCamera() {
        if phase == .reconnecting, cameraEnabled {
            cameraEnabled = false; reconcileMedia(); return
        }
        changeMedia { room in
            let enabled = !self.cameraEnabled
            if enabled { try await self.requestPermissions(microphone: false, camera: true) }
            try self.api.requireCurrent(); try Task.checkCancellation()
            guard self.room === room, self.phase == .connected else { throw CancellationError() }
            self.cameraEnabled = enabled
        }
    }
    func switchCamera() {
        changeMedia { room in
            guard self.foreground, self.cameraEnabled else { return }
            guard let track = room.localParticipant.videoTracks.first(where: { $0.source == .camera })?.track as? LocalVideoTrack,
                  let capturer = track.capturer as? CameraCapturer else { return }
            try await capturer.switchCameraPosition()
            if self.room === room { self.cameraPosition = capturer.position }
        }
    }
    private func changeMedia(_ body: @escaping (Room) async throws -> Void) {
        guard !retired, !mediaChanging, phase == .connected, let room else { return }
        runMedia(room, body: body)
    }

    func setForeground(_ active: Bool) {
        guard foreground != active else { return }
        foreground = active
        applyPresentedVideo()
        reconcileMedia()
        // Refresh terminal/revoked state immediately after an iOS suspension.
        if active, phase == .connected { startMonitor(attempt, immediately: true) }
    }

    private func reconcileMedia() {
        mediaRevision &+= 1
        guard !retired, phase != .leaving, phase != .idle, mediaTask == nil,
              let room, room.connectionState == .connected else { return }
        runMedia(room) { _ in }
    }

    private func runMedia(_ room: Room, body: @escaping (Room) async throws -> Void) {
        guard mediaTask == nil else { return }
        let ticket = attempt
        let originalConsent = microphoneConsentRevision
        mediaChanging = true; mediaFailure = nil
        mediaTask = Task { @MainActor in
            defer { self.mediaChanging = false; self.mediaTask = nil }
            do {
                try await body(room)
                repeat {
                    let revision = self.mediaRevision
                    let consent = self.microphoneConsentRevision
                    try self.check(ticket); try self.verifyEncryption(room)
                    guard self.room === room else { throw CancellationError() }
                    guard room.connectionState == .connected else { throw LiveKitError(.network) }
                    do {
                        try await room.localParticipant.setMicrophone(enabled: self.microphoneEnabled && !self.microphoneBlockedByOwner)
                    } catch {
                        try self.check(ticket)
                        guard self.room === room else { throw CancellationError() }
                        self.applyOwnAudioRestriction(room)
                        // An owner can revoke publish permission while the SDK
                        // is awaiting a first publication. Stay in the room,
                        // finish muted, and still apply the camera choice.
                        guard self.microphoneBlockedByOwner,
                              (error as? LiveKitError)?.type == .insufficientPermissions else { throw error }
                        self.microphoneEnabled = false
                        try await room.localParticipant.setMicrophone(enabled: false)
                    }
                    try self.check(ticket)
                    guard self.room === room else { throw CancellationError() }
                    if consent != self.microphoneConsentRevision {
                        self.microphoneEnabled = false
                        try await room.localParticipant.setMicrophone(enabled: false)
                        try self.check(ticket)
                        guard self.room === room else { throw CancellationError() }
                    }
                    guard room.connectionState == .connected else { throw LiveKitError(.network) }
                    try await room.localParticipant.setCamera(enabled: self.cameraEnabled && self.foreground)
                    try self.check(ticket)
                    guard self.room === room else { throw CancellationError() }
                    if consent != self.microphoneConsentRevision {
                        self.microphoneEnabled = false
                        try await room.localParticipant.setMicrophone(enabled: false)
                        try self.check(ticket)
                        guard self.room === room else { throw CancellationError() }
                    }
                    guard room.connectionState == .connected else { throw LiveKitError(.network) }
                    if revision == self.mediaRevision { break }
                } while !Task.isCancelled
                self.refresh(room)
            } catch {
                if !Task.isCancelled && self.room === room && self.phase != .leaving {
                    if originalConsent != self.microphoneConsentRevision {
                        self.microphoneEnabled = false
                        try? await room.localParticipant.setMicrophone(enabled: false)
                        guard !Task.isCancelled, self.room === room, self.attempt == ticket,
                              self.phase != .leaving else { return }
                    }
                    if error is CancellationError, originalConsent != self.microphoneConsentRevision,
                       room.connectionState == .connected {
                        // This was an invalidated enable gesture, not a
                        // transport or camera-permission failure to show users.
                        self.mediaFailure = nil
                        self.refresh(room)
                        return
                    }
                    let interrupted = (error is CancellationError || (error as? LiveKitError)?.type == .cancelled)
                        && room.connectionState != .connected
                    let failure: Error = interrupted ? LiveKitError(.network) : error
                    self.mediaFailure = failure
                    if self.phase == .connected && !Self.canRecover(failure) {
                        self.microphoneEnabled = room.localParticipant.isMicrophoneEnabled() && !self.microphoneBlockedByOwner
                            && originalConsent == self.microphoneConsentRevision
                        self.cameraEnabled = room.localParticipant.isCameraEnabled()
                    }
                    self.error = "Could not change conference media. Check camera and microphone permissions."
                }
            }
        }
    }

    private static func canRecover(_ error: Error) -> Bool {
        if let sdk = error as? LiveKitError {
            // SFU validation can reject an expired short-lived media grant;
            // the app API authenticates every fresh grant independently.
            return [.network, .timedOut, .serverPingTimedOut, .serverShutdown, .webRTC, .validation].contains(sdk.type)
        }
        if error is URLError { return true }
        switch error {
        case APIError.network: return true
        case APIError.http(let status): return status == 429 || (500...599).contains(status)
        case APIError.server(let code, _): return ["rate_limited", "internal_error", "unavailable"].contains(code)
        default: return false
        }
    }

    private func recover(_ oldRoom: Room) {
        guard self.room === oldRoom, !retired, phase != .leaving, connectedAt != nil,
              recoveryTask == nil, let invite = credential, let admission else { return }
        phase = .reconnecting
        stopJoinApprovalMonitor()
        // During recovery /join is authoritative. A member reaped while
        // offline may receive GET 404 yet still legitimately rejoin by invite.
        monitor?.cancel(); monitor = nil
        keyServiceTask?.cancel(); keyServiceTask = nil; keyEnvelopes.removeAll()
        stableConnectionTask?.cancel(); stableConnectionTask = nil
        if outageStartedAt == nil { outageStartedAt = Date() }
        let ticket = attempt
        // Detach delegates before the first suspension. Old transport callbacks
        // can never reopen the screen or mutate a successor's crypto/subscriptions.
        room = nil; provider = nil; expectedIdentity = nil
        resetSubscriptions()
        let pendingMedia = mediaTask; pendingMedia?.cancel()
        recoveryTask = Task { @MainActor in
            await pendingMedia?.value
            await self.stopRoom(oldRoom)
            let delays: [UInt64] = [0, 1, 2, 4, 7, 10, 15, 15]
            while self.recoveryAttempts < delays.count, let started = self.outageStartedAt,
                  Date().timeIntervalSince(started) < 120 {
                do {
                    let delay = delays[self.recoveryAttempts]; self.recoveryAttempts += 1
                    if delay > 0 { try await Task.sleep(nanoseconds: delay * 1_000_000_000) }
                    try self.check(ticket)
                    guard Date().timeIntervalSince(started) < 120 else { break }
                    // A refreshed grant is obtained even when the previous
                    // short-lived SFU token has expired. Never reuse its Room.
                    let request = Task { @MainActor in try await self.api.joinCredential(invite, supportsKeyHandoff: self.supportsKeyHandoff) }
                    admission.joinRequest = request
                    let joined = try await request.value
                    try self.check(ticket)
                    let successor = try self.prepareRoom(joined, credential: invite)
                    try await successor.connect(url: joined.livekitUrl, token: joined.participantToken)
                    try self.check(ticket); try self.verifyEncryption(successor)
                    self.applyOwnAudioRestriction(successor)
                    self.reconcileMedia(); await self.mediaTask?.value
                    try self.check(ticket)
                    if let failure = self.mediaFailure { throw failure }
                    try self.verifyEncryption(successor)
                    guard Date().timeIntervalSince(started) < 120 else { throw ConferenceInvite.Failure.closed }
                    self.phase = .connected; self.error = nil
                    self.refresh(successor); self.startMonitor(ticket, immediately: true)
                    self.startJoinApprovalMonitor(ticket)
                    self.startKeyService(ticket)
                    self.resetRecoveryAfterStability(successor, ticket: ticket)
                    self.recoveryTask = nil
                    return
                } catch {
                    // Also closes a fresh Room whose connect failed. The lease
                    // stays held through every SDK stop and subsequent admission.
                    let failedRoom = self.room; self.room = nil; self.provider = nil; self.expectedIdentity = nil
                    let transportCancelled = (error is CancellationError || (error as? LiveKitError)?.type == .cancelled)
                        && failedRoom != nil && failedRoom?.connectionState != .connected
                    let pending = self.mediaTask; pending?.cancel(); await pending?.value
                    await self.stopRoom(failedRoom); self.resetSubscriptions()
                    guard !Task.isCancelled, !self.retired, self.attempt == ticket else { return }
                    if !Self.canRecover(error) && !transportCancelled {
                        self.recoveryTask = nil
                        await self.releaseMedia()
                        self.error = Self.roomError(error)
                        return
                    }
                }
            }
            guard self.attempt == ticket, !self.retired, !Task.isCancelled else { return }
            self.recoveryTask = nil
            await self.releaseMedia(); self.error = ConferenceInvite.Failure.closed.localizedDescription
        }
    }

    private func resetRecoveryAfterStability(_ room: Room, ticket: UUID) {
        stableConnectionTask?.cancel()
        stableConnectionTask = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 10_000_000_000) } catch { return }
            guard !Task.isCancelled, !self.retired, self.attempt == ticket,
                  self.room === room, self.phase == .connected, room.connectionState == .connected,
                  self.api.isCurrentSession else { return }
            self.outageStartedAt = nil; self.recoveryAttempts = 0; self.stableConnectionTask = nil
        }
    }

    private func stopRoom(_ room: Room?) async {
        for publication in room?.localParticipant.videoTracks ?? [] { try? await publication.track?.stop() }
        for publication in room?.localParticipant.audioTracks ?? [] { try? await publication.track?.stop() }
        await room?.disconnect()
    }

    func leave(endForEveryone: Bool = false) {
        guard !retired, isBusy, phase != .leaving, !endForEveryone || isOwner else { return }
        admission?.endForEveryone = endForEveryone
        stopJoinApprovalMonitor()
        operation?.cancel(); monitor?.cancel(); connectionTask?.cancel(); recoveryTask?.cancel(); mediaTask?.cancel(); attempt = UUID()
        let ticket = attempt
        phase = .leaving
        operation = Task {
            guard !self.retired, self.attempt == ticket else { return }
            await releaseMedia()
        }
    }

    func shutdown() {
        guard !retired else { return }
        retired = true; attempt = UUID(); operation?.cancel(); monitor?.cancel()
        stopJoinApprovalMonitor()
        ownedAccess = nil
        keyHandoff.cancel(); keyServiceTask?.cancel(); keyServiceTask = nil; keyEnvelopes.removeAll(); credential = nil
        connectionTask?.cancel(); recoveryTask?.cancel(); mediaTask?.cancel()
        stableConnectionTask?.cancel(); stableConnectionTask = nil
        admission?.cancelRequests(); cleaningAdmission?.cancelRequests()
        // A leave/end already sent with the old admission must finish before a
        // same-account successor can acquire its room. Local capture still
        // stops immediately; cleanup never starts a new stale-session request.
        if cleaningAdmission?.finishingRequest != true { admissionCleanup?.cancel() }
        offeredInvite = nil; invitation = nil; provider = nil; expectedIdentity = nil; showLobby = false
        // SDK track teardown is async. Keep the media lease until it completes;
        // no HTTP request or credential refresh is involved in local teardown.
        Task { await releaseMedia() }
    }

    private func releaseMedia() async {
        if let teardown { await teardown.value; return }
        stopJoinApprovalMonitor(); approvalWaiting = false
        let oldRoom = room; room = nil
        keyHandoff.cancel()
        keyServiceTask?.cancel(); keyServiceTask = nil; keyEnvelopes.removeAll(); credential = nil
        let pendingConnection = connectionTask; connectionTask = nil; pendingConnection?.cancel()
        let pendingRecovery = recoveryTask; recoveryTask = nil; pendingRecovery?.cancel()
        let pendingMedia = mediaTask; pendingMedia?.cancel()
        let pendingAdmission = admission; admission = nil
        if let pendingAdmission { cleaningAdmission = pendingAdmission }
        operation?.cancel(); monitor?.cancel()
        stableConnectionTask?.cancel(); stableConnectionTask = nil
        outageStartedAt = nil; recoveryAttempts = 0; mediaFailure = nil
        resetSubscriptions(); visibleVideoIDs.removeAll()
        // Invalidate an operation which was still awaiting permissions/REST.
        attempt = UUID(); phase = .leaving
        invitation = nil; provider = nil; expectedIdentity = nil; offeredInvite = nil
        // A second logout/leave callback awaits the same teardown. It must
        // never release the process-wide lease while the first SDK stop awaits.
        let task = Task { @MainActor in
            // Stop what exists immediately, then join in-flight SDK work and
            // stop again: cancellation alone cannot retract a late capture start.
            for publication in oldRoom?.localParticipant.videoTracks ?? [] { try? await publication.track?.stop() }
            for publication in oldRoom?.localParticipant.audioTracks ?? [] { try? await publication.track?.stop() }
            await pendingMedia?.value
            await pendingConnection?.value
            await pendingRecovery?.value
            if pendingMedia != nil || pendingConnection != nil || pendingRecovery != nil {
                for publication in oldRoom?.localParticipant.videoTracks ?? [] { try? await publication.track?.stop() }
                for publication in oldRoom?.localParticipant.audioTracks ?? [] { try? await publication.track?.stop() }
            }
            await oldRoom?.disconnect()
            self.monitor?.cancel(); self.monitor = nil
            self.invitation = nil; self.provider = nil; self.offeredInvite = nil; self.conference = nil; self.tiles = []
            self.subscriptions.removeAll(); self.pinnedID = nil; self.microphoneEnabled = false; self.cameraEnabled = false
            self.remotePageAnchorID = nil; self.videoPresentationOwner = nil
            self.selectedVisibleCount = 0; self.fitEveryoneOnScreen = false; self.hostControlsAvailable = false
            self.changingRoomSettings = false
            self.participantModerationAvailable = false; self.moderatingParticipantID = nil; self.microphoneBlockedByOwner = false
            self.locallyMutedParticipantIDs.removeAll(); self.locallyHiddenParticipantIDs.removeAll()
            self.desiredPresentationIDs.removeAll(); self.presentationActive = true
            self.previewViewport = nil
            self.ownPreviewPosition = CGPoint(x: 0.96, y: 0.05)
            self.mediaChanging = false; self.connectedAt = nil; self.minimized = false
            if !self.retired, let pendingAdmission {
                self.admissionCleanup = Task { @MainActor in
                    await pendingAdmission.settle()
                    if !self.retired, self.api.isCurrentSession, !Task.isCancelled {
                        pendingAdmission.finishingRequest = true
                        if pendingAdmission.endForEveryone || (!pendingAdmission.connected && pendingAdmission.createdID != nil),
                           let id = pendingAdmission.createdID ?? pendingAdmission.roomID {
                            do { try await self.api.finish(id, end: true); self.forgetOwnedRoom(id) }
                            catch {
                                if !self.retired, !ConferencesAPI.endsConference(error) {
                                    self.error = "Could not end the conference for everyone. This device has left. Other participants may still be connected."
                                }
                            }
                        } else if let id = pendingAdmission.roomID {
                            try? await self.api.finish(id, end: false)
                        }
                        pendingAdmission.finishingRequest = false
                    }
                    self.cleaningAdmission = nil; self.admissionCleanup = nil
                    self.phase = .idle
                    if ConferenceMediaOwnership.owner == self.mediaLease {
                        ConferenceMediaOwnership.owner = nil
                        self.onMediaOwnershipChanged(false)
                    }
                }
            } else if (self.retired && self.cleaningAdmission?.finishingRequest != true) || self.admissionCleanup == nil {
                self.cleaningAdmission = nil
                self.phase = .idle
                if ConferenceMediaOwnership.owner == self.mediaLease {
                    ConferenceMediaOwnership.owner = nil
                    self.onMediaOwnershipChanged(false)
                }
            }
        }
        teardown = task
        await task.value
        teardown = nil
    }

    private func stopJoinApprovalMonitor() {
        approvalWaitTask?.cancel(); approvalWaitTask = nil
        joinApprovalRevision &+= 1
        joinApprovalTask?.cancel(); joinApprovalTask = nil
        joinDecisionTask?.cancel(); joinDecisionTask = nil
        pendingJoinRequests = []; decidingJoinRequestID = nil
    }

    private func startJoinApprovalMonitor(_ ticket: UUID) {
        joinApprovalTask?.cancel()
        joinApprovalTask = Task { @MainActor [weak self] in
            var pause: UInt64 = 0
            while !Task.isCancelled {
                if pause > 0 { do { try await Task.sleep(nanoseconds: pause * 1_000_000_000) } catch { return } }
                pause = 3
                guard let self, !self.retired, self.attempt == ticket, self.api.isCurrentSession, let room = self.conference else { return }
                guard self.foreground, !self.minimized, self.phase == .connected, self.isOwner,
                      self.joinApprovalAvailable, room.approvalRequired == true else {
                    if room.approvalRequired != true { self.pendingJoinRequests = [] }
                    continue
                }
                guard self.decidingJoinRequestID == nil else { continue }
                let revision = self.joinApprovalRevision
                do {
                    let requests = try await self.api.pendingJoinApprovals(room: room.id)
                    try self.check(ticket)
                    guard self.conference?.id == room.id, revision == self.joinApprovalRevision,
                          self.decidingJoinRequestID == nil, self.conference?.approvalRequired == true else { continue }
                    if self.pendingJoinRequests != requests { self.pendingJoinRequests = requests }
                } catch {
                    guard self.attempt == ticket, !self.retired, !Task.isCancelled else { return }
                    switch error {
                    case APIError.http(429), APIError.server("rate_limited", _): pause = 61
                    default: pause = 6
                    }
                }
            }
        }
    }

    func decideJoinRequest(_ request: ConferenceJoinRequest, approve: Bool) {
        guard !retired, api.isCurrentSession, isOwner, phase == .connected, let room = conference,
              room.approvalRequired == true, request.conferenceId == room.id,
              pendingJoinRequests.contains(where: { $0.requestId == request.requestId }), decidingJoinRequestID == nil else { return }
        let ticket = attempt
        decidingJoinRequestID = request.requestId; joinApprovalRevision &+= 1
        joinDecisionTask = Task { @MainActor in
            defer {
                if self.attempt == ticket {
                    self.joinApprovalRevision &+= 1; self.decidingJoinRequestID = nil; self.joinDecisionTask = nil
                }
            }
            do {
                _ = try await self.api.decideJoinApproval(room: room.id, id: request.requestId, approve: approve)
                try self.check(ticket)
                self.pendingJoinRequests.removeAll { $0.requestId == request.requestId }
            } catch {
                guard self.attempt == ticket, !self.retired, self.api.isCurrentSession, !Task.isCancelled else { return }
                self.error = "Could not update the join request. Try again."
            }
        }
    }

    private func startMonitor(_ ticket: UUID, immediately: Bool = false) {
        monitor?.cancel()
        monitor = Task { [weak self] in
            var first = immediately
            while !Task.isCancelled {
                if !first { do { try await Task.sleep(nanoseconds: 10_000_000_000) } catch { return } }
                first = false
                guard let self, let id = self.conference?.id else { return }
                do {
                    try self.check(ticket)
                    let settingsRevision = self.roomSettingsRevision
                    let value = try await self.api.room(id)
                    try self.check(ticket)
                    guard settingsRevision == self.roomSettingsRevision, !self.changingRoomSettings, self.moderatingParticipantID == nil else { continue }
                    guard value.state == "active", value.expiresAt > Date() else {
                        self.forgetOwnedRoom(id)
                        await self.releaseMedia(); self.error = ConferenceInvite.Failure.closed.localizedDescription; return
                    }
                    self.conference = value
                    if let room = self.room, room.connectionState == .connected {
                        self.applyOwnAudioRestriction(room)
                        self.refresh(room); self.reconcileMedia()
                    }
                } catch {
                    if ConferencesAPI.endsConference(error) {
                        self.forgetOwnedRoom(id)
                        await self.releaseMedia(); self.error = Self.roomError(error); return
                    }
                    // A transport failure is not proof of a terminal room state.
                }
            }
        }
    }

    private static func roomError(_ error: Error?) -> String {
        guard let error else { return ConferenceInvite.Failure.closed.localizedDescription }
        if (error as? LiveKitError)?.type == .participantRemoved { return ConferenceDirectoryFailure.removed.localizedDescription }
        let value = ConferencesAPI.accessError(error)
        return (value as? ConferenceDirectoryFailure)?.localizedDescription ??
            (value as? ConferenceInvite.Failure)?.localizedDescription ?? ConferenceInvite.Failure.closed.localizedDescription
    }

    /// A single sequential heartbeat/mailbox loop. It never publishes tile or
    /// audio-level state and only starts after successful E2EE verification.
    private func startKeyService(_ ticket: UUID) {
        keyServiceTask?.cancel(); keyServiceTask = nil; keyEnvelopes.removeAll()
        guard supportsKeyHandoff, let signalManager, let media = room,
              let credential, let id = conference?.id, let identity = expectedIdentity,
              let user = auth?.currentUser?.id.lowercased(), let device = auth?.currentDeviceId?.uuidString.lowercased(),
              phase == .connected else { return }
        func owns() -> Bool {
            self.attempt == ticket && !self.retired && self.api.isCurrentSession && self.room === media &&
                self.phase == .connected && self.expectedIdentity == identity && self.conference?.id == id &&
                self.auth?.currentUser?.id.lowercased() == user
        }
        keyServiceTask = Task { [weak self] in
            guard let self else { return }
            while owns(), !Task.isCancelled {
                do {
                    try self.verifyEncryption(media)
                    let pending = try await self.api.pendingKeys(room: id)
                    guard owns(), !Task.isCancelled else { return }
                    var currentBindings = Set<String>()
                    for request in pending.prefix(8) {
                        guard owns(), !Task.isCancelled else { return }
                        guard request.status == "pending", request.providerUserId == user, request.providerDeviceId == device,
                              request.providerIdentity == identity, request.roomId == id.uuidString.lowercased(),
                              request.accessRevision == (self.conference?.accessRevision ?? 1) else { continue }
                        try request.validate(now: Date())
                        let binding = request.encryptedEnvelopeBinding
                        currentBindings.insert(binding)
                        let envelope: EnvelopeInput
                        if let existing = self.keyEnvelopes[binding] { envelope = existing }
                        else {
                            let bytes = try ConferenceKeyPayload.providing(request: request, server: self.api.server, mediaKey: credential.mediaKey, now: Date())
                            envelope = try await signalManager.encryptExact(plaintext: bytes, userId: request.requesterUserId, deviceId: request.requesterDeviceId)
                            guard owns(), !Task.isCancelled else { return }
                            try self.verifyEncryption(media)
                            guard request.accessRevision == (self.conference?.accessRevision ?? 1) else { continue }
                            self.keyEnvelopes[binding] = envelope
                        }
                        guard owns(), !Task.isCancelled else { return }
                        try await self.api.fulfillKey(room: id, request: request, envelope: envelope)
                        await Task.yield()
                    }
                    self.keyEnvelopes = self.keyEnvelopes.filter { currentBindings.contains($0.key) }
                } catch {
                    guard owns(), !Task.isCancelled else { return }
                    // Identity/cipher failures never send a fallback. Ordinary
                    // media remains active; the bounded requester can retry.
                    if (error as? APIError)?.isRateLimited == true {
                        do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
                    }
                }
                do { try await Task.sleep(nanoseconds: self.foreground ? 2_000_000_000 : 5_000_000_000) } catch { return }
            }
        }
    }

    private func refresh(_ room: Room) {
        guard self.room === room, !retired, phase != .leaving else { return }
        applyOwnAudioRestriction(room)
        let participants: [Participant] = [room.localParticipant] + Array(room.remoteParticipants.values)
        let byID = Dictionary(uniqueKeysWithValues: participants.compactMap { participant -> (String, Participant)? in
            guard let id = participant.identity?.stringValue else { return nil }; return (id, participant)
        })
        var ordered = tiles.map(\.id).filter { byID[$0] != nil }
        ordered.append(contentsOf: byID.keys.filter { !ordered.contains($0) }.sorted())
        let previousStage = stageTiles.map(\.id)
        let previousAnchor = remotePageAnchorID.flatMap { previousStage.firstIndex(of: $0) } ?? 0
        tiles = ordered.prefix(30).compactMap { id in byID[id].map { Tile(id: id, participant: $0) } }
        if remotePageAnchorID == nil || !stageTiles.contains(where: { $0.id == remotePageAnchorID }) {
            let values = stageTiles
            remotePageAnchorID = values.isEmpty ? nil : values[min(previousAnchor, values.count - 1)].id
        }
        if let pinnedID, byID[pinnedID] == nil { self.pinnedID = nil }
        let audioObjects = Set(room.remoteParticipants.values.flatMap { $0.audioTracks }.compactMap {
            ($0.track as? RemoteAudioTrack).map(ObjectIdentifier.init)
        })
        locallySilencedAudioTracks.formIntersection(audioObjects)
        // Choices survive a transport recovery, but cannot grow without bound
        // during a long room with many successive, unrelated participants.
        if locallyMutedParticipantIDs.count > 128 { locallyMutedParticipantIDs.formIntersection(Set(byID.keys)) }
        if locallyHiddenParticipantIDs.count > 128 { locallyHiddenParticipantIDs.formIntersection(Set(byID.keys)) }
        for participant in room.remoteParticipants.values {
            for publication in participant.audioTracks + participant.videoTracks {
                guard let remote = publication as? RemoteTrackPublication else { continue }
                guard remote.encryptionType == .gcm else {
                    failEncryption(room); return
                }
            }
        }
        observeParticipantPermissions(room)
        for participant in room.remoteParticipants.values {
            if let id = participant.identity?.stringValue { applyLocalAudioPreference(id) }
        }
        updateSubscriptions(room)
    }

    func showVideoParticipants(_ ids: Set<String>) {
        guard ids != visibleVideoIDs else { return }
        visibleVideoIDs = ids
        if let room { updateSubscriptions(room) }
    }

    private func resetSubscriptions() {
        subscriptionEpoch = UUID()
        subscriptionTask?.cancel(); subscriptionTask = nil
        subscriptions.removeAll()
        locallySilencedAudioTracks.removeAll()
        participantPermissionObservers.removeAll()
    }

    private func updateSubscriptions(_ room: Room) {
        subscriptionRevision &+= 1
        guard subscriptionTask == nil else { return }
        let epoch = subscriptionEpoch
        subscriptionTask = Task { @MainActor in
            defer { if self.room === room, self.subscriptionEpoch == epoch { self.subscriptionTask = nil } }
            while !Task.isCancelled, self.room === room, self.subscriptionEpoch == epoch {
                let revision = self.subscriptionRevision
                for participant in room.remoteParticipants.values {
                    for publication in participant.audioTracks + participant.videoTracks {
                        guard !Task.isCancelled, self.room === room, self.subscriptionEpoch == epoch,
                              let remote = publication as? RemoteTrackPublication else { return }
                        guard remote.encryptionType == .gcm else { self.failEncryption(room); return }
                        let identity = participant.identity?.stringValue ?? ""
                        let wanted = remote.kind == .audio
                            ? !self.locallyMutedParticipantIDs.contains(identity) && !self.isParticipantAudioBlocked(identity)
                            : self.visibleVideoIDs.contains(identity) && !self.locallyHiddenParticipantIDs.contains(identity)
                        let sid = remote.sid.stringValue
                        guard self.subscriptions.contains(sid) != wanted else { continue }
                        do {
                            try await remote.set(subscribed: wanted)
                            guard self.room === room, self.subscriptionEpoch == epoch else { return }
                            if wanted { self.subscriptions.insert(sid) } else { self.subscriptions.remove(sid) }
                        } catch {
                            guard self.room === room, self.subscriptionEpoch == epoch else { return }
                            // SDK preferred state is assigned before signaling.
                            // Roll it back so the next monitor retry can resend.
                            try? await remote.set(subscribed: !wanted)
                            guard self.room === room, self.subscriptionEpoch == epoch else { return }
                            self.error = "A participant's media could not be received."
                        }
                    }
                }
                if revision == self.subscriptionRevision { return }
            }
        }
    }
    private func failEncryption(_ room: Room) {
        guard self.room === room, phase != .leaving else { return }
        error = ConferenceInvite.Failure.encryption.localizedDescription
        leave()
    }

    nonisolated func room(_ room: Room, didUpdateConnectionState state: ConnectionState, from old: ConnectionState) {
        Task { @MainActor in
            guard self.room === room, !self.retired, self.phase != .leaving else { return }
            if state == .reconnecting { self.phase = .reconnecting }
            // Only the admission task can mark a fresh Room connected after
            // identity/encryption validation and local media reconciliation.
            if state == .connected, self.phase == .connected { self.refresh(room) }
        }
    }
    nonisolated func room(_ room: Room, didStartReconnectWithMode mode: ReconnectMode) {
        Task { @MainActor in
            guard self.room === room, !self.retired, self.phase != .leaving else { return }
            self.phase = .reconnecting
        }
    }
    nonisolated func room(_ room: Room, didCompleteReconnectWithMode mode: ReconnectMode) {
        // App Rooms disable SDK retries. Never let a queued completion reopen
        // a leaving/retired Room or skip fresh-admission crypto verification.
        Task { @MainActor in
            guard self.room === room, !self.retired, self.phase == .connected else { return }
            self.refresh(room)
        }
    }
    nonisolated func room(_ room: Room, didDisconnectWithError error: LiveKitError?) {
        Task { @MainActor in
            guard self.room === room, !self.retired, self.phase != .leaving else { return }
            if let error, Self.canRecover(error), self.connectedAt != nil {
                self.recover(room); return
            }
            let message = error?.type == .participantRemoved ? ConferenceDirectoryFailure.removed.localizedDescription : Self.roomError(error)
            await self.releaseMedia(); self.error = message
        }
    }
    nonisolated func room(_ room: Room, participantDidConnect participant: RemoteParticipant) { update(room) }
    nonisolated func room(_ room: Room, participantDidDisconnect participant: RemoteParticipant) { update(room) }
    nonisolated func room(_ room: Room, participant: Participant, didUpdatePermissions permissions: ParticipantPermissions) {
        guard participant is LocalParticipant else { return }
        let sources = permissions.canPublishSources
        let denied = !permissions.canPublish || (!sources.isEmpty && !sources.contains(Track.Source.microphone.rawValue))
        Task { @MainActor in
            guard self.room === room, !self.retired, self.phase != .leaving else { return }
            // Keep the event's revocation even if a later grant has already
            // updated SDK state before this MainActor callback gets a turn.
            if denied {
                self.revokeOwnMicrophoneIntent()
                for publication in room.localParticipant.audioTracks {
                    guard self.room === room, !self.retired, self.phase != .leaving else { return }
                    try? await (publication as? LocalTrackPublication)?.mute()
                }
                guard self.room === room, !self.retired, self.phase != .leaving else { return }
                self.reconcileMedia()
            }
            self.applyOwnAudioRestriction(room)
        }
    }
    // LiveKit Participant is ObservableObject. Only visible tile views observe
    // speaking/audio-level changes; rebuilding the whole 30-person grid here
    // would also reconcile every subscription on every speaker update.
    nonisolated func room(_ room: Room, didUpdateSpeakingParticipants participants: [Participant]) {}
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didPublishTrack publication: RemoteTrackPublication) { update(room) }
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) { update(room) }
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didUnpublishTrack publication: RemoteTrackPublication) { update(room) }
    nonisolated func room(_ room: Room, participant: Participant, trackPublication: TrackPublication, didUpdateIsMuted muted: Bool) { update(room) }
    nonisolated func room(_ room: Room, trackPublication: TrackPublication, didUpdateE2EEState state: E2EEState) {
        guard state == .missing_key || state == .encryption_failed || state == .decryption_failed || state == .internal_error else { return }
        Task { @MainActor in self.failEncryption(room) }
    }
    nonisolated private func update(_ room: Room) { Task { @MainActor in self.refresh(room) } }
}
