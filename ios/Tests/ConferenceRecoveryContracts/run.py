"""Execute production recovery/media methods with suspended native/API seams.

No Apple SDK is required. These checks verify ordering and cancellation, not
hardware capture or real transport; ConferenceMediaIntegration covers that layer.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")


def method(marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func", "func", 1).replace("private static func", "static func", 1)


harness = r'''
import Foundation
enum ConnectionState { case disconnected, connecting, reconnecting, connected }
enum ReconnectMode { case quick, full }
enum Encryption { case gcm, none }
enum Derivation { case pbkdf2 }
enum Codec { case vp8 }
enum Dimensions { case h540_169 }
enum AVMediaType { case audio, video }
enum AVCaptureDevice { enum Position { case front, back } }
enum APIError: Error { case network(String), http(Int), server(code: String, message: String), missingSession }
enum LiveKitErrorType { case network, timedOut, serverPingTimedOut, serverShutdown, webRTC, validation, participantRemoved, roomDeleted, encryptionFailed, cancelled, insufficientPermissions }
final class LiveKitError: Error { let type: LiveKitErrorType; init(_ value: LiveKitErrorType) { type = value } }
enum ConferenceDirectoryFailure: Error { case removed; var localizedDescription: String { "removed" } }
struct ConferenceCatalogCredential { let roomID: UUID; let requestID: UUID; let mediaKey: String }
__CREDENTIAL__
struct ConferenceInvite { static func decodeKey(_ value: String) throws -> Data { Data() }; var roomID = UUID(); let mediaKey = "memory-key"; enum Failure: Error { case encryption, closed; var localizedDescription: String { "closed" } } }
struct ConferenceDTO { let id: UUID; var state = "active"; var maxParticipants = 30; var expiresAt = Date().addingTimeInterval(600) }
struct ConferenceJoinDTO {
    let conference: ConferenceDTO; let participantIdentity: String
    var livekitUrl = "wss://fixture.invalid"; var participantToken = "memory-token"
    var tokenExpiresAt = Date().addingTimeInterval(60); var e2eeRequired = true
}
struct KeyProviderOptions {
    init(sharedKey: Bool, ratchetSalt: Data, ratchetWindowSize: Int, uncryptedMagicBytes: Data,
         discardFrameWhenCryptorNotReady: Bool, keyDerivationAlgorithm: Derivation) {
        precondition(sharedKey && ratchetSalt == Data("LKFrameEncryptionKey".utf8) && ratchetWindowSize == 0)
        precondition(uncryptedMagicBytes.isEmpty && discardFrameWhenCryptorNotReady)
    }
}
@MainActor final class BaseKeyProvider {
    static var created = 0
    var key: String?
    init(options: KeyProviderOptions) { Self.created += 1 }
    func setKey(key: String, index: Int) { precondition(index == 0); self.key = key }
}
struct CameraCaptureOptions { init(position: AVCaptureDevice.Position, dimensions: Dimensions, fps: Int) {} }
struct VideoPublishOptions { init(preferredCodec: Codec) {} }
@MainActor struct E2EEOptions { let keyProvider: BaseKeyProvider; let encryptionType: Encryption }
@MainActor struct RoomOptions {
    let provider: BaseKeyProvider
    init(defaultCameraCaptureOptions: CameraCaptureOptions, defaultVideoPublishOptions: VideoPublishOptions,
         adaptiveStream: Bool, dynacast: Bool, suspendLocalVideoTracksInBackground: Bool, e2eeOptions: E2EEOptions) {
        precondition(!suspendLocalVideoTracksInBackground); provider = e2eeOptions.keyProvider
    }
}
struct ConnectOptions {
    let reconnectAttempts: Int
    init(autoSubscribe: Bool, reconnectAttempts: Int) { precondition(!autoSubscribe); self.reconnectAttempts = reconnectAttempts }
}
struct Identity { let stringValue: String }
@MainActor final class Gate {
    var continuation: CheckedContinuation<Void, Never>?
    var entered = false
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class Track { var stops = 0; func stop() async throws { stops += 1 } }
@MainActor struct Publication { var track: Track? }
@MainActor final class LocalParticipant {
    var identity: Identity? = Identity(stringValue: "identity")
    var audioTracks = [Publication(track: Track())], videoTracks = [Publication(track: Track())]
    var microphone = false, camera = false
    var cameraGate: Gate?, microphoneGate: Gate?
    var cameraHistory: [Bool] = []
    var failCamera = false, failMicrophoneOnce = false
    var onMicrophone: (() -> Void)?
    var onCamera: (() -> Void)?
    func isMicrophoneEnabled() -> Bool { microphone }
    func isCameraEnabled() -> Bool { camera }
    func setMicrophone(enabled: Bool) async throws {
        if let gate = microphoneGate { microphoneGate = nil; await gate.wait() }
        if failMicrophoneOnce { failMicrophoneOnce = false; onMicrophone?(); throw LiveKitError(.insufficientPermissions) }
        microphone = enabled
    }
    func setCamera(enabled: Bool) async throws {
        if failCamera { throw LiveKitError(.encryptionFailed) }
        if let gate = cameraGate { cameraGate = nil; await gate.wait() }
        camera = enabled; cameraHistory.append(enabled)
        onCamera?()
    }
}
@MainActor final class Room {
    static var created: [Room] = []
    let localParticipant = LocalParticipant()
    var connectionState = ConnectionState.connected
    let provider: BaseKeyProvider
    var e2eeManager: Manager? = Manager()
    let options: ConnectOptions
    init(delegate: Owner, connectOptions: ConnectOptions, roomOptions: RoomOptions) {
        provider = roomOptions.provider; options = connectOptions; Self.created.append(self)
    }
    func connect(url: String, token: String) async throws { connectionState = .connected }
    func disconnect() async { connectionState = .disconnected }
}
@MainActor final class Manager { var frameEncryptionType = Encryption.gcm }
@MainActor final class ConferenceAdmission { var joinRequest: Task<ConferenceJoinDTO, Error>? }
@MainActor final class ConferencesAPI {
    var gate: Gate?, calls = 0, failFirst = false
    var joinedCredentials: [ConferenceAdmissionCredential] = []
    var isCurrentSession = true
    func requireCurrent() throws {}
    static func accessError(_ error: Error) -> Error { error }
    func joinCredential(_ invite: ConferenceAdmissionCredential, supportsKeyHandoff: Bool) async throws -> ConferenceJoinDTO {
        calls += 1; joinedCredentials.append(invite)
        if let gate { await gate.wait() }
        if failFirst && calls == 1 { throw APIError.network("offline") }
        return ConferenceJoinDTO(conference: ConferenceDTO(id: invite.roomID), participantIdentity: "identity")
    }
}
@MainActor final class Owner {
    enum Phase { case idle, connecting, connected, reconnecting, leaving }
    var phase = Phase.connected
    // Presentation ownership is exercised by its own actual-method suite.
    // This seam observes recovery/media only and never owns SDK video views.
    func applyPresentedVideo() {}
    var retired = false, foreground = true, microphoneEnabled = true, cameraEnabled = true, mediaChanging = false
    var room: Room?, provider: BaseKeyProvider?, expectedIdentity: String?
    var invitation: ConferenceInvite? = ConferenceInvite()
    var catalogCredential: ConferenceAdmissionCredential?
    var credential: ConferenceAdmissionCredential? { catalogCredential ?? invitation.map { .invitation($0, password: nil) } }
    var supportsKeyHandoff = true
    var keyServiceTask: Task<Void, Never>?
    var keyEnvelopes: [String: String] = [:]
    var admission: ConferenceAdmission? = ConferenceAdmission()
    var conference: ConferenceDTO?
    var connectedAt: Date? = Date(), minimized = true, pinnedID: String? = "peer"
    var cameraPosition = AVCaptureDevice.Position.front
    var attempt = UUID(), mediaRevision: UInt64 = 0
    var mediaFailure: Error?, outageStartedAt: Date?, recoveryAttempts = 0
    var stableConnectionTask: Task<Void, Never>?
    var mediaTask: Task<Void, Never>?, recoveryTask: Task<Void, Never>?
    var monitor: Task<Void, Never>?
    var error: String?
    var refreshes = 0, resets = 0, releases = 0, monitorStarts = 0
    let api = ConferencesAPI()
    var isBusy: Bool { phase != .idle }
    func refresh(_ room: Room) { refreshes += 1 }
    func resetSubscriptions() { resets += 1 }
    func showVideoParticipants(_ ids: Set<String>) {}
    func startKeyService(_ ticket: UUID) {}
    func startMonitor(_ ticket: UUID, immediately: Bool = false) { monitorStarts += 1 }
    var approvalMonitorStarts = 0, approvalMonitorStops = 0
    func startJoinApprovalMonitor(_ ticket: UUID) { approvalMonitorStarts += 1 }
    func stopJoinApprovalMonitor() { approvalMonitorStops += 1 }
    func releaseMedia() async { releases += 1; room = nil; provider = nil; phase = .idle }
    var permissionGate: Gate?
    func requestPermissions(microphone: Bool, camera: Bool) async throws { if let permissionGate { await permissionGate.wait() } }
    var microphoneBlockedByOwner = false
    var microphoneConsentRevision: UInt64 = 0
    func applyOwnAudioRestriction(_ room: Room) {}
    __PREPARE__
    __VERIFY__
    __CHECK__
    __ROOM_ERROR__
    __CAN_RECOVER__
    __RECOVER__
    __STABLE__
    __STOP__
    __FOREGROUND__
    __RECONCILE__
    __RUN_MEDIA__
    __CHANGE_MEDIA__
    __TOGGLE_MIC__
    __TOGGLE_CAMERA__
    __STATE_CALLBACK__
    __RECONNECT_START__
    __RECONNECT_COMPLETE__
}
@main struct Checks {
    @MainActor static func main() async {
        var checks = 0
        func check(_ value: @autoclosure () -> Bool, _ message: String) { precondition(value(), message); checks += 1 }
        func connected(_ owner: Owner) throws -> Room {
            let invite = owner.invitation!
            return try owner.prepareRoom(ConferenceJoinDTO(conference: ConferenceDTO(id: invite.roomID), participantIdentity: "identity"), credential: .invitation(invite, password: nil))
        }
        let owner = Owner(), old = try! connected(Owner())
        owner.room = old; owner.provider = old.provider; owner.expectedIdentity = "identity"
        let initialDate = owner.connectedAt, firstKey = old.provider
        owner.api.failFirst = true
        owner.monitor = Task { try? await Task.sleep(nanoseconds: 1_000_000_000) }
        owner.recover(old)
        check(owner.monitor == nil, "fresh admission suspends stale membership GET monitoring")
        check(owner.phase == .reconnecting && owner.room == nil && owner.provider == nil,
              "transport loss retires the old crypto/Room synchronously")
        await owner.recoveryTask?.value
        check(owner.api.calls == 2 && owner.phase == .connected, "transient API outage retries a fresh authenticated admission")
        check(old.connectionState == .disconnected && owner.room !== old && owner.provider !== firstKey,
              "recovery creates a new Room and provider after disconnecting the old transport")
        check(owner.room!.options.reconnectAttempts == 0, "application rooms disable SDK retry and detached republication")
        check(owner.room!.localParticipant.microphone && owner.room!.localParticipant.camera,
              "fresh media restores both explicit user choices")
        check(owner.minimized && owner.pinnedID == "peer" && owner.connectedAt == initialDate,
              "recovery preserves minimized presentation, pin, and conference start time")
        let catalog = Owner(), catalogRoom = try! connected(Owner())
        catalog.room = catalogRoom; catalog.provider = catalogRoom.provider; catalog.expectedIdentity = "identity"
        let catalogRoomID = UUID(), catalogRequestID = UUID()
        catalog.catalogCredential = .catalog(ConferenceCatalogCredential(roomID: catalogRoomID, requestID: catalogRequestID, mediaKey: "memory-key"))
        catalog.invitation = nil; catalog.microphoneEnabled = false; catalog.cameraEnabled = false
        catalog.recover(catalogRoom); await catalog.recoveryTask?.value
        check(catalog.phase == .connected && catalog.api.calls == 1, "catalog membership recovers without fabricating an invitation")
        check(catalog.api.joinedCredentials.first?.invitation == nil && catalog.api.joinedCredentials.first?.roomID == catalogRoomID, "catalog recovery preserves credential type and exact room")
        if case .catalog(let value) = catalog.api.joinedCredentials.first! {
            check(value.requestID == catalogRequestID && value.mediaKey == "memory-key", "catalog recovery reuses its consumed request ID and RAM key")
        } else { preconditionFailure("catalog recovery leaked into invitation admission") }
        let background = Owner(); background.foreground = false
        let bgRoom = try! connected(background); background.recover(bgRoom)
        await background.recoveryTask?.value
        check(background.room!.localParticipant.microphone && !background.room!.localParticipant.camera && background.cameraEnabled,
              "background recovery retains camera intent without starting camera capture")
        background.setForeground(true); await background.mediaTask?.value
        check(background.room!.localParticipant.camera && background.api.calls == 1,
              "foreground restores the selected camera on the same admitted encrypted Room")
        let gate = Gate(), racing = Owner(), racingRoom = try! connected(Owner())
        racing.room = racingRoom; racing.provider = racingRoom.provider; racing.expectedIdentity = "identity"
        racingRoom.localParticipant.cameraGate = gate
        racing.reconcileMedia()
        while !gate.entered { await Task.yield() }
        racing.setForeground(false); gate.open(); await racing.mediaTask?.value
        check(!racingRoom.localParticipant.camera && racingRoom.localParticipant.cameraHistory.suffix(2) == [true, false],
              "background wins when an uncooperative camera-on setter completes late")
        check(racing.cameraEnabled && !racing.mediaChanging, "temporary camera suspension preserves user intent and releases the serializer")
        racing.phase = .reconnecting; racing.toggleCamera(); racing.toggleMicrophone()
        check(!racing.cameraEnabled && !racing.microphoneEnabled, "users can turn media off during an outage")
        let cancelled = Owner(), cancelledRoom = try! connected(Owner()), joinGate = Gate()
        cancelled.room = cancelledRoom; cancelled.provider = cancelledRoom.provider; cancelled.expectedIdentity = "identity"
        cancelled.api.gate = joinGate; cancelled.recover(cancelledRoom)
        while !joinGate.entered { await Task.yield() }
        let pending = cancelled.recoveryTask
        cancelled.phase = .leaving; cancelled.attempt = UUID(); pending?.cancel(); joinGate.open()
        await pending?.value
        check(cancelled.room == nil && cancelled.provider == nil && cancelled.phase == .leaving,
              "a join response arriving after Leave cannot revive capture, crypto, or the connected UI")
        check(cancelled.api.calls == 1, "canceled recovery never requests another grant")
        for type in [LiveKitErrorType.network, .timedOut, .validation, .serverShutdown] {
            check(Owner.canRecover(LiveKitError(type)), "temporary SFU or expired media grant can refresh through app authorization")
        }
        for type in [LiveKitErrorType.participantRemoved, .roomDeleted, .encryptionFailed] {
            check(!Owner.canRecover(LiveKitError(type)), "revocation and crypto failures cannot auto-rejoin")
        }
        for status in [401, 403, 404, 409, 410] {
            check(!Owner.canRecover(APIError.http(status)), "terminal application authorization never retries")
        }
        let invalid = Owner(); var dto = ConferenceJoinDTO(conference: ConferenceDTO(id: invalid.invitation!.roomID), participantIdentity: "identity")
        dto.e2eeRequired = false
        do { _ = try invalid.prepareRoom(dto, credential: .invitation(invalid.invitation!, password: nil)); preconditionFailure("plaintext grant accepted") }
        catch { check(invalid.room == nil && invalid.provider == nil, "plaintext grant fails before creating any transport/provider") }
        let budget = Owner(), exhaustedRoom = try! connected(Owner())
        budget.room = exhaustedRoom; budget.recoveryAttempts = 8; budget.outageStartedAt = Date()
        budget.recover(exhaustedRoom); await budget.recoveryTask?.value
        check(budget.api.calls == 0 && budget.releases == 1, "an unstable successor cannot reset the shared eight-attempt outage budget")
        let expired = Owner(), expiredRoom = try! connected(Owner())
        expired.room = expiredRoom; expired.outageStartedAt = Date().addingTimeInterval(-121)
        expired.recover(expiredRoom); await expired.recoveryTask?.value
        check(expired.api.calls == 0 && expired.releases == 1, "the shared two-minute outage deadline prevents another admission")
        let failedMedia = Owner(), failedMediaRoom = try! connected(Owner())
        failedMedia.room = failedMediaRoom; failedMedia.provider = failedMediaRoom.provider; failedMedia.expectedIdentity = "identity"
        failedMediaRoom.localParticipant.failCamera = true; failedMedia.reconcileMedia(); await failedMedia.mediaTask?.value
        check(failedMedia.mediaFailure != nil && !failedMedia.cameraEnabled,
              "publication failure is surfaced and a normal media control displays actual capture state")
        let hostBlocked = Owner(), hostBlockedRoom = try! connected(Owner())
        hostBlocked.room = hostBlockedRoom; hostBlocked.provider = hostBlockedRoom.provider; hostBlocked.expectedIdentity = "identity"
        hostBlockedRoom.localParticipant.failMicrophoneOnce = true
        hostBlockedRoom.localParticipant.onMicrophone = { hostBlocked.microphoneBlockedByOwner = true; hostBlocked.microphoneEnabled = false }
        hostBlocked.reconcileMedia(); await hostBlocked.mediaTask?.value
        check(hostBlocked.mediaFailure == nil && !hostBlocked.microphoneEnabled && !hostBlockedRoom.localParticipant.microphone
              && hostBlockedRoom.localParticipant.camera,
              "host revocation during initial SDK publication finishes muted without abandoning camera or room")
        let consentCycle = Owner(), consentRoom = try! connected(Owner()), consentGate = Gate()
        consentCycle.room = consentRoom; consentCycle.provider = consentRoom.provider; consentCycle.expectedIdentity = "identity"
        consentRoom.localParticipant.microphoneGate = consentGate
        consentCycle.reconcileMedia()
        while !consentGate.entered { await Task.yield() }
        consentCycle.microphoneConsentRevision &+= 1; consentCycle.microphoneEnabled = false
        consentCycle.microphoneBlockedByOwner = false; consentCycle.mediaRevision &+= 1
        consentGate.open(); await consentCycle.mediaTask?.value
        check(!consentCycle.microphoneEnabled && !consentRoom.localParticipant.microphone && consentCycle.mediaFailure == nil,
              "late SDK enable cannot survive host block+allow even when microphone permission is allowed again")
        let gesture = Owner(), gestureRoom = try! connected(Owner()), gestureGate = Gate()
        gesture.room = gestureRoom; gesture.provider = gestureRoom.provider; gesture.expectedIdentity = "identity"
        gesture.microphoneEnabled = false; gesture.permissionGate = gestureGate
        gesture.toggleMicrophone()
        while !gestureGate.entered { await Task.yield() }
        gesture.microphoneConsentRevision &+= 1
        gesture.microphoneBlockedByOwner = false; gesture.microphoneEnabled = false
        gestureGate.open(); await gesture.mediaTask?.value
        check(!gesture.microphoneEnabled && !gestureRoom.localParticipant.microphone && gesture.mediaFailure == nil && gesture.error == nil,
              "an invalidated old mic gesture stays muted without a misleading network/camera error banner")
        let interrupted = Owner(), interruptedRoom = try! connected(Owner())
        interrupted.room = interruptedRoom; interrupted.provider = interruptedRoom.provider; interrupted.expectedIdentity = "identity"
        interruptedRoom.localParticipant.onCamera = { interruptedRoom.connectionState = .disconnected }
        interrupted.reconcileMedia(); await interrupted.mediaTask?.value
        check((interrupted.mediaFailure as? LiveKitError)?.type == .network && interrupted.cameraEnabled && interrupted.microphoneEnabled,
              "transport loss during final media restoration is retryable and preserves user intent")
        let leaving = Owner(), leavingRoom = try! connected(Owner())
        leaving.room = leavingRoom; leaving.phase = .leaving
        leaving.room(leavingRoom, didUpdateConnectionState: .connected, from: .reconnecting)
        leaving.room(leavingRoom, didStartReconnectWithMode: .full)
        leaving.room(leavingRoom, didCompleteReconnectWithMode: .full)
        for _ in 0..<12 { await Task.yield() }
        check(leaving.phase == .leaving && leaving.refreshes == 0,
              "queued SDK state/reconnect callbacks cannot reopen Leaving or refresh its UI")
        let stale = Owner(), staleRoom = try! connected(Owner())
        stale.room = staleRoom; stale.retired = true
        stale.room(staleRoom, didStartReconnectWithMode: .quick)
        stale.room(staleRoom, didCompleteReconnectWithMode: .quick)
        for _ in 0..<12 { await Task.yield() }
        check(stale.phase == .connected && stale.refreshes == 0,
              "retired account reconnect callbacks are inert")
        let stable = Owner(), stableRoom = try! connected(Owner())
        stable.room = stableRoom; stable.recoveryAttempts = 4; stable.outageStartedAt = Date()
        stable.resetRecoveryAfterStability(stableRoom, ticket: stable.attempt)
        await stable.stableConnectionTask?.value
        check(stable.recoveryAttempts == 0 && stable.outageStartedAt == nil,
              "only an uninterrupted stability interval resets the shared outage budget")
        stable.recoveryAttempts = 4; stable.outageStartedAt = Date()
        stable.resetRecoveryAfterStability(stableRoom, ticket: stable.attempt)
        stable.phase = .leaving; await stable.stableConnectionTask?.value
        check(stable.recoveryAttempts == 4 && stable.outageStartedAt != nil,
              "a stale stability timer cannot reset a leaving or failed generation")
        print("PASS: \(checks) actual-method conference recovery contracts")
    }
}
'''
markers = {
    "__PREPARE__": "private func prepareRoom(", "__VERIFY__": "private func verifyEncryption(",
    "__ROOM_ERROR__": "private static func roomError(",
    "__CHECK__": "private func check(", "__CAN_RECOVER__": "private static func canRecover(",
    "__RECOVER__": "private func recover(", "__STOP__": "private func stopRoom(",
    "__STABLE__": "private func resetRecoveryAfterStability(",
    "__FOREGROUND__": "func setForeground(", "__RECONCILE__": "private func reconcileMedia(",
    "__RUN_MEDIA__": "private func runMedia(", "__CHANGE_MEDIA__": "private func changeMedia(",
    "__TOGGLE_MIC__": "func toggleMicrophone(", "__TOGGLE_CAMERA__": "func toggleCamera(",
    "__STATE_CALLBACK__": "nonisolated func room(_ room: Room, didUpdateConnectionState",
    "__RECONNECT_START__": "nonisolated func room(_ room: Room, didStartReconnectWithMode",
    "__RECONNECT_COMPLETE__": "nonisolated func room(_ room: Room, didCompleteReconnectWithMode",
}
for placeholder, marker in markers.items():
    body = method(marker)
    if placeholder == "__STABLE__":
        # Clock seam only: execute the production guards after 100 ms instead
        # of ten seconds. Recovery backoff itself keeps real timing.
        assert body.count("10_000_000_000") == 1
        body = body.replace("10_000_000_000", "100_000_000")
    harness = harness.replace(placeholder, body)
models = (ROOT / "ios/UNYRI/Conferences/ConferenceDirectoryModels.swift").read_text(encoding="utf-8")
start = models.index("enum ConferenceAdmissionCredential:"); end = models.index("\nstruct ConferenceKeyPayload", start)
harness = harness.replace("__CREDENTIAL__", models[start:end])
with tempfile.TemporaryDirectory(prefix="coco-conference-recovery-") as temporary:
    folder = Path(temporary)
    swift = folder / "main.swift"
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    swift.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(binary)], check=True, cwd=ROOT)
    subprocess.run([str(binary)], check=True, timeout=30, cwd=ROOT)
