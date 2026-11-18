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

// The restart schedule itself (timing, backoff, budget) is the pure
// CallRecoveryPolicy, contract-tested in ios/Tests/CallNetworkContracts.

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

// MARK: - B3: one local offer at a time, stale answers never applied

func sdp(_ session: String, _ version: Int, _ body: String = "m=audio 9 UDP/TLS/RTP/SAVPF 111") -> String {
    "v=0\r\no=- \(session) \(version) IN IP4 127.0.0.1\r\ns=-\r\n\(body)\r\n"
}

func connectedCall(_ direction: CallDirection = .outgoing, media: CallMediaKind = .audio) -> CallReducerState {
    let call = CallInfo(callId: callId, remoteUserId: calleeId, remoteDisplayName: "Peer", mediaKind: media, direction: direction)
    var s = CallReducerState(ui: .connected(ConnectedState(call: call, videoMode: media == .video ? .video : .audio)), activeCallId: callId)
    s.remoteDescriptionSet = true
    // The initial answer from the peer (session P, version 2).
    s.remoteSdpOrigin = CallSdpOrigin(session: "P", version: 2)
    return s
}

expect(CallSdpOrigin.parse(sdp("8016483157078594518", 7)), CallSdpOrigin(session: "8016483157078594518", version: 7), "o= line parsed (session, version)")
expect(CallSdpOrigin.parse("v=0\r\nm=audio 9 RTP/AVP 0\r\n"), nil, "no o= line -> no origin")
expect(CallSdpOrigin.parse("v=0\no=- 1 x IN IP4 0.0.0.0\n"), nil, "malformed version -> no origin")

// The capability marker: added once at session level, before the first media
// section, to the text that is sent; a description carrying it tells the
// reducer that the peer resolves crossing offers.
do {
    let plain = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    let marked = CallSdpCapabilities.advertising(plain)
    expect(marked, "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n\(CallSdpCapabilities.perfectNegotiation)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n", "marker inserted before the first media section")
    expect(CallSdpCapabilities.advertising(marked), marked, "marker added only once")
    check(CallSdpCapabilities.advertises(marked) && !CallSdpCapabilities.advertises(plain), "marker detected only where present")
    check(CallSdpCapabilities.advertising("v=0\ns=-\n").hasSuffix(CallSdpCapabilities.perfectNegotiation + "\n"), "a description without media gets it at the end")
    expect(CallSdpOrigin.parse(marked), CallSdpOrigin(session: "1", version: 2), "the marker leaves the o= line alone")
    // A plain peer (current Android, older iOS) is not marked capable; a marked one is.
    var s = connectedCall(.incoming)
    s.remoteSdpOrigin = nil
    check(!CallStateReducer.reduce(s, .remoteOffer(callId: callId, sdp: plain)).0.peerResolvesGlare, "a plain peer's offer does not announce the capability")
    check(CallStateReducer.reduce(s, .remoteOffer(callId: callId, sdp: marked)).0.peerResolvesGlare, "a marked offer announces the capability")
    var connecting = CallReducerState(ui: .connecting(info()), activeCallId: callId)
    connecting.remoteDescriptionSet = false
    check(CallStateReducer.reduce(connecting, .remoteAnswer(callId: callId, sdp: marked)).0.peerResolvesGlare, "the initial answer announces it too")
}

do {
    // Reconnecting caller: a restart offer starts; a second one is refused (B3).
    state = connectedCall()
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    let first = reduce(.restartIceOfferRequested(callId: callId))
    expect(first.1, [.engineSetVideoNegotiationEnabled(false), .produceIceRestartOfferAndSend(callId: callId)], "restart offer: intent first, then a fresh-credential offer")
    check(state.renegotiating && state.pendingOfferIsRestart, "restart offer is pending")
    let generation = state.offerGeneration
    let second = reduce(.restartIceOfferRequested(callId: callId))
    expect(second.1, [], "B3: no second offer while the first is unanswered")
    expect(state.offerGeneration, generation, "B3: the pending offer keeps its generation (the controller resends it)")

    // The stale answer of an earlier round (version not newer) is never applied.
    let stale = reduce(.remoteAnswer(callId: callId, sdp: sdp("P", 2)))
    expect(stale.1, [], "B3: an answer no newer than the applied description is ignored")
    check(state.renegotiating, "B3: the stale answer does not complete the round")
    // The real answer (newer version) completes the round.
    let real = reduce(.remoteAnswer(callId: callId, sdp: sdp("P", 3)))
    expect(real.1, [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp("P", 3))], "the newer answer is applied")
    check(!state.renegotiating && !state.pendingOfferIsRestart, "the answer completes the round")
    expect(state.remoteSdpOrigin, CallSdpOrigin(session: "P", version: 3), "the applied answer becomes the newest remote description")
    // A duplicate of it arriving late (the answer to a resent offer, or the same frame twice).
    expect(reduce(.remoteAnswer(callId: callId, sdp: sdp("P", 3))).1, [], "a duplicate answer is ignored")
    // The next round may start now.
    let third = reduce(.restartIceOfferRequested(callId: callId))
    check(third.1.contains(.produceIceRestartOfferAndSend(callId: callId)), "a new round starts once the previous one is answered")
    // An answer from a new peer session (no shared counter) is not judged stale.
    expect(reduce(.remoteAnswer(callId: callId, sdp: sdp("Q", 1))).1, [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp("Q", 1))], "another session's description is not compared")
}

do {
    // A restart offer from the peer while the call is connected (its network
    // switched) keeps the picture: the frames keep coming on the same track,
    // and no new first frame would end a `.waiting` covering the video.
    var video = connectedCall(.incoming, media: .video)
    var vc = video.audioControls!
    vc.remoteVideoEnabled = true
    vc.remoteVideoState = .on
    video.ui = .connected(vc)
    let videoOffer = sdp("P", 3, "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv")
    let kept = CallStateReducer.reduce(video, .remoteOffer(callId: callId, sdp: videoOffer))
    expect(kept.0.audioControls?.remoteVideoState, .on, "a restart offer keeps the peer's picture on")
    check(kept.1.contains(.produceAnswerAndSend(callId: callId)), "and is answered")
    // A peer's video that only starts with this offer still waits for its first frame.
    vc.remoteVideoState = .waiting
    video.ui = .connected(vc)
    expect(CallStateReducer.reduce(video, .remoteOffer(callId: callId, sdp: videoOffer)).0.audioControls?.remoteVideoState, .waiting,
           "video not yet shown keeps waiting for its first frame")
}

do {
    // A caller that moved the call to the relay (relay-only ICE, then the
    // restart) sends an ordinary restart offer whose candidates are all relay
    // ones. The reconnecting callee applies and answers it like any restart
    // offer, adds those candidates, and the call comes back as it was.
    state = connectedCall(.incoming)
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    if case .reconnecting = state.ui {} else { fail("connected + disconnected -> reconnecting") }
    let relayOffer = sdp("P", 3)
    let answered = reduce(.remoteOffer(callId: callId, sdp: relayOffer))
    check(answered.1.contains(.engineSetRemoteDescription(callId: callId, type: "offer", sdp: relayOffer)), "the callee applies the relay restart offer")
    check(answered.1.contains(.produceAnswerAndSend(callId: callId)), "and answers it")
    check(!state.renegotiating, "answering it starts no offer of the callee's own")
    let relayCandidate = "candidate:3 1 udp 41885439 203.0.113.7 3478 typ relay raddr 198.51.100.2 rport 60000 generation 0 ufrag Rl01"
    expect(reduce(.remoteIce(callId: callId, candidate: relayCandidate, sdpMid: "0", sdpMLineIndex: 0)).1,
           [.engineAddIce(callId: callId, candidate: relayCandidate, sdpMid: "0", sdpMLineIndex: 0)],
           "the caller's relay candidates are added like any others")
    _ = reduce(.engineConnectionState(callId: callId, state: .connected))
    if case .connected = state.ui { print("PASS: the relayed restart brings the call back") }
    else { fail("the relayed restart brings the call back") }
}

do {
    // A remote offer older than the newest applied description is a replay; the
    // same version (a resend of the offer we answered) is answered again.
    state = connectedCall(.incoming)
    state.remoteSdpOrigin = CallSdpOrigin(session: "P", version: 5)
    expect(reduce(.remoteOffer(callId: callId, sdp: sdp("P", 4))).1, [], "an older remote offer is ignored")
    check(reduce(.remoteOffer(callId: callId, sdp: sdp("P", 5))).1.contains(.produceAnswerAndSend(callId: callId)), "a resent offer (same version) is answered again")
}

do {
    // Glare during recovery: the caller (impolite) keeps its offer and ignores
    // the callee's; the callee (polite) rolls its own back and answers.
    state = connectedCall(.outgoing)
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    _ = reduce(.restartIceOfferRequested(callId: callId))
    let impolite = reduce(.remoteOffer(callId: callId, sdp: sdp("P", 3)))
    expect(impolite.1, [], "caller ignores the callee's offer while its own is pending")
    check(state.renegotiating, "caller's offer still pending")

    state = connectedCall(.incoming)
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    _ = reduce(.restartIceOfferRequested(callId: callId))
    let polite = reduce(.remoteOffer(callId: callId, sdp: sdp("P", 3)))
    expect(polite.1.first, .engineRollbackLocalOffer, "callee rolls its offer back first")
    check(polite.1.contains(.engineSetRemoteDescription(callId: callId, type: "offer", sdp: sdp("P", 3))), "callee applies the caller's offer")
    check(polite.1.contains(.produceAnswerAndSend(callId: callId)), "callee answers the caller's offer")
    check(!state.renegotiating && !state.pendingOfferIsRestart, "callee has no offer pending after the rollback")
    expect(reduce(.localAnswerSent(callId: callId)).1, [], "a rolled-back restart needs no new offer")

    // A rolled-back video offer is offered again once the answer went out.
    state = connectedCall(.incoming)
    state.renegotiating = true
    state.renegotiationTarget = .video
    _ = reduce(.remoteOffer(callId: callId, sdp: sdp("P", 3)))
    expect(state.deferredOfferTarget, .video, "the rolled-back video offer waits for our answer")
    expect(reduce(.localAnswerSent(callId: callId)).1, [.engineSetVideoNegotiationEnabled(true), .produceOfferAndSend(callId: callId)], "then it is offered again")
}

do {
    // A video upgrade accepted while a restart is in flight waits for it (B3),
    // then goes out as its own offer.
    state = connectedCall(.outgoing)
    var c = state.audioControls!
    c.videoUpgrade = .requesting
    state.ui = .connected(c)
    state.renegotiating = true
    state.pendingOfferIsRestart = true
    state.renegotiationTarget = .audio
    let accepted = reduce(.remoteVideoUpgradeAccept(callId: callId))
    expect(accepted.1, [.cancelVideoUpgradeTimeout], "upgrade offer deferred while a restart is pending")
    expect(state.deferredOfferTarget, .video, "upgrade waits behind the restart")
    let answered = reduce(.remoteAnswer(callId: callId, sdp: sdp("P", 3)))
    expect(answered.1, [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp("P", 3)),
                        .engineSetVideoNegotiationEnabled(true), .produceOfferAndSend(callId: callId)], "restart answer, then the upgrade offer")
    expect(state.audioControls?.videoUpgrade, .upgrading, "the restart answer leaves the upgrade in progress")
    check(state.renegotiating && !state.pendingOfferIsRestart && state.renegotiationTarget == .video, "the upgrade offer is now the pending one")
}

do {
    // Media came back but the restart offer was never answered: roll it back.
    state = connectedCall()
    _ = reduce(.restartIceOfferRequested(callId: callId))
    check(state.renegotiating, "a proactive restart (network switch) starts while connected")
    let abandoned = reduce(.localOfferAbandoned(callId: callId))
    expect(abandoned.1, [.engineRollbackLocalOffer], "abandoning rolls the offer back")
    check(!state.renegotiating && !state.pendingOfferIsRestart, "nothing pending afterwards")
    expect(state.audioControls?.notice, nil, "an abandoned restart shows no notice")
    // No proactive restart in the middle of a video change.
    var upgrading = connectedCall()
    var uc = upgrading.audioControls!
    uc.videoUpgrade = .incomingRequest
    upgrading.ui = .connected(uc)
    expect(CallStateReducer.reduce(upgrading, .restartIceOfferRequested(callId: callId)).1, [], "no proactive restart during a video change")
}

do {
    // A failed round while reconnecting does not end the call (the recovery
    // budget does); while connected a failed restart shows no video notice.
    state = connectedCall()
    _ = reduce(.engineConnectionState(callId: callId, state: .disconnected))
    _ = reduce(.restartIceOfferRequested(callId: callId))
    let failed = reduce(.mediaNegotiationFailed(callId: callId))
    expect(state.ui, .reconnecting(info()), "a failed restart round keeps the call reconnecting")
    expect(failed.1, [.engineRollbackLocalOffer], "the failed round is rolled back")
    check(!state.renegotiating, "nothing pending after a failed round")
    state = connectedCall()
    _ = reduce(.restartIceOfferRequested(callId: callId))
    _ = reduce(.mediaNegotiationFailed(callId: callId))
    expect(state.audioControls?.notice, nil, "a failed proactive restart is not a video failure")
}

// MARK: - Pausing outgoing video on a starved link

do {
    var s = connectedCall(media: .video)
    var c = s.audioControls!
    c.localVideoEnabled = true
    c.localCameraState = .on
    s.ui = .connected(c)
    state = s
    let paused = reduce(.outgoingVideoPaused(callId: callId, paused: true))
    expect(paused.1, [.engineSetVideoSendActive(false), .sendVideoState(callId: callId, enabled: false)], "pause stops the encoder and tells the peer")
    expect(state.audioControls?.visibleNotice, .videoPausedWeakConnection, "the pause is shown for as long as it lasts")
    expect(state.audioControls?.notice, nil, "the pause is not a timed notice")
    expect(reduce(.outgoingVideoPaused(callId: callId, paused: true)).1, [], "a repeated pause changes nothing")
    let interrupted = reduce(.engineLocalCaptureInterrupted(callId: callId, interrupted: false))
    check(interrupted.1.contains(.sendVideoState(callId: callId, enabled: false)), "capture recovering while paused keeps the peer on the avatar")
    let resumed = reduce(.outgoingVideoPaused(callId: callId, paused: false))
    expect(resumed.1, [.engineSetVideoSendActive(true), .sendVideoState(callId: callId, enabled: true)], "resume restarts the encoder and tells the peer")
    expect(state.audioControls?.visibleNotice, nil, "no notice after resuming")
    _ = reduce(.outgoingVideoPaused(callId: callId, paused: true))
    let cameraOn = reduce(.setCameraEnabled(true))
    expect(cameraOn.1.first, .engineSetVideoSendActive(true), "turning the camera on again retries sending")
    check(state.audioControls?.outgoingVideoPaused == false, "the camera toggle clears the pause")
    _ = reduce(.outgoingVideoPaused(callId: callId, paused: true))
    let cameraOff = reduce(.setCameraEnabled(false))
    expect(Array(cameraOff.1.prefix(2)), [.engineSetVideoEnabled(false), .engineSetVideoSendActive(true)],
           "camera-off stops capture before reactivating the paused encoding")
    check(state.audioControls?.outgoingVideoPaused == false, "camera-off ends the pause")
    let cameraBackOn = reduce(.setCameraEnabled(true))
    // beab0c32: entering STARTING must re-arm the local first-frame sink.
    expect(cameraBackOn.1, [.engineSetVideoEnabled(true), .sendVideoState(callId: callId, enabled: true), .engineExpectLocalVideoFrame],
           "camera-on enables retained capture and re-arms its first-frame sink without a pause-resume effect")
    check(state.audioControls?.localVideoEnabled == true && state.audioControls?.outgoingVideoPaused == false,
          "the ordinary off/on toggle restores video intent without a pause")
    // Nothing to pause in an audio call or before the call is up.
    state = connectedCall()
    expect(reduce(.outgoingVideoPaused(callId: callId, paused: true)).1, [], "audio calls never pause video")
}

do {
    // Every way out of a pause turns sending back on in the engine, or a
    // later camera (an upgrade, the camera turned on) would never encode.
    func pausedVideoCall() -> CallReducerState {
        var s = connectedCall(media: .video)
        var c = s.audioControls!
        c.localVideoEnabled = true
        c.localCameraState = .on
        c.outgoingVideoPaused = true
        s.ui = .connected(c)
        return s
    }
    state = pausedVideoCall()
    let audio = reduce(.switchToAudio)
    check(state.audioControls?.outgoingVideoPaused == false, "switching to audio ends the pause")
    check(audio.1.contains(.engineSetVideoSendActive(true)), "and turns sending back on")
    let stop = audio.1.firstIndex(of: .engineStopLocalVideo)
    let resume = audio.1.firstIndex(of: .engineSetVideoSendActive(true))
    check(stop != nil && resume != nil && stop! < resume!, "after the camera is stopped")
    var pendingOffer = pausedVideoCall()
    pendingOffer.renegotiating = true
    state = pendingOffer
    expect(reduce(.switchToAudio).1, [.engineStopLocalVideo, .engineSetVideoSendActive(true)],
           "with an offer in flight too")
    state = pausedVideoCall()
    let downgrade = reduce(.remoteOffer(callId: callId, sdp: sdp("P", 3, "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 0 UDP/TLS/RTP/SAVPF 96")))
    check(state.audioControls?.outgoingVideoPaused == false, "the peer's full audio downgrade ends the pause")
    expect(Array(downgrade.1.prefix(2)), [.engineStopLocalVideo, .engineSetVideoSendActive(true)],
           "and turns sending back on once the camera is gone")
    var notPaused = connectedCall(media: .video)
    var c = notPaused.audioControls!
    c.localVideoEnabled = true
    notPaused.ui = .connected(c)
    state = notPaused
    check(!reduce(.switchToAudio).1.contains(.engineSetVideoSendActive(true)), "without a pause nothing to turn back on")
}

if failures > 0 {
    print("\(failures) ICE restart contract check(s) failed")
    exit(1)
}
print("ICE restart contract checks passed")
