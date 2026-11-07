import Foundation

// Pure reconnect/reconcile contract checks: the authoritative reconciliation
// matrix, reconnecting-state preservation, stale/duplicate event guards, and
// keepalive start/stop policy. No network, no WebRTC — macOS CI only.

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
let callerId = "22222222-2222-2222-2222-222222222222"
let calleeId = "33333333-3333-3333-3333-333333333333"

func info(_ media: CallMediaKind = .audio) -> CallInfo {
    CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: media, direction: .outgoing)
}

func dto(_ state: String, _ endReason: String? = nil) -> CallDTO {
    CallDTO(id: UUID(uuidString: callId)!, callerUserId: UUID(uuidString: callerId)!,
            calleeUserId: UUID(uuidString: calleeId)!, mediaKind: .audio, state: state,
            endReason: endReason, acceptedDeviceId: nil, createdAt: Date(),
            acceptedAt: nil, connectedAt: nil, endedAt: nil)
}

// MARK: - Pure reconcile matrix (table-driven)

let matrix: [(CallUiState, CallDTO, CallReconciliation.Decision, String)] = [
    (.outgoingRinging(info()), dto("ringing"), .keepCurrent, "outgoing ringing + server ringing -> keep"),
    (.outgoingRinging(info()), dto("ended", "cancelled"), .endLocally(reason: "cancelled"), "outgoing ringing + server ended -> end cancelled"),
    (.incomingRinging(info()), dto("accepted"), .keepCurrent, "incoming ringing + server accepted -> keep (Android parity)"),
    (.connecting(info()), dto("connecting"), .keepCurrent, "connecting + server connecting -> keep"),
    (.connecting(info()), dto("connected"), .keepCurrent, "connecting + server connected -> keep"),
    (.connecting(info()), dto("ended", "failed"), .endLocally(reason: "failed"), "connecting + server failed -> end failed"),
    (.connected(ConnectedState(call: info())), dto("connected"), .keepCurrent, "connected + server connected -> keep"),
    (.connected(ConnectedState(call: info())), dto("ended", "hangup"), .endLocally(reason: "hangup"), "connected + server ended -> end hangup"),
    (.reconnecting(info()), dto("connected"), .restoreConnected, "reconnecting + server connected -> restore"),
    (.reconnecting(info()), dto("ended", "timeout"), .endLocally(reason: "timeout"), "reconnecting + server ended -> end timeout"),
]
for (local, server, expected, label) in matrix {
    expect(CallReconciliation.resolve(local: local, server: server), expected, label)
}

// MARK: - Reducer-level reconnect behavior

var state = CallReducerState()
func reduce(_ event: CallEvent) -> (CallReducerState, [CallEffect]) {
    let result = CallStateReducer.reduce(state, event)
    state = result.0
    return result
}

// Reconnect preserves mute/speaker/video mode (Android N.1A §21).
state = CallReducerState(ui: .connected(ConnectedState(call: info(.video), isMuted: true, isSpeakerOn: true, videoMode: .video)), activeCallId: callId)
let rec = reduce(.socketReconnected)
expect(rec.0.ui, .reconnecting(info(.video)), "socket reconnect -> reconnecting")
let restored = reduce(.reconnectReconcileSucceeded(dto("connected")))
if case .connected(let c) = restored.0.ui {
    check(c.isMuted, "reconnect preserves mute")
    check(c.isSpeakerOn, "reconnect preserves speaker")
    check(c.videoMode == .video, "reconnect preserves video mode")
} else { fail("reconcile restored connected") }
expect(restored.1, [], "reconcile restore has no effects (no duplicate keepalive)")

// Wrong call id reconcile row ignored.
state = CallReducerState(ui: .reconnecting(info()), activeCallId: callId)
let wrongDto = CallDTO(id: UUID(), callerUserId: UUID(uuidString: callerId)!,
                       calleeUserId: UUID(uuidString: calleeId)!, mediaKind: .audio, state: "ended",
                       endReason: "hangup", acceptedDeviceId: nil, createdAt: Date(),
                       acceptedAt: nil, connectedAt: nil, endedAt: nil)
let stale = reduce(.reconnectReconcileSucceeded(wrongDto))
expect(stale.0.ui, .reconnecting(info()), "wrong call id reconcile ignored")
expect(stale.1, [], "wrong call id reconcile has no effects")

// Duplicate socket reconnect ignored while already reconnecting.
state = CallReducerState(ui: .reconnecting(info()), activeCallId: callId)
let dupRec = reduce(.socketReconnected)
expect(dupRec.0.ui, .reconnecting(info()), "duplicate socketReconnected ignored")
expect(dupRec.1, [], "duplicate socketReconnected has no effects")

// Duplicate remote ended is idempotent (no second teardown, no state regression).
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
_ = reduce(.remoteEnded(callId: callId, reason: "hangup"))
let dupEnded = reduce(.remoteEnded(callId: callId, reason: "hangup"))
if case .ended = dupEnded.0.ui { } else { fail("duplicate ended stays ended") }
expect(dupEnded.1, [], "duplicate ended has no effects")

// Transient reconcile fetch failure restores connected (never a fake terminal).
state = CallReducerState(ui: .reconnecting(info()), activeCallId: callId)
let fetchFail = reduce(.reconnectReconcileFailed)
expect(fetchFail.0.ui, .connected(ConnectedState(call: info())), "reconcile failed -> restore connected")

// Keepalive policy: starts only on connected, not during connecting.
state = CallReducerState(ui: .connecting(info()), activeCallId: callId)
let conn = reduce(.engineConnectionState(callId: callId, state: .connected))
check(conn.1.contains(.startKeepAlive(callId: callId)), "connected starts keepalive")
check(!conn.1.contains(.stopKeepAlive), "connected does not stop keepalive")
state = CallReducerState(ui: .outgoingRinging(info()), activeCallId: callId)
let accepted = reduce(.remoteAccepted(callId: callId))
if case .connecting = accepted.0.ui { } else { fail("remoteAccepted -> connecting") }
check(!accepted.1.contains(.startKeepAlive(callId: callId)), "keepalive not started while connecting")

// Keepalive stops on every terminal path.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
let ended = reduce(.remoteEnded(callId: callId, reason: "hangup"))
check(ended.1.contains(.stopKeepAlive), "terminal stops keepalive")

if failures > 0 {
    print("\(failures) reconnect contract check(s) failed")
    exit(1)
}
print("Reconnect contract checks passed")
