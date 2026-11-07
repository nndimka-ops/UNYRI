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
expect(rec.0.ui, .reconnecting(outgoingInfo()), "socket reconnect -> reconnecting")
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
    expect(offer.1, [.engineSetVideoNegotiationEnabled(false), .produceOfferAndSend(callId: callId)], "ICE restart preserves audio negotiation intent before signaling offer")
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

    init(route: AudioRouteKind = .earpiece, camera: Bool = false) {
        var c = ConnectedState(call: outgoingInfo(.video), videoMode: .video)
        c.audioRoute = route; c.isSpeakerOn = route == .speaker
        c.localVideoEnabled = camera
        c.localCameraState = camera ? .starting : .off
        state = CallReducerState(ui: .connected(c), activeCallId: callId)
        state.observedAudioRoute = route
        _ = policy.update(event: .engineAudioRouteChanged(AudioRoute(kind: route, label: "OS")), after: state)
    }

    mutating func send(_ event: CallEvent) -> [CallEffect] {
        let reduced = CallStateReducer.reduce(state, event)
        state = reduced.0
        var effects = reduced.1
        if let on = policy.update(event: event, after: state) { effects.append(.engineSetSpeaker(on)) }
        return effects
    }
}

do {
    var video = AutomaticAudioScenario(camera: true)
    check(!video.policy.ownsSpeaker, "starting a camera does not route audio before its first frame")
    expect(video.send(.engineLocalFirstFrame(callId: callId)), [.engineSetSpeaker(true)], "ready own video selects speaker once")
    check(video.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "duplicate first frame does not repeat speaker override")
    _ = video.send(.engineAudioRouteChanged(AudioRoute(kind: .speaker, label: "Speaker"), userInitiated: true))
    check(video.policy.ownsSpeaker && !video.policy.manualRouteChosen, "our speaker confirmation is not mistaken for a manual choice")
    let off = video.send(.setCameraEnabled(false))
    check(off.contains(.engineSetSpeaker(false)), "camera off releases the automatic speaker")
    check(!video.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "camera off releases automatic speaker only once")

    var failed = AutomaticAudioScenario(camera: true)
    _ = failed.send(.engineLocalFirstFrame(callId: callId))
    check(failed.send(.localVideoFailed).contains(.engineSetSpeaker(false)), "capture failure releases automatic speaker")
    var neverReady = AutomaticAudioScenario(camera: true)
    check(!neverReady.send(.localVideoFailed).contains(.engineSetSpeaker(true)), "failed startup never turns on speaker")

    for kind: AudioRouteKind in [.earpiece, .speaker, .bluetooth, .wiredHeadset] {
        var manual = AutomaticAudioScenario(camera: true)
        _ = manual.send(.setAudioRoute(AudioRoute(kind: kind, label: "Chosen")))
        check(manual.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "manual \(kind) wins over ready camera")
        check(!manual.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "camera off preserves manual \(kind)")
        check(!manual.policy.restoreSpeakerIfNeeded(in: manual.state), "resume never overrides manual \(kind)")
    }
    for kind: AudioRouteKind in [.bluetooth, .wiredHeadset] {
        var headset = AutomaticAudioScenario(route: kind, camera: true)
        check(headset.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "ready video preserves connected \(kind)")
        check(!headset.policy.ownsSpeaker, "accessory has no automatic speaker owner")
        var connectedLater = AutomaticAudioScenario(camera: true)
        _ = connectedLater.send(.engineLocalFirstFrame(callId: callId))
        _ = connectedLater.send(.engineAudioRouteChanged(AudioRoute(kind: kind, label: "Headset")))
        check(!connectedLater.policy.ownsSpeaker, "new \(kind) cancels automatic route ownership")
        check(!connectedLater.send(.setCameraEnabled(false)).contains(.engineSetSpeaker(false)), "camera off cannot pull audio out of \(kind)")
    }
    var remoteOnly = AutomaticAudioScenario()
    check(!remoteOnly.send(.remoteVideoState(callId: callId, enabled: true)).contains(.engineSetSpeaker(true)), "remote video alone cannot enable speaker")
    check(remoteOnly.send(.engineRemoteFirstFrame(callId: callId)).isEmpty && !remoteOnly.policy.ownsSpeaker,
          "remote first frame cannot enable speaker")
    var resume = AutomaticAudioScenario(camera: true)
    _ = resume.send(.engineLocalFirstFrame(callId: callId))
    _ = resume.send(.engineAudioRouteChanged(AudioRoute(kind: .earpiece, label: "Interrupted")))
    check(resume.policy.restoreSpeakerIfNeeded(in: resume.state), "reactivation restores a still-owned automatic speaker")
    _ = resume.send(.engineAudioRouteChanged(AudioRoute(kind: .earpiece, label: "CallKit"), userInitiated: true))
    check(resume.policy.manualRouteChosen && !resume.policy.restoreSpeakerIfNeeded(in: resume.state),
          "native CallKit speaker-off wins over later reactivation")
    var ended = AutomaticAudioScenario(camera: true)
    _ = ended.send(.engineLocalFirstFrame(callId: callId))
    check(ended.send(.remoteEnded(callId: callId, reason: "cancelled")).contains(.engineSetSpeaker(false)), "call termination releases automatic output")
    check(ended.send(.engineLocalFirstFrame(callId: callId)).isEmpty && !ended.policy.ownsSpeaker, "late frame cannot resurrect a cancelled call route")
    var foreign = AutomaticAudioScenario(camera: true)
    check(foreign.send(.engineLocalFirstFrame(callId: UUID().uuidString)).isEmpty && !foreign.policy.ownsSpeaker,
          "another call's first frame cannot route this call")
    _ = foreign.send(.engineLocalFirstFrame(callId: callId))
    check(foreign.policy.reset(), "account shutdown releases owned automatic route")
    check(!foreign.policy.reset() && foreign.policy.callId == nil, "account shutdown is idempotent and clears identity")
    var next = AutomaticAudioScenario(camera: true)
    _ = next.send(.setAudioRoute(AudioRoute(kind: .earpiece, label: "Manual")))
    _ = next.send(.remoteEnded(callId: callId, reason: "hangup"))
    var c = ConnectedState(call: CallInfo(callId: "new-call", remoteUserId: calleeId, remoteDisplayName: "Next", mediaKind: .video, direction: .outgoing), videoMode: .video)
    c.localVideoEnabled = true; c.localCameraState = .starting
    next.state = CallReducerState(ui: .connected(c), activeCallId: "new-call")
    expect(next.send(.engineLocalFirstFrame(callId: "new-call")), [.engineSetSpeaker(true)], "manual choice does not leak into a different call")
    for frameFirst in [true, false] {
        var initial = AutomaticAudioScenario()
        initial.state = CallReducerState(ui: .connecting(outgoingInfo(.video)), activeCallId: callId)
        _ = initial.policy.reset()
        if frameFirst {
            check(initial.send(.engineLocalFirstFrame(callId: callId)).isEmpty, "early local frame never makes connecting audio loud")
        }
        let connectedEffects = initial.send(.engineConnectionState(callId: callId, state: .connected))
        if frameFirst {
            check(connectedEffects.contains(.engineSetSpeaker(true)), "buffered local frame enables automatic speaker on connect")
        } else {
            check(!connectedEffects.contains(.engineSetSpeaker(true)), "connection without proven camera keeps private audio")
            expect(initial.send(.engineLocalFirstFrame(callId: callId)), [.engineSetSpeaker(true)], "frame after connection enables speaker")
        }
        expect(initial.state.audioControls?.localCameraState, .on, "both frame orderings finish camera readiness")
    }
    var earlyFailure = AutomaticAudioScenario()
    earlyFailure.state = CallReducerState(ui: .connecting(outgoingInfo(.video)), activeCallId: callId)
    _ = earlyFailure.policy.reset()
    _ = earlyFailure.send(.engineLocalFirstFrame(callId: callId))
    _ = earlyFailure.send(.localVideoFailed)
    check(!earlyFailure.send(.engineConnectionState(callId: callId, state: .connected)).contains(.engineSetSpeaker(true)),
          "camera failure invalidates a buffered frame before connect")
    _ = earlyFailure.send(.setCameraEnabled(true))
    expect(earlyFailure.send(.engineLocalFirstFrame(callId: callId)), [.engineSetSpeaker(true)],
           "camera explicitly recovered after initial failure can enable automatic speaker")
    var elsewhere = AutomaticAudioScenario(camera: true)
    _ = elsewhere.send(.engineLocalFirstFrame(callId: callId))
    check(elsewhere.send(.remoteEnded(callId: callId, reason: "answered_elsewhere")).contains(.engineSetSpeaker(false)),
          "answered-elsewhere terminal releases automatic route")
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

if failures > 0 {
    print("\(failures) call state contract check(s) failed")
    exit(1)
}
print("Call state contract checks passed")
