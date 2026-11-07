import Foundation

// Pure ICE-restart contract checks: the bounded restart policy (attempt
// count/timing) and the reducer transitions around ICE disconnect/recover/
// failure. No network, no WebRTC — macOS CI only.

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

// MARK: - Pure restart policy

expect(IceRestartPolicy.maxAttempts, 2, "max restart attempts = 2")
expect(IceRestartPolicy.gracePeriodMs, 15_000, "grace period = 15000ms")
expect(IceRestartPolicy.retryDelayMs, 7_500, "retry delay = 7500ms (15000 / 2)")

expect(IceRestartPolicy.step(attempt: 0, isReconnecting: true), IceRestartPolicy.Step(shouldRestart: true, shouldFail: false), "attempt 0 -> restart")
expect(IceRestartPolicy.step(attempt: 1, isReconnecting: true), IceRestartPolicy.Step(shouldRestart: true, shouldFail: false), "attempt 1 -> restart")
expect(IceRestartPolicy.step(attempt: 2, isReconnecting: true), IceRestartPolicy.Step(shouldRestart: false, shouldFail: true), "attempt 2 -> fail")
expect(IceRestartPolicy.step(attempt: 0, isReconnecting: false), IceRestartPolicy.Step(shouldRestart: false, shouldFail: false), "not reconnecting -> no-op")

// MARK: - Reducer transitions

var state = CallReducerState()
func reduce(_ event: CallEvent) -> (CallReducerState, [CallEffect]) {
    let result = CallStateReducer.reduce(state, event)
    state = result.0
    return result
}

// Connected + ICE disconnected -> reconnecting (in-call state preserved).
state = CallReducerState(ui: .connected(ConnectedState(call: info(), isMuted: true, isSpeakerOn: true)), activeCallId: callId)
let disc = reduce(.engineConnectionState(callId: callId, state: .disconnected))
expect(disc.0.ui, .reconnecting(info()), "connected + disconnected -> reconnecting")

// Duplicate disconnected while already reconnecting -> ignored.
let dupDisc = reduce(.engineConnectionState(callId: callId, state: .disconnected))
expect(dupDisc.0.ui, .reconnecting(info()), "duplicate disconnected ignored (already reconnecting)")
expect(dupDisc.1, [], "duplicate disconnected has no effects")

// ICE recovered -> restore connected, preserving mute/speaker.
let recovered = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = recovered.0.ui {
    check(c.isMuted, "recover preserves mute")
    check(c.isSpeakerOn, "recover preserves speaker")
} else { fail("reconnecting + connected -> connected") }
expect(recovered.1, [], "recover has no effects")

// Disconnect again after success -> reconnecting again (fresh restart cycle).
let discAgain = reduce(.engineConnectionState(callId: callId, state: .disconnected))
expect(discAgain.0.ui, .reconnecting(info()), "disconnect after success -> reconnecting again")

// ICE failed -> terminal ended(failed) + backend failed + cleanup.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
let iceFailed = reduce(.engineConnectionState(callId: callId, state: .failed))
if case .ended(let c, let reason) = iceFailed.0.ui {
    check(c == info(), "ice failed keeps call identity")
    check(reason == .failed, "ice failed -> ended(failed)")
} else { fail("ice failed -> ended") }
check(iceFailed.1.contains(.restFailed(callId: callId)), "ice failed self-reports to backend")
check(iceFailed.1.contains(.engineDispose), "ice failed disposes engine")
check(iceFailed.1.contains(.stopKeepAlive), "ice failed stops keepalive")

// Duplicate failed after terminal -> ignored (idempotent).
let dupFailed = reduce(.engineConnectionState(callId: callId, state: .failed))
expect(dupFailed.1, [], "duplicate failed has no effects")

// Wrong call id disconnected -> ignored.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
let wrongDisc = reduce(.engineConnectionState(callId: "00000000-0000-0000-0000-000000000000", state: .disconnected))
expect(wrongDisc.0.ui, state.ui, "wrong call id disconnected ignored")

// WS disconnect alone (socketReconnected) -> no ICE restart.
state = CallReducerState(ui: .connected(ConnectedState(call: info())), activeCallId: callId)
let wsRec = reduce(.socketReconnected)
expect(wsRec.1, [.restGet(callId: callId)], "WS reconnect emits only restGet (no ICE restart)")

// Video upgrade pending + ICE disconnected -> reconnecting, upgrade preserved.
state = CallReducerState(ui: .connected(ConnectedState(call: info(), videoUpgrade: .requesting)), activeCallId: callId)
let upgradeDisc = reduce(.engineConnectionState(callId: callId, state: .disconnected))
expect(upgradeDisc.0.ui, .reconnecting(info()), "upgrade-pending + disconnected -> reconnecting")
let upgradeRecover = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = upgradeRecover.0.ui {
    check(c.videoUpgrade == .requesting, "recover preserves pending video upgrade")
} else { fail("upgrade-pending recover -> connected") }

if failures > 0 {
    print("\(failures) ICE restart contract check(s) failed")
    exit(1)
}
print("ICE restart contract checks passed")
