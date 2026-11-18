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
// B10: among formats of the target size, the lowest frame rate that still
// reaches the target wins (no 120/240 fps slow-motion formats), unbinned and
// '420f' first.
expect(CaptureFormatSelector.bestIndex(in: [f720HighFps, f720]), 1, "same resolution: the lowest frame rate that reaches the target wins")
let f720Slomo = CaptureFormatInfo(width: 1280, height: 720, maxFps: 240)
expect(CaptureFormatSelector.bestIndex(in: [f720Slomo, f720HighFps, f720]), 2, "a slow-motion format is never chosen when 30 fps exists")
let f720Binned = CaptureFormatInfo(width: 1280, height: 720, maxFps: 30, isBinned: true)
expect(CaptureFormatSelector.bestIndex(in: [f720Binned, f720HighFps]), 1, "an unbinned format beats a binned one of the same size")
let f720Video = CaptureFormatInfo(width: 1280, height: 720, maxFps: 30, isFullRange420: false)
expect(CaptureFormatSelector.bestIndex(in: [f720Video, f720]), 1, "'420f' beats '420v' of the same size and rate")
expect(CaptureFormatSelector.bestIndex(in: [f720, f720Video]), 0, "an equal format keeps the device's order")
expect(CaptureFormatSelector.bestIndex(in: [f720LowFps, CaptureFormatInfo(width: 1280, height: 720, maxFps: 20)]), 0, "below the target the fastest format is closest")

// Every ladder row the camera may be asked for (CallMediaLadder capture rows).
let f360 = CaptureFormatInfo(width: 640, height: 360, maxFps: 15)
let f1080 = CaptureFormatInfo(width: 1920, height: 1080, maxFps: 30)
expect(CaptureFormatSelector.bestIndex(in: [f4k, f720, f360], width: 640, height: 360, fps: 15), 2, "the saving row avoids larger sensor formats")
expect(CaptureFormatSelector.bestIndex(in: [f4k, f1080, f720], width: 1280, height: 720, fps: 30), 2, "the Wi-Fi row selects 720p instead of 1080p or 4K")
expect(CaptureFormatSelector.bestIndex(in: [f640], width: 1280, height: 720, fps: 30), 0, "a limited camera still gets its only format")

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
var upgraded = ConnectedState(call: audioInfo(), localVideoEnabled: true, remoteVideoEnabled: true, videoMode: .video)
upgraded.remoteVideoState = .on
check(pip.update(.connected(upgraded))?.showVideo == true, "PiP uses negotiated video after audio upgrade")
upgraded.remoteVideoEnabled = false
upgraded.remoteVideoState = .off
check(pip.update(.connected(upgraded))?.showVideo == false, "peer camera OFF keeps PiP with the avatar, never a stale frame")
upgraded.remoteVideoEnabled = true
upgraded.remoteVideoState = .waiting
check(pip.update(.connected(upgraded))?.showVideo == false, "camera ON waits for a fresh frame before PiP")
upgraded.remoteVideoState = .on
check(pip.update(.connected(upgraded))?.showVideo == true, "fresh remote frame enables PiP again")
check(pip.update(.reconnecting(audioInfo()))?.showVideo == false, "PiP masks stale frames during ICE recovery")
check(pip.update(.ended(audioInfo(), reason: .hangup)) == nil, "ended call removes PiP")
check(pip.update(.reconnecting(audioInfo())) == nil, "ended call cannot resurrect PiP from stale reconnect")
check(pip.update(.connected(ConnectedState(call: audioInfo()))) == nil, "audio-only call cannot launch PiP")
var bothOff = ConnectedState(call: videoInfo(), localVideoEnabled: false, remoteVideoEnabled: true, videoMode: .video)
bothOff.remoteVideoState = .on
check(pip.update(.connected(bothOff))?.showVideo == true, "peer video alone keeps PiP while own camera is off")
bothOff.remoteVideoEnabled = false
bothOff.remoteVideoState = .off
check(pip.update(.connected(bothOff))?.showVideo == false, "both cameras off keeps the PiP window with the peer's avatar")
check(pip.update(.reconnecting(videoInfo()))?.showVideo == false, "reconnect after both cameras off keeps the avatar window")
bothOff.localVideoEnabled = true
check(pip.update(.connected(bothOff))?.showVideo == false, "own camera back on keeps the avatar until the peer's frame")
var downgraded = bothOff
downgraded.videoMode = .audio
check(pip.update(.connected(downgraded)) == nil, "a downgrade to audio closes PiP")
check(CallPipGeometry.orientedSize(CGSize(width: 1280, height: 720), rotationDegrees: 90) == CGSize(width: 720, height: 1280), "rotated frame gives upright PiP size")
check(CallPipGeometry.orientedSize(CGSize(width: 1280, height: 720), rotationDegrees: 180) == CGSize(width: 1280, height: 720), "upside-down frame keeps its axes")
check(CallPipGeometry.preferredContentSize(for: CGSize(width: 720, height: 1280)) == CGSize(width: 300, height: 533), "portrait peer video gives a portrait PiP window")
check(CallPipGeometry.preferredContentSize(for: CGSize(width: 1280, height: 720)) == CGSize(width: 300, height: 169), "landscape peer video gives a landscape PiP window")
check(CallPipGeometry.preferredContentSize(for: CGSize(width: 100, height: 1000))?.height == 700, "extreme aspect ratio is clamped")
check(CallPipGeometry.preferredContentSize(for: .zero) == nil, "empty frame size never reaches AVKit")

state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), localVideoEnabled: true, videoMode: .video)), activeCallId: callId)
let interrupted = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: true))
check(interrupted.1.contains(.sendVideoState(callId: callId, enabled: false)), "camera interruption clears peer frozen frame")
if case .connected(let c) = interrupted.0.ui { check(c.localVideoEnabled, "interruption preserves user camera intent") }
let cameraRecovered = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
check(cameraRecovered.1.contains(.sendVideoState(callId: callId, enabled: true)), "camera recovery asks peer to wait for fresh frames")
_ = reduce(.setCameraEnabled(false))
let disabledRecovery = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
check(disabledRecovery.1.isEmpty, "recovery never turns a user-disabled camera back on")

// The own first frame is one-shot: every restart of the camera re-arms it, and
// a frame that arrives while reconnecting is kept (0.4.11: no self-view, Flip
// greyed for the rest of the call after leaving the app or a network drop).
do {
    state = CallReducerState(ui: .connected(ConnectedState(call: videoInfo(), localVideoEnabled: true, videoMode: .video, localCameraState: .on)), activeCallId: callId)
    let away = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: true))
    check(away.1.contains(.engineExpectLocalVideoFrame), "leaving the app re-arms the own preview")
    let back = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
    check(back.1.contains(.engineExpectLocalVideoFrame), "the camera coming back re-arms the own preview")
    expect(back.0.audioControls?.localCameraState, .starting, "the preview waits for a fresh frame")
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    let frame = reduce(.engineLocalFirstFrame(callId: callId))
    expect(frame.0.audioControls?.localCameraState, .on, "a first frame while reconnecting is kept")
    let restored = reduce(.engineConnectionState(callId: callId, state: .connected))
    expect(restored.0.audioControls?.localCameraState, .on, "the self-view stays on after the reconnect")
    check(!restored.1.contains(.engineExpectLocalVideoFrame), "a running preview is not re-armed on restore")

    var starting = CallReducerState(ui: .reconnecting(videoInfo()), activeCallId: callId)
    starting.lastConnected = ConnectedState(call: videoInfo(), localVideoEnabled: true, videoMode: .video, localCameraState: .starting)
    starting.iceInterrupted = true
    let restoredStarting = CallStateReducer.reduce(starting, .engineConnectionState(callId: callId, state: .connected))
    check(restoredStarting.1.contains(.engineExpectLocalVideoFrame), "a restore with the camera still starting re-arms the own preview")
    expect(CallStateReducer.reduce(restoredStarting.0, .engineLocalFirstFrame(callId: callId)).0.audioControls?.localCameraState, .on,
           "the re-armed frame turns the self-view on")

    state = CallReducerState(ui: .outgoingRinging(videoInfo()), activeCallId: callId)
    check(reduce(.engineLocalFirstFrame(callId: callId)).0.localVideoFrameReady, "a preview frame while ringing out is kept for connect")
}

// The peer's video entering `.waiting` re-arms its renderer, once.
do {
    let videoOffer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\n"
    let upgrading = CallReducerState(ui: .connected(ConnectedState(call: audioInfo(), videoUpgrade: .upgrading)), activeCallId: callId)
    let offer = CallStateReducer.reduce(upgrading, .remoteOffer(callId: callId, sdp: videoOffer))
    expect(offer.0.audioControls?.remoteVideoState, .waiting, "the peer's video starts waiting for its first frame")
    expect(offer.1.filter { $0 == .engineExpectRemoteVideoFrame }.count, 1, "a connected offer that starts the peer's video re-arms its renderer")

    var reconnecting = CallReducerState(ui: .reconnecting(audioInfo()), activeCallId: callId)
    reconnecting.lastConnected = ConnectedState(call: audioInfo(), videoUpgrade: .upgrading)
    let reconnectOffer = CallStateReducer.reduce(reconnecting, .remoteOffer(callId: callId, sdp: videoOffer))
    expect(reconnectOffer.1.filter { $0 == .engineExpectRemoteVideoFrame }.count, 1, "the reconnecting offer path re-arms once")

    let incoming = CallInfo(callId: callId, remoteUserId: callerId, remoteDisplayName: "Peer", mediaKind: .audio, direction: .incoming)
    var glare = CallReducerState(ui: .connected(ConnectedState(call: incoming, videoUpgrade: .upgrading)), activeCallId: callId)
    glare.renegotiating = true
    glare.renegotiationTarget = .video
    let polite = CallStateReducer.reduce(glare, .remoteOffer(callId: callId, sdp: videoOffer))
    check(polite.1.contains(.engineRollbackLocalOffer), "the callee rolls its crossing offer back")
    expect(polite.1.filter { $0 == .engineExpectRemoteVideoFrame }.count, 1, "the glare path re-arms once")

    var shown = ConnectedState(call: videoInfo(), remoteVideoEnabled: true, videoMode: .video, remoteVideoState: .on)
    shown.localVideoEnabled = true; shown.localCameraState = .on
    let steady = CallStateReducer.reduce(CallReducerState(ui: .connected(shown), activeCallId: callId), .toggleMute)
    expect(steady.1, [.engineSetMuted(true)], "a steady picture is never re-armed")
}

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

// The call cover: an ended call keeps the cover raised for that very call
// until it is acknowledged, so "Declined" / "No answer" / "Cancelled" are
// seen, but never raises one for an ended/minimized call. Incoming ringing
// raises a cover only without a system host. The claim follows the binding
// at once, not the cover's dismissal slide.
for systemHostsIncoming in [true, false] {
    typealias Claim = CallCoverPolicy.Claim
    let outgoing = audioInfo()
    let incoming = CallInfo(callId: callId, remoteUserId: callerId, remoteDisplayName: "Peer", mediaKind: .audio, direction: .incoming)
    // A second call, ringing in after the first one ended.
    let next = CallInfo(callId: "44444444-4444-4444-4444-444444444444", remoteUserId: callerId,
                        remoteDisplayName: "Peer", mediaKind: .audio, direction: .incoming)
    let own = Claim.call(callId)
    func cover(_ state: CallUiState, starting: Bool = false, minimized: Bool = false, claim: Claim) -> Bool {
        CallCoverPolicy.presented(state, starting: starting, minimized: minimized, claim: claim,
                                  systemHostsIncoming: systemHostsIncoming)
    }
    /// One update of the root: the binding's answer, then the claim it leaves.
    func update(_ state: CallUiState, starting: Bool = false, minimized: Bool = false, claim: Claim) -> (shown: Bool, claim: Claim) {
        let shown = cover(state, starting: starting, minimized: minimized, claim: claim)
        return (shown, CallCoverPolicy.claim(state, presented: shown))
    }
    for reason in [CallEndReason.rejected, .missed, .cancelled, .hangup, .remoteEnded, .failed] {
        check(cover(.ended(outgoing, reason: reason), claim: own),
              "the cover raised for a call stays through its ended screen (\(reason.rawValue))")
        check(!cover(.ended(outgoing, reason: reason), claim: .none),
              "an ended call never raises a cover that is not up (\(reason.rawValue))")
        check(!cover(.ended(outgoing, reason: reason), minimized: true, claim: own),
              "a minimized call stays minimized when it ends (\(reason.rawValue))")
        check(cover(.ended(outgoing, reason: reason), claim: .starting),
              "a call placed and ended before its ringing screen keeps Calling… (\(reason.rawValue))")
    }
    check(!cover(.ended(incoming, reason: .cancelled), claim: .none)
            && !cover(.ended(incoming, reason: .answeredElsewhere), claim: .none)
            && !cover(.ended(incoming, reason: .cancelled), claim: .starting),
          "an incoming end never raises a cover without its own claim")
    check(!cover(.ended(next, reason: .cancelled), claim: own) && !cover(.ended(next, reason: .rejected), claim: own),
          "an earlier call's cover never carries the ended screen of another call")
    expect(cover(.incomingRinging(incoming), claim: own), !systemHostsIncoming,
           "incoming ringing uses the cover exactly when the system does not host it")
    check(!cover(.incomingRinging(incoming), minimized: true, claim: own), "minimized incoming never raises a cover")
    let ringing = update(.incomingRinging(incoming), claim: .none)
    check(ringing.shown == !systemHostsIncoming && ringing.claim == (systemHostsIncoming ? .none : own),
          "incoming ringing claims only its own in-app cover")
    expect(cover(.ended(incoming, reason: .cancelled), claim: ringing.claim), !systemHostsIncoming,
           "an incoming end retains the cover only when its ringing was shown in-app")
    check(cover(.outgoingRinging(outgoing), claim: .none) && cover(.connecting(outgoing), claim: .none)
            && cover(.reconnecting(outgoing), claim: .none) && cover(.connected(ConnectedState(call: outgoing)), claim: .none),
          "a live call raises the cover")
    check(!cover(.connected(ConnectedState(call: outgoing)), minimized: true, claim: own), "a minimized live call has no cover")
    check(cover(.idle, starting: true, claim: .none) && !cover(.idle, claim: own)
            && !cover(.idle, starting: true, minimized: true, claim: .starting),
          "idle shows only the instant Calling… screen, unless minimized")
    check(cover(.failed(nil, reason: .busy), claim: .starting), "a failed start keeps its screen")

    // What each state leaves as the claim.
    func leaves(_ result: (shown: Bool, claim: Claim), _ shown: Bool, _ claim: Claim) -> Bool {
        result.shown == shown && result.claim == claim
    }
    check(leaves(update(.idle, starting: true, claim: .none), true, .starting), "Calling… claims the call being placed")
    check(leaves(update(.outgoingRinging(outgoing), claim: .starting), true, own), "the ringing call takes over the claim")
    check(leaves(update(.connecting(incoming), claim: .none), true, own), "a call answered in claims the cover")
    check(leaves(update(.ended(outgoing, reason: .rejected), claim: own), true, own), "the ended screen keeps its claim")
    check(leaves(update(.ended(outgoing, reason: .rejected), claim: .starting), true, own),
          "an instant outgoing end binds Calling… to its call id")
    check(leaves(update(.idle, claim: own), false, .none), "acknowledged: the claim goes in the same update")
    check(leaves(update(.incomingRinging(next), claim: own), !systemHostsIncoming,
                 systemHostsIncoming ? .none : .call(next.callId)), "incoming ringing replaces an earlier claim")
    check(leaves(update(.connected(ConnectedState(call: outgoing)), minimized: true, claim: own), false, .none),
          "minimizing releases the claim")
    check(leaves(update(.ended(next, reason: .cancelled), claim: .none), false, .none), "a call that was never up claims nothing")
    check(leaves(update(.failed(nil, reason: .busy), claim: .starting), true, .starting), "a failed start keeps the claim of Calling…")

    // The review case: a call's ended screen is acknowledged, and a second
    // call rings in and is cancelled while the cover still slides away (its
    // onDisappear has not come yet). Only an in-app incoming host raises a
    // new cover; it must bind to the second call's own claim.
    var claim = Claim.none
    var shown: [Bool] = []
    for state in [CallUiState.outgoingRinging(outgoing), .connected(ConnectedState(call: outgoing)),
                  .ended(outgoing, reason: .hangup), .idle, .incomingRinging(next), .ended(next, reason: .cancelled), .idle] {
        let result = update(state, claim: claim)
        shown.append(result.shown)
        claim = result.claim
    }
    expect(shown, [true, true, true, false, !systemHostsIncoming, !systemHostsIncoming, false],
           "a new incoming call has its own cover only when the system does not host ringing")
    // Even with no update between the acknowledgement and the second call
    // (the claim still names the first call), its ended screen is not raised.
    check(!update(.ended(next, reason: .cancelled), claim: own).shown,
          "a claim left by the first call never raises the second call's ended screen")
    let nextOutgoing = CallInfo(callId: next.callId, remoteUserId: callerId, remoteDisplayName: "Peer",
                                mediaKind: .audio, direction: .outgoing)
    check(!update(.ended(nextOutgoing, reason: .rejected), claim: own).shown,
          "an instant outgoing end cannot leave a pending claim for a different call")
}

if failures > 0 {
    print("\(failures) video contract check(s) failed")
    exit(1)
}
print("Video contract checks passed")
