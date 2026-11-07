import Foundation

// Pure video-call contract checks: capture-format selection, camera-position
// toggling, video upgrade signaling encode/decode, and the pure video state
// machine. No WebRTC / AVFoundation / network — macOS CI only.

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

func videoInfo() -> CallInfo {
    CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: .video, direction: .outgoing)
}
func audioInfo() -> CallInfo {
    CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: .audio, direction: .outgoing)
}

// MARK: - Camera position + capture format selector

expect(CameraPosition.front.toggled, .back, "front toggles to back")
expect(CameraPosition.back.toggled, .front, "back toggles to front")

expect(CaptureFormatSelector.bestIndex(in: []), nil, "no formats -> nil")

let f640 = CaptureFormatInfo(width: 640, height: 480, maxFps: 30)
let f720 = CaptureFormatInfo(width: 1280, height: 720, maxFps: 30)
let f4k = CaptureFormatInfo(width: 3840, height: 2160, maxFps: 30)
let f720LowFps = CaptureFormatInfo(width: 1280, height: 720, maxFps: 24)
let f720HighFps = CaptureFormatInfo(width: 1280, height: 720, maxFps: 60)

expect(CaptureFormatSelector.bestIndex(in: [f4k, f640, f720]), 2, "selects 1280x720 over 4K and 640x480")
expect(CaptureFormatSelector.bestIndex(in: [f720LowFps, f640]), 1, "prefers an FPS-capable format over exact resolution below target FPS")
expect(CaptureFormatSelector.bestIndex(in: [f720, f720HighFps]), 1, "same resolution: higher frame rate wins")

// Quality survives unknown/legacy preferences and camera capability differences.
expect(CallQuality.parse(nil), .auto, "missing quality defaults to Auto")
expect(CallQuality.parse("future-mode"), .auto, "unknown quality defaults to Auto")
let economy = CallQuality.economy.video
let high = CallQuality.high.video
let f360 = CaptureFormatInfo(width: 640, height: 360, maxFps: 15)
let f1080 = CaptureFormatInfo(width: 1920, height: 1080, maxFps: 30)
expect(CaptureFormatSelector.bestIndex(in: [f4k, f720, f360], width: economy.width, height: economy.height, fps: Double(economy.fps)), 2, "economy avoids larger sensor formats")
expect(CaptureFormatSelector.bestIndex(in: [f720, f1080, f4k], width: high.width, height: high.height, fps: Double(high.fps)), 1, "high selects 1080p instead of 4K")
expect(CaptureFormatSelector.bestIndex(in: [f640], width: high.width, height: high.height, fps: Double(high.fps)), 0, "high falls back on limited camera")
check(CallQuality.economy.audioBitrate < CallQuality.auto.audioBitrate && CallQuality.auto.audioBitrate < CallQuality.high.audioBitrate, "independent audio budgets ordered")

// MARK: - Signaling encode/decode for video upgrade + video_state

check(CallSignalingEncoder.videoUpgradeRequest(callId: callId).contains("\"type\":\"call.video_upgrade_request\""), "encode video_upgrade_request type")
check(CallSignalingEncoder.videoUpgradeRequest(callId: callId).contains("\"call_id\":\"\(callId)\""), "encode video_upgrade_request call_id")
check(CallSignalingEncoder.videoUpgradeAccept(callId: callId).contains("\"type\":\"call.video_upgrade_accept\""), "encode video_upgrade_accept type")
check(CallSignalingEncoder.videoUpgradeReject(callId: callId).contains("\"type\":\"call.video_upgrade_reject\""), "encode video_upgrade_reject type")
check(CallSignalingEncoder.videoUpgradeCancel(callId: callId).contains("\"type\":\"call.video_upgrade_cancel\""), "encode video_upgrade_cancel type")
check(CallSignalingEncoder.videoState(callId: callId, enabled: true).contains("\"enabled\":true"), "encode video_state enabled=true")
check(CallSignalingEncoder.videoState(callId: callId, enabled: false).contains("\"enabled\":false"), "encode video_state enabled=false")

expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_upgrade_accept","call_id":"\#(callId)"}"#.utf8)), .videoUpgradeAccept(callId: callId), "video_upgrade_accept parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_upgrade_reject","call_id":"\#(callId)"}"#.utf8)), .videoUpgradeReject(callId: callId), "video_upgrade_reject parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_upgrade_cancel","call_id":"\#(callId)"}"#.utf8)), .videoUpgradeCancel(callId: callId), "video_upgrade_cancel parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_state","call_id":"\#(callId)","enabled":false}"#.utf8)), .videoState(callId: callId, enabled: false), "video_state disabled parsed")

// MARK: - Pure state machine (video)

var state = CallReducerState()
func reduce(_ event: CallEvent) -> (CallReducerState, [CallEffect]) {
    let result = CallStateReducer.reduce(state, event)
    state = result.0
    return result
}

// Video call connecting -> connected: local video enabled + starting.
state = CallReducerState(ui: .connecting(videoInfo()), activeCallId: callId)
let conn = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = conn.0.ui {
    check(c.videoMode == .video, "video call connected as video")
    check(c.localVideoEnabled, "video call has local video enabled")
    check(c.localCameraState == .starting, "video call local camera starting")
} else { fail("video connecting -> connected") }

// Toggle local camera off/on within a video call.
state = CallReducerState(ui: .connecting(videoInfo()), activeCallId: callId)
_ = reduce(.engineRemoteVideoEnabled(callId: callId, enabled: true))
let earlyTrack = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = earlyTrack.0.ui {
    check(c.remoteVideoEnabled && c.remoteVideoState == .waiting, "receiver before ICE survives connection")
}
check(earlyTrack.1.contains(.engineExpectRemoteVideoFrame), "connection re-arms frame evidence consumed before ICE")
_ = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = state.ui { check(c.remoteVideoState == .on, "early receiver displays fresh video") }
_ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
let recovered = reduce(.engineConnectionState(callId: callId, state: .connected))
check(recovered.1.contains(.engineExpectRemoteVideoFrame), "ICE recovery re-arms retained renderer")
_ = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = state.ui { check(c.remoteVideoState == .on, "video resumes after reconnect") }

state = CallReducerState(ui: .connecting(videoInfo()), activeCallId: callId)
_ = reduce(.remoteVideoState(callId: callId, enabled: false))
_ = reduce(.engineRemoteVideoEnabled(callId: callId, enabled: true))
_ = reduce(.engineConnectionState(callId: callId, state: .connected))
_ = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = state.ui {
    check(!c.remoteVideoEnabled && c.remoteVideoState == .off, "track discovery never overrides early camera OFF")
}
_ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
_ = reduce(.remoteVideoState(callId: callId, enabled: true))
_ = reduce(.engineConnectionState(callId: callId, state: .connected))
_ = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = state.ui { check(c.remoteVideoState == .on, "camera ON during reconnect survives restoration") }

state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), videoMode: .video)), activeCallId: callId)
let off = reduce(.setCameraEnabled(false))
if case .connected(let c) = off.0.ui {
    check(!c.localVideoEnabled, "setCameraEnabled(false) disables local video")
    check(c.localCameraState == .off, "setCameraEnabled(false) -> off")
}
check(off.1.contains(.engineSetVideoEnabled(false)), "camera off sets engine video disabled")
check(off.1.contains(.sendVideoState(callId: callId, enabled: false)), "camera off sends video_state false")
let on = reduce(.setCameraEnabled(true))
check(on.1.contains(.engineSetVideoEnabled(true)), "camera on sets engine video enabled")
check(on.1.contains(.sendVideoState(callId: callId, enabled: true)), "camera on sends video_state true")
if case .connected(let c) = on.0.ui { check(c.localCameraState == .starting, "camera on -> starting") }

// Remote video state signaling.
state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), videoMode: .video)), activeCallId: callId)
let rvOn = reduce(.remoteVideoState(callId: callId, enabled: true))
if case .connected(let c) = rvOn.0.ui {
    check(c.remoteVideoEnabled, "remoteVideoState(true) sets enabled")
    check(c.remoteVideoState == .waiting, "remoteVideoState(true) -> waiting")
}
check(rvOn.1.contains(.engineExpectRemoteVideoFrame), "remote camera on re-arms first-frame evidence")
let rvOff = reduce(.remoteVideoState(callId: callId, enabled: false))
if case .connected(let c) = rvOff.0.ui {
    check(!c.remoteVideoEnabled, "remoteVideoState(false) clears enabled")
    check(c.remoteVideoState == .off, "remoteVideoState(false) -> off")
}

// First-frame evidence.
state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), remoteVideoEnabled: true, videoMode: .video, remoteVideoState: .waiting)), activeCallId: callId)
let remoteFrame = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = remoteFrame.0.ui { check(c.remoteVideoState == .on, "remote first frame -> on") }
state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), localVideoEnabled: true, videoMode: .video, localCameraState: .starting)), activeCallId: callId)
let localFrame = reduce(.engineLocalFirstFrame(callId: callId))
if case .connected(let c) = localFrame.0.ui { check(c.localCameraState == .on, "local first frame -> on") }

// Audio -> video upgrade request.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo())), activeCallId: callId)
let req = reduce(.requestVideoUpgrade)
if case .connected(let c) = req.0.ui { check(c.videoUpgrade == .requesting, "requestVideoUpgrade -> requesting") }
if case .connected(let c) = req.0.ui {
    check(c.localVideoEnabled, "upgrade exposes local preview before remote answer")
    check(c.localCameraState == .starting, "upgrade waits for local first frame")
}
check(req.1.contains(.sendVideoUpgradeRequest(callId: callId)), "upgrade request sends request")
check(req.1.contains(.armVideoUpgradeTimeout), "upgrade request arms timeout")

// Duplicate incoming request ignored (simultaneous race resolves by timeout).
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let dup = reduce(.remoteVideoUpgradeRequest(callId: callId))
if case .connected(let c) = dup.0.ui { check(c.videoUpgrade == .requesting, "duplicate upgrade request ignored while requesting") }
expect(dup.1, [], "duplicate upgrade request has no effects")

// Remote accept -> renegotiation offer.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let acc = reduce(.remoteVideoUpgradeAccept(callId: callId))
check(acc.0.renegotiating, "upgrade accept sets renegotiating")
check(acc.0.renegotiationTarget == .video, "upgrade accept targets video")
check(acc.1.contains(.produceOfferAndSend(callId: callId)), "upgrade accept produces offer")
check(acc.1.contains(.cancelVideoUpgradeTimeout), "upgrade accept cancels timeout")

// Remote reject -> back to idle with notice.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let rej = reduce(.remoteVideoUpgradeReject(callId: callId))
if case .connected(let c) = rej.0.ui {
    check(c.videoUpgrade == .idle, "upgrade reject -> idle")
    check(c.notice == .videoRequestDeclined, "upgrade reject shows declined notice")
}
check(rej.1.contains(.engineStopLocalVideo), "upgrade reject stops local video")

// Remote cancel -> dismiss prompt idempotently.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .incomingRequest)), activeCallId: callId)
let cancel = reduce(.remoteVideoUpgradeCancel(callId: callId))
if case .connected(let c) = cancel.0.ui { check(c.videoUpgrade == .idle, "upgrade cancel -> idle") }
check(cancel.1.contains(.cancelVideoUpgradeTimeout), "upgrade cancel cancels timeout")

// Upgrade timeout leaves audio connected.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let timeout = reduce(.videoUpgradeTimeout)
if case .connected(let c) = timeout.0.ui {
    check(c.videoUpgrade == .idle, "timeout resets upgrade state")
    check(c.notice == .videoRequestTimeout, "timeout shows timeout notice")
}
check(timeout.1.contains(.engineStopLocalVideo), "timeout stops local video")

// Camera failure (no camera / permission) -> notice, no request sent.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let camFail = reduce(.localVideoFailed)
if case .connected(let c) = camFail.0.ui {
    check(c.videoUpgrade == .idle, "camera failure resets upgrade")
    check(!c.localVideoEnabled, "camera failure disables local video")
    check(c.localCameraState == .failed, "camera failure -> failed")
    check(c.notice == .cameraFailed, "camera failure shows notice")
}

// End call while upgrade pending -> full cleanup.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let endWhilePending = reduce(.remoteEnded(callId: callId, reason: "hangup"))
if case .ended = endWhilePending.0.ui { } else { fail("end while upgrade pending -> ended") }
check(endWhilePending.1.contains(.engineDispose), "end while pending disposes engine")
check(endWhilePending.1.contains(.stopKeepAlive), "end while pending stops keepalive")

// Camera permission denied while an upgrade request is pending -> notice, audio stays.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .incomingRequest)), activeCallId: callId)
let denied = reduce(.cameraDenied)
if case .connected(let c) = denied.0.ui {
    check(c.videoUpgrade == .incomingRequest, "camera denied keeps the upgrade request pending")
    check(c.notice == .cameraDenied, "camera denied shows notice")
}

// Stale / wrong call id ignored.
state = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .incomingRequest)), activeCallId: callId)
let stale = reduce(.remoteVideoUpgradeAccept(callId: "00000000-0000-0000-0000-000000000000"))
if case .connected(let c) = stale.0.ui { check(c.videoUpgrade == .incomingRequest, "wrong call id upgrade event ignored") }
expect(stale.1, [], "wrong call id has no effects")

// A queued video frame may arrive after the peer has disabled its camera.
state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), videoMode: .video)), activeCallId: callId)
_ = reduce(.remoteVideoState(callId: callId, enabled: false))
let lateFrame = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = lateFrame.0.ui {
    check(!c.remoteVideoEnabled && c.remoteVideoState == .off, "late frame does not resurrect disabled remote camera")
}
_ = reduce(.remoteVideoState(callId: callId, enabled: true))
let resumedFrame = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = resumedFrame.0.ui {
    check(c.remoteVideoState == .on, "enabled remote camera accepts first frame")
}

// PiP must survive an audio-to-video upgrade and ICE recovery without showing stale video.
var pip = CallPipPresentation()
var upgraded = ConnectedState(call: audioInfo(), videoMode: .video)
upgraded.remoteVideoState = .on
check(pip.update(.connected(upgraded))?.showVideo == true, "PiP uses negotiated video after audio upgrade")
upgraded.remoteVideoState = .off
check(pip.update(.connected(upgraded))?.showVideo == false, "camera OFF disables PiP even when the call is video")
upgraded.remoteVideoState = .waiting
check(pip.update(.connected(upgraded))?.showVideo == false, "camera ON waits for a fresh frame before PiP")
upgraded.remoteVideoState = .on
check(pip.update(.connected(upgraded))?.showVideo == true, "fresh remote frame enables PiP again")
check(pip.update(.reconnecting(audioInfo()))?.showVideo == false, "PiP masks stale frames during ICE recovery")
check(pip.update(.ended(audioInfo(), reason: .hangup)) == nil, "ended call removes PiP")
check(pip.update(.reconnecting(audioInfo())) == nil, "ended call cannot resurrect PiP from stale reconnect")
check(pip.update(.connected(ConnectedState(call: audioInfo()))) == nil, "audio-only call cannot launch PiP")

state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), localVideoEnabled: true, videoMode: .video)), activeCallId: callId)
let interrupted = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: true))
check(interrupted.1.contains(.sendVideoState(callId: callId, enabled: false)), "camera interruption clears peer frozen frame")
if case .connected(let c) = interrupted.0.ui { check(c.localVideoEnabled, "interruption preserves user camera intent") }
let cameraRecovered = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
check(cameraRecovered.1.contains(.sendVideoState(callId: callId, enabled: true)), "camera recovery asks peer to wait for fresh frames")
_ = reduce(.setCameraEnabled(false))
let disabledRecovery = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
check(disabledRecovery.1.isEmpty, "recovery never turns a user-disabled camera back on")

check(CallPipFramePolicy.acceptsFrame(valid: true, running: false, primed: false, visible: true, busy: false), "PiP can prepare its first frame before system start")
check(!CallPipFramePolicy.acceptsFrame(valid: true, running: false, primed: true, visible: true, busy: false), "inline PiP stops decoding after preroll")
check(CallPipFramePolicy.acceptsFrame(valid: true, running: true, primed: true, visible: true, busy: false), "active PiP continues remote video")
check(!CallPipFramePolicy.acceptsFrame(valid: true, running: true, primed: true, visible: false, busy: false), "disabled camera cannot feed PiP stale frames")
check(!CallPipFramePolicy.acceptsFrame(valid: false, running: true, primed: false, visible: true, busy: false), "ended PiP rejects queued frames")
check(!CallPipFramePolicy.acceptsFrame(valid: true, running: true, primed: false, visible: true, busy: true), "PiP never queues multiple frames")

let dragStart: CGFloat = 0.8
let dragPositions = [CGFloat(0), -10, -20, -30].map {
    FloatingVideoPosition.normalized(origin: dragStart, translation: $0, range: 100)
}
check(zip(dragPositions, [CGFloat(0.8), 0.7, 0.6, 0.5]).allSatisfy { abs($0 - $1) < 0.0001 },
      "drag uses one fixed origin without accumulating previous frame offsets")
check(FloatingVideoPosition.normalized(origin: 0.5, translation: -1000, range: 100) == 0, "preview stays inside leading edge")
check(FloatingVideoPosition.normalized(origin: 0.5, translation: 1000, range: 100) == 1, "preview stays inside trailing edge")
check(FloatingVideoPosition.normalized(origin: 0.7, translation: 30, range: 0) == 0.7, "small viewport preserves position without division by zero")
check(CallVideoWindowPolicy.showsInline(videoCall: true, minimized: true, fullScreenVisible: false, appActive: true, systemPipVisible: false), "minimized video call appears inside COCO")
check(!CallVideoWindowPolicy.showsInline(videoCall: true, minimized: false, fullScreenVisible: true, appActive: true, systemPipVisible: false), "full screen has no duplicate floating remote video")
check(!CallVideoWindowPolicy.showsInline(videoCall: true, minimized: true, fullScreenVisible: true, appActive: true, systemPipVisible: false), "wait for full-screen dismissal before moving renderer")
check(!CallVideoWindowPolicy.showsInline(videoCall: true, minimized: true, fullScreenVisible: false, appActive: false, systemPipVisible: false), "background does not mount inline Metal video")
check(!CallVideoWindowPolicy.showsInline(videoCall: true, minimized: true, fullScreenVisible: false, appActive: true, systemPipVisible: true), "inline video waits for system PiP to close")
check(!CallVideoWindowPolicy.showsInline(videoCall: false, minimized: true, fullScreenVisible: false, appActive: true, systemPipVisible: false), "ended or audio-only call has no floating video")

if failures > 0 {
    print("\(failures) video contract check(s) failed")
    exit(1)
}
print("Video contract checks passed")
