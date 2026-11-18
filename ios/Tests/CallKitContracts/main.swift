import Foundation

// Pure CallKit decision-layer checks: end-action mapping, backend end-reason
// -> system reason mapping, mute extraction, report idempotency, and call-id
// stability across reconnect/renegotiation (no duplicate CallKit call). No
// CallKit import — macOS CI only.

var failures = 0

func fail(_ description: String) {
    failures += 1
    FileHandle.standardError.write(Data("FAIL: \(description)\n".utf8))
}

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    if condition() { print("PASS: \(description)") } else { fail(description) }
}

func expect<T: Equatable>(_ actual: T, _ expected: T, _ description: String) {
    if actual == expected { print("PASS: \(description)") }
    else { fail("\(description) (expected \(expected), got \(actual))") }
}

let callId = "11111111-1111-1111-1111-111111111111"
let calleeId = "33333333-3333-3333-3333-333333333333"

func info(_ media: CallMediaKind = .audio) -> CallInfo {
    CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: media, direction: .outgoing)
}

// MARK: - End-action mapping (system End -> existing controller op)
for state: CallUiState in [.incomingRinging(info()), .outgoingRinging(info()), .connecting(info()), .connected(ConnectedState(call: info())), .reconnecting(info())] {
    check(CallKitPolicy.keepsSignalingInBackground(state), "active call keeps remote hangup signaling in background")
}
check(!CallKitPolicy.keepsSignalingInBackground(.idle), "idle does not keep background signaling")
check(!CallKitPolicy.keepsSignalingInBackground(.ended(info(), reason: .hangup)), "remote hangup releases background signaling")

expect(CallKitPolicy.endAction(for: .incomingRinging(info())), .reject, "end action: incoming ringing -> reject")
expect(CallKitPolicy.endAction(for: .outgoingRinging(info())), .cancel, "end action: outgoing ringing -> cancel")
expect(CallKitPolicy.endAction(for: .connecting(info())), .hangup, "end action: connecting -> hangup")
expect(CallKitPolicy.endAction(for: .connected(ConnectedState(call: info()))), .hangup, "end action: connected -> hangup")
expect(CallKitPolicy.endAction(for: .reconnecting(info())), .hangup, "end action: reconnecting -> hangup")
expect(CallKitPolicy.endAction(for: .idle), .none, "end action: idle -> none")
expect(CallKitPolicy.endAction(for: .ended(info(), reason: .hangup)), .none, "end action: ended -> none")

// MARK: - Backend end reason -> system reason mapping

expect(CallKitPolicy.endReasonKind(for: .hangup), .remoteEnded, "hangup -> remoteEnded")
expect(CallKitPolicy.endReasonKind(for: .cancelled), .remoteEnded, "cancelled -> remoteEnded")
expect(CallKitPolicy.endReasonKind(for: .remoteEnded), .remoteEnded, "remoteEnded -> remoteEnded")
expect(CallKitPolicy.endReasonKind(for: .rejected), .declinedElsewhere, "rejected -> declinedElsewhere")
expect(CallKitPolicy.endReasonKind(for: .answeredElsewhere), .answeredElsewhere, "answeredElsewhere -> answeredElsewhere")
expect(CallKitPolicy.endReasonKind(for: .failed), .failed, "failed -> failed")
expect(CallKitPolicy.endReasonKind(for: .missed), .unanswered, "missed -> unanswered")
expect(CallKitPolicy.endReasonKind(for: .timeout), .unanswered, "timeout -> unanswered")

// MARK: - Mute extraction

expect(CallKitPolicy.mute(for: .connected(ConnectedState(call: info(), isMuted: true))), true, "mute: connected reads isMuted")
expect(CallKitPolicy.mute(for: .connected(ConnectedState(call: info()))), false, "mute: connected default false")
expect(CallKitPolicy.mute(for: .connecting(info())), nil, "mute: non-connected -> nil")

// MARK: - Report idempotency tracker

var tracker = CallKitReportTracker()
var outgoingGate = CallKitOutgoingStartGate()
let attemptOne = UUID()
let attemptTwo = UUID()
check(outgoingGate.begin(attemptOne), "first outgoing attempt is admitted")
check(!outgoingGate.begin(attemptTwo), "second tap is blocked while server create still has idle UI")
outgoingGate.finish(attemptTwo)
check(!outgoingGate.begin(attemptTwo), "late foreign completion cannot release active attempt")
outgoingGate.finish(attemptOne)
check(outgoingGate.begin(attemptTwo), "next call is admitted after first attempt completes")

tracker.beginIncomingReport(callId)
check(tracker.deferEndWhileIncomingPending(callId, reason: .cancelled), "fast remote cancel waits for incoming CallKit registration")
check(tracker.isTerminal(callId), "pending-registration terminal blocks duplicate incoming registration")
expect(tracker.finishIncomingReport(callId), .cancelled, "registration completion receives deferred terminal")
check(!tracker.deferEndWhileIncomingPending(callId, reason: .cancelled), "registered call can now end immediately")
expect(tracker.finishIncomingReport(callId), nil, "deferred terminal is consumed once")
check(tracker.shouldReportEnded(callId), "first terminal is reportable")
check(!tracker.shouldReportEnded(callId), "second terminal is NOT reported (idempotent)")

tracker = CallKitReportTracker()
tracker.markLocallyEnded(callId)
check(tracker.isTerminal(callId.uppercased()), "locally ended call blocks UUID-case-variant ringing duplicates")
check(!tracker.shouldReportEnded(callId), "locally-ended call is not reported again")

tracker = CallKitReportTracker()
check(tracker.shouldReportEnded("a"), "id a reportable")
check(tracker.shouldReportEnded("b"), "id b reportable independently")

tracker = CallKitReportTracker()
_ = tracker.shouldReportEnded(callId)
tracker.reset()
check(tracker.shouldReportEnded(callId), "reset clears reported state")

// MARK: - Call id stability (same CallKit UUID across renegotiation/reconnect)

var state = CallReducerState()
func reduce(_ event: CallEvent) -> (CallReducerState, [CallEffect]) {
    let result = CallStateReducer.reduce(state, event)
    state = result.0
    return result
}

// Audio -> video upgrade keeps the same call id (no new CallKit call).
state = CallReducerState(ui: .connected(ConnectedState(call: info(), videoUpgrade: .requesting)), activeCallId: callId)
let upgrade = reduce(.remoteVideoUpgradeAccept(callId: callId))
if case .connected(let c) = upgrade.0.ui { check(c.call.callId == callId, "video upgrade keeps call id") }
check(upgrade.0.activeCallId == callId, "video upgrade keeps activeCallId")

// WS reconnect keeps the same call id.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
let ws = reduce(.socketReconnected)
expect(ws.0.activeCallId, callId, "WS reconnect keeps activeCallId")

// ICE disconnect/recover keeps the same call id.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
_ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
let recovered = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = recovered.0.ui { check(c.call.callId == callId, "ICE recover keeps call id") }

check(CallKitPolicy.matchesActiveCall(backendId: callId, state: .incomingRinging(info())), "current system action matches")
check(!CallKitPolicy.matchesActiveCall(backendId: "44444444-4444-4444-4444-444444444444", state: .connected(ConnectedState(call: info()))), "late end/mute from a previous call must not control current call")
check(!CallKitPolicy.matchesActiveCall(backendId: nil, state: .incomingRinging(info())), "unmapped action is rejected")
check(!CallKitPolicy.matchesActiveCall(backendId: callId, state: .idle), "idle has no action target")
check(!CallKitPolicy.matchesActiveCall(backendId: "invalid", state: .incomingRinging(info())), "invalid call ID is rejected")

var progress = CallKitReportTracker()
check(progress.shouldReportConnecting(callId), "first outgoing connection start reports")
check(!progress.shouldReportConnecting(callId), "duplicate connection start is suppressed")
check(progress.shouldReportConnected(callId), "first outgoing media connection reports")
check(!progress.shouldReportConnected(callId), "ICE recovery does not reset system connection time")
progress.reset()
check(progress.shouldReportConnected(callId), "provider reset clears connection reports")
expect(CallKitPolicy.hasVideo(for: .idle), nil, "idle has no video update")
expect(CallKitPolicy.hasVideo(for: .incomingRinging(info(.video))), true, "incoming video advertised")
var videoConnection = ConnectedState(call: info(.audio))
videoConnection.videoMode = .video
expect(CallKitPolicy.hasVideo(for: .connected(videoConnection)), true, "negotiated video overrides original audio kind")
videoConnection.videoMode = .audio
expect(CallKitPolicy.hasVideo(for: .connected(videoConnection)), false, "downgrade clears video advertisement")

// MARK: - Real incoming-answer permission preflight
// Execute the production async policy with controlled OS permission results.
// A camera-only denial must not make the in-app Answer path stricter than
// the controller's receive-video-with-camera-off path.
enum PermissionFixtureError: Error, Equatable {
    case microphoneDenied, cameraDenied, other
}
func incomingPreflight(_ kind: CallMediaKind, failAt: CallMediaKind? = nil,
                       failure: Error = PermissionFixtureError.other) async -> ([CallMediaKind], Error?) {
    var requests: [CallMediaKind] = []
    do {
        try await CallKitPolicy.prepareIncomingAnswer(for: kind, request: { media in
            requests.append(media)
            if media == failAt { throw failure }
        }, isCameraDenied: { ($0 as? PermissionFixtureError) == .cameraDenied })
        return (requests, nil)
    } catch { return (requests, error) }
}
let audioAnswer = await incomingPreflight(.audio)
expect(audioAnswer.0, [.audio], "audio answer requests microphone only")
check(audioAnswer.1 == nil, "audio answer succeeds without a camera request")
let videoAnswer = await incomingPreflight(.video)
expect(videoAnswer.0, [.audio, .video], "video answer requires microphone before optional camera")
check(videoAnswer.1 == nil, "granted video answer continues")
let cameraOffAnswer = await incomingPreflight(.video, failAt: .video, failure: PermissionFixtureError.cameraDenied)
expect(cameraOffAnswer.0, [.audio, .video], "camera-off answer still checks microphone first")
check(cameraOffAnswer.1 == nil, "denied camera permits incoming video with local camera off")
for media: CallMediaKind in [.audio, .video] {
    let denied = await incomingPreflight(media, failAt: .audio, failure: PermissionFixtureError.microphoneDenied)
    expect(denied.0, [.audio], "microphone denial stops before camera for \(media)")
    expect(denied.1 as? PermissionFixtureError, .microphoneDenied, "microphone denial is not swallowed for \(media)")
}
let revokedMicrophone = await incomingPreflight(.video, failAt: .video, failure: PermissionFixtureError.microphoneDenied)
expect(revokedMicrophone.1 as? PermissionFixtureError, .microphoneDenied, "a microphone revoked during video preflight still blocks Answer")
let otherError = await incomingPreflight(.video, failAt: .video)
expect(otherError.1 as? PermissionFixtureError, .other, "an unrelated permission error is not treated as camera denial")
for point: CallMediaKind in [.audio, .video] {
    let cancelled = await incomingPreflight(.video, failAt: point, failure: CancellationError())
    check(cancelled.1 is CancellationError, "cancellation at \(point) propagates")
    expect(cancelled.0, point == .audio ? [.audio] : [.audio, .video], "cancelled preflight makes no later request")
}
let cancelledBeforeStart = Task {
    await incomingPreflight(.video)
}
cancelledBeforeStart.cancel()
let alreadyCancelled = await cancelledBeforeStart.value
check(alreadyCancelled.0.isEmpty && alreadyCancelled.1 is CancellationError,
      "an already cancelled Answer never starts an OS permission request")

do {
    var lifecycle = CallKitReportTracker()
    for n in 0..<1_000 {
        let id = "finished-\(n)"
        _ = lifecycle.shouldReportConnecting(id); _ = lifecycle.shouldReportConnected(id)
        if n % 2 == 0 { lifecycle.markLocallyEnded(id) } else { _ = lifecycle.shouldReportEnded(id) }
        lifecycle.completeCall(id)
    }
    check(lifecycle.retainedTerminalCount == CallKitReportTracker.terminalHistoryLimit, "terminal history remains bounded after 1000 calls")
    check(lifecycle.locallyEnded.count + lifecycle.reportedEnded.count <= CallKitReportTracker.terminalHistoryLimit,
          "local and remote tombstones share one bounded budget")
    check(lifecycle.activeTrackingCount == 0, "completed calls release all progress entries")
    check(!lifecycle.shouldReportEnded("finished-999") && !lifecycle.shouldReportEnded("finished-998"), "recent local/remote ended echoes stay idempotent")
    check(!lifecycle.shouldReportConnecting("finished-999") && !lifecycle.shouldReportConnected("finished-998"), "late progress cannot resurrect a completed call")
    let active = "pending-incoming"
    lifecycle.beginIncomingReport(active)
    check(lifecycle.deferEndWhileIncomingPending(active, reason: .answeredElsewhere), "terminal still waits for pending incoming registration")
    expect(lifecycle.finishIncomingReport(active), .answeredElsewhere, "bounded cleanup preserves deferred terminal reason")
    lifecycle.completeCall(active)
    check(lifecycle.activeTrackingCount == 0 && !lifecycle.shouldReportEnded(active), "completed deferred call releases state and retains tombstone")
    lifecycle.reset()
    check(lifecycle.activeTrackingCount == 0 && lifecycle.retainedTerminalCount == 0, "new account/provider clears bounded lifecycle state")
}

if failures > 0 {
    print("\(failures) CallKit contract check(s) failed")
    exit(1)
}
print("CallKit contract checks passed")
