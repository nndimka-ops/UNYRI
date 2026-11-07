"""Execute the actual production teardown methods with suspended SDK stops.

This is lifecycle evidence, independent of physical capture or LiveKit transport.
Run with Python and swiftc on Windows or macOS; no Apple SDK is required.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")
api_source = (ROOT / "ios/UNYRI/Conferences/ConferencesAPI.swift").read_text(encoding="utf-8")


def method(marker, contents=source):
    start = contents.index(marker)
    brace = contents.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (contents[end] == "{") - (contents[end] == "}")
        end += 1
    return contents[start:end].replace("private func", "func", 1)


harness = r'''
import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
@MainActor enum ConferenceMediaOwnership { static var owner: UUID? }
struct ConferenceDTO { let id: UUID }
struct ConferenceJoinDTO {}
enum APIError: Error { case http(Int), server(code: String, message: String), missingSession, network }
@MainActor __ADMISSION__
@MainActor final class ConferencesAPI {
    var isCurrentSession = true
    var finished: [(UUID, Bool)] = []
    var gate: Gate?
    func finish(_ id: UUID, end: Bool) async throws {
        finished.append((id, end)); if let gate { await gate.wait() }
    }
    __TERMINAL_ERROR__
}
enum AVMediaType: Hashable { case audio, video }
enum CaptureStatus { case authorized, notDetermined, denied, restricted }
@MainActor enum AVCaptureDevice {
    static var status: [AVMediaType: CaptureStatus] = [.audio: .authorized, .video: .authorized]
    static var queried: [AVMediaType] = []
    static func authorizationStatus(for kind: AVMediaType) -> CaptureStatus {
        queried.append(kind); return status[kind]!
    }
    static func requestAccess(for kind: AVMediaType) async -> Bool { status[kind] == .authorized }
}
enum CallMediaPermissions { enum PermissionError: Error { case microphoneDenied, cameraDenied } }
@MainActor final class Gate {
    var continuation: CheckedContinuation<Void, Never>?
    var entered = false
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class Track {
    let gate: Gate?
    var stops = 0
    init(_ gate: Gate? = nil) { self.gate = gate }
    func stop() async throws { stops += 1; if let gate { await gate.wait() } }
}
@MainActor struct Publication { let track: Track? }
@MainActor final class Participant {
    var videoTracks: [Publication] = []
    var audioTracks: [Publication] = []
}
@MainActor final class Room {
    let localParticipant = Participant()
    var disconnected = false
    func disconnect() async { disconnected = true }
}
@MainActor final class Handoff { var cancelled = 0; func cancel() { cancelled += 1 } }
@MainActor final class Owner {
    enum Phase { case connected, idle, leaving }
    let mediaLease = UUID()
    var room: Room?
    var monitor: Task<Void, Never>?
    var operation: Task<Void, Never>?
    var connectionTask: Task<Void, Never>?
    var recoveryTask: Task<Void, Never>?
    var mediaTask: Task<Void, Never>?
    var stableConnectionTask: Task<Void, Never>?
    var outageStartedAt: Date?, recoveryAttempts = 0, mediaFailure: Error?
    var teardown: Task<Void, Never>?
    var admission: ConferenceAdmission?
    var cleaningAdmission: ConferenceAdmission?
    var admissionCleanup: Task<Void, Never>?
    let api = ConferencesAPI()
    let keyHandoff = Handoff()
    var keyServiceTask: Task<Void, Never>?
    var joinApprovalTask: Task<Void, Never>?
    var joinDecisionTask: Task<Void, Never>?
    var approvalWaitTask: Task<Void, Error>?
    var joinApprovalRevision: UInt64 = 0
    var pendingJoinRequests = ["pending"]
    var decidingJoinRequestID: UUID?
    var approvalWaiting = true
    var keyEnvelopes = ["request": "ciphertext"]
    var credential: String? = "memory-only-credential"
    // Persistence is a separate production-vault contract. Here only its
    // release/removal boundary is observed during real teardown ordering.
    var ownedAccess: String? = "loaded-account-key-cache"
    var forgottenOwnedRooms = [UUID]()
    func forgetOwnedRoom(_ id: UUID) { forgottenOwnedRooms.append(id) }
    var invitation: String? = "memory-only-invitation"
    var provider: String? = "memory-only-key-provider"
    var expectedIdentity: String? = "identity"
    var offeredInvite: String? = "preview"
    var conference: String? = "room"
    var tiles = [1, 2]
    var subscriptions: Set<String> = ["one"]
    var visibleVideoIDs = Set<String>()
    var subscriptionTask: Task<Void, Never>?
    func resetSubscriptions() { subscriptionTask?.cancel(); subscriptionTask = nil; subscriptions.removeAll() }
    var pinnedID: String? = "one"
    var remotePageAnchorID: String? = "peer"
    var selectedVisibleCount = 5, fitEveryoneOnScreen = true, hostControlsAvailable = true, changingRoomSettings = false
    var locallyMutedParticipantIDs = Set(["peer"]), locallyHiddenParticipantIDs = Set(["peer"])
    var participantModerationAvailable = true, microphoneBlockedByOwner = true
    var moderatingParticipantID: String? = "peer"
    var videoPresentationOwner: UUID? = UUID()
    var desiredPresentationIDs = Set(["peer"])
    var presentationActive = false
    var previewViewport: CGSize? = CGSize(width: 300, height: 600)
    var ownPreviewPosition = CGPoint(x: 0.1, y: 0.7)
    var microphoneEnabled = true
    var cameraEnabled = true
    var mediaChanging = true
    var connectedAt: Date? = Date()
    var minimized = true
    var error: String?
    var phase = Phase.connected
    var attempt = UUID()
    var retired = false
    var showLobby = true
    var releases = 0
    lazy var onMediaOwnershipChanged: (Bool) -> Void = { [weak self] active in
        if !active { self?.releases += 1 }
    }
    __RELEASE__
    __STOP_APPROVAL__
    __SHUTDOWN__
    __PERMISSIONS__
    __CAPTURE_AUTHORIZED__
}
@main struct Checks {
    @MainActor static func main() async {
        var checks = 0
        func check(_ value: @autoclosure () -> Bool, _ message: String) {
            precondition(value(), message); checks += 1
        }
        let gate = Gate(), camera = Track(gate), microphone = Track(), room = Room()
        room.localParticipant.videoTracks = [Publication(track: camera)]
        room.localParticipant.audioTracks = [Publication(track: microphone)]
        let owner = Owner(); owner.room = room
        ConferenceMediaOwnership.owner = owner.mediaLease
        let first = Task { await owner.releaseMedia() }
        while !gate.entered { await Task.yield() }
        check(owner.room == nil, "late SDK callbacks cannot find an active room")
        check(owner.teardown != nil, "SDK suspension retains a shared teardown task")
        check(ConferenceMediaOwnership.owner == owner.mediaLease, "camera stop owns global lease until completion")
        let second = Task { await owner.releaseMedia() }
        await Task.yield()
        check(owner.releases == 0, "duplicate release cannot clear ownership while capture is stopping")
        owner.shutdown()
        check(owner.invitation == nil && owner.provider == nil && owner.offeredInvite == nil,
              "logout drops application references to invitation, key provider and preview synchronously")
        check(owner.keyHandoff.cancelled > 0 && owner.keyServiceTask == nil && owner.keyEnvelopes.isEmpty && owner.credential == nil, "logout stops key service/request and clears RAM credentials")
        check(owner.retired && !owner.showLobby, "logout prevents new presentation and admission")
        check(owner.ownedAccess == nil, "logout discards the loaded re-entry cache before async media stops")
        await Task.yield()
        check(owner.releases == 0 && ConferenceMediaOwnership.owner == owner.mediaLease,
              "logout joins the same suspended stop without releasing ownership early")
        gate.open(); await first.value; await second.value
        for _ in 0..<5 { await Task.yield() }
        check(camera.stops == 1 && microphone.stops == 1, "each local SDK track stops once")
        check(room.disconnected, "SDK disconnect completes before releasing ownership")
        check(owner.releases == 1 && ConferenceMediaOwnership.owner == nil, "last teardown releases lease exactly once")
        check(!owner.cameraEnabled && !owner.microphoneEnabled && owner.phase == .idle,
              "local media flags terminate together")
        check(owner.tiles.isEmpty && owner.subscriptions.isEmpty && owner.connectedAt == nil,
              "participant and time references are cleared")
        check(owner.locallyMutedParticipantIDs.isEmpty && owner.locallyHiddenParticipantIDs.isEmpty,
              "leave/logout never leaks another room's listener preferences")
        check(!owner.participantModerationAvailable && owner.moderatingParticipantID == nil && !owner.microphoneBlockedByOwner,
              "teardown clears owner actions and revoked microphone state")
        let successor = UUID(); ConferenceMediaOwnership.owner = successor
        await owner.releaseMedia()
        check(ConferenceMediaOwnership.owner == successor, "late previous-account release cannot clear successor ownership")

        let joinGate = Gate(), joinOwner = Owner(), joinRoom = Room(), joinID = UUID()
        let joinAdmission = ConferenceAdmission(); joinAdmission.roomID = joinID
        let joinRequest = Task<ConferenceJoinDTO, Error> { @MainActor in await joinGate.wait(); return ConferenceJoinDTO() }
        joinAdmission.joinRequest = joinRequest; joinOwner.admission = joinAdmission; joinOwner.room = joinRoom
        joinOwner.operation = Task { _ = try? await joinRequest.value }
        ConferenceMediaOwnership.owner = joinOwner.mediaLease
        await joinOwner.releaseMedia()
        while !joinGate.entered { await Task.yield() }
        check(joinRoom.disconnected && joinOwner.provider == nil, "cancel joining stops SDK media and key references before admission settles")
        check(joinOwner.phase == .leaving && ConferenceMediaOwnership.owner == joinOwner.mediaLease,
              "pending admission cleanup reserves private and successor conference media")
        joinGate.open(); await joinOwner.admissionCleanup?.value
        check(joinOwner.api.finished.count == 1 && joinOwner.api.finished[0].0 == joinID && !joinOwner.api.finished[0].1,
              "cancelling UI work does not cancel admission outcome; leave follows join completion")
        check(ConferenceMediaOwnership.owner == nil && joinOwner.phase == .idle, "cleanup acknowledgement releases the media lease")

        let createGate = Gate(), createOwner = Owner(), createdID = UUID(), createAdmission = ConferenceAdmission()
        let createRequest = Task<ConferenceDTO, Error> { @MainActor in
            await createGate.wait(); createAdmission.createdID = createdID; return ConferenceDTO(id: createdID)
        }
        createAdmission.createRequest = createRequest; createOwner.admission = createAdmission
        ConferenceMediaOwnership.owner = createOwner.mediaLease
        await createOwner.releaseMedia()
        while !createGate.entered { await Task.yield() }
        check(ConferenceMediaOwnership.owner == createOwner.mediaLease, "cancelled creator retains ownership while create commits")
        createGate.open(); await createOwner.admissionCleanup?.value
        check(createOwner.api.finished.count == 1 && createOwner.api.finished[0].0 == createdID && createOwner.api.finished[0].1,
              "cancelled unconnected creator ends its captured room instead of orphaning the room quota")

        let finishGate = Gate(), finishOwner = Owner(), finishAdmission = ConferenceAdmission()
        finishAdmission.roomID = UUID(); finishAdmission.connected = true
        finishOwner.admission = finishAdmission; finishOwner.api.gate = finishGate
        ConferenceMediaOwnership.owner = finishOwner.mediaLease
        await finishOwner.releaseMedia()
        while !finishGate.entered { await Task.yield() }
        finishOwner.shutdown()
        for _ in 0..<5 { await Task.yield() }
        check(finishOwner.room == nil && finishOwner.provider == nil, "logout does not wait for HTTP to stop local media")
        check(ConferenceMediaOwnership.owner == finishOwner.mediaLease,
              "already sent old-admission leave settles before a successor can join the same room")
        finishGate.open(); await finishOwner.admissionCleanup?.value
        check(ConferenceMediaOwnership.owner == nil && finishOwner.api.finished.count == 1,
              "logout does not duplicate the in-flight admission cleanup")

        let staleGate = Gate(), staleOwner = Owner(), staleAdmission = ConferenceAdmission()
        let staleRequest = Task<ConferenceJoinDTO, Error> { @MainActor in await staleGate.wait(); return ConferenceJoinDTO() }
        staleAdmission.roomID = UUID(); staleAdmission.joinRequest = staleRequest; staleOwner.admission = staleAdmission
        ConferenceMediaOwnership.owner = staleOwner.mediaLease
        await staleOwner.releaseMedia()
        while !staleGate.entered { await Task.yield() }
        staleOwner.api.isCurrentSession = false; staleOwner.shutdown()
        for _ in 0..<5 { await Task.yield() }
        check(ConferenceMediaOwnership.owner == nil, "logout local release does not await a pending admission network response")
        staleGate.open()
        for _ in 0..<10 { await Task.yield() }
        check(staleOwner.api.finished.isEmpty, "stale account never sends cleanup with successor credentials")

        let permissions = Owner()
        AVCaptureDevice.status = [.audio: .denied, .video: .authorized]; AVCaptureDevice.queried = []
        try! await permissions.requestPermissions(microphone: false, camera: false)
        check(AVCaptureDevice.queried.isEmpty, "listen-only never queries or requests capture permissions")
        try! await permissions.requestPermissions(microphone: false, camera: true)
        check(AVCaptureDevice.queried == [.video], "camera-only works when microphone permission is denied")
        do { try await permissions.requestPermissions(microphone: true, camera: false); preconditionFailure("microphone denied") }
        catch CallMediaPermissions.PermissionError.microphoneDenied { checks += 1 }
        catch { preconditionFailure("unexpected permission error") }
        for code in ["session_revoked", "unauthorized", "installation_restricted", "not_found", "gone"] {
            check(ConferencesAPI.endsConference(APIError.server(code: code, message: "private detail")), "standard server JSON terminal errors end local media")
        }
        check(!ConferencesAPI.endsConference(APIError.network), "a transient transport error does not invent room termination")
        let lateGate = Gate(), lateOwner = Owner(), lateRoom = Room(), lateTrack = Track()
        lateOwner.room = lateRoom
        ConferenceMediaOwnership.owner = lateOwner.mediaLease
        lateOwner.mediaTask = Task { @MainActor in
            await lateGate.wait()
            // Model an SDK setter that completes despite cooperative cancellation.
            lateRoom.localParticipant.videoTracks.append(Publication(track: lateTrack))
        }
        while !lateGate.entered { await Task.yield() }
        let lateRelease = Task { await lateOwner.releaseMedia() }
        for _ in 0..<8 { await Task.yield() }
        check(ConferenceMediaOwnership.owner == lateOwner.mediaLease && !lateRoom.disconnected,
              "leave holds the exclusive capture lease while an SDK setter is suspended")
        lateGate.open(); await lateRelease.value
        check(lateTrack.stops == 1 && lateRoom.disconnected,
              "late camera publication is stopped after the canceled setter settles")
        check(ConferenceMediaOwnership.owner == nil && lateOwner.expectedIdentity == nil,
              "crypto identity and media ownership clear only after the final SDK stop")
        check(lateOwner.remotePageAnchorID == nil && lateOwner.videoPresentationOwner == nil && lateOwner.desiredPresentationIDs.isEmpty && lateOwner.previewViewport == nil,
              "final teardown clears only this conference presentation state")
        let ending = Owner(), endAdmission = ConferenceAdmission(), endingID = UUID()
        ending.admission = endAdmission; endAdmission.roomID = endingID
        endAdmission.connected = true; endAdmission.endForEveryone = true
        await ending.releaseMedia(); await ending.admissionCleanup?.value
        check(ending.api.finished.count == 1 && ending.api.finished[0].1,
              "owner end uses the acknowledged end endpoint after local capture has stopped")
        let waitingOwner = Owner(), waitingAdmission = ConferenceAdmission()
        waitingOwner.admission = waitingAdmission; waitingAdmission.roomID = UUID()
        let approval = Task<Void, Error> { try await Task.sleep(nanoseconds: 130_000_000_000) }
        waitingOwner.approvalWaitTask = approval
        waitingAdmission.joinRequest = Task { try await approval.value; return ConferenceJoinDTO() }
        await waitingOwner.releaseMedia(); await waitingOwner.admissionCleanup?.value
        check(approval.isCancelled && !waitingOwner.approvalWaiting && waitingOwner.pendingJoinRequests.isEmpty,
              "leaving cancels creator wait and clears request UI immediately")
        check(waitingOwner.phase == .idle && waitingOwner.api.finished.count == 1,
              "creator wait does not strand the teardown or media lease for 130 seconds")
        print("PASS: \(checks) actual-method conference teardown contracts")
    }
}
'''
harness = harness.replace("__RELEASE__", method("private func releaseMedia() async"))
harness = harness.replace("__STOP_APPROVAL__", method("private func stopJoinApprovalMonitor()"))
harness = harness.replace("__SHUTDOWN__", method("func shutdown()"))
harness = harness.replace("__ADMISSION__", method("private final class ConferenceAdmission").replace("private final class", "final class", 1))
harness = harness.replace("__TERMINAL_ERROR__", method("static func endsConference", api_source))
harness = harness.replace("__PERMISSIONS__", method("private func requestPermissions"))
harness = harness.replace("__CAPTURE_AUTHORIZED__", method("private func captureAuthorized"))
with tempfile.TemporaryDirectory(prefix="coco-conference-lifecycle-") as temporary:
    folder = Path(temporary)
    swift = folder / "main.swift"
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    swift.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(binary)], check=True, cwd=ROOT)
    subprocess.run([str(binary)], check=True, timeout=30, cwd=ROOT)
