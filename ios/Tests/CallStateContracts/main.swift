import Foundation

// Pure call state-machine + signaling contract checks — no network, no
// WebRTC, no libsignal. Mirrors the backend WS call-event shapes and the
// Android CallController state machine, caught on macOS CI.

var failures = 0

func fail(_ description: String) {
    failures += 1
    let line = "FAIL: \(description)\n"
    FileHandle.standardError.write(Data(line.utf8))
}

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    if condition() {
        print("PASS: \(description)")
    } else {
        fail(description)
    }
}

func expect<T: Equatable>(_ actual: T, _ expected: T, _ description: String) {
    if actual == expected {
        print("PASS: \(description)")
    } else {
        fail("\(description) (expected \(expected), got \(actual))")
    }
}

let callId = "11111111-1111-1111-1111-111111111111"
let callerId = "22222222-2222-2222-2222-222222222222"
let calleeId = "33333333-3333-3333-3333-333333333333"

func outgoingInfo(_ media: CallMediaKind = .audio) -> CallInfo {
    CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: media, direction: .outgoing)
}
func incomingInfo(_ media: CallMediaKind = .audio) -> CallInfo {
    CallInfo(callId: callId, remoteUserId: callerId, remoteDisplayName: "Peer", mediaKind: media, direction: .incoming)
}

// MARK: - Signaling parser (server -> client)

let offerJSON = #"{"type":"call.offer","call_id":"\#(callId)","sdp":"v=0"}"#
expect(CallSignalingEventParser.parse(Data(offerJSON.utf8)), .offer(callId: callId, sdp: "v=0"), "call.offer parsed")

let answerJSON = #"{"type":"call.answer","call_id":"\#(callId)","sdp":"v=0"}"#
expect(CallSignalingEventParser.parse(Data(answerJSON.utf8)), .answer(callId: callId, sdp: "v=0"), "call.answer parsed")

let iceJSON = #"{"type":"call.ice","call_id":"\#(callId)","candidate":"candidate:1","sdp_mid":"0","sdp_m_line_index":3}"#
expect(CallSignalingEventParser.parse(Data(iceJSON.utf8)), .ice(callId: callId, candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 3), "call.ice parsed (snake_case)")

let ringingJSON = #"{"type":"call.ringing","call_id":"\#(callId)","caller_user_id":"\#(callerId)","media_kind":"video"}"#
expect(CallSignalingEventParser.parse(Data(ringingJSON.utf8)), .ringing(callId: callId, callerUserId: callerId, mediaKind: "video"), "call.ringing parsed")

expect(CallSignalingEventParser.parse(Data(#"{"type":"call.accepted","call_id":"\#(callId)"}"#.utf8)), .accepted(callId: callId), "call.accepted parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.answered_elsewhere","call_id":"\#(callId)"}"#.utf8)), .answeredElsewhere(callId: callId), "call.answered_elsewhere parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.ended","call_id":"\#(callId)","reason":"rejected"}"#.utf8)), .ended(callId: callId, reason: "rejected"), "call.ended parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_upgrade_request","call_id":"\#(callId)"}"#.utf8)), .videoUpgradeRequest(callId: callId), "video_upgrade_request parsed")
expect(CallSignalingEventParser.parse(Data(#"{"type":"call.video_state","call_id":"\#(callId)","enabled":true}"#.utf8)), .videoState(callId: callId, enabled: true), "video_state parsed")
check(CallSignalingEventParser.parse(Data(#"{"type":"PONG"}"#.utf8)) == nil, "PONG is not a call event")
check(CallSignalingEventParser.parse(Data(#"{"type":"message.new","message":{}}"#.utf8)) == nil, "message.new is not a call event")

// MARK: - Signaling encoder (client -> server)

check(CallSignalingEncoder.offer(callId: callId, sdp: "v=0").contains("\"type\":\"call.offer\""), "encode offer type")
check(CallSignalingEncoder.offer(callId: callId, sdp: "v=0").contains("\"call_id\":\"\(callId)\""), "encode offer call_id")
check(CallSignalingEncoder.ice(callId: callId, candidate: "c", sdpMid: "0", sdpMLineIndex: 1).contains("\"sdp_mid\":\"0\""), "encode ice sdp_mid")
check(CallSignalingEncoder.ice(callId: callId, candidate: "c", sdpMid: "0", sdpMLineIndex: 1).contains("\"sdp_m_line_index\":1"), "encode ice sdp_m_line_index")
check(CallSignalingEncoder.ice(callId: callId, candidate: "c", sdpMid: nil, sdpMLineIndex: nil).contains("sdp_mid") == false, "encode ice omits absent sdp_mid")
check(CallSignalingEncoder.videoState(callId: callId, enabled: true).contains("\"enabled\":true"), "encode video_state enabled")
check(CallSignalingEncoder.keepAlive(callId: callId).contains("\"type\":\"call.keep_alive\""), "encode keep_alive")

// MARK: - State machine

var state = CallReducerState()
func reduce(_ event: CallEvent) -> (CallReducerState, [CallEffect]) {
    let result = CallStateReducer.reduce(state, event)
    state = result.0
    return result
}

// IDLE -> outgoingRinging
state = CallReducerState()
let r1 = reduce(.startCall(outgoingInfo()))
expect(r1.0.ui, .outgoingRinging(outgoingInfo()), "startCall -> outgoingRinging")
expect(r1.0.activeCallId, callId, "startCall sets activeCallId")

// createFailed -> failed
state = CallReducerState()
let r2 = reduce(.createFailed(.network))
expect(r2.0.ui, .failed(nil, reason: .network), "createFailed -> failed")

// outgoing cancel -> restCancel
state = CallReducerState(ui: .outgoingRinging(outgoingInfo()), activeCallId: callId)
expect(reduce(.cancelOutgoing).1, [.restCancel(callId: callId)], "cancelOutgoing -> restCancel")

// outgoing remoteAccepted -> connecting + engine init + offer
state = CallReducerState(ui: .outgoingRinging(outgoingInfo()), activeCallId: callId)
let acc = reduce(.remoteAccepted(callId: callId))
expect(acc.0.ui, .connecting(outgoingInfo()), "remoteAccepted -> connecting")
expect(acc.1, [.engineInitialize(mediaKind: .audio), .produceOfferAndSend(callId: callId)], "remoteAccepted effects")

// incoming ringing -> accept -> connecting
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
expect(reduce(.acceptIncoming).1, [.restAccept(callId: callId)], "acceptIncoming -> restAccept")
let accIn = reduce(.acceptSucceeded(incomingInfo()))
expect(accIn.0.ui, .connecting(incomingInfo()), "acceptSucceeded -> connecting")
expect(accIn.1.first, .engineInitialize(mediaKind: .audio), "acceptSucceeded engine init")

// incoming reject -> restReject
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
expect(reduce(.rejectIncoming).1, [.restReject(callId: callId)], "rejectIncoming -> restReject")

// incoming remoteEnded -> ended + cleanup
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
let re = reduce(.remoteEnded(callId: callId, reason: "rejected"))
expect(re.0.ui, .ended(incomingInfo(), reason: .rejected), "remoteEnded -> ended (rejected)")
check(re.1.contains(.engineDispose), "remoteEnded disposes engine")
check(re.1.contains(.stopKeepAlive), "remoteEnded stops keepalive")
check(re.1.contains(.dismissIncomingPush(callId: callId)), "incoming terminal uses the shared immediate incoming-presentation cleanup")

// answeredElsewhere -> idle + dismiss push
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
let ae = reduce(.remoteAnsweredElsewhere(callId: callId))
expect(ae.0.ui, .ended(incomingInfo(), reason: .answeredElsewhere), "answeredElsewhere exposes terminal identity to CallKit")
check(ae.1.contains(.dismissIncomingPush(callId: callId)), "answeredElsewhere dismisses push")
check(ae.1.contains(.engineDispose), "answeredElsewhere releases media")

// connecting: ICE before SDP queued, then drained by offer
state = CallReducerState(ui: .connecting(incomingInfo()), activeCallId: callId)
let iceQueued = reduce(.remoteIce(callId: callId, candidate: "c1", sdpMid: "0", sdpMLineIndex: 0))
expect(iceQueued.0.pendingRemoteIce.count, 1, "ICE before SDP queued")
let offer = reduce(.remoteOffer(callId: callId, sdp: "v=0"))
expect(offer.0.remoteDescriptionSet, true, "offer sets remoteDescriptionSet")
check(offer.1.contains(.engineSetRemoteDescription(callId: callId, type: "offer", sdp: "v=0")), "offer sets remote description")
check(offer.1.contains(.produceAnswerAndSend(callId: callId)), "offer produces answer")
check(offer.1.contains(.engineAddIce(callId: callId, candidate: "c1", sdpMid: "0", sdpMLineIndex: 0)), "queued ICE drained after offer")
expect(offer.0.pendingRemoteIce.count, 0, "ICE queue emptied")

// connecting -> engine connected -> connected + restConnected + keepalive
state = CallReducerState(ui: .connecting(incomingInfo()), activeCallId: callId)
let conn = reduce(.engineConnectionState(callId: callId, state: .connected))
if case .connected(let c) = conn.0.ui {
    check(c.videoMode == .audio, "audio call connected as audio")
} else { fail("connecting -> connected") }
check(conn.1.contains(.restConnected(callId: callId)), "connected marks backend connected")
check(conn.1.contains(.startKeepAlive(callId: callId)), "connected starts keepalive")

// connected hangup -> restHangup
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
expect(reduce(.hangup).1, [.restHangup(callId: callId)], "hangup -> restHangup")

// wrong call id ignored
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
let wrong = reduce(.remoteEnded(callId: "00000000-0000-0000-0000-000000000000", reason: "hangup"))
expect(wrong.0.ui, state.ui, "wrong call id event ignored")
expect(wrong.1, [], "wrong call id has no effects")

// mute / speaker
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
let mute = reduce(.toggleMute)
if case .connected(let c) = mute.0.ui { check(c.isMuted, "toggleMute mutates state") }
expect(mute.1, [.engineSetMuted(true)], "toggleMute -> engineSetMuted(true)")
let spk = reduce(.toggleSpeaker)
expect(spk.1, [.engineSetSpeaker(true)], "toggleSpeaker -> engineSetSpeaker(true)")

// video upgrade request
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
let up = reduce(.requestVideoUpgrade)
check(up.1.contains(.engineStartLocalVideo), "requestVideoUpgrade starts local video")
check(up.1.contains(.sendVideoUpgradeRequest(callId: callId)), "requestVideoUpgrade sends request")
check(up.1.contains(.armVideoUpgradeTimeout), "requestVideoUpgrade arms timeout")

// duplicate incoming upgrade request ignored
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo(), videoUpgrade: .incomingRequest)), activeCallId: callId)
let dup = reduce(.remoteVideoUpgradeRequest(callId: callId))
if case .connected(let c) = dup.0.ui { check(c.videoUpgrade == .incomingRequest, "duplicate upgrade request not clobbered") }

// upgrade accept -> produce offer + renegotiating
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo(), videoUpgrade: .incomingRequest)), activeCallId: callId)
let receiveVideo = reduce(.acceptVideoUpgrade)
check(receiveVideo.1.contains(.enginePrepareLocalVideo), "receiving video prepares a disabled track")
check(!receiveVideo.1.contains(.engineStartLocalVideo), "receiving video must not open the local camera")

// upgrade accept -> produce offer + renegotiating
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let upAcc = reduce(.remoteVideoUpgradeAccept(callId: callId))
check(upAcc.1.contains(.produceOfferAndSend(callId: callId)), "upgrade accept produces offer")
check(upAcc.0.renegotiating, "upgrade accept sets renegotiating")

// upgrade timeout
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo(), videoUpgrade: .requesting)), activeCallId: callId)
let to = reduce(.videoUpgradeTimeout)
check(to.1.contains(.engineStopLocalVideo), "upgrade timeout stops local video")
if case .connected(let c) = to.0.ui { check(c.videoUpgrade == .idle, "upgrade timeout resets upgrade state") }

// reconnect
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
let rec = reduce(.socketReconnected)
expect(rec.0.ui, .connected(ConnectedState(call: outgoingInfo())), "socket reconnect keeps the call connected (media unaffected)")
expect(rec.1, [.restGet(callId: callId)], "reconnect -> restGet")

let dto = CallDTO(id: UUID(uuidString: callId)!, callerUserId: UUID(uuidString: callerId)!, calleeUserId: UUID(uuidString: calleeId)!, mediaKind: .audio, state: "connected", endReason: nil, acceptedDeviceId: nil, createdAt: Date(), acceptedAt: nil, connectedAt: nil, endedAt: nil)
let recOK = reduce(.reconnectReconcileSucceeded(dto))
expect(recOK.0.ui, .connected(ConnectedState(call: outgoingInfo())), "reconcile connected -> connected")
let endedDto = CallDTO(id: UUID(uuidString: callId)!, callerUserId: UUID(uuidString: callerId)!, calleeUserId: UUID(uuidString: calleeId)!, mediaKind: .audio, state: "ended", endReason: "hangup", acceptedDeviceId: nil, createdAt: Date(), acceptedAt: nil, connectedAt: nil, endedAt: nil)
let recEnd = reduce(.reconnectReconcileSucceeded(endedDto))
if case .ended(let c, let reason) = recEnd.0.ui {
    check(c == outgoingInfo(), "reconcile ended keeps call identity")
    check(reason == .hangup, "reconcile ended maps server reason")
} else { fail("reconcile ended -> ended") }
check(recEnd.1.contains(.engineDispose), "reconcile ended disposes engine")
check(recEnd.1.contains(.stopKeepAlive), "reconcile ended stops keepalive")

// permission denied -> idle + dispose
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
let pd = reduce(.permissionDenied)
expect(pd.0.ui, .ended(incomingInfo(), reason: .rejected), "permission denial ends system call with identity")
check(pd.1.contains(.engineDispose), "permission denial releases media")
check(pd.1.contains(.restReject(callId: callId)), "permission denial tells caller to stop ringing")
state = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
let unrelatedDenial = reduce(.permissionDenied)
expect(unrelatedDenial.0.ui, .connected(ConnectedState(call: outgoingInfo())), "late permission denial must not end a connected call")
state = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
let failedAccept = reduce(.acceptFailed)
expect(failedAccept.0.ui, .ended(incomingInfo(), reason: .failed), "failed answer must terminate CallKit UI")

// mapEndReason
expect(CallStateReducer.mapEndReason("hangup"), .hangup, "mapEndReason hangup")
expect(CallStateReducer.mapEndReason("rejected"), .rejected, "mapEndReason rejected")
expect(CallStateReducer.mapEndReason("missed"), .missed, "mapEndReason missed")
expect(CallStateReducer.mapEndReason("timeout"), .timeout, "mapEndReason timeout")
expect(CallStateReducer.mapEndReason("bogus"), .remoteEnded, "mapEndReason unknown -> remoteEnded")

// MARK: - Remote camera state (Runtime Correction A)

func videoConnected(remoteEnabled: Bool = false, remoteState: RemoteVideoState = .off) -> ConnectedState {
    var c = ConnectedState(call: outgoingInfo(.video))
    c.videoMode = .video
    c.remoteVideoEnabled = remoteEnabled
    c.remoteVideoState = remoteState
    return c
}

// video_state(true) -> waiting + re-arm the first-frame detector
state = CallReducerState(ui: .connected(videoConnected()), activeCallId: callId)
let vsOn = reduce(.remoteVideoState(callId: callId, enabled: true))
if case .connected(let c) = vsOn.0.ui {
    check(c.remoteVideoEnabled, "video_state(true) marks remote video enabled")
    expect(c.remoteVideoState, .waiting, "signaling alone never shows video as live")
} else { fail("video_state(true) stays connected") }
check(vsOn.1.contains(.engineExpectRemoteVideoFrame), "video_state(true) re-arms first-frame detector")

// fresh frame after ON -> video visible
let firstFrame = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = firstFrame.0.ui {
    expect(c.remoteVideoState, .on, "fresh frame reveals remote video")
} else { fail("remote first frame stays connected") }

// video_state(false) -> off
let vsOff = reduce(.remoteVideoState(callId: callId, enabled: false))
if case .connected(let c) = vsOff.0.ui {
    check(!c.remoteVideoEnabled, "video_state(false) marks remote video disabled")
    expect(c.remoteVideoState, .off, "camera off hides remote video")
} else { fail("video_state(false) stays connected") }
check(vsOff.1.isEmpty, "camera off has no engine effect")

// late frame after OFF must NOT reveal video
let lateFrame = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = lateFrame.0.ui {
    expect(c.remoteVideoState, .off, "late frame after OFF keeps video hidden")
} else { fail("late frame stays connected") }

// duplicate OFF is idempotent
let dupOff = reduce(.remoteVideoState(callId: callId, enabled: false))
if case .connected(let c) = dupOff.0.ui { expect(c.remoteVideoState, .off, "duplicate OFF idempotent") } else { fail("dup off") }

// ON -> WAITING -> fresh frame -> ON
state = CallReducerState(ui: .connected(videoConnected()), activeCallId: callId)
_ = reduce(.remoteVideoState(callId: callId, enabled: true))
if case .connected(let c) = state.ui { expect(c.remoteVideoState, .waiting, "ON without frame -> waiting") } else { fail("on") }
let dupOn = reduce(.remoteVideoState(callId: callId, enabled: true))
if case .connected(let c) = dupOn.0.ui { expect(c.remoteVideoState, .waiting, "duplicate ON idempotent") } else { fail("dup on") }
_ = reduce(.engineRemoteFirstFrame(callId: callId))
if case .connected(let c) = state.ui { expect(c.remoteVideoState, .on, "fresh frame -> on") } else { fail("frame") }

// OFF -> ON -> OFF race ends hidden
state = CallReducerState(ui: .connected(videoConnected()), activeCallId: callId)
_ = reduce(.remoteVideoState(callId: callId, enabled: false))
_ = reduce(.remoteVideoState(callId: callId, enabled: true))
_ = reduce(.remoteVideoState(callId: callId, enabled: false))
if case .connected(let c) = state.ui { expect(c.remoteVideoState, .off, "OFF/ON/OFF race ends hidden") } else { fail("race") }

// local camera off sends the ephemeral state + disables the track, never mute
state = CallReducerState(ui: .connected(videoConnected(remoteEnabled: false)), activeCallId: callId)
let camOff = reduce(.setCameraEnabled(false))
if case .connected(let c) = camOff.0.ui {
    check(!c.localVideoEnabled, "camera off flips local video off")
    check(!c.isMuted, "camera off never mutes the mic")
    expect(c.localCameraState, .off, "camera off local state")
} else { fail("camera off") }
check(camOff.1.contains(.sendVideoState(callId: callId, enabled: false)), "camera off signals the peer")
check(camOff.1.contains(.engineSetVideoEnabled(false)), "camera off disables the local track")

// reconnect restore never resurrects a stale .on
let staleOn = CallStateReducer.restoredAfterReconnect(videoConnected(remoteEnabled: true, remoteState: .on))
expect(staleOn.remoteVideoState, .waiting, "reconnect restores video as waiting, not on")
let restoredOff = CallStateReducer.restoredAfterReconnect(videoConnected(remoteEnabled: false, remoteState: .off))
expect(restoredOff.remoteVideoState, .off, "reconnect keeps camera-off as off")

// MARK: - Call layout policy (pure, Phase 4 §41/§42)

func approx(_ a: CGFloat, _ b: CGFloat, _ tolerance: CGFloat = 0.01) -> Bool {
    abs(a - b) <= tolerance
}

expect(CallLayoutPolicy.avatarSize(availableHeight: 800, isVideo: false), CallLayoutPolicy.maxAvatar, "tall avatar caps at max")
expect(CallLayoutPolicy.avatarSize(availableHeight: 300, isVideo: false), CallLayoutPolicy.minAvatar, "short avatar floors at min")
expect(CallLayoutPolicy.avatarSize(availableHeight: 800, isVideo: true), CallLayoutPolicy.videoAvatar, "video placeholder avatar fixed")
check(CallLayoutPolicy.controlSize(availableWidth: 320, controlCount: 5) >= CallLayoutPolicy.controlMin, "narrow 5-control stays above floor")
check(CallLayoutPolicy.controlSize(availableWidth: 320, controlCount: 5) <= CallLayoutPolicy.controlMax, "narrow 5-control stays below cap")
expect(CallLayoutPolicy.controlSize(availableWidth: 480, controlCount: 5), CallLayoutPolicy.controlMax, "wide controls capped")
expect(CallLayoutPolicy.controlSpacing(availableWidth: 320, controlCount: 5), 6, "narrow spacing")
expect(CallLayoutPolicy.controlSpacing(availableWidth: 400, controlCount: 5), 12, "wide spacing")
expect(CallLayoutPolicy.trayWidth(availableWidth: 480), CallLayoutPolicy.trayMaxWidth, "tray width capped on tablets")

for width: CGFloat in [320, 360, 400, 480, 800] {
    let tray = CallLayoutPolicy.trayWidth(availableWidth: width)
    let padding = CallLayoutPolicy.trayPadding(availableWidth: width)
    let size = CallLayoutPolicy.controlSize(availableWidth: width, controlCount: 5)
    let spacing = CallLayoutPolicy.controlSpacing(availableWidth: width, controlCount: 5)
    check(5 * size + 4 * spacing <= tray - 2 * padding, "5 controls fit inside tray at \(Int(width))pt")
}

for width: CGFloat in [320, 360, 400, 480, 800] {
    check(CallLayoutPolicy.showCallCaptions(availableWidth: width, controlCount: 5), "captions show at \(Int(width))pt")
}

let pv = CallLayoutPolicy.localPreviewSize(availableWidth: 600)
expect(pv.width, CallLayoutPolicy.previewMax, "preview width capped")
expect(pv.height, pv.width * CallLayoutPolicy.previewAspect, "preview keeps 4:3 aspect")
let pvNarrow = CallLayoutPolicy.localPreviewSize(availableWidth: 320)
expect(pvNarrow.width, CallLayoutPolicy.previewMin, "preview width floored")
check(CallLayoutPolicy.callNameMaxLines == 2, "caller name truncates to two lines")

do {
    var early = CallReducerState()
    (early, _) = CallStateReducer.reduce(early, .createSucceeded(outgoingInfo()))
    (early, _) = CallStateReducer.reduce(early, .toggleMute)
    (early, _) = CallStateReducer.reduce(early, .setAudioRoute(AudioRoute(kind: .speaker, label: "Speaker")))
    expect(early.audioControls?.isMuted, true, "ringing mute is available")
    expect(early.audioControls?.audioRoute, .speaker, "ringing speaker is available")
    (early, _) = CallStateReducer.reduce(early, .remoteAccepted(callId: callId))
    (early, _) = CallStateReducer.reduce(early, .engineConnectionState(callId: callId, state: .connected))
    expect(early.audioControls?.isMuted, true, "answer preserves mute")
    expect(early.audioControls?.audioRoute, .speaker, "answer preserves speaker")
    (early, _) = CallStateReducer.reduce(early, .engineConnectionState(callId: callId, state: .disconnected))
    (early, _) = CallStateReducer.reduce(early, .toggleMute)
    expect(early.audioControls?.isMuted, false, "reconnecting mute works")
    (early, _) = CallStateReducer.reduce(early, .remoteEnded(callId: callId, reason: "hangup"))
    expect(early.selectedAudioRoute, nil, "end clears chosen route")
    expect(early.pendingMuted, false, "end clears mute")
}

do {
    var pending = CallReducerState(ui: .connecting(outgoingInfo()), activeCallId: callId)
    let failed = CallStateReducer.reduce(pending, .mediaNegotiationFailed(callId: callId))
    expect(failed.0.ui, .ended(outgoingInfo(), reason: .failed), "initial media failure ends connecting")
    check(failed.1.contains(.engineDispose), "initial media failure disposes engine")
    let answer = CallStateReducer.reduce(pending, .remoteAnswer(callId: callId, sdp: "answer"))
    expect(CallStateReducer.reduce(answer.0, .remoteAnswer(callId: callId, sdp: "answer")).1, [], "duplicate initial answer ignored")
    pending.ui = .connected(ConnectedState(call: outgoingInfo()))
    let upgradeFailed = CallStateReducer.reduce(pending, .mediaNegotiationFailed(callId: callId))
    check(upgradeFailed.0.audioControls != nil, "upgrade failure preserves audio call")
    expect(upgradeFailed.0.audioControls?.notice, .videoFailed, "upgrade failure is visible")
    let stale = CallStateReducer.reduce(pending, .mediaNegotiationFailed(callId: "old-call"))
    expect(stale.0, pending, "old media failure cannot end new call")
    let facing = CallStateReducer.reduce(pending, .localCameraFacingChanged(false))
    expect(facing.0.audioControls?.localCameraIsFrontFacing, false, "rear camera facing reaches UI")
}

do {
    var reconnect = CallReducerState(ui: .reconnecting(outgoingInfo()), activeCallId: callId)
    reconnect.lastConnected = ConnectedState(call: outgoingInfo())
    let offer = CallStateReducer.reduce(reconnect, .restartIceOfferRequested(callId: callId))
    expect(offer.1, [.engineSetVideoNegotiationEnabled(false), .produceIceRestartOfferAndSend(callId: callId)], "ICE restart preserves audio negotiation intent before signaling offer")
    let answer = CallStateReducer.reduce(offer.0, .remoteAnswer(callId: callId, sdp: "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
    check(answer.1.contains(.engineSetRemoteDescription(callId: callId, type: "answer", sdp: "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")), "restart answer accepted while reconnecting")
    var audio = CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId)
    let renegotiated = CallStateReducer.reduce(audio, .remoteOffer(callId: callId, sdp: "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
    expect(renegotiated.0.audioControls?.videoMode, .audio, "audio ICE offer never upgrades video")
    let facing = CallStateReducer.reduce(audio, .localCameraFacingChanged(false))
    expect(facing.0.audioControls?.localCameraIsFrontFacing, false, "camera direction survives control state")
}

do {
    var reconnect = CallReducerState(ui: .reconnecting(outgoingInfo(.video)), activeCallId: callId)
    reconnect.lastConnected = ConnectedState(call: outgoingInfo(.video), videoMode: .video)
    let camera = CallStateReducer.reduce(reconnect, .setCameraEnabled(false))
    expect(camera.0.ui, reconnect.ui, "camera action does not pretend ICE is connected")
    expect(camera.0.audioControls?.localVideoEnabled, false, "camera off works during reconnect")
    check(camera.1.contains(.engineSetVideoEnabled(false)), "reconnect camera action reaches engine")
}

do {
    let ringing = CallUiState.outgoingRinging(outgoingInfo(.audio))
    check(CallRingbackPolicy.shouldPlay(ringing, audioActive: true), "ringback starts after CallKit activation")
    check(!CallRingbackPolicy.shouldPlay(ringing, audioActive: false), "ringback stops on audio deactivation")
    check(!CallRingbackPolicy.shouldPlay(.connecting(outgoingInfo(.audio)), audioActive: true), "ringback stops at answer")
    check(!CallRingbackPolicy.shouldPlay(.idle, audioActive: true), "ringback cannot play after teardown")
    check(!CallRingbackPolicy.shouldPlay(.incomingRinging(outgoingInfo(.audio)), audioActive: true), "incoming call never plays ringback")
    let wave = CallRingbackPolicy.waveData()
    expect(wave.count, 48044, "ringback WAV has complete 3 second PCM payload")
    check(wave.suffix(24000).allSatisfy { $0 == 0 }, "ringback cadence includes silence")
    check(wave.dropFirst(44).prefix(24000).contains { $0 != 0 }, "ringback tone is audible PCM")
}

// An accepted event can arrive before REST create returns the caller's id.
// Reconciliation must repair that ordering without fabricating connected media.
func acceptedRow(_ phase: String = "accepted", id: UUID = UUID(uuidString: callId)!,
                 caller: UUID = UUID(uuidString: callerId)!,
                 callee: UUID = UUID(uuidString: calleeId)!, reason: String? = nil) -> CallDTO {
    CallDTO(id: id, callerUserId: caller, calleeUserId: callee, mediaKind: .audio,
        state: phase, endReason: reason, acceptedDeviceId: phase == "ringing" ? nil : UUID(),
        createdAt: Date(), acceptedAt: phase == "ringing" ? nil : Date(),
        connectedAt: phase == "connected" ? Date() : nil, endedAt: phase == "ended" ? Date() : nil)
}
do {
    let early = CallStateReducer.reduce(CallReducerState(), .remoteAccepted(callId: callId))
    expect(early.0.ui, .idle, "uncorrelated accepted before create does not fabricate a call")
    let dialing = CallStateReducer.reduce(early.0, .startCall(outgoingInfo())).0
    for phase in ["accepted", "connecting", "connected"] {
        let fixed = CallStateReducer.reduce(dialing, .reconnectReconcileSucceeded(acceptedRow(phase)))
        expect(fixed.0.ui, .connecting(outgoingInfo()), "server \(phase) repairs a missed accepted event into negotiation")
        expect(fixed.1.filter { $0 == .engineInitialize(mediaKind: .audio) }.count, 1, "recovery initializes one engine for \(phase)")
        expect(fixed.1.filter { $0 == .produceOfferAndSend(callId: callId) }.count, 1, "recovery produces one SDP offer for \(phase)")
        let wsEcho = CallStateReducer.reduce(fixed.0, .remoteAccepted(callId: callId))
        check(wsEcho.1.isEmpty && wsEcho.0 == fixed.0, "late accepted WS echo cannot repeat REST recovery for \(phase)")
        let restEcho = CallStateReducer.reduce(fixed.0, .reconnectReconcileSucceeded(acceptedRow(phase)))
        check(restEcho.1.isEmpty && restEcho.0 == fixed.0, "duplicate REST recovery cannot repeat negotiation for \(phase)")
    }
    let wsFirst = CallStateReducer.reduce(dialing, .remoteAccepted(callId: callId))
    let restSecond = CallStateReducer.reduce(wsFirst.0, .reconnectReconcileSucceeded(acceptedRow()))
    check(restSecond.1.isEmpty && restSecond.0 == wsFirst.0, "normal WS acceptance followed by REST never emits a second offer")
    let stillRinging = CallStateReducer.reduce(dialing, .reconnectReconcileSucceeded(acceptedRow("ringing")))
    check(stillRinging.0 == dialing && stillRinging.1.isEmpty, "unanswered server ringing is not auto-accepted")
    let foreignID = CallStateReducer.reduce(dialing, .reconnectReconcileSucceeded(acceptedRow(id: UUID())))
    check(foreignID.0 == dialing && foreignID.1.isEmpty, "a previous call's accepted row cannot affect this call")
    let foreignPeer = CallStateReducer.reduce(dialing, .reconnectReconcileSucceeded(acceptedRow(callee: UUID())))
    check(foreignPeer.0 == dialing && foreignPeer.1.isEmpty, "a wrong peer accepted row cannot start an offer")
    let incoming = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
    let noAutoAnswer = CallStateReducer.reduce(incoming, .reconnectReconcileSucceeded(acceptedRow()))
    check(noAutoAnswer.0 == incoming && noAutoAnswer.1.isEmpty, "incoming ringing is never answered by caller reconciliation")
    let ended = CallStateReducer.reduce(dialing, .remoteEnded(callId: callId, reason: "cancelled"))
    let lateAfterCancel = CallStateReducer.reduce(ended.0, .reconnectReconcileSucceeded(acceptedRow()))
    check(lateAfterCancel.0 == ended.0 && lateAfterCancel.1.isEmpty, "cancelled call is never resurrected by a delayed accepted row")
    let endedOnServer = CallStateReducer.reduce(dialing, .reconnectReconcileSucceeded(acceptedRow("ended", reason: "cancelled")))
    expect(endedOnServer.0.ui, .ended(outgoingInfo(), reason: .cancelled), "authoritative terminal wins over missed acceptance")
    check(!endedOnServer.1.contains(.produceOfferAndSend(callId: callId)), "terminal reconciliation never negotiates")
    let unauthenticated = CallReconciliation.matchesParticipants(call: outgoingInfo(), server: acceptedRow(), owner: nil)
    check(!unauthenticated, "reconciliation requires an authenticated owner")
    check(CallReconciliation.matchesParticipants(call: outgoingInfo(), server: acceptedRow(), owner: UUID(uuidString: callerId)),
          "caller row must match authenticated owner and actual callee")
    check(!CallReconciliation.matchesParticipants(call: outgoingInfo(), server: acceptedRow(), owner: UUID()),
          "changed account cannot consume the previous owner's call row")
    check(!CallReconciliation.matchesParticipants(call: outgoingInfo(), server: acceptedRow(caller: UUID()), owner: UUID(uuidString: callerId)),
          "a foreign caller is rejected even with the current call UUID")
    check(!CallReconciliation.matchesParticipants(call: outgoingInfo(), server: acceptedRow(callee: UUID()), owner: UUID(uuidString: callerId)),
          "a foreign recipient is rejected even with the current call UUID")
    check(CallReconciliation.matchesParticipants(call: incomingInfo(), server: acceptedRow(), owner: UUID(uuidString: calleeId)),
          "callee reconciliation uses the opposite authenticated participant mapping")
    check(!CallReconciliation.matchesParticipants(call: incomingInfo(), server: acceptedRow(), owner: UUID(uuidString: callerId)),
          "incoming reconciliation cannot borrow the caller account")
}

// The OS route is authoritative: a native CallKit choice or a newly connected
// headset must not be undone by replaying an older app speaker preference.
do {
    let speaker = AudioRoute(kind: .speaker, label: "Speaker")
    for phase in ["ringing", "connecting", "connected", "reconnecting"] {
        var original = CallReducerState(activeCallId: callId)
        switch phase {
        case "ringing": original.ui = .outgoingRinging(outgoingInfo())
        case "connecting": original.ui = .connecting(outgoingInfo())
        case "connected": original.ui = .connected(ConnectedState(call: outgoingInfo()))
        default:
            original.ui = .reconnecting(outgoingInfo())
            original.lastConnected = ConnectedState(call: outgoingInfo())
        }
        let chosen = CallStateReducer.reduce(original, .setAudioRoute(speaker)).0
        expect(chosen.selectedAudioRoute?.kind, .speaker, "explicit app speaker is recorded in \(phase)")
        for actual: AudioRouteKind in [.earpiece, .wiredHeadset, .bluetooth] {
            let routed = CallStateReducer.reduce(chosen, .engineAudioRouteChanged(AudioRoute(kind: actual, label: "OS")))
            expect(routed.0.audioControls?.audioRoute, actual, "OS \(actual) is published in \(phase)")
            check(routed.0.audioControls?.isSpeakerOn == false, "speaker indicator follows OS \(actual) in \(phase)")
            check(routed.0.selectedAudioRoute == nil && routed.1.isEmpty,
                  "OS \(actual) drops stale app speaker without fighting route in \(phase)")
        }
        let same = CallStateReducer.reduce(chosen, .engineAudioRouteChanged(speaker))
        expect(same.0.selectedAudioRoute?.kind, .speaker, "matching speaker confirmation preserves current app choice in \(phase)")
        let ended = CallStateReducer.reduce(chosen, .remoteEnded(callId: callId, reason: "hangup"))
        check(ended.0.selectedAudioRoute == nil && ended.0.audioControls == nil,
              "call end releases route intent in \(phase)")
    }
    for kind: AudioRouteKind in [.earpiece, .speaker, .bluetooth, .wiredHeadset] {
        var c = ConnectedState(call: outgoingInfo(.video), videoMode: .video)
        c.audioRoute = kind; c.isSpeakerOn = kind == .speaker
        let route = AudioRoute(kind: kind, label: "Chosen")
        var s = CallReducerState(ui: .connected(c), activeCallId: callId)
        s.observedAudioRoute = kind; s.selectedAudioRoute = route
        for event: CallEvent in [.setCameraEnabled(true), .setCameraEnabled(false), .localVideoFailed] {
            let camera = CallStateReducer.reduce(s, event)
            expect(camera.0.audioControls?.audioRoute, kind, "camera event keeps \(kind)")
            expect(camera.0.selectedAudioRoute, route, "camera event keeps explicit route intent \(kind)")
            check(!camera.1.contains(where: { effect in
                if case .engineSetSpeaker = effect { return true }
                if case .engineSetAudioRoute = effect { return true }
                return false
            }), "camera event never requests a speaker switch for \(kind)")
        }
        c.videoMode = .audio
        s.ui = .connected(c)
        let upgrade = CallStateReducer.reduce(s, .requestVideoUpgrade)
        expect(upgrade.0.audioControls?.audioRoute, kind, "audio-to-video upgrade keeps \(kind)")
        check(!upgrade.1.contains(where: { if case .engineSetSpeaker = $0 { return true }; if case .engineSetAudioRoute = $0 { return true }; return false }),
              "audio-to-video upgrade never selects an output for \(kind)")
    }
}

// Exercise the production reducer and the production per-call route owner as
// the controller does, including synchronous engine-route confirmations.
struct AutomaticAudioScenario {
    var state: CallReducerState
    var policy = CallAutomaticSpeakerPolicy()
    /// What the policy said while the scenario was set up (a video call engages at once).
    var initial: Bool?
    /// What AVAudioSession reports right now (the engine's `currentAudioRoute()`).
    var osRoute: AudioRoute

    init(route: AudioRouteKind = .earpiece, camera: Bool = false, media: CallMediaKind = .video) {
        var c = ConnectedState(call: outgoingInfo(media), videoMode: .video)
        c.audioRoute = route; c.isSpeakerOn = route == .speaker
        c.localVideoEnabled = camera
        c.localCameraState = camera ? .starting : .off
        state = CallReducerState(ui: .connected(c), activeCallId: callId)
        state.observedAudioRoute = route
        osRoute = AudioRoute(kind: route, label: "OS")
        initial = policy.update(event: .engineAudioRouteChanged(AudioRoute(kind: route, label: "OS")), after: state)
    }

    /// A call that has not reported any route yet: a fresh controller state, as
    /// `CallController` builds it for a new incoming or outgoing call.
    init(unseeded ui: CallUiState, osRoute: AudioRoute) {
        state = CallReducerState(ui: ui, activeCallId: ui.callInfo?.callId)
        self.osRoute = osRoute
    }

    /// The production controller step (`CallReduction`), with this scenario's OS route.
    mutating func send(_ event: CallEvent) -> [CallEffect] {
        let route = osRoute
        let reduced = CallReduction.apply(state, event, speaker: &policy, currentRoute: { route })
        state = reduced.0
        return reduced.1
    }

    /// An `AVAudioSession.routeChangeNotification` as the controller forwards it.
    mutating func routeChanged(to route: AudioRoute, userInitiated: Bool = false) -> [CallEffect] {
        osRoute = route
        return send(.engineAudioRouteChanged(route, userInitiated: userInitiated))
    }
}

let airPods = AudioRoute(kind: .bluetooth, label: "AirPods", uid: "40:4E:36:12:34:56-tsco")
let wired = AudioRoute(kind: .wiredHeadset, label: "Headphones", uid: "Wired Headphones")
let receiver = AudioRoute(kind: .earpiece, label: "iPhone")
let loudspeaker = AudioRoute(kind: .speaker, label: "Speaker")

// A call placed or answered as video is hands-free from the start; the own
// camera going off, failing or never starting does not pull audio back to the ear.
do {
    var video = AutomaticAudioScenario(camera: true)
    expect(video.initial, true, "a video call selects the speaker before any camera frame")
    check(video.policy.ownsSpeaker, "the automatic speaker belongs to this video call")
    check(video.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "the first camera frame does not repeat the speaker override")
    _ = video.send(.engineAudioRouteChanged(AudioRoute(kind: .speaker, label: "Speaker"), userInitiated: true))
    check(video.policy.ownsSpeaker && !video.policy.manualRouteChosen, "our speaker confirmation is not mistaken for a manual choice")
    let off = video.send(.setCameraEnabled(false))
    check(!off.contains(.engineSetSpeaker(false)), "turning the own camera off keeps the speaker")
    check(video.policy.ownsSpeaker, "camera off keeps automatic ownership for the video call")

    var failed = AutomaticAudioScenario(camera: true)
    _ = failed.send(.engineLocalFirstFrame(callId: callId))
    check(!failed.send(.localVideoFailed).contains(.engineSetSpeaker(false)), "a camera failure keeps the speaker of a video call")
    var cameraless = AutomaticAudioScenario(camera: false)
    expect(cameraless.initial, true, "a video call with the camera off (watching the peer) still uses the speaker")
    check(!cameraless.send(.remoteVideoState(callId: callId, enabled: false)).contains(.engineSetSpeaker(false)),
          "the peer's camera going off keeps the speaker")

    for kind: AudioRouteKind in [.earpiece, .speaker, .bluetooth, .wiredHeadset] {
        var manual = AutomaticAudioScenario(camera: true)
        _ = manual.send(.setAudioRoute(AudioRoute(kind: kind, label: "Chosen")))
        check(manual.policy.manualRouteChosen && !manual.policy.ownsSpeaker, "manual \(kind) takes over the route")
        check(manual.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "manual \(kind) wins over a ready camera")
        check(!manual.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "camera off preserves manual \(kind)")
        check(!manual.policy.restoreSpeakerIfNeeded(in: manual.state), "resume never overrides manual \(kind)")
    }
    for kind: AudioRouteKind in [.bluetooth, .wiredHeadset] {
        var headset = AutomaticAudioScenario(route: kind, camera: true)
        check(headset.initial == nil && !headset.policy.ownsSpeaker, "a video call keeps connected \(kind)")
        check(headset.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "ready video preserves connected \(kind)")
        var connectedLater = AutomaticAudioScenario(camera: true)
        _ = connectedLater.send(.engineAudioRouteChanged(AudioRoute(kind: kind, label: "Headset")))
        check(!connectedLater.policy.ownsSpeaker, "new \(kind) cancels automatic route ownership")
        check(!connectedLater.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "camera off cannot pull audio out of \(kind)")
        expect(connectedLater.send(.engineAudioRouteChanged(AudioRoute(kind: .earpiece, label: "iPhone"))), [.engineSetSpeaker(true)],
               "removing \(kind) during a video call returns to the speaker")
    }

    // An audio call upgraded to video: the speaker waits for this user's camera.
    var upgraded = AutomaticAudioScenario(camera: true, media: .audio)
    check(upgraded.initial == nil && !upgraded.policy.ownsSpeaker, "an upgraded call does not route audio before its camera runs")
    expect(upgraded.send(.engineLocalFirstFrame(callId: callId)), [.engineSetSpeaker(true)], "the own camera running selects the speaker once")
    check(!upgraded.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "upgraded call: camera off keeps the speaker")
    var watching = AutomaticAudioScenario(media: .audio)
    check(!watching.send(.remoteVideoState(callId: callId, enabled: true)).contains(.engineSetSpeaker(true)),
          "the peer's video alone cannot make an upgraded call loud")
    check(watching.send(.engineRemoteFirstFrame(callId: callId)).isEmpty && !watching.policy.ownsSpeaker,
          "the peer's first frame cannot make an upgraded call loud")

    var resume = AutomaticAudioScenario(camera: true)
    _ = resume.send(.engineAudioRouteChanged(AudioRoute(kind: .earpiece, label: "Interrupted")))
    check(resume.policy.restoreSpeakerIfNeeded(in: resume.state), "reactivation restores a still-owned automatic speaker")
    _ = resume.send(.setCameraEnabled(false))
    check(resume.policy.restoreSpeakerIfNeeded(in: resume.state), "reactivation restores the speaker with the camera off too")
    _ = resume.send(.engineAudioRouteChanged(AudioRoute(kind: .earpiece, label: "CallKit"), userInitiated: true))
    check(resume.policy.manualRouteChosen && !resume.policy.restoreSpeakerIfNeeded(in: resume.state),
          "native CallKit speaker-off wins over later reactivation")
    var ended = AutomaticAudioScenario(camera: true)
    check(ended.send(.remoteEnded(callId: callId, reason: "cancelled")).contains(.engineSetSpeaker(false)), "call termination releases automatic output")
    check(ended.send(.engineLocalFirstFrame(callId: callId)).isEmpty && !ended.policy.ownsSpeaker, "late frame cannot resurrect a cancelled call route")
    var foreign = AutomaticAudioScenario(media: .audio)
    var foreignControls = foreign.state.audioControls!
    foreignControls.localVideoEnabled = true; foreignControls.localCameraState = .starting
    foreign.state.ui = .connected(foreignControls)
    check(foreign.send(.engineLocalFirstFrame(callId: UUID().uuidString)).isEmpty && !foreign.policy.ownsSpeaker,
          "another call's first frame cannot route this call")
    _ = foreign.send(.engineLocalFirstFrame(callId: callId))
    check(foreign.policy.reset(), "account shutdown releases owned automatic route")
    check(!foreign.policy.reset() && foreign.policy.callId == nil, "account shutdown is idempotent and clears identity")
    var next = AutomaticAudioScenario(camera: true)
    _ = next.send(.setAudioRoute(AudioRoute(kind: .earpiece, label: "Manual")))
    _ = next.send(.remoteEnded(callId: callId, reason: "hangup"))
    let c = ConnectedState(call: CallInfo(callId: "new-call", remoteUserId: calleeId, remoteDisplayName: "Next", mediaKind: .video, direction: .outgoing), videoMode: .video)
    next.state = CallReducerState(ui: .connected(c), activeCallId: "new-call")
    expect(next.send(.engineLocalFirstFrame(callId: "new-call")), [.engineSetSpeaker(true)], "manual choice does not leak into a different call")

    // Placed as video: hands-free from the outgoing ring (the ringback too),
    // like FaceTime. Placed as audio: the tone stays at the ear.
    for media: CallMediaKind in [.video, .audio] {
        var placed = AutomaticAudioScenario(unseeded: .idle, osRoute: receiver)
        let ringing = placed.send(.startCall(outgoingInfo(media)))
        expect(ringing.contains(.engineSetSpeaker(true)), media == .video, "ringing \(media) call: speaker \(media == .video)")
        expect(placed.policy.ownsSpeaker, media == .video, "ringing \(media) call: automatic ownership")
        if media == .video {
            check(placed.routeChanged(to: loudspeaker, userInitiated: true).isEmpty && !placed.policy.manualRouteChosen,
                  "the speaker confirmation of a ringing video call is not a manual choice")
        }
        let connecting = placed.send(.remoteAccepted(callId: callId))
        check(!connecting.contains(.engineSetSpeaker(true)) && !connecting.contains(.engineSetSpeaker(false)),
              "connecting \(media) does not toggle the route again")
        let connected = placed.send(.engineConnectionState(callId: callId, state: .connected))
        check(!connected.contains(.engineSetSpeaker(true)) && !connected.contains(.engineSetSpeaker(false)),
              "connect does not toggle the route again for \(media)")
        expect(placed.state.audioControls?.audioRoute, media == .video ? .speaker : .earpiece, "connected \(media) shows its route")
    }
    var answered = AutomaticAudioScenario(unseeded: .incomingRinging(incomingInfo(.video)), osRoute: receiver)
    expect(answered.send(.acceptSucceeded(incomingInfo(.video))).contains(.engineSetSpeaker(true)), true,
           "answering a video call takes the speaker at once")
    var elsewhere = AutomaticAudioScenario(camera: true)
    check(elsewhere.send(.remoteEnded(callId: callId, reason: "answered_elsewhere")).contains(.engineSetSpeaker(false)),
          "answered-elsewhere terminal releases automatic route")
}

// Answering on a headset, from a fresh (unseeded) call state: no route has been
// reported yet, or it was reported only while ringing in (no controls then).
do {
    for headset in [airPods, wired] {
        for media: CallMediaKind in [.video, .audio] {
            // Reported while ringing: kept although nothing is on screen.
            var reported = AutomaticAudioScenario(unseeded: .incomingRinging(incomingInfo(media)), osRoute: receiver)
            check(reported.routeChanged(to: headset).isEmpty, "a route report while ringing in changes no output (\(media))")
            expect(reported.state.observedAudioRoute, headset.kind, "the \(headset.kind) reported while ringing in is kept (\(media))")
            let answer = reported.send(.acceptSucceeded(incomingInfo(media)))
            check(!answer.contains(.engineSetSpeaker(true)) && !reported.policy.ownsSpeaker,
                  "answering a \(media) call on \(headset.kind) reported while ringing never forces the speaker")
            expect(reported.state.audioControls?.audioRoute, headset.kind, "connecting \(media) shows \(headset.kind)")
            check(!CallProximityPolicy.wantsProximity(state: reported.state.ui, controls: reported.state.audioControls),
                  "answering a \(media) call on \(headset.kind) never darkens the screen")

            // Never reported: the answer itself reads the OS route first.
            var silent = AutomaticAudioScenario(unseeded: .incomingRinging(incomingInfo(media)), osRoute: headset)
            expect(silent.state.observedAudioRoute, .earpiece, "an unseeded call starts at the earpiece default")
            let unseededAnswer = silent.send(.acceptSucceeded(incomingInfo(media)))
            check(!unseededAnswer.contains(.engineSetSpeaker(true)) && !silent.policy.ownsSpeaker,
                  "answering a \(media) call on \(headset.kind) with no report yet never forces the speaker")
            expect(silent.state.audioControls?.audioRoute, headset.kind, "the answer reads the \(headset.kind) route (\(media))")
            check(!CallProximityPolicy.wantsProximity(state: silent.state.ui, controls: silent.state.audioControls),
                  "proximity stays off for a \(media) call answered on \(headset.kind) before any report")
            let connected = silent.send(.engineConnectionState(callId: callId, state: .connected))
            check(!connected.contains(.engineSetSpeaker(true)), "connect keeps \(headset.kind) for \(media)")

            // Placed on a headset: no speaker for the ringback either.
            var placed = AutomaticAudioScenario(unseeded: .idle, osRoute: headset)
            let ringing = placed.send(.startCall(outgoingInfo(media)))
            check(!ringing.contains(.engineSetSpeaker(true)) && !placed.policy.ownsSpeaker,
                  "a \(media) call placed on \(headset.kind) keeps it for the ringback")
            check(!CallProximityPolicy.wantsProximity(state: placed.state.ui, controls: placed.state.audioControls),
                  "calling out on \(headset.kind) never darkens the screen (\(media))")
            check(!placed.send(.remoteAccepted(callId: callId)).contains(.engineSetSpeaker(true)),
                  "connecting a \(media) call placed on \(headset.kind) keeps it")
        }
    }
    // Without a headset an unseeded answer still behaves as designed.
    var plainVideo = AutomaticAudioScenario(unseeded: .incomingRinging(incomingInfo(.video)), osRoute: receiver)
    expect(plainVideo.send(.acceptSucceeded(incomingInfo(.video))), [.engineInitialize(mediaKind: .video), .engineSetSpeaker(true)],
           "a video call answered without a headset takes the speaker")
    var plainAudio = AutomaticAudioScenario(unseeded: .incomingRinging(incomingInfo(.audio)), osRoute: receiver)
    _ = plainAudio.send(.acceptSucceeded(incomingInfo(.audio)))
    check(CallProximityPolicy.wantsProximity(state: plainAudio.state.ui, controls: plainAudio.state.audioControls),
          "an audio call answered on the receiver uses proximity")
    // A headset that disappears during the ring of a video call: speaker after all.
    var unplugged = AutomaticAudioScenario(unseeded: .idle, osRoute: airPods)
    _ = unplugged.send(.startCall(outgoingInfo(.video)))
    expect(unplugged.routeChanged(to: receiver), [.engineSetSpeaker(true)], "losing the headset while ringing out takes the speaker")
    // The phase-change read happens only on a new phase, not per event.
    check(CallReduction.entersLivePhase(from: .idle, to: .outgoingRinging(outgoingInfo())), "ringing out is a new phase")
    check(CallReduction.entersLivePhase(from: .incomingRinging(incomingInfo()), to: .connecting(incomingInfo())), "an answer is a new phase")
    check(!CallReduction.entersLivePhase(from: .connected(ConnectedState(call: outgoingInfo())), to: .connected(ConnectedState(call: outgoingInfo(), isMuted: true))),
          "a change inside the connected phase reads no route")
    check(!CallReduction.entersLivePhase(from: .connected(ConnectedState(call: outgoingInfo())), to: .ended(outgoingInfo(), reason: .hangup)),
          "the end reads no route")
}

// Routes are identified by port uid: two headsets with one name are two
// devices, while the HFP and A2DP ports of one headset are the same device.
do {
    let other = AudioRoute(kind: .bluetooth, label: "AirPods", uid: "40:4E:36:AA:BB:CC-tsco")
    let sameDeviceA2DP = AudioRoute(kind: .bluetooth, label: "AirPods", uid: "40:4E:36:12:34:56-tacl")
    check(!airPods.isSameOutput(as: other), "two AirPods with the same name are different outputs")
    check(airPods.isSameOutput(as: sameDeviceA2DP), "the HFP and A2DP ports of one headset are one output")
    expect(AudioRoute.deviceID("40:4E:36:12:34:56-tsco"), "40:4E:36:12:34:56", "a Bluetooth port uid names its device")
    expect(AudioRoute.deviceID("Wired Headphones"), "Wired Headphones", "a uid without a profile suffix is the device")
    expect(AudioRoute.deviceID("USB-C Headset-1"), "USB-C Headset-1", "a dash outside a Bluetooth address is not a profile suffix")
    check(receiver.isSameOutput(as: AudioRoute(kind: .earpiece, label: "Receiver")), "the receiver is one output whatever its label")
    check(!receiver.isSameOutput(as: loudspeaker), "receiver and speaker differ")
    check(!airPods.isSameOutput(as: AudioRoute(kind: .bluetooth, label: "AirPods")), "a uid-less report never matches a uid by name")
    check(AudioRoute(kind: .bluetooth, label: "Car").isSameOutput(as: AudioRoute(kind: .bluetooth, label: "Car")),
          "two uid-less reports fall back to the name")

    // A system switch to another headset of the same kind drops the stale app pick.
    var c = ConnectedState(call: outgoingInfo())
    c.audioRoute = .bluetooth
    var picked = CallReducerState(ui: .connected(c), activeCallId: callId)
    picked.observedAudioRoute = .bluetooth
    picked = CallStateReducer.reduce(picked, .setAudioRoute(airPods)).0
    expect(picked.selectedAudioRoute, airPods, "the picked headset is recorded with its uid")
    let sameDevice = CallStateReducer.reduce(picked, .engineAudioRouteChanged(sameDeviceA2DP)).0
    expect(sameDevice.selectedAudioRoute, airPods, "the same headset on another profile keeps the pick")
    let otherHeadset = CallStateReducer.reduce(picked, .engineAudioRouteChanged(other)).0
    expect(otherHeadset.selectedAudioRoute, nil, "another headset with the same name drops the stale pick")
}

// The call screen's layout comes from the call: a video call is in the video
// layout from its first frame, and connecting changes nothing on screen.
do {
    func layout(_ state: CallUiState, controls: ConnectedState? = nil, starting: Bool? = nil, ended: Bool = false) -> Bool {
        CallPresentationPolicy.showsVideoLayout(state: state, controls: controls, startingVideo: starting, endedInVideo: ended)
    }
    check(layout(.idle, starting: true), "the instant Calling screen of a video call is the video layout")
    check(!layout(.idle, starting: false) && !layout(.idle), "an audio start or nothing is the audio layout")
    for media: CallMediaKind in [.video, .audio] {
        let video = media == .video
        expect(layout(.outgoingRinging(outgoingInfo(media))), video, "ringing out \(media)")
        expect(layout(.incomingRinging(incomingInfo(media))), video, "ringing in \(media)")
        expect(layout(.connecting(outgoingInfo(media))), video, "connecting \(media)")
    }
    check(layout(.connected(ConnectedState(call: outgoingInfo(.audio), videoMode: .video))), "an upgraded call is video once negotiated")
    check(!layout(.connected(ConnectedState(call: outgoingInfo(.video), videoMode: .audio))), "a video call switched to audio is audio")
    let kept = ConnectedState(call: outgoingInfo(.audio), videoMode: .video)
    check(layout(.reconnecting(outgoingInfo(.audio)), controls: kept), "reconnecting keeps the negotiated video layout")
    check(!layout(.reconnecting(outgoingInfo(.video)), controls: ConnectedState(call: outgoingInfo(.video), videoMode: .audio)),
          "reconnecting keeps the negotiated audio layout")
    check(layout(.ended(outgoingInfo(.audio), reason: .hangup), ended: true), "an ended call keeps the video layout it ended in")
    check(!layout(.ended(outgoingInfo(.video), reason: .hangup), ended: false), "an ended call keeps the audio layout it ended in")
    check(!layout(.failed(nil, reason: .generic)), "a failed start has its own screen")

    for media: CallMediaKind in [.video, .audio] {
        var call = CallReducerState()
        (call, _) = CallStateReducer.reduce(call, .startCall(outgoingInfo(media)))
        let ringing = call.audioControls
        expect(ringing?.videoMode, media == .video ? .video : .audio, "ringing out \(media): the controls carry the call's media")
        expect(ringing?.localVideoEnabled, media == .video, "ringing out \(media): the camera shows the call's intent")
        (call, _) = CallStateReducer.reduce(call, .remoteAccepted(callId: callId))
        if media == .video {
            (call, _) = CallStateReducer.reduce(call, .engineLocalFirstFrame(callId: callId))
            expect(call.audioControls?.localCameraState, .on, "the camera preview shows while connecting")
        }
        let connecting = call.audioControls
        (call, _) = CallStateReducer.reduce(call, .engineConnectionState(callId: callId, state: .connected))
        expect(call.audioControls, connecting, "connect changes nothing on screen but the phase (\(media))")
    }
    var failedCamera = CallReducerState(ui: .connecting(outgoingInfo(.video)), activeCallId: callId)
    failedCamera = CallStateReducer.reduce(failedCamera, .localVideoFailed).0
    expect(failedCamera.audioControls?.localCameraState, .off, "a camera that failed while connecting shows off, still in the video layout")
    expect(failedCamera.audioControls?.videoMode, .video, "a camera failure keeps the video layout")
    let early = CallStateReducer.reduce(CallReducerState(ui: .connecting(outgoingInfo(.video)), activeCallId: callId), .setCameraEnabled(false))
    check(early.1.contains(.engineSetVideoEnabled(false)) && early.0.audioControls?.localVideoEnabled == false,
          "the camera button works while connecting")
}

// B-036: crossing camera requests already carry consent from both users. They
// acknowledge each other without stopping capture or waiting for a new timeout.
do {
    for info in [outgoingInfo(), incomingInfo()] {
        var call = CallReducerState(ui: .connected(ConnectedState(call: info)), activeCallId: callId)
        call = CallStateReducer.reduce(call, .requestVideoUpgrade).0
        call = CallStateReducer.reduce(call, .engineLocalFirstFrame(callId: callId)).0
        let crossed = CallStateReducer.reduce(call, .remoteVideoUpgradeRequest(callId: callId))
        expect(crossed.0.audioControls?.videoUpgrade, .upgrading, "crossing upgrades immediately (\(info.direction))")
        check(crossed.0.audioControls?.localVideoEnabled == true && crossed.0.audioControls?.localCameraState == .on,
              "crossing preserves our live camera (\(info.direction))")
        check(crossed.1.contains(.sendVideoUpgradeAccept(callId: callId))
              && crossed.1.contains(.produceOfferAndSend(callId: callId))
              && crossed.1.contains(.cancelVideoUpgradeTimeout), "crossing acknowledges and offers without waiting")
        check(!crossed.1.contains(.engineStopLocalVideo) && !crossed.1.contains(.engineSetVideoEnabled(false)),
              "crossing never tears down capture")
        let lateTimeout = CallStateReducer.reduce(crossed.0, .videoUpgradeTimeout)
        check(lateTimeout.0 == crossed.0 && lateTimeout.1.isEmpty, "a queued old timeout cannot disable crossing cameras")
        let duplicate = CallStateReducer.reduce(crossed.0, .remoteVideoUpgradeRequest(callId: callId))
        check(duplicate.0 == crossed.0 && duplicate.1.isEmpty, "a crossing duplicate cannot make a second offer")
        var restarting = call
        restarting.renegotiating = true
        restarting.pendingOfferIsRestart = true
        let deferred = CallStateReducer.reduce(restarting, .remoteVideoUpgradeRequest(callId: callId))
        expect(deferred.0.deferredOfferTarget, .video, "a crossing serializes behind the in-flight restart")
        check(deferred.1.contains(.sendVideoUpgradeAccept(callId: callId))
              && deferred.1.contains(.cancelVideoUpgradeTimeout)
              && !deferred.1.contains(.produceOfferAndSend(callId: callId)), "a crossing keeps one offer in flight")
    }
}

// B-036 integration: connect two real reducers through their emitted signaling
// effects. Both users request before either receives the other's request.
do {
    for firstDelivery in [0, 1] {
        var peers = [
            CallReducerState(ui: .connected(ConnectedState(call: outgoingInfo())), activeCallId: callId),
            CallReducerState(ui: .connected(ConnectedState(call: incomingInfo())), activeCallId: callId)
        ]
        var wire: [(Int, CallEvent)] = []
        var versions = [0, 0]
        var offers = 0, stoppedCameras = 0
        func description(_ side: Int) -> String {
            versions[side] += 1
            return "v=0\r\no=- \(100 + side) \(versions[side]) IN IP4 0.0.0.0\r\na=x-unyri-perfect-negotiation\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\n"
        }
        func deliver(_ side: Int, _ event: CallEvent) {
            let reduced = CallStateReducer.reduce(peers[side], event)
            peers[side] = reduced.0
            for effect in reduced.1 {
                switch effect {
                case .sendVideoUpgradeRequest:
                    wire.append((1 - side, .remoteVideoUpgradeRequest(callId: callId)))
                case .sendVideoUpgradeAccept:
                    wire.append((1 - side, .remoteVideoUpgradeAccept(callId: callId)))
                case .produceOfferAndSend:
                    offers += 1
                    wire.append((1 - side, .remoteOffer(callId: callId, sdp: description(side))))
                case .produceAnswerAndSend:
                    wire.append((1 - side, .remoteAnswer(callId: callId, sdp: description(side))))
                    wire.append((side, .localAnswerSent(callId: callId)))
                case .engineStopLocalVideo, .engineSetVideoEnabled(false): stoppedCameras += 1
                default: break
                }
            }
        }
        deliver(firstDelivery, .requestVideoUpgrade)
        deliver(1 - firstDelivery, .requestVideoUpgrade)
        var steps = 0
        while !wire.isEmpty && steps < 64 {
            let message = wire.removeFirst()
            deliver(message.0, message.1)
            steps += 1
        }
        check(wire.isEmpty && steps < 64, "two crossing reducers settle in a bounded exchange")
        check(offers >= 2 && offers <= 3, "crossing glare needs at most three offers across both peers")
        check(stoppedCameras == 0, "crossing signaling never stops either camera")
        for peer in peers {
            expect(peer.audioControls?.videoMode, .video, "both crossing peers reach video")
            expect(peer.audioControls?.videoUpgrade, .idle, "both crossing requests settle")
            check(peer.audioControls?.localVideoEnabled == true && !peer.renegotiating,
                  "both cameras stay enabled with no unanswered offer")
        }
    }
}

// B-037: controls retain the user's choices before ICE exists and through connect.
do {
    var call = CallReducerState(ui: .outgoingRinging(outgoingInfo(.video)), activeCallId: callId)
    let speaker = AudioRoute(kind: .speaker, label: "Speaker")
    let muted = CallStateReducer.reduce(call, .toggleMute)
    check(muted.1.contains(.engineSetMuted(true)), "ringing mute reaches the engine")
    call = CallStateReducer.reduce(muted.0, .setAudioRoute(speaker)).0
    let cameraOff = CallStateReducer.reduce(call, .setCameraEnabled(false))
    check(cameraOff.1.contains(.engineSetVideoEnabled(false)), "ringing camera off reaches the engine")
    call = CallStateReducer.reduce(cameraOff.0, .remoteAccepted(callId: callId)).0
    expect(call.audioControls?.isMuted, true, "mute survives ringing to connecting")
    expect(call.selectedAudioRoute, speaker, "the selected output survives ringing to connecting")
    expect(call.audioControls?.localVideoEnabled, false, "camera off survives ringing to connecting")
    let strayFrame = CallStateReducer.reduce(call, .engineLocalFirstFrame(callId: callId))
    check(!strayFrame.0.localVideoFrameReady, "a late frame cannot restore an explicitly disabled camera")
    let connected = CallStateReducer.reduce(call, .engineConnectionState(callId: callId, state: .connected))
    expect(connected.0.audioControls?.isMuted, true, "mute survives connecting to connected")
    expect(connected.0.audioControls?.audioRoute, .speaker, "the selected output survives connect")
    expect(connected.0.audioControls?.localVideoEnabled, false, "camera stays off on connect")
    check(connected.1.contains(.sendVideoState(callId: callId, enabled: false)), "connect announces the retained camera-off choice")
    let enabled = CallStateReducer.reduce(call, .setCameraEnabled(true))
    expect(enabled.0.audioControls?.localVideoEnabled, true, "camera can turn back on while connecting")
    let cameraOnConnect = CallStateReducer.reduce(enabled.0, .engineConnectionState(callId: callId, state: .connected))
    expect(cameraOnConnect.0.audioControls?.localVideoEnabled, true, "the last preconnect camera choice wins")
    let audioRing = CallReducerState(ui: .outgoingRinging(outgoingInfo()), activeCallId: callId)
    check(CallStateReducer.reduce(audioRing, .requestVideoUpgrade).1.isEmpty,
          "audio upgrade is unavailable while ringing and its UI control must stay hidden")
}

// A notice raised while reconnecting lives in the kept controls (the source the
// controller's expiry timer watches), and expires there.
do {
    var c = ConnectedState(call: outgoingInfo(.video), videoMode: .video)
    c.localVideoEnabled = true; c.localCameraState = .on
    var reconnecting = CallReducerState(ui: .reconnecting(outgoingInfo(.video)), activeCallId: callId)
    reconnecting.lastConnected = c
    let failed = CallStateReducer.reduce(reconnecting, .localVideoFailed).0
    expect(failed.ui, reconnecting.ui, "a camera failure while reconnecting leaves the phase alone")
    expect(failed.audioControls?.notice, .cameraFailed, "the camera notice shows while reconnecting")
    expect(CallStateReducer.reduce(failed, .noticeExpired(.cameraFailed)).0.audioControls?.notice, nil,
           "the reconnecting notice expires")
}

do {
    let audioSDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    let rejectedVideoSDP = audioSDP + "m=video 0 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n"
    let receiveOnlySDP = audioSDP + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"
    for reconnect in [false, true] {
        for sdp in [audioSDP, rejectedVideoSDP] {
            var audio = AutomaticAudioScenario(camera: true)
            _ = audio.send(.engineLocalFirstFrame(callId: callId))
            if reconnect {
                audio.state.lastConnected = audio.state.audioControls
                audio.state.ui = .reconnecting(outgoingInfo(.video))
            }
            let effects = audio.send(.remoteOffer(callId: callId, sdp: sdp))
            check(audio.state.audioControls?.localVideoEnabled == false && audio.state.audioControls?.localCameraState == .off,
                  "full SDP downgrade clears own camera in \(reconnect ? "reconnect" : "connected")")
            check(effects.contains(.engineStopLocalVideo) && effects.contains(.engineSetSpeaker(false)),
                  "full SDP downgrade stops capture and releases automatic speaker")
            check(!audio.state.localVideoFrameReady && !audio.policy.ownsSpeaker, "full SDP downgrade clears capture readiness and auto ownership")
        }
        for event: CallEvent in [.remoteOffer(callId: callId, sdp: receiveOnlySDP), .remoteVideoState(callId: callId, enabled: false)] {
            var video = AutomaticAudioScenario(camera: true)
            _ = video.send(.engineLocalFirstFrame(callId: callId))
            if reconnect {
                video.state.lastConnected = video.state.audioControls
                video.state.ui = .reconnecting(outgoingInfo(.video))
            }
            let effects = video.send(event)
            check(video.state.audioControls?.localVideoEnabled == true && video.policy.ownsSpeaker,
                  "remote video hiding/recvonly preserves own camera and automatic speaker")
            check(!effects.contains(.engineStopLocalVideo) && !effects.contains(.engineSetSpeaker(false)),
                  "remote video hiding/recvonly does not stop own capture")
        }
    }
}

do {
    let device = UUID(), otherDevice = UUID()
    let caller = UUID(uuidString: callerId)!, callee = UUID(uuidString: calleeId)!
    func row(_ accepted: UUID?, phase: String = "accepted") -> CallDTO {
        CallDTO(id: UUID(uuidString: callId)!, callerUserId: caller, calleeUserId: callee,
                mediaKind: .audio, state: phase, endReason: nil, acceptedDeviceId: accepted,
                createdAt: Date(), acceptedAt: Date(), connectedAt: nil, endedAt: nil)
    }
    for phase in ["accepted", "connecting", "connected"] {
        let ringing = CallReducerState(ui: .incomingRinging(incomingInfo()), activeCallId: callId)
        let other = CallStateReducer.reduce(ringing, .reconnectReconcileSucceeded(row(otherDevice, phase: phase), localDeviceId: device))
        expect(other.0.ui, .ended(incomingInfo(), reason: .answeredElsewhere), "REST \(phase) recovers missing answered_elsewhere")
        check(other.1.contains(.engineDispose) && other.1.contains(.dismissIncomingPush(callId: callId)), "REST elsewhere clears media and incoming presentation")
        for accepted: UUID? in [device, nil] {
            let own = CallStateReducer.reduce(ringing, .reconnectReconcileSucceeded(row(accepted, phase: phase), localDeviceId: device))
            check(own.0 == ringing && own.1.isEmpty, "passive own/unknown accepted row never kills or auto-answers explicit answer")
        }
        let unknown = CallStateReducer.reduce(ringing, .reconnectReconcileSucceeded(row(otherDevice, phase: phase)))
        check(unknown.0 == ringing && unknown.1.isEmpty, "missing local device ID cannot prove elsewhere")
    }
    for ui: CallUiState in [.connecting(incomingInfo()), .connected(ConnectedState(call: incomingInfo())), .reconnecting(incomingInfo())] {
        var current = CallReducerState(ui: ui, activeCallId: callId)
        current.lastConnected = ConnectedState(call: incomingInfo())
        let lost = CallStateReducer.reduce(current, .reconnectReconcileSucceeded(row(otherDevice), localDeviceId: device))
        expect(lost.0.ui, .ended(incomingInfo(), reason: .answeredElsewhere), "foreign accepting device cannot restore this device's media")
    }
    expect(CallReconciliation.recoverExplicitAnswer(call: incomingInfo(), server: row(device), owner: callee, localDeviceId: device),
           .acceptedHere, "lost successful HTTP answer can recover only its own accepting device")
    expect(CallReconciliation.recoverExplicitAnswer(call: incomingInfo(), server: row(otherDevice), owner: callee, localDeviceId: device),
           .endLocally(reason: "answered_elsewhere"), "HTTP conflict recovers another accepting device")
    for accepted: UUID? in [device, nil] {
        expect(CallReconciliation.recoverExplicitAnswer(call: incomingInfo(), server: row(accepted), owner: callee, localDeviceId: nil),
               .unresolved, "unknown local UUID never authorizes answer recovery")
    }
    expect(CallReconciliation.recoverExplicitAnswer(call: incomingInfo(), server: row(nil), owner: callee, localDeviceId: device),
           .unresolved, "unknown accepted UUID never authorizes answer recovery")
    expect(CallReconciliation.recoverExplicitAnswer(call: incomingInfo(), server: row(device), owner: UUID(), localDeviceId: device),
           .unresolved, "changed owner cannot recover a previous account's answer")
    expect(CallReconciliation.recoverExplicitAnswer(call: outgoingInfo(), server: row(device), owner: caller, localDeviceId: device),
           .unresolved, "callee device ownership never changes outgoing recovery semantics")
}

// Incoming WS/foreground recovery must never turn our own caller row into an
// incoming system call. UUID parsing also rejects malformed identity hints.
do {
    let owner = UUID(), peer = UUID(), id = UUID().uuidString
    check(CallIncomingRecoveryPolicy.validIncomingIdentity(callId: id, callerUserId: peer.uuidString, owner: owner), "another valid caller can ring this account")
    check(!CallIncomingRecoveryPolicy.validIncomingIdentity(callId: id, callerUserId: owner.uuidString.uppercased(), owner: owner), "self caller cannot appear as an incoming call")
    check(!CallIncomingRecoveryPolicy.validIncomingIdentity(callId: id, callerUserId: peer.uuidString, owner: nil), "missing account identity cannot display an incoming call")
    check(!CallIncomingRecoveryPolicy.validIncomingIdentity(callId: "invalid", callerUserId: peer.uuidString, owner: owner), "malformed call ID cannot enter the incoming UI")
    check(!CallIncomingRecoveryPolicy.validIncomingIdentity(callId: id, callerUserId: "invalid", owner: owner), "malformed caller identity cannot enter the incoming UI")
    let selfInfo = CallInfo(callId: id, remoteUserId: owner.uuidString, remoteDisplayName: "Self", mediaKind: .audio, direction: .incoming)
    let selfRow = CallDTO(id: UUID(uuidString: id)!, callerUserId: owner, calleeUserId: owner, mediaKind: .audio,
                          state: "ringing", endReason: nil, acceptedDeviceId: nil, createdAt: Date(), acceptedAt: nil, connectedAt: nil, endedAt: nil)
    check(!CallReconciliation.matchesParticipants(call: selfInfo, server: selfRow, owner: owner), "self peer cannot be restored by authoritative reconciliation")
}

do {
    let directionCases: [(String, String, Bool)] = [
        ("media_inactive_nonzero", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n", false),
        ("session_inactive_inherited", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", false),
        ("zero_port_overrides_sendrecv", "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\nm=video 0 UDP/TLS/RTP/SAVPF 96\na=sendrecv\n", false),
        ("media_sendrecv_overrides_session_inactive", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\n", true),
        ("media_recvonly_overrides_session_inactive", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n", true),
        ("session_sendonly_inherited", "v=0\r\na=sendonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
        ("session_recvonly_inherited", "v=0\r\na=recvonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
        ("default_sendrecv", "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\nm=video 9 UDP/TLS/RTP/SAVPF 96\n", true),
        ("audio_inactive_does_not_leak", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=inactive\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
        ("mixed_video_any_active_preserves_video", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n", true),
        ("all_video_rejected_or_inactive", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 0 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n", false),
        ("no_media_compatibility_preserves_current", "v=0 compatibility-offer", true)
    ]
    for (name, sdp, video) in directionCases {
        for reconnect in [false, true] {
            var scenario = AutomaticAudioScenario(camera: true)
            _ = scenario.send(.engineLocalFirstFrame(callId: callId))
            let before = scenario.state.audioControls
            if reconnect {
                scenario.state.lastConnected = before
                scenario.state.ui = .reconnecting(outgoingInfo(.video))
            }
            let effects = scenario.send(.remoteOffer(callId: callId, sdp: sdp))
            expect(scenario.state.audioControls?.videoMode, video ? .video : .audio, "\(name): effective direction")
            expect(scenario.state.audioControls?.localVideoEnabled, video, "\(name): own camera")
            expect(effects.contains(.engineStopLocalVideo), !video, "\(name): bounded camera teardown")
            check(effects.contains(.engineSetVideoNegotiationEnabled(video)), "\(name): matching native negotiation intent")
            expect(scenario.policy.ownsSpeaker, video, "\(name): automatic speaker ownership")
            expect(scenario.state.audioControls?.call.callId, before?.call.callId, "\(name): call identity")
            check(!effects.contains(.engineDispose), "\(name): audio call remains alive")
            if !video {
                check(!scenario.state.localVideoFrameReady, "\(name): old capture readiness cleared")
                _ = scenario.send(.engineLocalFirstFrame(callId: callId))
                check(!scenario.policy.ownsSpeaker, "\(name): late frame cannot restore speaker")
            }
        }
    }
    var scenario = AutomaticAudioScenario(camera: true)
    let cameraOff = scenario.send(.setCameraEnabled(false))
    check(!cameraOff.contains(.engineSetVideoNegotiationEnabled(false)), "Camera off is not a mutual audio-only intent")
    _ = scenario.send(.setCameraEnabled(true))
    let audio = scenario.send(.switchToAudio)
    check(audio.contains(.engineSetVideoNegotiationEnabled(false)), "Explicit full audio withdraws receiving as well as sending")
    let intent = audio.firstIndex(of: .engineSetVideoNegotiationEnabled(false))
    let offer = audio.firstIndex(of: .produceOfferAndSend(callId: callId))
    check(intent != nil && offer != nil && intent! < offer!, "Audio-only intent precedes native offer")
    var reconnect = scenario.state
    reconnect.lastConnected = reconnect.audioControls
    reconnect.ui = .reconnecting(outgoingInfo(.video))
    let restart = CallStateReducer.reduce(reconnect, .restartIceOfferRequested(callId: callId))
    expect(restart.0.renegotiationTarget, .audio, "ICE restart retains negotiated audio target")
    check(!restart.1.contains(.engineSetVideoNegotiationEnabled(true)), "ICE restart cannot restore receiving video")
    _ = scenario.send(.remoteAnswer(callId: callId, sdp: "v=0 audio-answer"))
    let upgrade = scenario.send(.requestVideoUpgrade)
    check(upgrade.contains(.engineSetVideoNegotiationEnabled(true)), "Later explicit upgrade restores video negotiation")
    let cancel = scenario.send(.cancelVideoUpgrade)
    check(!cancel.contains(.engineSetVideoNegotiationEnabled(false)), "Generic upgrade cleanup does not impose mutual audio-only intent")
}

// Notices are toasts: the reducer clears exactly the one that expired.
do {
    var c = ConnectedState(call: outgoingInfo())
    c.notice = .videoRequestDeclined
    let s = CallReducerState(ui: .connected(c), activeCallId: callId)
    let cleared = CallStateReducer.reduce(s, .noticeExpired(.videoRequestDeclined))
    check(cleared.0.audioControls?.notice == nil && cleared.1.isEmpty, "an expired notice is cleared without effects")
    let newer = CallStateReducer.reduce(s, .noticeExpired(.cameraFailed))
    expect(newer.0.audioControls?.notice, .videoRequestDeclined, "an older timer never clears a newer notice")
    var reconnecting = CallReducerState(ui: .reconnecting(outgoingInfo()), activeCallId: callId)
    reconnecting.lastConnected = c
    check(CallStateReducer.reduce(reconnecting, .noticeExpired(.videoRequestDeclined)).0.lastConnected?.notice == nil,
          "a notice expiring during reconnect is cleared from the kept call state")
}

// Timer: two minute digits, then h:mm:ss from the first hour.
do {
    expect(formatCallDuration(59), "00:59", "timer under a minute")
    expect(formatCallDuration(3599), "59:59", "timer just under an hour")
    expect(formatCallDuration(3600), "1:00:00", "timer switches to hours at one hour")
    expect(formatCallDuration(6005), "1:40:05", "timer never shows three minute digits")
}

// Fixed control slots: four audio, four video (the flip sits on the self view); rows follow the width only.
do {
    expect(CallLayoutPolicy.videoControlCount, 4, "camera, mute, end, output: no dead flip slot in the row")
    expect(CallLayoutPolicy.controlRows(availableWidth: 320, controlCount: CallLayoutPolicy.videoControlCount), 1,
           "four video controls fit one row on the smallest iPhone")
    expect(CallLayoutPolicy.controlRows(availableWidth: 430, controlCount: CallLayoutPolicy.videoControlCount), 1,
           "four video controls fit one row on a large iPhone")
    expect(CallLayoutPolicy.controlRows(availableWidth: 200, controlCount: CallLayoutPolicy.videoControlCount), 2,
           "a very narrow window wraps the video tray to two rows")
}

// Voice level from receiver energy: gate, dB mapping, asymmetric smoothing.
do {
    func sample(_ energy: Double, _ duration: Double, _ bytes: Double) -> CallAudioEnergySample {
        CallAudioEnergySample(totalAudioEnergy: energy, totalSamplesDuration: duration, bytesReceived: bytes)
    }
    check(CallVoiceLevelEstimator.normalizedLevel(energy: 0.000_000_1, duration: 0.14) == 0, "noise below -55 dBFS is gated to zero")
    let speech = CallVoiceLevelEstimator.normalizedLevel(energy: 0.01 * 0.01 * 0.14, duration: 0.14) // -40 dBFS
    check(abs(speech - 0.375) < 0.001, "-40 dBFS maps to 0.375 over the 40 dB range")
    check(CallVoiceLevelEstimator.normalizedLevel(energy: 0.14, duration: 0.14) == 1, "full scale clamps to 1")
    var meter = CallVoiceLevelEstimator()
    check(meter.update(sample(0, 0, 0)) == 0, "the first sample only primes the deltas")
    let loud = 0.25 * 0.25 * 0.14 // -12 dBFS, above the range
    let first = meter.update(sample(loud, 0.14, 1000))
    check(abs(first - 0.5) < 0.001, "attack moves half way to the target on one poll")
    let second = meter.update(sample(loud * 2, 0.28, 2000))
    check(second > first, "continued speech keeps rising")
    let falling = meter.update(sample(loud * 2, 0.42, 3000))
    check(falling < second && falling > second * 0.8, "release falls slowly (alpha 0.12) in silence")
    check(meter.update(sample(loud * 3, 0.56, 3000)) == 0, "no new audio bytes force zero")
    _ = meter.update(sample(loud * 4, 0.70, 4000))
    check(meter.update(nil) == 0 && meter.level == 0, "an inactive call or background forces zero")
    check(meter.update(sample(loud * 5, 0.84, 5000)) == 0, "after a forced pause the next sample primes again")
    check(meter.update(sample(0, 0, 6000)) == 0, "counters going backwards (new receiver) read as silence")
    expect([0.0, 0.2, 0.5, 0.9].map(CallVoiceLevelEstimator.step), [0, 1, 2, 3], "Reduce Motion halo uses still steps")
}

// Route: direct (host/srflx/prflx) versus via the TURN relay and its transport.
do {
    expect(CallMediaRoute.from(localType: "host", remoteType: "srflx", localRelayProtocol: nil), .direct, "host/srflx is direct")
    expect(CallMediaRoute.from(localType: "prflx", remoteType: "host", localRelayProtocol: nil), .direct, "prflx is direct")
    expect(CallMediaRoute.from(localType: "relay", remoteType: "host", localRelayProtocol: "TLS"), .relay(transport: "tls"), "own relay names its transport")
    expect(CallMediaRoute.from(localType: "srflx", remoteType: "relay", localRelayProtocol: nil), .relay(transport: nil), "peer relay is via server")
    check(CallMediaRoute.from(localType: nil, remoteType: "host", localRelayProtocol: nil) == nil, "no selected pair, no route")
}

// Quality grade: counters over a window, worst metric wins, the first grade
// from a full window, quick to fall, straight back up after five better
// samples, measuring after a shown reconnect or a gap, no colour of its own.
do {
    /// A synthetic link sampled once a second: each `next()` adds one
    /// second of counters, as `getStats` reports them.
    struct Link {
        var t = 0.0
        var rtt = 0.05, jitter = 0.005
        var packets = 50.0, lost = 0.0
        var concealedShare = 0.0, silentShare = 0.0, concealmentStats = true
        var audio = true
        var fps: Double? = nil
        /// The peer's picture still sends packets (false: its encoder stopped).
        var videoPackets = true
        var limit: String? = nil, availableKbps: Double? = nil
        /// Our audio packets a second, and the peer's reports on them.
        var outPackets = 50.0, outLostTotal = 0.0, outFraction: Double? = nil
        var received = 0.0, lostTotal = 0.0, bytes = 0.0, concealed = 0.0, silent = 0.0, samples = 0.0
        var responses = 0.0, totalRtt = 0.0, decoded = 0.0, videoBytes = 0.0, sent = 0.0

        mutating func next(after seconds: Double = 1) -> CallStatsSample {
            t += seconds
            if audio {
                received += packets; lostTotal += lost; bytes += packets * 100
                samples += 48_000; concealed += 48_000 * concealedShare; silent += 48_000 * silentShare
            }
            responses += 1; totalRtt += rtt
            sent += outPackets
            if let fps {
                decoded += fps
                if videoPackets { videoBytes += 60_000 }
            }
            var s = CallStatsSample(timestamp: t)
            s.pairId = "pair-1"
            s.rttSeconds = rtt
            s.totalRoundTripTime = totalRtt; s.responsesReceived = responses
            s.audioPacketsReceived = received; s.audioPacketsLost = lostTotal; s.audioBytesReceived = bytes
            s.audioJitterSeconds = jitter
            if concealmentStats {
                s.concealedSamples = concealed; s.silentConcealedSamples = silent; s.totalSamplesReceived = samples
            }
            if fps != nil { s.videoFramesDecoded = decoded; s.videoTotalFreezesDuration = 0; s.videoBytesReceived = videoBytes }
            s.outAudioPacketsSent = sent; s.outgoingPacketsLost = outLostTotal; s.outgoingFractionLost = outFraction
            s.qualityLimitationReason = limit
            s.availableOutgoingBitrate = availableKbps.map { $0 * 1000 }
            s.localCandidateType = "host"; s.remoteCandidateType = "srflx"
            return s
        }
    }
    let audio = CallQualityContext()

    var link = Link()
    var grader = CallQualityGrader()
    check(grader.update(link.next(), context: audio) == nil, "one sample: measuring")
    check(grader.update(link.next(), context: audio) == nil, "two samples: still measuring")
    check(grader.update(link.next(), context: audio) == nil, "three samples: still measuring")
    check(grader.update(link.next(), context: audio) == nil, "four samples: still measuring")
    expect(grader.update(link.next(), context: audio), .excellent, "the first grade comes from a full window")
    expect(grader.metrics.route, .direct, "the selected pair gives the route")
    link.rtt = 0.4
    _ = grader.update(link.next(), context: audio)
    link.rtt = 0.05
    let afterSpike = (0..<6).map { _ in grader.update(link.next(), context: audio) }
    check(afterSpike.allSatisfy { $0 == .excellent }, "a single 400 ms RTT response does not degrade")
    link.rtt = 0.5
    let slow = (0..<8).map { _ in grader.update(link.next(), context: audio) }
    expect(slow.last ?? nil, .weak, "a sustained 500 ms round trip degrades to weak")
    check(!slow.contains(.poor), "and never overshoots to poor")
    link.rtt = 0.05
    let rising = (0..<12).map { _ in grader.update(link.next(), context: audio) }
    let firstRise = rising.firstIndex { $0 != CallQualityGrade.weak }
    check(firstRise == 5, "a better link rises only after five better samples")
    expect(rising[5], .excellent, "straight back to the measured grade")
    expect(rising.last ?? nil, .excellent, "and stays there")

    // Exit band: a value on a threshold does not flip the bars back and forth.
    var hover = CallQualityMetrics()
    hover.rttMs = 240
    expect(CallQualityGrader.grade(hover), .excellent, "240 ms alone is excellent")
    expect(CallQualityGrader.grade(hover, current: .good), .good, "but leaving good needs a 15 % margin")
    hover.rttMs = 200
    expect(CallQualityGrader.grade(hover, current: .good), .excellent, "200 ms leaves good")

    // DTX silence: a handful of packets a second say nothing about loss, and
    // comfort noise is not heard as glitches.
    var dtx = Link()
    dtx.packets = 3; dtx.lost = 1; dtx.concealedShare = 0.6; dtx.silentShare = 0.6
    var quiet = CallQualityGrader()
    for _ in 0..<10 { _ = quiet.update(dtx.next(), context: audio) }
    expect(quiet.grade, .excellent, "a DTX second with 3 packets and 1 lost does not degrade")
    var glitchy = Link()
    glitchy.concealedShare = 0.07
    var heard = CallQualityGrader()
    for _ in 0..<5 { _ = heard.update(glitchy.next(), context: audio) }
    expect(heard.grade, .weak, "7 % audible concealment is weak")
    var lossy = Link()
    lossy.lost = 5; lossy.concealmentStats = false
    var fallback = CallQualityGrader()
    for _ in 0..<5 { _ = fallback.update(lossy.next(), context: audio) }
    expect(fallback.grade, .weak, "without concealment stats, 9 % loss is weak")

    // Our outgoing audio: the peer's report on a DTX silence (a dozen
    // packets, one lost) says nothing about the link; lost speech does.
    var listening = Link()
    listening.outPackets = 2.5
    var muted = CallQualityGrader()
    for i in 1...12 {
        if i == 5 { listening.outLostTotal = 1; listening.outFraction = 0.08 }
        _ = muted.update(listening.next(), context: audio)
    }
    expect(muted.grade, .excellent, "one lost packet in a muted sender's DTX report does not degrade")
    listening.outPackets = 50
    for _ in 0..<6 { _ = muted.update(listening.next(), context: audio) }
    expect(muted.grade, .excellent, "nor does that report once speech starts and it is still the latest")
    var speaking = Link()
    var uplink = CallQualityGrader()
    for i in 1...15 {
        // A report about every 5 s: 20 of 250 packets lost (8 %).
        if i % 5 == 0 { speaking.outLostTotal += 20; speaking.outFraction = 0.08 }
        _ = uplink.update(speaking.next(), context: audio)
    }
    expect(uplink.grade, .weak, "8 % of our speech lost, as the peer reports it, is weak")

    // The peer's picture: neutral to its frame rate, quick to see a stall.
    let watching = CallQualityContext(receivingVideo: true, peerVideoEnabled: true)
    for fps in [15.0, 24.0, 30.0] {
        var peer = Link()
        peer.fps = fps
        var g = CallQualityGrader()
        for _ in 0..<8 { _ = g.update(peer.next(), context: watching) }
        expect(g.grade, .excellent, "a \(Int(fps)) fps peer stays excellent")
    }
    var frozen = Link()
    frozen.fps = 30
    var stall = CallQualityGrader()
    for _ in 0..<6 { _ = stall.update(frozen.next(), context: watching) }
    frozen.fps = 0
    let stalled = (0..<4).map { _ in stall.update(frozen.next(), context: watching) }
    check(stalled.contains(.poor), "a picture that stops while the audio flows reads poor within about 3 s")
    var stopping = Link()
    stopping.fps = 30
    var sender = CallQualityGrader()
    for _ in 0..<6 { _ = sender.update(stopping.next(), context: watching) }
    stopping.fps = 0; stopping.videoPackets = false
    for _ in 0..<8 { _ = sender.update(stopping.next(), context: watching) }
    expect(sender.grade, .excellent, "a sender that stops sending its picture (a backgrounded encoder) is not the link")
    var cameraOff = Link()
    cameraOff.fps = 30
    var off = CallQualityGrader()
    for _ in 0..<6 { _ = off.update(cameraOff.next(), context: watching) }
    cameraOff.fps = 0
    for _ in 0..<6 { _ = off.update(cameraOff.next(), context: audio) }
    expect(off.grade, .excellent, "the peer turning the camera off is not a freeze")
    var slowPeer = Link()
    slowPeer.fps = 4
    var choppy = CallQualityGrader()
    for _ in 0..<6 { _ = choppy.update(slowPeer.next(), context: watching) }
    expect(choppy.grade, .weak, "a picture under 5 fps is weak")

    // Our upload counts only while bandwidth really holds the encoder back.
    let sending = CallQualityContext(sendingVideo: true)
    var upload = Link()
    upload.availableKbps = 1000
    var up = CallQualityGrader()
    for _ in 0..<8 { _ = up.update(upload.next(), context: sending) }
    expect(up.grade, .excellent, "a 1 Mbps estimate without a bandwidth limit stays excellent")
    upload.availableKbps = 90
    for _ in 0..<6 { _ = up.update(upload.next(), context: sending) }
    expect(up.grade, .excellent, "a low estimate of an encoder that is not held back is not the link's fault")
    upload.availableKbps = 300; upload.limit = "bandwidth"
    for _ in 0..<8 { _ = up.update(upload.next(), context: sending) }
    expect(up.grade, .weak, "held back by bandwidth for 3 s at 300 kbps is weak")

    // A shown reconnect ends in measuring and a fresh grade: no poor tail.
    var dropLink = Link()
    var drop = CallQualityGrader()
    for _ in 0..<5 { _ = drop.update(dropLink.next(), context: audio) }
    dropLink.audio = false
    for _ in 0..<4 { _ = drop.update(dropLink.next(), context: audio) }
    expect(drop.grade, .poor, "no incoming audio for over 2 s is poor")
    var reconnecting = audio
    reconnecting.reconnecting = true
    for _ in 0..<3 { _ = drop.update(dropLink.next(), context: reconnecting) }
    // The monitor ends a drop that was shown (over 1.2 s) in measuring.
    drop.restartMeasuring()
    check(drop.grade == nil, "a shown drop ends in measuring at once")
    dropLink.audio = true
    let back = (0..<5).map { _ in drop.update(dropLink.next(), context: audio) }
    expect(back, [nil, nil, nil, nil, .excellent], "after a shown reconnect: measuring, then a grade from a full window with no poor tail")

    // A blip nobody saw (under 1.2 s, so no restartMeasuring) keeps the bars.
    var blipLink = Link()
    var blip = CallQualityGrader()
    for _ in 0..<5 { _ = blip.update(blipLink.next(), context: audio) }
    _ = blip.update(blipLink.next(), context: reconnecting)
    let afterBlip = (0..<3).map { _ in blip.update(blipLink.next(), context: audio) }
    expect(afterBlip, [.excellent, .excellent, .excellent], "a hidden blip keeps the grade: no dim measuring bars")

    // Back from the background: nothing from before the gap counts.
    var gapLink = Link()
    var gapped = CallQualityGrader()
    for _ in 0..<5 { _ = gapped.update(gapLink.next(), context: audio) }
    gapLink.rtt = 0.8
    let resumed = [gapped.update(gapLink.next(after: 10), context: audio), gapped.update(gapLink.next(), context: audio)]
    expect(resumed, [nil, nil], "a gap of more than 3 s resets the window: measuring again")
    check(gapped.update(gapLink.next(), context: audio) == nil, "three fresh samples: still measuring")
    check(gapped.update(gapLink.next(), context: audio) == nil, "four fresh samples: still measuring")
    expect(gapped.update(gapLink.next(), context: audio), .poor, "then a grade from a full window of fresh samples only")

    // What is said in words.
    var weak = CallQualityIndicator(level: .graded(.weak))
    check(weak.caption == nil, "weak is not said before it has held")
    weak.settled = true
    expect(weak.caption, "Weak connection", "a weak link that held is said")
    check(CallQualityIndicator(level: .graded(.good), settled: true).caption == nil, "good needs no words")
    expect(CallQualityIndicator(level: .reconnecting(waitingForNetwork: false)).caption, "Reconnecting…", "a shown drop says reconnecting")
    expect(CallQualityIndicator(level: .reconnecting(waitingForNetwork: true)).caption, "Waiting for network…", "without a network it says so")
    check(CallQualityIndicator().grade == nil && CallQualityIndicator().caption == nil, "measuring: no grade and no words")
    expect([CallQualityGrade.excellent, .good, .weak, .poor].map(\.bars), [4, 3, 2, 1], "bars per grade")
}

// Playout delay over the window: cumulative jitter-buffer and processing
// delays divided by what they cover, never a single second's value.
do {
    func playoutRead(_ t: Double, audioDelay: Double, audioTarget: Double?, samples: Double,
                     videoDelay: Double, frames: Double, processing: Double?) -> CallStatsSample {
        var s = CallStatsSample(timestamp: t)
        s.pairId = "pair-1"
        s.audioJitterBufferDelay = audioDelay; s.audioJitterBufferTargetDelay = audioTarget; s.audioJitterBufferEmitted = samples
        s.videoJitterBufferDelay = videoDelay; s.videoJitterBufferEmitted = frames; s.videoFramesDecoded = frames
        s.videoTotalProcessingDelay = processing
        s.outVideoFrameWidth = 640; s.outVideoFrameHeight = 360
        return s
    }
    // Each second: 48 000 samples held 60 ms on average (target 80 ms), 30
    // frames held 50 ms and decoded 70 ms after their first packet.
    var grader = CallQualityGrader()
    for second in 0..<5 {
        let t = Double(second)
        _ = grader.update(playoutRead(t, audioDelay: 48_000 * 0.06 * t, audioTarget: 48_000 * 0.08 * t, samples: 48_000 * t,
                                      videoDelay: 30 * 0.05 * t, frames: 30 * t, processing: 30 * 0.07 * t),
                          context: CallQualityContext())
    }
    let m = grader.metrics
    check(abs((m.audioPlayoutDelayMs ?? 0) - 60) < 0.001, "the voice's mean jitter-buffer delay in ms")
    check(abs((m.audioTargetDelayMs ?? 0) - 80) < 0.001, "and the delay that buffer aims for")
    check(abs((m.videoPlayoutDelayMs ?? 0) - 70) < 0.001, "the picture: first packet to decode")
    expect(m.videoOutWidth, 640, "the encoded width as a number")
    expect(m.videoOutHeight, 360, "the encoded height as a number")
    var noProcessing = CallQualityGrader()
    for second in 0..<3 {
        let t = Double(second)
        _ = noProcessing.update(playoutRead(t, audioDelay: 0, audioTarget: nil, samples: 48_000 * t,
                                            videoDelay: 30 * 0.05 * t, frames: 30 * t, processing: nil),
                                context: CallQualityContext())
    }
    check(abs((noProcessing.metrics.videoPlayoutDelayMs ?? 0) - 50) < 0.001, "without the processing counter: the jitter buffer's delay")
    check(noProcessing.metrics.audioTargetDelayMs == nil, "no target reported: none shown")
    var single = CallQualityGrader()
    _ = single.update(playoutRead(0, audioDelay: 10, audioTarget: 10, samples: 100, videoDelay: 1, frames: 10, processing: 1),
                      context: CallQualityContext())
    check(single.metrics.audioPlayoutDelayMs == nil && single.metrics.videoPlayoutDelayMs == nil, "one read is no window: nothing yet")
}

// Media diagnostics over the window (phases 119, 121 and 122, the stats
// sheet): counter deltas over what they cover, each direction's codec and
// the STUN and RTCP round trips apart.
do {
    func mediaRead(_ t: Double) -> CallStatsSample {
        var s = CallStatsSample(timestamp: t)
        s.pairId = "pair-1"
        // Our encoder each second: 30 frames at QP 28, 5 ms each; 90 packets
        // that waited 2 ms in the pacer; held back by bandwidth half the time.
        s.outVideoFramesEncoded = 30 * t; s.outVideoQpSum = 30 * 28 * t; s.outVideoTotalEncodeTime = 30 * 0.005 * t
        s.outVideoPacketsSent = 90 * t; s.outVideoPacketSendDelay = 90 * 0.002 * t
        s.outVideoBandwidthLimitedSeconds = 0.5 * t; s.outVideoResolutionChanges = t < 2 ? 0 : 3
        s.outVideoKeyFramesEncoded = 1 + t; s.outVideoKeyFrameRequests = 2 * t; s.outVideoRetransmittedBytes = 1000 * t
        s.targetBitrate = 1_200_000; s.outVideoCodec = "H264"; s.outVideoCodecCode = 131
        s.outVideoFrameWidth = 1280; s.outVideoFrameHeight = 720
        s.cameraWidth = 1280; s.cameraHeight = 720; s.cameraFps = 30
        // The peer's picture: 30 frames decoded in 4 ms each, gaps of 20 and
        // 40 ms in turn (15 each: sum 0.9 s, squares 0.03 s², deviation 10 ms).
        s.videoFramesDecoded = 30 * t; s.videoTotalDecodeTime = 30 * 0.004 * t
        s.videoTotalInterFrameDelay = 0.9 * t; s.videoTotalSquaredInterFrameDelay = 0.03 * t
        s.videoFramesDropped = t; s.videoKeyFrameRequestsSent = t
        s.videoCodec = "VP8"; s.videoCodecCode = 300; s.videoFrameWidth = 640; s.videoFrameHeight = 360
        // The voice: 48 000 samples aiming for 80 ms against the buffer's own
        // 60 ms, 24 dropped to catch up; 50 packets out, 48 in and 2 lost.
        s.audioJitterBufferEmitted = 48_000 * t; s.audioJitterBufferDelay = 48_000 * 0.07 * t
        s.audioJitterBufferTargetDelay = 48_000 * 0.08 * t; s.audioJitterBufferMinimumDelay = 48_000 * 0.06 * t
        s.totalSamplesReceived = 48_000 * t; s.audioRemovedSamplesForAcceleration = 24 * t
        s.outAudioPacketsSent = 50 * t; s.audioPacketsReceived = 48 * t; s.audioPacketsLost = 2 * t
        // Round trips: STUN 10 ms from the totals, RTCP 30 ms for the voice
        // and 25 ms for the video while its reports come.
        s.responsesReceived = t; s.totalRoundTripTime = 0.01 * t; s.rttSeconds = 0.012
        s.remoteRttSeconds = 0.03; s.videoRemoteRttSeconds = 0.025; s.videoRemoteRttMeasurements = t
        s.thermalState = 1
        return s
    }
    func near(_ value: Double?, _ expected: Double) -> Bool { value.map { abs($0 - expected) < 0.001 } ?? false }
    func measured(_ seconds: Int = 5, _ edit: (inout CallStatsSample) -> Void = { _ in }) -> CallQualityMetrics {
        var grader = CallQualityGrader()
        for second in 0..<seconds {
            var s = mediaRead(Double(second))
            edit(&s)
            _ = grader.update(s, context: CallQualityContext())
        }
        return grader.metrics
    }
    let m = measured()
    check(near(m.videoOutQp, 28), "the mean QP of the frames encoded")
    check(near(m.videoEncodeMs, 5), "encode ms per frame")
    check(near(m.videoPacerMs, 2), "ms each packet waited in the pacer")
    check(near(m.videoBandwidthLimitedMs, 2000), "ms held back by bandwidth over the 4 s window")
    expect(m.videoResolutionChanges, 3, "resolution changes in the window")
    expect(m.videoKeyFramesEncoded, 4, "key frames encoded in the window")
    expect(m.videoKeyFrameRequestsIn, 8, "key frames the peer asked for")
    expect(m.videoRetransmittedBytes, 4000, "bytes retransmitted")
    check(near(m.videoOutTargetKbps, 1200), "the encoder's target in kbps")
    expect(m.cameraResolution, "1280×720", "the camera's capture size")
    expect(m.cameraFps, 30, "and its rate")
    check(near(m.videoDecodeMs, 4), "decode ms per frame")
    expect(m.videoFramesDropped, 4, "frames dropped")
    expect(m.videoKeyFrameRequestsOut, 4, "key frames this side asked for")
    check(near(m.videoInterFrameStddevMs, 10), "the deviation of the gaps between decoded frames")
    expect([m.videoInCodec, m.videoOutCodec], ["VP8", "H264"], "each direction's codec apart")
    expect([m.videoInCodecCode, m.videoOutCodecCode], [300, 131], "and their codes")
    expect([m.videoInWidth, m.videoInHeight], [640, 360], "the peer's picture size as numbers")
    check(near(m.avSyncDelayMs, 20), "lip-sync adds the target minus the buffer's own delay")
    check(near(m.audioPacketsOutPerSecond, 50), "our audio packets a second")
    check(near(m.audioPacketTimeMs, 20), "50 packets a second are 20 ms packets")
    check(near(m.audioPacketsInPerSecond, 50), "the peer's packets: arrived plus lost")
    check(near(m.audioAcceleratedPerMille, 0.5), "24 of 48 000 samples dropped: 0.5 per mille")
    check(near(m.stunRttMs, 10) && near(m.rttMs, 10), "the STUN round trip from the totals, as the grade's")
    check(near(m.rtcpRttMs, 25), "RTCP: the video's reports while they come")
    expect(m.thermalState, 1, "the thermal state of the newest read")

    let stale = measured { $0.videoRemoteRttMeasurements = 7 }
    check(near(stale.rtcpRttMs, 30), "a stopped video's last report gives way to the voice's")
    let noStun = measured(3) { $0.responsesReceived = nil; $0.totalRoundTripTime = nil; $0.rttSeconds = nil }
    check(near(noStun.rttMs, 30) && noStun.stunRttMs == nil, "no STUN round trip: the grade falls back to RTCP, the STUN row does not")
    let medianStun = measured(3) { $0.responsesReceived = nil; $0.totalRoundTripTime = nil }
    check(near(medianStun.stunRttMs, 12), "no STUN totals: the last responses' round trip")
    let quiet = measured { $0.outAudioPacketsSent = 2.5 * $0.timestamp }
    check(quiet.audioPacketTimeMs == nil, "DTX silence (2.5 packets a second): no packet time")
    let paused = measured { $0.outVideoFramesEncoded = 100; $0.outVideoQpSum = 2800; $0.outVideoTotalEncodeTime = 0.5 }
    check(paused.videoOutQp == nil && paused.videoEncodeMs == nil, "nothing encoded in the window: no QP and no encode time")
    let unsynced = measured { $0.audioJitterBufferMinimumDelay = 48_000 * 0.09 * $0.timestamp }
    expect(unsynced.avSyncDelayMs, 0, "a buffer's own delay above its target reads as no sync delay")
    let single = measured(1)
    check(single.videoOutQp == nil && single.avSyncDelayMs == nil && single.audioPacketsOutPerSecond == nil,
          "one read is no window: no counter ratios yet")
    check(near(single.stunRttMs, 12) && near(single.rtcpRttMs, 30) && single.videoInCodec == "VP8",
          "but the newest read's own values")

    // Since the pair's last packet: whichever clock libwebrtc kept it on.
    expect(CallIceDiagnostics.sinceLastPacketMs(1_000_000, clocksMs: [1_731_000_000_000, 1_000_250]), 250,
           "a monotonic last-packet time against a UTC report: the uptime clock")
    expect(CallIceDiagnostics.sinceLastPacketMs(1_731_000_000_000 - 40, clocksMs: [1_731_000_000_000, 5_000]), 40,
           "the report's clock when it fits")
    expect(CallIceDiagnostics.sinceLastPacketMs(9_000_000, clocksMs: [1_000_000, 2_000_000]), nil,
           "after every clock: left out")
    expect(CallIceDiagnostics.sinceLastPacketMs(0, clocksMs: [5_000]), nil, "nothing arrived yet")
    expect(CallIceDiagnostics.sinceLastPacketMs(nil, clocksMs: [5_000]), nil, "not reported")
}

// ICE diagnostic: what arrived and how many STUN responses came back are
// measured over 5-12 s of one pair, and only a fresh read describes an event.
do {
    func pairRead(_ pair: String?, responses: Double, bytes: Double) -> CallStatsSample {
        var s = CallStatsSample(timestamp: 0)
        s.pairId = pair; s.responsesReceived = responses; s.pairBytesReceived = bytes
        return s
    }
    var history = CallIceReadHistory()
    check(history.window(at: 0) == nil, "no read: nothing to say")
    // A healthy stable pair pinged every 2.5 s, read once a second: most
    // single seconds hold no response at all.
    let responses: [Double] = [0, 0, 0, 1, 1, 2, 2, 2, 3]
    for (second, total) in responses.enumerated() {
        history.add(pairRead("pair-1", responses: total, bytes: Double(second) * 1000), at: Double(second))
    }
    let window = history.window(at: 8.5)
    expect(window?.span, 5, "the base is the newest read of the pair at least 5 s older")
    expect(window?.base?.responsesReceived, 1, "responses are counted over that span, not the last second")
    expect(window?.newest.responsesReceived, 3, "the newest read describes the event")
    check(history.window(at: 11) == nil, "a newest read over 2.5 s old says nothing (stats are not read in the background)")

    history.add(pairRead("pair-2", responses: 4, bytes: 9000), at: 9)
    let switched = history.window(at: 9.2)
    check(switched?.newest.pairId == "pair-2" && switched?.base == nil && switched?.span == nil,
          "a new pair: no counters diffed across pairs")

    var short = CallIceReadHistory()
    for second in 0..<4 { short.add(pairRead("pair-1", responses: 0, bytes: 0), at: Double(second)) }
    check(short.window(at: 3.5)?.base == nil, "under 5 s of reads: no span yet")

    var gap = CallIceReadHistory()
    for second in [0.0, 1, 2, 30, 31] { gap.add(pairRead("pair-1", responses: second, bytes: second), at: second) }
    check(gap.window(at: 31.5)?.base == nil, "reads from before a gap of more than 12 s are not a base")

    var unpaired = CallIceReadHistory()
    for second in 0..<8 { unpaired.add(pairRead(nil, responses: 0, bytes: 0), at: Double(second)) }
    check(unpaired.window(at: 7.5)?.base == nil, "no selected pair: no span")

    expect(history.window(at: 9.5)?.age, 0.5, "the window says how old its newest read is")
    expect(unpaired.window(at: 7.25)?.age, 0.25, "also without a selected pair")
}

// ICE detail diagnostic (operation 5): candidates, IP families and switch
// reasons reduced to numbers, never an address.
do {
    typealias Candidate = CallIceDiagnostics.Candidate
    expect(CallIceDiagnostics.addressFamily("192.168.1.20"), 1, "a dotted quad is IPv4")
    expect(CallIceDiagnostics.addressFamily("2001:db8::5"), 2, "an address with colons is IPv6")
    expect(CallIceDiagnostics.addressFamily("4b1d1b2c-1111-4a4a-9a9a-0123456789ab.local"), nil, "an mDNS name has no family")
    expect(CallIceDiagnostics.addressFamily(nil), nil, "no address, no family")
    expect(CallIceDiagnostics.addressFamily(""), nil, "an empty address, no family")
    expect(CallIceDiagnostics.addressFamily("256.1.1.1"), nil, "an octet over 255 is not IPv4")
    expect(CallIceDiagnostics.addressFamily("1.2.3"), nil, "three octets are not IPv4")
    expect(CallIceDiagnostics.addressFamily("+1.2.3.4"), nil, "a signed octet is not IPv4")

    expect(CallIceDiagnostics.candidate(sdp: "candidate:842163049 1 udp 2122260223 192.168.1.20 54321 typ host generation 0 ufrag Ab12 network-id 1 network-cost 10"),
           Candidate(type: 1, family: 1, networkCost: 10, ufrag: "Ab12"), "an IPv4 Wi-Fi host candidate")
    expect(CallIceDiagnostics.candidate(sdp: "candidate:1 1 udp 2122262783 2001:db8::5 50000 typ host generation 0 ufrag Ab12 network-id 2 network-cost 10"),
           Candidate(type: 1, family: 2, networkCost: 10, ufrag: "Ab12"), "an IPv6 host candidate")
    expect(CallIceDiagnostics.candidate(sdp: "a=candidate:3 1 udp 41885439 203.0.113.7 3478 typ relay raddr 198.51.100.2 rport 60000 generation 0 ufrag Cd34 network-cost 900"),
           Candidate(type: 4, family: 1, networkCost: 900, ufrag: "Cd34"), "a relay candidate, with the a= prefix")
    expect(CallIceDiagnostics.candidate(sdp: "candidate:4 1 udp 1853824767 198.51.100.9 61000 typ prflx raddr 0.0.0.0 rport 0 generation 0 ufrag Ef56"),
           Candidate(type: 3, family: 1, networkCost: nil, ufrag: "Ef56"), "a peer-reflexive candidate carries no cost")
    expect(CallIceDiagnostics.candidate(sdp: "candidate:5 1 udp 2122260223 4b1d1b2c-1111-4a4a-9a9a-0123456789ab.local 54321 typ host generation 0"),
           Candidate(type: 1, family: nil, networkCost: nil, ufrag: nil), "an mDNS host candidate has no family")
    expect(CallIceDiagnostics.candidate(sdp: "candidate:6 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active generation 0 ufrag Ab12 network-id 1 network-cost 10"),
           Candidate(type: 1, family: 1, networkCost: 10, ufrag: "Ab12"), "a TCP candidate's extra pair does not shift the tail")
    expect(CallIceDiagnostics.candidate(sdp: "candidate:7 1 udp 1 10.0.0.1 1 typ weird"), Candidate(type: 0, family: 1), "an unknown type is 0")
    check(CallIceDiagnostics.candidate(sdp: "hello world") == nil, "garbage is no candidate")
    check(CallIceDiagnostics.candidate(sdp: "candidate:1 1 udp 2122260223 192.168.1.20") == nil, "a cut-off candidate is none")
    check(CallIceDiagnostics.candidate(sdp: "candidate:1 1 udp 2122260223 192.168.1.20 54321 type host") == nil, "no typ keyword, no candidate")
    check(CallIceDiagnostics.candidate(sdp: "") == nil, "an empty line is no candidate")

    // libwebrtc's IceSwitchReasonToString texts.
    let reasons: [(String, Double)] = [
        ("nomination on the controlled side", 1),
        ("data received", 2),
        ("new candidate pairs created from a new local candidate", 3),
        ("new candidate pairs created from a new remote candidate", 3),
        ("a new candidate pair created from an unknown remote address", 3),
        ("candidate pair state changed", 4),
        ("selected candidate pair destroyed", 5),
        ("network preference changed", 6),
        ("remote candidate generation maybe changed", 7),
        ("ice-controller-request-recheck", 8),
        ("application requested", 9),
        ("unknown", 0),
        ("", 0),
    ]
    for (text, code) in reasons { expect(CallIceDiagnostics.switchReason(text), code, "switch reason '\(text)'") }

    // A candidate after connect is the network changing only once its
    // generation's own gathering is over.
    check(CallIceDiagnostics.isGathering(sinceGenerationStart: 0), "a generation's first candidate starts its gathering")
    check(CallIceDiagnostics.isGathering(sinceGenerationStart: 0.4),
          "the callee's srflx, relay and TCP candidates after a LAN pair connected are its first gathering")
    check(CallIceDiagnostics.isGathering(sinceGenerationStart: CallIceDiagnostics.gatheringWindow - 0.01),
          "a slow relay allocation still belongs to the gathering")
    check(!CallIceDiagnostics.isGathering(sinceGenerationStart: CallIceDiagnostics.gatheringWindow),
          "from the window on, a candidate is a network appearing or a re-enumeration")
    check(!CallIceDiagnostics.isGathering(sinceGenerationStart: 40), "the caller's candidate long after its gathering")
    check(!CallIceDiagnostics.isGathering(sinceGenerationStart: nil), "an unknown generation is no gathering")
    check(!CallIceDiagnostics.isGathering(sinceGenerationStart: -1), "a time before the generation began is none")
    check(CallIceDiagnostics.gatheringWindow >= 3 && CallIceDiagnostics.gatheringWindow <= 6,
          "long enough for TURN over TLS, short enough to show a change soon after connect")

    func flat(_ values: [String: Double?]) -> [String: Double] { values.compactMapValues { $0 } }
    var newest = CallStatsSample(timestamp: 10)
    newest.pairId = "pair-1"
    newest.rttSeconds = 0.0078125
    newest.transportPairChanges = 1
    newest.dtlsConnected = true
    newest.localAddressFamily = 1
    newest.remoteAddressFamily = 2
    newest.audioBytesReceived = 3000
    newest.videoBytesReceived = 5000
    newest.pairRequestsReceived = 12
    newest.pairResponsesSent = 12
    newest.pairRequestsSent = 23
    newest.pairPacketsDiscardedOnSend = 4
    let alone = flat(CallIceDiagnostics.pairDetail(CallIceReadHistory.Window(newest: newest, age: 0.5)))
    expect(alone, ["status": 12, "duration_ms": 500, "budget_ms": 7.8125, "visible_count": 1, "success": 1],
           "no base read: only what the newest read says")

    var base = CallStatsSample(timestamp: 5)
    base.pairId = "pair-1"
    base.audioBytesReceived = 1000
    base.pairRequestsReceived = 10
    base.pairResponsesSent = 10
    base.pairRequestsSent = 20
    base.pairPacketsDiscardedOnSend = 0
    let spanned = flat(CallIceDiagnostics.pairDetail(CallIceReadHistory.Window(newest: newest, base: base, span: 5, age: 0.5)))
    expect(spanned["gap_ms"], 5000, "the span of the base read")
    expect(spanned["bytes"], 7000, "audio and video payload together; a video receiver that was not there yet counts as zero")
    expect(spanned["count"], 2, "the peer's checks that reached us")
    expect(spanned["frames"], 2, "our answers to them")
    expect(spanned["items"], 3, "the checks we sent")
    expect(spanned["late_frames"], 4, "packets the socket refused")
    check(CallIceDiagnostics.pairDetail(CallIceReadHistory.Window(newest: newest, base: base, span: 5)).count + 3 <= 16,
          "within the 16 metrics of an event, with operation, phase and a trace")

    var reset = base
    reset.pairRequestsReceived = 40
    reset.audioBytesReceived = 9000
    reset.videoBytesReceived = 1
    let backwards = flat(CallIceDiagnostics.pairDetail(CallIceReadHistory.Window(newest: newest, base: reset, span: 5)))
    check(backwards["count"] == nil && backwards["bytes"] == nil, "a counter that went backwards is left out")
    expect(backwards["frames"], 2, "the other counters still count")

    var unknownFamily = newest
    unknownFamily.localAddressFamily = nil
    unknownFamily.remoteAddressFamily = nil
    check(flat(CallIceDiagnostics.pairDetail(CallIceReadHistory.Window(newest: unknownFamily)))["status"] == nil,
          "no family on either side: no status")

    // Who ended the call: the controller ends its own hang-up with the same
    // event a server end arrives as, so only it can say so.
    let info = outgoingInfo()
    let ownHangup = CallIceDiagnostics.endDetail(.ended(info, reason: .hangup),
                                                 after: .remoteEnded(callId: callId, reason: "hangup"), endedHere: true)
    check(ownHangup?.status == 1 && ownHangup?.direction == 1, "our own hang-up: hangup, this device")
    let peerHangup = CallIceDiagnostics.endDetail(.ended(info, reason: .hangup),
                                                  after: .remoteEnded(callId: callId, reason: "hangup"), endedHere: false)
    check(peerHangup?.status == 1 && peerHangup?.direction == 2, "the peer's hang-up: hangup, the peer")
    let elsewhere = CallIceDiagnostics.endDetail(.ended(incomingInfo(), reason: .answeredElsewhere),
                                                 after: .remoteAnsweredElsewhere(callId: callId), endedHere: false)
    check(elsewhere?.status == 7 && elsewhere?.direction == 2, "answered on another device")
    let reconciled = CallIceDiagnostics.endDetail(.ended(info, reason: .remoteEnded),
                                                  after: .reconnectReconcileSucceeded(nil), endedHere: false)
    check(reconciled?.status == 8 && reconciled?.direction == 2, "the server's record ended it")
    let acceptFailed = CallIceDiagnostics.endDetail(.ended(incomingInfo(), reason: .failed), after: .acceptFailed, endedHere: false)
    check(acceptFailed?.status == 6 && acceptFailed?.direction == 1, "a failed accept is this device")
    let mediaFailed = CallIceDiagnostics.endDetail(.failed(info, reason: .network),
                                                   after: .engineConnectionState(callId: callId, state: .failed), endedHere: false)
    check(mediaFailed?.status == 9 && mediaFailed?.direction == 1, "recovery gave up: the failed screen, this device")
    let reasonCodes: [(CallEndReason, Double)] = [(.hangup, 1), (.rejected, 2), (.cancelled, 3), (.missed, 4), (.timeout, 5),
                                                  (.failed, 6), (.answeredElsewhere, 7), (.remoteEnded, 8)]
    for (reason, code) in reasonCodes {
        expect(CallIceDiagnostics.endDetail(.ended(info, reason: reason), after: .hangup, endedHere: true)?.status, code,
               "end reason \(reason.rawValue)")
    }
    check(CallIceDiagnostics.endDetail(.connected(ConnectedState(call: info)), after: .hangup, endedHere: true) == nil,
          "a live call has no end")
}

// Proximity: only an audio call on the receiver darkens the screen at the ear.
do {
    let audioCall = ConnectedState(call: outgoingInfo())
    check(CallProximityPolicy.wantsProximity(state: .connected(audioCall), controls: audioCall), "audio call on the receiver")
    var speaker = audioCall; speaker.audioRoute = .speaker
    check(!CallProximityPolicy.wantsProximity(state: .connected(speaker), controls: speaker), "speaker releases proximity")
    var headset = audioCall; headset.audioRoute = .bluetooth
    check(!CallProximityPolicy.wantsProximity(state: .connected(headset), controls: headset), "Bluetooth releases proximity")
    let videoCall = ConnectedState(call: outgoingInfo(.video), videoMode: .video)
    check(!CallProximityPolicy.wantsProximity(state: .connected(videoCall), controls: videoCall), "video releases proximity")
    var preview = audioCall; preview.localVideoEnabled = true
    check(!CallProximityPolicy.wantsProximity(state: .connected(preview), controls: preview), "an upgrade preview releases proximity")
    let ringing = ConnectedState(call: outgoingInfo())
    check(CallProximityPolicy.wantsProximity(state: .outgoingRinging(outgoingInfo()), controls: ringing), "calling out on the receiver")
    check(!CallProximityPolicy.wantsProximity(state: .connecting(outgoingInfo(.video)), controls: ConnectedState(call: outgoingInfo(.video))),
          "connecting a video call never darkens the screen")
    check(!CallProximityPolicy.wantsProximity(state: .ended(outgoingInfo(), reason: .hangup), controls: nil), "the end releases proximity")
}

// A protected call with no TURN configuration ends before engine setup and
// retains a dedicated explanation after stale engine/signaling callbacks.
do {
    for info in [outgoingInfo(), incomingInfo(.video)] {
        var initial = CallReducerState()
        initial.ui = .connecting(info)
        initial.activeCallId = callId
        let (failed, effects) = CallStateReducer.reduce(initial, .relayUnavailable(callId: callId))
        expect(failed.ui, .failed(info, reason: .relayUnavailable), "missing TURN keeps a dedicated call failure")
        check(failed.activeCallId == nil, "missing TURN retires the active call identity")
        check(effects.contains(.restFailed(callId: callId)), "missing TURN reports the terminal call to the server")
        check(effects.contains(.engineDispose) && effects.contains(.stopKeepAlive), "missing TURN cancels media setup and keepalive")
        check(!effects.contains(.engineInitialize(mediaKind: info.mediaKind)), "missing TURN never schedules engine setup")
        let (late, lateEffects) = CallStateReducer.reduce(failed, .engineConnectionState(callId: callId, state: .connected))
        expect(late, failed, "late connected callback cannot overwrite the relay explanation")
        check(lateEffects.isEmpty, "retired relay failure emits no late effects")
        let (unrelated, unrelatedEffects) = CallStateReducer.reduce(initial, .relayUnavailable(callId: "another-call"))
        expect(unrelated, initial, "a stale relay failure cannot end another call")
        check(unrelatedEffects.isEmpty, "stale relay failure has no effects")
    }
    check(CallFailureReason.relayUnavailable.textKey.contains("TURN"), "dedicated failure explains the unavailable relay")
}

if failures > 0 {
    print("\(failures) call state contract check(s) failed")
    exit(1)
}
print("Call state contract checks passed")
