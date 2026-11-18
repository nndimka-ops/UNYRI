import Foundation

// Pure contracts for calls on real networks (CallNetworkPolicy.swift): the
// media ladder, interface changes, relay-only privacy, the data-saving
// setting and its migration, the recovery schedule and budget, the fallback
// to the relay, a selected pair that never carries the voice, pausing video
// on a starved link, where the bandwidth estimate restarts, when the media
// diagnostics are taken, TURN credential timing and the SDP details this
// client negotiates. No network, no WebRTC.

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

func path(_ interface: CallNetworkInterface?, expensive: Bool = false, constrained: Bool = false) -> CallNetworkPath {
    CallNetworkPath(satisfied: interface != nil, interface: interface, isExpensive: expensive, isConstrained: constrained)
}

// MARK: - Media ladder (audit section 5)

expect(CallMediaLadder.wifi.width, 1280, "Wi-Fi row: 1280 wide")
expect(CallMediaLadder.wifi.height, 720, "Wi-Fi row: 720 high")
expect(CallMediaLadder.wifi.fps, 30, "Wi-Fi row: 30 fps")
expect(CallMediaLadder.wifi.maxVideoBitrate, 2_000_000, "Wi-Fi row: 2.0 Mbps")
expect(CallMediaLadder.wifi.startBitrate, 800_000, "Wi-Fi row: starts at 800 kbps")
expect([CallMediaLadder.cellular.width, CallMediaLadder.cellular.height, CallMediaLadder.cellular.fps], [960, 540, 30],
       "cellular row: 960x540 at 30 fps, the capture's own rate (24 dropped one frame in five)")
expect(CallMediaLadder.cellular.maxVideoBitrate, 1_000_000, "cellular row: 1.0 Mbps")
expect(CallMediaLadder.cellular.startBitrate, 650_000, "cellular row: starts at 650 kbps")
expect([CallMediaLadder.saving.width, CallMediaLadder.saving.height, CallMediaLadder.saving.fps], [640, 360, 15], "saving row: 640x360 at 15 fps")
expect(CallMediaLadder.saving.maxVideoBitrate, 350_000, "saving row: 350 kbps")
expect(CallMediaLadder.saving.startBitrate, 400_000, "saving row: starts at 400 kbps")
for row in [CallMediaLadder.wifi, CallMediaLadder.cellular, CallMediaLadder.saving] {
    expect(row.audioMinBitrate, 16_000, "\(row.tier): audio never below 16 kbps")
    // libwebrtc drops a starting encoder's frames down a size step while its
    // target is under 500 kbps (frames over 640x480) or 300 kbps (over
    // 320x240). The target at the start: the seed less the audio maximum and
    // about 30 kbps of packet overhead, never above the video cap.
    let overhead = 30_000
    let videoAtStart = min(row.startBitrate - row.audioMaxBitrate - overhead, row.maxVideoBitrate)
    let pixels = row.width * row.height
    let threshold = pixels > 640 * 480 ? 500_000 : pixels > 320 * 240 ? 300_000 : 0
    check(videoAtStart >= threshold, "\(row.tier): the first frames go out at \(row.width)x\(row.height) (\(videoAtStart) >= \(threshold))")
    check(row.startBitrate <= row.maxVideoBitrate + row.audioMaxBitrate + overhead,
          "\(row.tier): the estimate never starts above what the row may send")
}
check((16_000...20_000).contains(CallMediaLadder.saving.audioMaxBitrate), "saving: 16-20 kbps audio")
check(CallMediaLadder.saving.requestsDtx && CallMediaLadder.cellular.requestsDtx && !CallMediaLadder.wifi.requestsDtx, "DTX only on cellular and saving")
check(CallMediaLadder.wifi.prefersRed && CallMediaLadder.cellular.prefersRed && !CallMediaLadder.saving.prefersRed, "RED except when saving data")

expect(CallMediaLadder.tier(for: path(.wifi), saving: .never), .wifi, "Wi-Fi, Never -> Wi-Fi row")
expect(CallMediaLadder.tier(for: path(.wired), saving: .never), .wifi, "wired counts as Wi-Fi")
expect(CallMediaLadder.tier(for: path(.cellular, expensive: true), saving: .never), .cellular, "cellular, Never -> cellular row")
expect(CallMediaLadder.tier(for: path(.wifi, expensive: true), saving: .never), .cellular, "a metered Wi-Fi (hotspot) gets the cellular row")
expect(CallMediaLadder.tier(for: path(.wifi, constrained: true), saving: .never), .saving, "Low Data Mode -> saving row")
expect(CallMediaLadder.tier(for: path(.cellular, expensive: true), saving: .cellular), .saving, "On cellular + cellular -> saving row")
expect(CallMediaLadder.tier(for: path(.wifi), saving: .cellular), .wifi, "On cellular + Wi-Fi -> Wi-Fi row")
expect(CallMediaLadder.tier(for: path(.wifi), saving: .always), .saving, "Always -> saving row on Wi-Fi too")
expect(CallMediaLadder.tier(for: .unknown, saving: .never), .cellular, "before the first path report: the middle row")
expect(CallMediaLadder.profile(for: path(.cellular, expensive: true), saving: .never), CallMediaLadder.cellular, "profile follows the tier")
expect(CallMediaLadder.captureProfile(saving: .never), CallMediaLadder.wifi, "the camera captures for the best reachable row")
expect(CallMediaLadder.captureProfile(saving: .cellular), CallMediaLadder.wifi, "On cellular can still reach Wi-Fi quality")
expect(CallMediaLadder.captureProfile(saving: .always), CallMediaLadder.saving, "Always never captures more than it sends")

// MARK: - Interface changes

check(CallNetworkPath.interfaceChanged(from: path(.wifi), to: path(.cellular, expensive: true)), "Wi-Fi -> cellular is a change")
check(CallNetworkPath.interfaceChanged(from: path(.cellular), to: path(.wifi)), "cellular -> Wi-Fi is a change")
check(!CallNetworkPath.interfaceChanged(from: path(.wifi), to: path(.wifi)), "same interface again is not a change")
check(!CallNetworkPath.interfaceChanged(from: path(.wifi), to: .unknown), "losing the network is not yet a change")
check(!CallNetworkPath.interfaceChanged(from: nil, to: path(.wifi)), "the first path is not a change")
check(!CallNetworkPath.interfaceChanged(from: path(.wifi), to: path(.wifi, constrained: true)), "Low Data Mode toggling is not an interface change")

// MARK: - Relay-only privacy

check(!CallRelayPolicy.relayOnly(.everyone, peerIsContact: false), "Everyone: direct with anyone")
check(!CallRelayPolicy.relayOnly(.contacts, peerIsContact: true), "My contacts: direct with a contact")
check(CallRelayPolicy.relayOnly(.contacts, peerIsContact: false), "My contacts: relay-only with anyone else")
check(CallRelayPolicy.relayOnly(.nobody, peerIsContact: true), "Nobody: relay-only even with a contact")
expect(CallPeerToPeer.resolved(stored: nil), .everyone, "Peer-to-peer defaults to Everyone (unchanged behaviour)")
expect(CallPeerToPeer.resolved(stored: "nobody"), .nobody, "stored choice kept")
expect(CallPeerToPeer.resolved(stored: "future"), .everyone, "unknown value falls back")

// MARK: - "Use less data for calls" and the old "Call quality" values

expect(CallDataSaving.resolved(stored: "cellular", legacyVideo: "economy", legacyAudio: nil), .cellular, "a stored choice wins over old values")
expect(CallDataSaving.resolved(stored: nil, legacyVideo: nil, legacyAudio: nil), .never, "fresh install: Never")
expect(CallDataSaving.resolved(stored: nil, legacyVideo: "auto", legacyAudio: "auto"), .never, "Auto/Auto -> Never")
expect(CallDataSaving.resolved(stored: nil, legacyVideo: "high", legacyAudio: "high"), .never, "High -> Never (the network decides)")
expect(CallDataSaving.resolved(stored: nil, legacyVideo: "economy", legacyAudio: "auto"), .always, "video Save data -> Always")
expect(CallDataSaving.resolved(stored: nil, legacyVideo: "auto", legacyAudio: "economy"), .always, "audio Save data -> Always")
expect(CallDataSaving.resolved(stored: "bogus", legacyVideo: nil, legacyAudio: nil), .never, "an unknown stored value falls back")

// MARK: - Recovery schedule and budget

do {
    // Caller toward a peer that resolves crossing offers: disconnected
    // restarts at once. ICE has already waited 5 s without an answer, and the
    // pair does not come back by itself; every second more is frozen media.
    expect(CallRecoveryPolicy.disconnectGrace, 0, "the caller does not wait after disconnected")
    var p = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    p.mediaDisconnected(at: 100)
    expect(p.next(now: 100), .restart, "disconnected: the caller restarts at once")
    p.offerRequested(at: 100, restart: true)
    expect(p.next(now: 101), .wait(until: 105), "the offer waits 5 s for its answer")
    expect(p.next(now: 105), .resendOffer, "B3: unanswered -> resend the same offer")
    p.offerSent(at: 105)
    p.offerResolved(at: 106)
    check(p.earlierRoundFailed, "answered while media is still down: that round failed")
    expect(p.next(now: 106), .wait(until: 110), "then the usual 4 s backoff")
    p.offerRequested(at: 110, restart: true)
    p.offerResolved(at: 111)
    expect(p.next(now: 111), .wait(until: 119), "second backoff: 8 s")
    p.offerRequested(at: 119, restart: true)
    p.offerResolved(at: 120)
    expect(p.next(now: 120), .wait(until: 130), "third backoff (12 s) would pass the budget: wait for the deadline")
    expect(p.next(now: 130), .fail, "30 s after the first trouble the call fails")
    // Media that came back while the restart waited for credentials: the
    // restart is no longer wanted (the controller checks again after the wait).
    var healed = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    healed.mediaDisconnected(at: 0)
    expect(healed.next(now: 0), .restart, "a restart is due")
    check(!healed.earlierRoundFailed, "the first restart of an outage follows no failed round")
    healed.mediaConnected(at: 0.8)
    expect(healed.next(now: 0.8), .idle, "media back before the offer went out: no restart")
    check(!healed.isActive && !healed.earlierRoundFailed, "and recovery is over")
}

do {
    // Caller toward a plain peer (the current Android app, older iOS builds):
    // disconnected still waits 3 s, never more than one offer, 30 s budget.
    // That peer may notice the loss up to a check interval later, and one
    // still connected on its side takes a restart offer for a video change.
    expect(CallRecoveryPolicy.plainPeerDisconnectGrace, 3, "toward a plain peer the caller waits 3 s")
    var p = CallRecoveryPolicy(role: .caller)
    expect(p.next(now: 0), .idle, "connected: nothing to do")
    p.mediaDisconnected(at: 100)
    expect(p.next(now: 100), .wait(until: 103), "disconnected: wait 3 s before restarting")
    expect(p.next(now: 103), .restart, "then restart")
    p.offerRequested(at: 103, restart: true)
    expect(p.next(now: 104), .wait(until: 108), "the offer waits 5 s for its answer")
    expect(p.next(now: 108), .resendOffer, "B3: unanswered -> resend the same offer, never a new one")
    p.offerSent(at: 108)
    expect(p.next(now: 109), .wait(until: 113), "the resend restarts the answer timer")
    p.offerResolved(at: 109)
    expect(p.next(now: 109), .wait(until: 113), "after the answer: back off 4 s before the next round")
    p.offerRequested(at: 113, restart: true)
    p.offerResolved(at: 114)
    expect(p.next(now: 114), .wait(until: 122), "second backoff: 8 s")
    p.offerRequested(at: 122, restart: true)
    p.offerResolved(at: 123)
    expect(p.next(now: 123), .wait(until: 130), "third backoff (12 s) would pass the budget: wait for the deadline")
    expect(p.next(now: 130), .fail, "30 s after the first trouble the call fails")
    check(CallRecoveryPolicy.budget < 40, "the budget stays under the server's 40 s liveness window")
}

do {
    // A pending offer at the deadline fails the call (media still down).
    var p = CallRecoveryPolicy(role: .caller)
    p.mediaDisconnected(at: 0)
    p.offerRequested(at: 28, restart: true)
    expect(p.next(now: 30), .fail, "deadline with an unanswered offer -> fail")
}

do {
    // ICE failed restarts at once (caller); toward a plain peer, a disconnect
    // that heals within the grace needs no restart.
    var p = CallRecoveryPolicy(role: .caller)
    p.mediaDisconnected(at: 0)
    p.mediaConnected(at: 1.5)
    check(!p.isActive, "plain peer: a short disconnect that heals by itself ends recovery without a restart")
    p.mediaFailed(at: 10)
    expect(p.next(now: 10), .restart, "ICE failed: the caller restarts at once")
    var callee = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    callee.mediaFailed(at: 10)
    expect(callee.next(now: 10), .wait(until: 16), "ICE failed: the callee lets the caller go first")
}

do {
    // Toward a caller that does not resolve crossing offers (the current
    // Android app, older iOS builds) the callee never offers: it waits for the
    // caller and ICE, and fails only at the budget, as before.
    var callee = CallRecoveryPolicy(role: .callee)
    callee.interfaceChanged(at: 0)
    check(!callee.isActive, "callee + plain caller: a network switch alone starts nothing")
    expect(callee.next(now: 0), .idle, "callee + plain caller: no restart while media is up")
    callee.mediaDisconnected(at: 10)
    expect(callee.next(now: 10), .wait(until: 40), "callee + plain caller: waits for the caller until the budget")
    callee.interfaceChanged(at: 12)
    expect(callee.next(now: 12), .wait(until: 40), "callee + plain caller: a switch while down still offers nothing")
    expect(callee.next(now: 40), .fail, "callee + plain caller: fails at the budget")
    var silent = false
    var plain = CallRecoveryPolicy(role: .callee)
    plain.mediaDisconnected(at: 0)
    for step in 0..<300 where plain.next(now: Double(step) / 10) == .restart { silent = true }
    check(!silent, "callee + plain caller: never a restart offer within the budget")
    // Once the caller's description announces it, the callee may offer.
    plain.peerResolvesGlare = true
    expect(plain.next(now: 6), .restart, "callee + capable caller: its own offer after 6 s of silence")
}

do {
    // A caller with a plain peer never restarts before ICE reports trouble:
    // the peer, still connected on its side, would take a restart offer for a
    // video renegotiation and could hide the picture (current Android app).
    var caller = CallRecoveryPolicy(role: .caller)
    caller.interfaceChanged(at: 0)
    check(!caller.isActive, "caller + plain peer: a network switch while connected restarts nothing")
    caller.mediaDisconnected(at: 2)
    caller.interfaceChanged(at: 2.5)
    expect(caller.next(now: 2.5), .wait(until: 5), "caller + plain peer: the usual 3 s grace after disconnected")
    expect(caller.next(now: 5), .restart, "caller + plain peer: then the restart")
    var failed = CallRecoveryPolicy(role: .caller)
    failed.mediaFailed(at: 0)
    expect(failed.next(now: 0), .restart, "caller + plain peer: ICE failed still restarts at once")
    var capable = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    capable.interfaceChanged(at: 0)
    expect(capable.next(now: 0), .restart, "caller + capable peer: a network switch restarts at once")
    var capableDown = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    capableDown.mediaDisconnected(at: 2)
    expect(capableDown.next(now: 2), .restart, "caller + capable peer: disconnected restarts at once")
}

do {
    // A network switch restarts at once, even while ICE still says connected.
    var p = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    p.interfaceChanged(at: 50)
    expect(p.next(now: 50), .restart, "Wi-Fi <-> cellular: restart now (either side)")
    p.offerRequested(at: 50, restart: true)
    expect(p.next(now: 51), .wait(until: 55), "its offer waits for the answer")
    p.offerResolved(at: 51)
    check(!p.isActive, "answered while media is up: recovery is over")
    // Media came back but the offer stays unanswered until the deadline: roll it back.
    var q = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    q.interfaceChanged(at: 0)
    q.offerRequested(at: 0, restart: true)
    expect(q.next(now: 5), .resendOffer, "connected but unanswered: resend")
    q.offerSent(at: 5)
    expect(q.next(now: 30), .abandonOffer, "connected and still unanswered at the deadline: abandon the offer")
    q.offerResolved(at: 30)
    check(!q.isActive, "abandoned: recovery is over")
    // A switch while an offer is pending does not stack a second restart (B3).
    var r = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    r.mediaDisconnected(at: 0)
    r.offerRequested(at: 3, restart: true)
    r.interfaceChanged(at: 4)
    expect(r.next(now: 4), .wait(until: 8), "a network switch during a pending offer waits for its answer")
    r.offerResolved(at: 5)
    expect(r.next(now: 5), .wait(until: 9), "that restart answered: the usual backoff, no second urgent restart")
}

do {
    // An urgent restart that comes while a non-restart offer (switch to
    // audio, a video upgrade) is out waits for that offer (B3) and goes out
    // right after it; the answer no longer settles it away.
    var switched = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    switched.offerRequested(at: 0, restart: false)
    switched.interfaceChanged(at: 0.2)
    expect(switched.next(now: 0.2), .wait(until: 5), "a network switch while a video offer is out waits for its answer (B3)")
    switched.offerResolved(at: 0.4)
    check(switched.isActive, "the video answer does not end the recovery")
    expect(switched.next(now: 0.4), .restart, "the restart goes out right after it")
    switched.offerRequested(at: 0.4, restart: true)
    switched.offerResolved(at: 0.5)
    check(!switched.isActive, "the restart answered with media up: recovery is over")
    var resent = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    resent.offerRequested(at: 0, restart: false)
    resent.interfaceChanged(at: 1)
    expect(resent.next(now: 5), .resendOffer, "the video offer unanswered: resent as is")
    resent.offerSent(at: 5)
    resent.offerResolved(at: 5.3)
    expect(resent.next(now: 5.3), .restart, "answered on the resend: the restart follows")
    // A restart already due when the reducer starts a video offer first.
    var raced = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    raced.interfaceChanged(at: 0)
    raced.offerRequested(at: 0.1, restart: false)
    expect(raced.next(now: 0.1), .wait(until: 5.1), "a video offer that went out first: the restart waits for it")
    raced.offerResolved(at: 0.3)
    expect(raced.next(now: 0.3), .restart, "and follows its answer")
    // ICE failed during a video offer: the caller restarts at once after it.
    var failedWithOffer = CallRecoveryPolicy(role: .caller)
    failedWithOffer.offerRequested(at: 0, restart: false)
    failedWithOffer.mediaFailed(at: 1)
    failedWithOffer.offerResolved(at: 1.2)
    expect(failedWithOffer.next(now: 1.2), .restart, "ICE failed during a video offer: restart right after its answer")
    // No longer wanted: media came back, the peer drove a round, or the offer
    // was abandoned at the deadline.
    var healed = CallRecoveryPolicy(role: .caller)
    healed.offerRequested(at: 0, restart: false)
    healed.mediaFailed(at: 1)
    healed.mediaConnected(at: 1.5)
    healed.offerResolved(at: 2)
    check(!healed.isActive && healed.next(now: 2) == .idle, "media back before the answer: no restart")
    var peerDrove = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    peerDrove.remoteDescriptionApplied(sdp: "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=ice-ufrag:old\na=ice-pwd:old-password\n")
    peerDrove.offerRequested(at: 0, restart: false)
    peerDrove.interfaceChanged(at: 0.2)
    peerDrove.peerOfferReceived(sdp: "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=ice-ufrag:new\na=ice-pwd:new-password\n")
    peerDrove.offerResolved(at: 0.3)
    peerDrove.peerNegotiated(at: 0.4)
    check(!peerDrove.isActive && peerDrove.next(now: 0.4) == .idle, "the peer answered with its own round: ours backs off")
    var abandoned = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    abandoned.offerRequested(at: 0, restart: false)
    abandoned.interfaceChanged(at: 0)
    expect(abandoned.next(now: 30), .abandonOffer, "unanswered for the whole budget: rolled back")
    abandoned.offerResolved(at: 30)
    check(!abandoned.isActive && abandoned.next(now: 30) == .idle, "an abandoned offer takes the restart along")
}

do {
    // Actual callee glare order: accept the crossing offer, resolve the local
    // offer, await rollback/setRemote/createAnswer, then send the answer.
    func sdp(_ ufrag: String = "old", _ password: String = "old-password", sessionLevel: Bool = false) -> String {
        let ice = "a=ice-ufrag:\(ufrag)\r\na=ice-pwd:\(password)\r\n"
        let audio = "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        return "v=0\r\n" + (sessionLevel ? ice + audio : audio + ice)
    }
    func crossing(_ offer: String, down: Bool = false) -> CallRecoveryPolicy {
        var p = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
        p.remoteDescriptionApplied(sdp: sdp())
        p.offerRequested(at: 0, restart: false)
        p.interfaceChanged(at: 0.2)
        if down { p.mediaDisconnected(at: 0.25) }
        p.peerOfferReceived(sdp: offer)
        p.offerResolved(at: 0.3)
        return p
    }
    let restart = sdp("new", "new-password", sessionLevel: true)
    var healed = crossing(restart)
    expect(healed.next(now: 0.3), .idle, "glare rollback: no restart while the peer answer is pending")
    healed.remoteDescriptionApplied(sdp: restart)
    healed.peerNegotiated(at: 0.5)
    expect(healed.next(now: 0.5), .idle, "the peer ICE restart covers the switch: no extra round after the answer")
    check(!healed.isActive, "answered peer restart with media up settles recovery")

    var stillDown = crossing(restart, down: true)
    expect(stillDown.next(now: 0.4), .wait(until: 30.2), "media down during glare: wait for the answer without offering")
    stillDown.peerNegotiated(at: 0.5)
    expect(stillDown.next(now: 0.5), .wait(until: 8.5), "peer restart answered but still down: callee gives it the usual backoff")
    expect(stillDown.next(now: 8.5), .restart, "if the peer restart fails, callee recovery still runs")

    var videoOnly = crossing(sdp())
    expect(videoOnly.next(now: 0.4), .idle, "a crossing video offer is answered before the deferred restart")
    videoOnly.peerNegotiated(at: 0.5)
    expect(videoOnly.next(now: 0.5), .restart, "unchanged ICE credentials did not cover the switch: restart is still needed")
    var partial = crossing(sdp("new"))
    partial.peerNegotiated(at: 0.5)
    expect(partial.next(now: 0.5), .restart, "changing only one ICE credential cannot prove a peer restart")
    var unknown = crossing("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
    unknown.peerNegotiated(at: 0.5)
    expect(unknown.next(now: 0.5), .restart, "missing ICE credentials cannot cancel recovery intent")
    var videoIceOnly = crossing(sdp() + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=ice-ufrag:new\r\na=ice-pwd:new-password\r\n")
    videoIceOnly.peerNegotiated(at: 0.5)
    expect(videoIceOnly.next(now: 0.5), .restart, "another media section's ICE credentials do not prove a voice restart")

    var switchedAgain = crossing(restart)
    switchedAgain.interfaceChanged(at: 0.4)
    expect(switchedAgain.next(now: 0.4), .idle, "a new switch during createAnswer still waits for the answer")
    switchedAgain.peerNegotiated(at: 0.5)
    expect(switchedAgain.next(now: 0.5), .restart, "the earlier peer offer cannot swallow a later switch")
    var duplicate = crossing(restart)
    duplicate.interfaceChanged(at: 0.4)
    duplicate.peerOfferReceived(sdp: restart)
    duplicate.remoteDescriptionApplied(sdp: restart)
    duplicate.peerNegotiated(at: 0.5)
    expect(duplicate.next(now: 0.5), .idle, "a resend queued before setRemote completed still waits for its answer")
    duplicate.peerNegotiated(at: 0.6)
    expect(duplicate.next(now: 0.6), .restart, "the same peer offer resent cannot cover a later local switch")
    var mediaBack = crossing(sdp(), down: true)
    mediaBack.mediaConnected(at: 0.4)
    mediaBack.peerNegotiated(at: 0.5)
    expect(mediaBack.next(now: 0.5), .idle, "media back during the answer removes the remaining need")

    var expired = crossing(sdp())
    expired.peerNegotiated(at: 31)
    expect(expired.next(now: 31), .idle, "an async answer beyond the recovery budget cannot release a stale restart")
    check(!expired.isActive, "expired recovery with media up settles")
    var queued = crossing(sdp())
    queued.peerOfferReceived(sdp: sdp())
    queued.peerNegotiated(at: 0.5)
    expect(queued.next(now: 0.5), .idle, "an earlier answer cannot release recovery while another accepted offer awaits its answer")
    queued.peerNegotiated(at: 0.6)
    expect(queued.next(now: 0.6), .restart, "only after both answers can the still-needed restart run")

    var failed = crossing(sdp())
    failed.peerAnswerFailed(at: 0.5)
    expect(failed.next(now: 0.5), .restart, "a failed answer keeps the existing network switch")
    expect(failed.negotiations, 0, "a failed peer answer is not a completed restart round")

    var failedDown = crossing(restart, down: true)
    failedDown.peerAnswerFailed(at: 0.5)
    expect(failedDown.next(now: 0.5), .wait(until: 6.2), "a failed answer keeps the callee's original consent-loss schedule")
    expect(failedDown.next(now: 6.2), .restart, "answer failure does not block consent-loss recovery")

    var failedQueued = crossing(sdp())
    failedQueued.peerOfferReceived(sdp: sdp())
    failedQueued.peerAnswerFailed(at: 0.5)
    expect(failedQueued.next(now: 0.5), .idle, "a failed answer releases only its own slot")
    failedQueued.peerNegotiated(at: 0.6)
    expect(failedQueued.next(now: 0.6), .restart, "the remaining queued answer releases recovery on success")

    var bothFailed = crossing(sdp())
    bothFailed.peerOfferReceived(sdp: sdp())
    bothFailed.peerAnswerFailed(at: 0.5)
    bothFailed.peerAnswerFailed(at: 0.6)
    expect(bothFailed.next(now: 0.6), .restart, "all queued answers can fail without leaving a pending slot")

    var healedFailed = crossing(sdp(), down: true)
    healedFailed.mediaConnected(at: 0.4)
    healedFailed.peerAnswerFailed(at: 0.5)
    check(!healedFailed.isActive && healedFailed.next(now: 0.5) == .idle,
          "a failed answer does not restart media that already recovered")

    var failedExpired = crossing(restart)
    failedExpired.peerAnswerFailed(at: 31)
    check(!failedExpired.isActive && failedExpired.next(now: 31) == .idle,
          "a failed answer beyond the budget cannot resurrect an expired switch")
}

do {
    // The callee restarts only when the caller is silent; the caller always
    // goes first, so the two offers do not cross. A caller toward a plain
    // peer first, then a capable one.
    var callee = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    callee.mediaDisconnected(at: 0)
    expect(callee.next(now: 0), .wait(until: 6), "callee: first own offer after 6 s")
    callee.peerNegotiated(at: 3)
    expect(callee.next(now: 3), .wait(until: 11), "the caller restarted at 3 s: the callee waits backoff + 4 s")
    callee.peerNegotiated(at: 7.3)
    expect(callee.next(now: 7.3), .wait(until: 19.3), "each caller round pushes the callee back")
    // A caller restarting at 3, 7.3, 15.6, 27.9 s never meets a callee offer.
    var caller = CallRecoveryPolicy(role: .caller)
    caller.mediaDisconnected(at: 0)
    var calleeTwin = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    calleeTwin.mediaDisconnected(at: 0)
    var t: TimeInterval = 0
    var callerRounds: [TimeInterval] = []
    var calleeOffered = false
    while t < 30 {
        if caller.next(now: t) == .restart {
            caller.offerRequested(at: t, restart: true)
            caller.offerResolved(at: t + 0.3)
            calleeTwin.peerNegotiated(at: t + 0.2)
            callerRounds.append(t)
        }
        if calleeTwin.next(now: t) == .restart { calleeOffered = true }
        t += 0.1
    }
    check(!calleeOffered, "with a caller restarting, the callee never offers")
    check(callerRounds.count >= 3 && callerRounds.count <= 5, "the caller makes 3-5 rounds in the budget (\(callerRounds.count))")
    // A capable caller restarts at 0, then at about 4.3, 12.6 and 24.9 s; the
    // callee (6 s first, backoff + 4 s after each caller round) never crosses it.
    var fastCaller = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    fastCaller.mediaDisconnected(at: 0)
    var fastCallee = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    fastCallee.mediaDisconnected(at: 0)
    var tick = 0
    var fastRounds: [TimeInterval] = []
    var fastCalleeOffered = false
    while tick < 300 {
        let now = Double(tick) / 10
        if fastCaller.next(now: now) == .restart {
            fastCaller.offerRequested(at: now, restart: true)
            fastCaller.offerResolved(at: now + 0.3)
            fastCallee.peerNegotiated(at: now + 0.2)
            fastRounds.append(now)
        }
        if fastCallee.next(now: now) == .restart { fastCalleeOffered = true }
        tick += 1
    }
    expect(fastRounds.first, 0, "a capable caller's first restart is at once")
    check(!fastCalleeOffered, "with a caller restarting at once, the callee never offers")
    check(fastRounds.count >= 3 && fastRounds.count <= 5, "the capable caller makes 3-5 rounds in the budget (\(fastRounds.count))")
    // Even with a capable caller that restarts at 0 and 7.5 s (Android's
    // cadence), the callee's yield keeps its own offer out of that window.
    var cadenceCallee = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    cadenceCallee.mediaDisconnected(at: 0)
    cadenceCallee.peerNegotiated(at: 0.2)
    cadenceCallee.peerNegotiated(at: 7.7)
    var clash = false
    for step in 0..<150 where cadenceCallee.next(now: Double(step) / 10) == .restart { clash = true }
    check(!clash, "a caller restarting every 7.5 s is never crossed by the callee within 15 s")
}

do {
    // A non-restart offer (video change) pending when recovery starts is resent, not replaced.
    var p = CallRecoveryPolicy(role: .caller)
    p.offerRequested(at: 0, restart: false)
    expect(p.next(now: 100), .idle, "outside recovery a pending offer is left alone")
    p.mediaDisconnected(at: 100)
    expect(p.next(now: 100), .resendOffer, "recovery resends the long-unanswered offer first")
    p.offerSent(at: 100)
    p.offerResolved(at: 101)
    expect(p.negotiations, 0, "a video-change answer is not a restart round")
    expect(p.next(now: 101), .wait(until: 103), "then the normal restart schedule")
    // A restart the reducer refused (another offer holds it) backs off instead of spinning.
    var q = CallRecoveryPolicy(role: .caller)
    q.mediaFailed(at: 0)
    expect(q.next(now: 0), .restart, "urgent restart")
    q.restartDeferred(at: 0)
    check(q.next(now: 0) != .restart, "a deferred restart does not repeat at once")
}

// MARK: - Falling back to the relay

do {
    // What the controller reads off WebRTC: candidate types, direct pairs, TURN servers.
    expect(CallRelayFallbackPolicy.candidateType(sdp: "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0 ufrag Ab12 network-id 1"),
           "host", "a host candidate line")
    expect(CallRelayFallbackPolicy.candidateType(sdp: "a=candidate:3 1 udp 41885439 203.0.113.7 3478 typ relay raddr 198.51.100.2 rport 60000"),
           "relay", "a relay candidate, with the a= prefix")
    expect(CallRelayFallbackPolicy.candidateType(sdp: "candidate:2 1 udp 1686052607 198.51.100.2 60000 typ srflx raddr 0.0.0.0 rport 0"),
           "srflx", "a server-reflexive candidate")
    expect(CallRelayFallbackPolicy.candidateType(sdp: "m=audio 9 UDP/TLS/RTP/SAVPF 111"), nil, "not a candidate line")
    check(CallRelayFallbackPolicy.isDirect(localType: "host", remoteType: "host"), "host-host is direct")
    check(CallRelayFallbackPolicy.isDirect(localType: "srflx", remoteType: "prflx"), "reflexive ends are direct")
    check(!CallRelayFallbackPolicy.isDirect(localType: "relay", remoteType: "host"), "our relay is not direct")
    check(!CallRelayFallbackPolicy.isDirect(localType: "host", remoteType: "relay"), "the peer's relay is not direct")
    check(!CallRelayFallbackPolicy.isDirect(localType: nil, remoteType: "host"), "an unknown pair is never taken for direct")
    check(CallRelayFallbackPolicy.hasTurnServer(["stun:turn.example.org:3478", "turn:turn.example.org:3478?transport=udp"]), "a turn: URL")
    check(CallRelayFallbackPolicy.hasTurnServer(["TURNS:turn.example.org:5349?transport=tcp"]), "turns:, any case")
    check(!CallRelayFallbackPolicy.hasTurnServer(["stun:stun.example.org:3478"]), "STUN alone is no relay")
    check(!CallRelayFallbackPolicy.hasTurnServer([]), "no servers, no relay")
}

do {
    // The second consent loss of a direct pair in one call: the next restart
    // goes through the relay, for the rest of the call.
    var f = CallRelayFallbackPolicy()
    f.consentLost(direct: true, at: 10)
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "one loss: the ordinary restart (it heals in tens of ms)")
    f.mediaRestored()
    f.consentLost(direct: true, at: 70)
    expect(f.directLosses, 2, "two direct losses counted")
    expect(f.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "the second loss: relay")
    expect(f.relayReason(relayGathered: false, pairDirect: true), nil, "no relay candidate gathered in this call: the ordinary restart")
    expect(f.relayReason(relayGathered: true, pairDirect: false), nil, "the peer already went through the relay: nothing to switch here")
    f.relayApplied()
    check(f.relayOnly, "relay-only from now on")
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "never switched twice")
    f.mediaRestored()
    f.consentLost(direct: true, at: 120)
    expect(f.directLosses, 2, "losses after the switch are not counted")

    // A relay that never took (WebRTC refused it) is tried again at the next restart.
    var refused = CallRelayFallbackPolicy()
    refused.consentLost(direct: true, at: 0)
    refused.mediaRestored()
    refused.consentLost(direct: true, at: 30)
    expect(refused.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "due")
    expect(refused.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "still due until it is applied")

    // Losses of a relayed pair never count.
    var relayed = CallRelayFallbackPolicy()
    relayed.consentLost(direct: false, at: 0)
    relayed.mediaRestored()
    relayed.consentLost(direct: false, at: 30)
    relayed.restartFailed()
    expect(relayed.relayReason(relayGathered: true, pairDirect: true), nil, "losses on a relayed pair never switch")

    // "Peer-to-peer calls: Nobody" is relay-only from the start: nothing to do.
    var privacy = CallRelayFallbackPolicy(relayOnly: true)
    privacy.consentLost(direct: true, at: 0)
    privacy.mediaRestored()
    privacy.consentLost(direct: true, at: 30)
    privacy.restartFailed()
    expect(privacy.directLosses, 0, "relay-only from the start counts nothing")
    expect(privacy.relayReason(relayGathered: true, pairDirect: true), nil, "and never switches")
}

do {
    // A restart on a direct path that did not bring media back: relay at once.
    var f = CallRelayFallbackPolicy()
    f.consentLost(direct: true, at: 0)
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "the first restart of a first loss is ordinary")
    f.restartFailed()
    expect(f.relayReason(relayGathered: true, pairDirect: true), .restartFailed, "a failed direct restart: relay")
    // The same once media came back in between: that outage is over.
    var g = CallRelayFallbackPolicy()
    g.consentLost(direct: true, at: 0)
    g.mediaRestored()
    g.restartFailed()
    expect(g.relayReason(relayGathered: true, pairDirect: true), nil, "a failed round outside an outage says nothing")
    // A failed round whose relay restart never went out (the reducer refused
    // it, the peer's restart healed the link) is forgotten with its outage:
    // a much later restart (a Wi-Fi <-> cellular switch while connected, the
    // first restart of a new loss) is an ordinary one.
    var stale = CallRelayFallbackPolicy()
    stale.consentLost(direct: true, at: 0)
    stale.restartFailed()
    expect(stale.relayReason(relayGathered: true, pairDirect: true), .restartFailed, "due while that outage lasts")
    stale.mediaRestored()
    expect(stale.relayReason(relayGathered: true, pairDirect: true), nil, "media came back: the failed round is forgotten")
    stale.networkChanged(at: 600)
    expect(stale.relayReason(relayGathered: true, pairDirect: true), nil, "a later network switch restarts over the direct path")
    var staleSwitch = CallRelayFallbackPolicy()
    staleSwitch.consentLost(direct: true, at: 0)
    staleSwitch.restartFailed()
    staleSwitch.networkChanged(at: 3)
    expect(staleSwitch.relayReason(relayGathered: true, pairDirect: true), nil, "a switch during the outage: its failed round is the network's")
    var staleLoss = CallRelayFallbackPolicy()
    staleLoss.consentLost(direct: false, at: 0)
    staleLoss.mediaRestored()
    staleLoss.consentLost(direct: true, at: 0.5)
    staleLoss.restartFailed()
    staleLoss.mediaRestored()
    staleLoss.consentLost(direct: false, at: 400)
    expect(staleLoss.relayReason(relayGathered: true, pairDirect: true), nil, "a new outage starts with nothing failed")
}

do {
    // The network's doing is not the pair's: a loss within 10 s of a Wi-Fi <->
    // cellular switch (or of losing the network) is not counted, and restarts
    // failing after the network changed during an outage do not switch.
    var f = CallRelayFallbackPolicy()
    f.networkChanged(at: 0)
    f.consentLost(direct: true, at: 6)
    expect(f.directLosses, 0, "a loss 6 s after a switch is the switch")
    f.restartFailed()
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "its failed restarts do not switch")
    f.mediaRestored()
    f.consentLost(direct: true, at: 40)
    f.mediaRestored()
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "one counted loss: still direct")
    f.consentLost(direct: true, at: 80)
    expect(f.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "the second counted loss: relay")
    var g = CallRelayFallbackPolicy()
    g.consentLost(direct: true, at: 0)
    g.networkChanged(at: 2)
    g.restartFailed()
    expect(g.relayReason(relayGathered: true, pairDirect: true), nil, "the network changed during the outage: its failed restarts are the network's")
    var h = CallRelayFallbackPolicy()
    h.networkChanged(at: 0)
    h.consentLost(direct: true, at: 10)
    expect(h.directLosses, 1, "10 s after a switch a loss is the pair's again")
}

do {
    // The whole story on a capable caller: the first loss restarts at once
    // over the direct path, the second restarts at once over the relay.
    var recovery = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    var fallback = CallRelayFallbackPolicy()
    fallback.consentLost(direct: true, at: 6)
    recovery.mediaDisconnected(at: 6)
    expect(recovery.next(now: 6), .restart, "first loss: restart at once")
    expect(fallback.relayReason(relayGathered: true, pairDirect: true), nil, "first loss: over the direct path")
    recovery.offerRequested(at: 6, restart: true)
    recovery.offerResolved(at: 6.1)
    recovery.mediaConnected(at: 6.15)
    fallback.mediaRestored()
    check(!recovery.isActive, "healed by the restart")
    fallback.consentLost(direct: true, at: 300)
    recovery.mediaDisconnected(at: 300)
    expect(recovery.next(now: 300), .restart, "second loss: restart at once")
    expect(fallback.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "second loss: over the relay")
}

do {
    // A TURN URL alone proves nothing (its port blocked on this network, the
    // server's quota or relay ports used up): the call switches only once
    // this connection gathered a relay candidate of its own. Without one,
    // every restart stays ordinary and keeps the direct pair that a restart
    // still heals in tens of ms.
    var f = CallRelayFallbackPolicy()
    f.consentLost(direct: true, at: 10)
    f.mediaRestored()
    f.consentLost(direct: true, at: 70)
    expect(f.relayReason(relayGathered: false, pairDirect: true), nil, "two losses, no relay candidate in this call: the ordinary restart")
    f.restartFailed()
    expect(f.relayReason(relayGathered: false, pairDirect: true), nil, "nor after a failed direct round")
    check(!f.relayOnly && !f.backToAllDue, "nothing switched, nothing to undo")
    expect(f.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "once a relay candidate came, the switch is due")
}

do {
    // A relay that does not work is undone: after a round over the
    // fallback's relay that did not bring media back, the next restart goes
    // back to every candidate type (a superset of the relay pairs, so never
    // worse), for the rest of the call.
    var f = CallRelayFallbackPolicy()
    f.consentLost(direct: true, at: 10)
    f.mediaRestored()
    f.consentLost(direct: true, at: 70)
    f.relayApplied()
    check(f.relayOnly && f.relayByFallback, "relay-only by the fallback")
    check(!f.backToAllDue, "the relay round is not judged before it ran")
    f.restartFailed()
    check(f.backToAllDue, "the relay round did not bring media back: back to every candidate type")
    f.relayReverted()
    check(!f.relayOnly && f.reverted && !f.backToAllDue, "every candidate type again")
    f.mediaRestored()
    f.consentLost(direct: true, at: 200)
    f.restartFailed()
    expect(f.relayReason(relayGathered: true, pairDirect: true), nil, "never a second switch in this call")
    // A refused configuration leaves the relay in place and stays due.
    var refused = CallRelayFallbackPolicy()
    refused.consentLost(direct: true, at: 0)
    refused.mediaRestored()
    refused.consentLost(direct: true, at: 30)
    refused.relayApplied()
    refused.restartFailed()
    check(refused.backToAllDue, "due")
    check(refused.backToAllDue && refused.relayOnly, "still due until it is applied")
    // A relay round that brought media back, even late, keeps the relay; a
    // later outage on the relay that its restarts do not heal (the relay
    // went away) goes back as well.
    var healed = CallRelayFallbackPolicy()
    healed.consentLost(direct: true, at: 0)
    healed.mediaRestored()
    healed.consentLost(direct: true, at: 30)
    healed.relayApplied()
    healed.mediaRestored()
    check(!healed.backToAllDue && healed.relayOnly, "media came back over the relay: it stays")
    healed.consentLost(direct: false, at: 200)
    check(!healed.backToAllDue, "a new outage on the relay: its first restart is still relay-only")
    healed.restartFailed()
    check(healed.backToAllDue, "a failed round over the relay: back to every candidate type")
    // A network switch does not keep a failing relay: a relay the new
    // network cannot reach would hold the call until it fails.
    var switched = CallRelayFallbackPolicy()
    switched.consentLost(direct: true, at: 0)
    switched.mediaRestored()
    switched.consentLost(direct: true, at: 30)
    switched.relayApplied()
    switched.networkChanged(at: 31)
    switched.restartFailed()
    check(switched.backToAllDue, "a failed relay round after a network switch still goes back")
    // "Peer-to-peer calls" relay-only is never undone, whatever fails.
    var privacy = CallRelayFallbackPolicy(relayOnly: true)
    privacy.consentLost(direct: false, at: 0)
    privacy.restartFailed()
    privacy.restartFailed()
    check(!privacy.backToAllDue && privacy.relayOnly, "relay-only from the start is never undone")
}

do {
    // The whole story when the relay cannot allocate any more (quota, relay
    // ports, the server gone) on a capable caller: the second loss restarts
    // at once over the relay; that round does not bring media back, so the
    // next round (4 s after the answer) goes back to every candidate type
    // and heals over the direct path, well inside the 30 s budget.
    var recovery = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    var fallback = CallRelayFallbackPolicy()
    fallback.consentLost(direct: true, at: 6)
    fallback.mediaRestored()
    fallback.consentLost(direct: true, at: 300)
    recovery.mediaDisconnected(at: 300)
    expect(recovery.next(now: 300), .restart, "second loss: restart at once")
    check(!recovery.earlierRoundFailed, "the first round of the outage follows no failed one")
    expect(fallback.relayReason(relayGathered: true, pairDirect: true), .repeatedLoss, "over the relay")
    fallback.relayApplied()
    recovery.offerRequested(at: 300, restart: true)
    recovery.offerResolved(at: 300.1)
    expect(recovery.next(now: 300.1), .wait(until: 304.1), "the relay round gets 4 s")
    expect(recovery.next(now: 304.1), .restart, "media still down: the next round")
    check(recovery.earlierRoundFailed, "the relay round failed")
    fallback.restartFailed()
    check(fallback.backToAllDue, "so this round goes back to every candidate type")
    fallback.relayReverted()
    recovery.offerRequested(at: 304.1, restart: true)
    recovery.offerResolved(at: 304.2)
    recovery.mediaConnected(at: 304.25)
    fallback.mediaRestored()
    check(!recovery.isActive && !fallback.relayOnly, "healed over the direct path about 4 s later")
    check(304.25 - 300 < CallRecoveryPolicy.budget, "inside the budget")
}

// MARK: - A pair that never carries the voice

do {
    // One read a second, as the quality monitor makes them; `packets` is the
    // call's cumulative inbound voice counter at each read, `refused` the
    // pair's sends its socket refused, and `bytesSent` the pair's successfully
    // sent bytes over that same span.
    func reads(_ policy: inout CallDeadPairPolicy, pair: String?, dtls: Bool? = true, packets: [Double?],
               refused: [Double]? = nil, bytesSent: [Double?]? = nil,
               voiceExpected: Bool = true, from: Int = 0) -> [Bool] {
        packets.enumerated().map { index, count in
            policy.update(now: TimeInterval(from + index), pairId: pair, dtlsConnected: dtls, audioPacketsReceived: count,
                          packetsDiscardedOnSend: refused?[index], pairBytesSent: bytesSent?[index],
                          voiceExpected: voiceExpected)
        }
    }
    var p = CallDeadPairPolicy()
    expect(reads(&p, pair: nil, packets: [0, 0, 0, 0, 0]), [false, false, false, false, false], "no selected pair: nothing to judge")
    expect(reads(&p, pair: "a", packets: [0, 2, 4, 6, 8, 9], from: 10), [false, false, false, true, false, false],
           "fewer than 10 voice packets 3 s into a DTLS-connected pair: dead, reported once")
    expect(p.packets, 6, "the voice packets counted on the pair when it was judged")
    expect(p.watched, 3, "and how long it was watched")
    check(!p.sendsRefused, "judged on the peer's silence")
    expect(reads(&p, pair: "b", packets: [9, 10, 11, 12], from: 16), [false, false, false, true],
           "a new pair selected while the voice was still coming in is watched afresh")
    expect(p.reported, 2, "two dead pairs in this call")
    expect(reads(&p, pair: "c", packets: [12, 12, 12, 12, 12], from: 20), [false, false, false, false, false],
           "at most two a call: a peer whose voice never starts cannot restart the call over and over")

    // Loss in our direction only (report 12 Nov: sends refused by one
    // phone's own socket): the peer's voice arrives, but the pair refuses
    // what we send. The caller sees it only there.
    var oneWay = CallDeadPairPolicy()
    expect(reads(&oneWay, pair: "a", packets: [0, 50, 100, 150, 200], refused: [0, 40, 90, 140, 190],
                 bytesSent: [1000, 1000, 1000, 1000, 1000]),
           [false, false, false, true, false], "our sends refused on the pair: dead although the voice arrives")
    check(oneWay.sendsRefused, "judged on our refused sends")
    expect(oneWay.refused, 140, "the refused sends counted on the pair")
    var blip = CallDeadPairPolicy()
    expect(reads(&blip, pair: "a", packets: [0, 50, 100, 150, 200], refused: [3, 5, 8, 12, 40],
                 bytesSent: [1000, 1000, 1000, 1000, 1000]).filter { $0 }, [],
           "a few refused sends in the first 3 s (a full socket buffer for a moment) are not a dead pair")
    var wouldBlock = CallDeadPairPolicy()
    expect(reads(&wouldBlock, pair: "a", packets: [0, 50, 100, 150], refused: [0, 5, 10, 15],
                 bytesSent: [1000, 4000, 7000, 10000]).filter { $0 }, [],
           "15 temporary EWOULDBLOCK/ENOBUFS refusals while bytes keep flowing do not restart a healthy pair")
    var smallGrowth = CallDeadPairPolicy()
    expect(reads(&smallGrowth, pair: "a", packets: [0, 50, 100, 150], refused: [0, 5, 10, 15],
                 bytesSent: [1000, 1000, 1000, 1001]).filter { $0 }, [],
           "even a little successful send growth means sends have not stopped")
    var unknownSends = CallDeadPairPolicy()
    expect(reads(&unknownSends, pair: "a", packets: [0, 50, 100, 150], refused: [0, 40, 90, 140]).filter { $0 }, [],
           "missing sent-byte stats cannot prove that refused sends stopped the pair")
    var resetSends = CallDeadPairPolicy()
    expect(reads(&resetSends, pair: "a", packets: [0, 50, 100, 150], refused: [0, 40, 90, 140],
                 bytesSent: [1000, 900, 900, 900]).filter { $0 }, [],
           "a reset byte counter cannot prove sends stopped")

    // A pair that comes while the peer is silent (its CallKit hold: no
    // packets at all, our network switching meanwhile) is not judged on that
    // silence; a pair selected while the voice was arriving is.
    var hold = CallDeadPairPolicy()
    _ = reads(&hold, pair: "a", packets: [0, 50, 100, 150])
    _ = reads(&hold, pair: "a", packets: [150, 150, 150, 150, 150], from: 4)
    expect(reads(&hold, pair: "b", packets: [150, 150, 150, 150, 150], from: 9).filter { $0 }, [],
           "a pair selected during the peer's hold is not judged on its silence")
    expect(reads(&hold, pair: "c", packets: [150, 150, 150, 150], refused: [0, 20, 40, 60],
                 bytesSent: [1000, 1000, 1000, 1000], from: 14), [false, false, false, true],
           "our own refused sends still count there")
    var switched = CallDeadPairPolicy()
    _ = reads(&switched, pair: "a", packets: [0, 50, 100, 150, 200])
    expect(reads(&switched, pair: "b", packets: [200, 200, 200, 200], from: 5), [false, false, false, true],
           "a pair selected while the voice was arriving is judged")
    var outage = CallDeadPairPolicy()
    _ = reads(&outage, pair: "a", packets: [0, 50, 100, 150, 200])
    outage.noteOutage(at: 5)
    outage.noteOutage(at: 8)
    expect(reads(&outage, pair: "b", packets: [200, 200, 200, 200], from: 9), [false, false, false, true],
           "voice arriving at outage start: a silent replacement pair four seconds later is judged")
    var heldOutage = CallDeadPairPolicy()
    _ = reads(&heldOutage, pair: "a", packets: [0, 50, 100, 150])
    _ = reads(&heldOutage, pair: "a", packets: [150, 150, 150, 150, 150], from: 4)
    heldOutage.noteOutage(at: 9)
    expect(reads(&heldOutage, pair: "b", packets: [150, 150, 150, 150], from: 13).filter { $0 }, [],
           "a hold already silent before the outage remains exempt on a delayed replacement pair")
    var restored = CallDeadPairPolicy()
    _ = reads(&restored, pair: "a", packets: [0, 50, 100, 150])
    restored.noteOutage(at: 4)
    _ = reads(&restored, pair: "a", packets: [200, 200, 200, 200, 200], from: 5)
    expect(reads(&restored, pair: "b", packets: [200, 200, 200, 200], from: 10).filter { $0 }, [],
           "renewed voice on the same pair ends an outage snapshot before a later hold")
    var start = CallDeadPairPolicy()
    _ = reads(&start, pair: "a", packets: [0, 0])
    expect(reads(&start, pair: "b", packets: [0, 0, 0, 0], from: 2), [false, false, false, true],
           "a second pair at the call's start, before any voice came: judged")

    // A dead pair that lets a few packets through (report 12 Nov: 278-408
    // bytes): its replacement is judged as it was, so a second dead pair on
    // the same Wi-Fi is found too (two a call).
    var trickle = CallDeadPairPolicy()
    expect(reads(&trickle, pair: "a", packets: [0, 3, 3, 3]), [false, false, false, true], "3 voice packets in 3 s: dead")
    expect(reads(&trickle, pair: "b", packets: [3, 3, 3, 3], from: 5), [false, false, false, true],
           "its silent replacement is judged although the dead pair let packets through")
    var trickleAfter = CallDeadPairPolicy()
    expect(reads(&trickleAfter, pair: "a", packets: [0, 3, 3, 3, 4]), [false, false, false, true, false],
           "dead, and one more packet after the report")
    expect(reads(&trickleAfter, pair: "b", packets: [4, 4, 4, 4], from: 7), [false, false, false, true],
           "a packet the dead pair still lets through is not renewed voice")
    var holdRefused = CallDeadPairPolicy()
    _ = reads(&holdRefused, pair: "a", packets: [0, 50, 100, 150])
    _ = reads(&holdRefused, pair: "a", packets: [150, 150, 150, 150, 150], from: 4)
    expect(reads(&holdRefused, pair: "b", packets: [150, 150, 150, 150], refused: [0, 20, 40, 60],
                 bytesSent: [1000, 1000, 1000, 1000], from: 9), [false, false, false, true],
           "refused sends during the peer's hold: dead")
    expect(reads(&holdRefused, pair: "c", packets: [150, 150, 150, 150], from: 13).filter { $0 }, [],
           "its replacement is still not judged on the hold's silence")

    // No replacement follows the report (callee/plain peer, or a refused
    // restart). Real voice on that same pair ends the carry-over. It stays
    // settled, and a much later outage during hold takes a fresh snapshot.
    var samePairHealed = CallDeadPairPolicy()
    _ = reads(&samePairHealed, pair: "a", packets: [0, 3, 3, 3])
    expect(reads(&samePairHealed, pair: "a", packets: [4, 12, 13, 63, 63, 63, 63, 63], from: 4).filter { $0 }, [],
           "at least 10 packets since the report renew voice without ever reporting the same pair twice")
    samePairHealed.noteOutage(at: 12)
    samePairHealed.noteOutage(at: 15)
    expect(reads(&samePairHealed, pair: "b", packets: [63, 63, 63, 63], from: 20).filter { $0 }, [],
           "same pair healed, then hold: a later outage cannot inherit the old dead-pair silence judgment")
    expect(samePairHealed.reported, 1, "a later held pair does not spend the second report")
    var missingStats = CallDeadPairPolicy()
    _ = reads(&missingStats, pair: "a", packets: [100, 103, 103, 103])
    _ = reads(&missingStats, pair: "a", packets: [nil, 104, 104, 104], from: 4)
    expect(reads(&missingStats, pair: "b", packets: [104, 104, 104, 104], from: 10), [false, false, false, true],
           "missing voice stats followed by one packet cannot manufacture same-pair renewal")
    var resetStats = CallDeadPairPolicy()
    _ = reads(&resetStats, pair: "a", packets: [100, 103, 103, 103])
    _ = reads(&resetStats, pair: "a", packets: [0, 10, 10, 10, 10, 10], from: 4)
    resetStats.noteOutage(at: 10)
    expect(reads(&resetStats, pair: "b", packets: [10, 10, 10, 10], from: 14).filter { $0 }, [],
           "a reset voice counter can still renew the same pair and clear carry-over before a later hold")

    // A pair selected during hold was exempt, but voice resumed before its
    // refused-send report. The replacement must inherit that arrival state
    // even when it takes more than arrivingWithin to appear.
    var resumedBeforeReport = CallDeadPairPolicy()
    _ = reads(&resumedBeforeReport, pair: "a", packets: [0, 50, 100, 150, 150, 150, 150, 150])
    resumedBeforeReport.noteOutage(at: 8)
    expect(reads(&resumedBeforeReport, pair: "b", packets: [150, 150, 200, 250], refused: [0, 20, 40, 60],
                 bytesSent: [1000, 1000, 1000, 1000], from: 9), [false, false, false, true],
           "selected during hold, voice resumed, own sends refused: dead")
    expect(reads(&resumedBeforeReport, pair: "c", packets: [250, 250, 250, 250], from: 17), [false, false, false, true],
           "its replacement is judged on silence because voice was arriving at the report")

    // Our row asks the peer for DTX (cellular): its silence is by design, so
    // its silence is exempt, while refused sends are still judged. A Wi-Fi
    // row after it still knows the voice had stopped.
    var cellular = CallDeadPairPolicy()
    expect(reads(&cellular, pair: "a", packets: [0, 1, 1, 1, 2, 2], voiceExpected: false).filter { $0 }, [],
           "under DTX nothing is judged on silence")
    expect(reads(&cellular, pair: "a", packets: [2, 2, 2, 2, 2], from: 6).filter { $0 }, [],
           "a pair first seen under DTX is not judged later")
    expect(reads(&cellular, pair: "b", packets: [2, 2, 2, 2, 2], from: 11).filter { $0 }, [],
           "cellular to Wi-Fi during the peer's hold: the new pair is not judged on the silence")

    var cellularRefusing = CallDeadPairPolicy()
    expect(reads(&cellularRefusing, pair: "a", packets: [0, 1, 1, 1], refused: [0, 5, 10, 15],
                 bytesSent: [1000, 1000, 1000, 1000], voiceExpected: false), [false, false, false, true],
           "under DTX our refused sends still mark a pair dead when sent bytes stop growing")
    check(cellularRefusing.sendsRefused, "DTX pair is judged on refused sends, never on peer silence")
    var cellularBlip = CallDeadPairPolicy()
    expect(reads(&cellularBlip, pair: "a", packets: [0, 1, 1, 1], refused: [0, 5, 10, 15],
                 bytesSent: [1000, 4000, 7000, 10000], voiceExpected: false).filter { $0 }, [],
           "under DTX temporary send refusals with successful bytes are still harmless")

    var voice = CallDeadPairPolicy()
    expect(reads(&voice, pair: "a", packets: [100, 150, 200, 250, 300, 350]).filter { $0 }, [], "a pair that carries the voice never fires")
    var slow = CallDeadPairPolicy()
    expect(reads(&slow, pair: "a", packets: [0, 3, 6, 10, 10, 10, 10]).filter { $0 }, [], "10 packets in 3 s are enough")
    var none = CallDeadPairPolicy()
    expect(reads(&none, pair: "a", packets: [nil, nil, nil, nil]), [false, false, false, true], "no inbound voice stats at all: dead")

    var handshake = CallDeadPairPolicy()
    expect(reads(&handshake, pair: "a", dtls: false, packets: Array(repeating: 0, count: 12)).filter { $0 }, [], "DTLS not connected: never judged")
    expect(reads(&handshake, pair: "a", dtls: nil, packets: Array(repeating: 0, count: 12), from: 12).filter { $0 }, [], "DTLS unknown: never judged")
    expect(reads(&handshake, pair: "a", packets: [0, 0, 0, 0], from: 24), [false, false, false, true],
           "the clock starts once DTLS is connected (no voice can arrive before)")

    var stalled = CallDeadPairPolicy()
    check(!stalled.update(now: 0, pairId: "a", dtlsConnected: true, audioPacketsReceived: 0), "the watch starts")
    check(!stalled.update(now: 11, pairId: "a", dtlsConnected: true, audioPacketsReceived: 0), "the next read 11 s later: past the watch, left to ICE")
    check(!stalled.update(now: 12, pairId: "a", dtlsConnected: true, audioPacketsReceived: 0), "and it stays settled")
    var later = CallDeadPairPolicy()
    _ = reads(&later, pair: "a", packets: [0, 50, 100])
    expect(reads(&later, pair: "a", packets: [100, 100, 100, 100, 100, 100], from: 3).filter { $0 }, [],
           "a pair that carried the voice and then stops (CallKit hold) is left to ICE")
}

do {
    // The caller restarts at once toward a capable peer; media stays up, so
    // it never fails the call and is never a failed round for the relay.
    var caller = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    check(caller.mediaStalled(at: 10), "caller + capable peer: acts on a dead pair")
    expect(caller.next(now: 10), .restart, "a dead pair: restart at once")
    caller.offerRequested(at: 10, restart: true)
    check(!caller.earlierRoundFailed, "media never went down: never a failed round for the relay fallback")
    expect(caller.next(now: 10.1), .wait(until: 15), "its offer waits for the answer")
    caller.offerResolved(at: 10.2)
    check(!caller.isActive, "answered: recovery is over")
    expect(caller.next(now: 10.2), .idle, "and nothing more is due")

    var unanswered = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    unanswered.mediaStalled(at: 0)
    unanswered.offerRequested(at: 0, restart: true)
    var failed = false
    for step in 0...400 {
        let now = Double(step) / 10
        let action = unanswered.next(now: now)
        if action == .fail { failed = true }
        if action == .resendOffer { unanswered.offerSent(at: now) }
    }
    check(!failed, "a dead-pair restart never fails the call")
    expect(unanswered.next(now: 30), .abandonOffer, "unanswered at the deadline: the offer is rolled back")

    var callee = CallRecoveryPolicy(role: .callee, peerResolvesGlare: true)
    check(!callee.mediaStalled(at: 0), "the callee leaves it to the caller (it hears the same silence)")
    check(!callee.isActive, "the callee starts nothing")
    expect(callee.next(now: 0), .idle, "the callee: no restart")
    var plain = CallRecoveryPolicy(role: .caller)
    check(!plain.mediaStalled(at: 0), "toward a plain peer: nothing")
    expect(plain.next(now: 0), .idle, "a plain peer: no restart while ICE says connected")
    var pending = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    pending.offerRequested(at: 0, restart: false)
    check(pending.mediaStalled(at: 1), "a dead pair while a video offer is out: this side still acts on it")
    expect(pending.next(now: 1), .wait(until: 5), "an offer already out waits for its answer first (B3)")
    pending.offerResolved(at: 1.2)
    expect(pending.next(now: 1.2), .restart, "then the dead-pair restart follows its answer")

    // Consent lost while the dead-pair restart is still unanswered: the
    // outage begins at the loss. The dead-pair round is not one of its
    // rounds, so the next restart is an ordinary one (no relay), and the
    // budget runs from the loss.
    var lost = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    lost.mediaStalled(at: 3.5)
    lost.offerRequested(at: 3.5, restart: true)
    lost.mediaDisconnected(at: 5.5)
    expect(lost.startedAt, 5.5, "the outage starts at the consent loss, not at the stall")
    expect(lost.negotiations, 0, "the dead-pair round is not a round of the outage")
    lost.offerResolved(at: 6)
    check(!lost.earlierRoundFailed, "its late answer is not a failed round for the relay fallback")
    expect(lost.next(now: 6), .wait(until: 10), "the answer starts the usual 4 s backoff")
    expect(lost.next(now: 10), .restart, "then an ordinary restart")
    lost.offerRequested(at: 10, restart: true)
    lost.offerResolved(at: 10.2)
    check(lost.earlierRoundFailed, "a round of the outage itself that did not heal it counts")
    check(lost.next(now: 35.4) != .fail, "the 30 s budget runs from the loss")
    expect(lost.next(now: 35.5), .fail, "and ends there")
    var failedAfterStall = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    failedAfterStall.mediaStalled(at: 0)
    failedAfterStall.offerRequested(at: 0, restart: true)
    failedAfterStall.mediaFailed(at: 2)
    expect(failedAfterStall.startedAt, 2, "ICE failed after a stall: the outage starts there too")
    // An outage that was already under way is not re-anchored by a stall.
    var down = CallRecoveryPolicy(role: .caller, peerResolvesGlare: true)
    down.interfaceChanged(at: 0)
    down.mediaStalled(at: 1)
    down.mediaDisconnected(at: 2)
    expect(down.startedAt, 0, "a stall inside a running recovery changes nothing")
}

// MARK: - Pausing outgoing video

do {
    // One sample a second, as the quality monitor sends them. By default a
    // congested link: 450 ms round trip, no loss reported.
    func run(_ policy: inout CallVideoPausePolicy, from: Int, through: Int, kbps: Double?, limited: Bool = true,
             rtt: Double? = 450, loss: Double? = 0, sending: Bool = true,
             interrupted: Bool = false) -> [CallVideoPausePolicy.Decision?] {
        (from...through).map {
            policy.update(now: TimeInterval($0), availableKbps: kbps, limitedByBandwidth: limited, rttMs: rtt,
                          outgoingLoss: loss, sending: sending, captureInterrupted: interrupted)
        }
    }
    func at(_ policy: inout CallVideoPausePolicy, _ time: Int, kbps: Double?, limited: Bool = true,
            rtt: Double? = 450, loss: Double? = 0, sending: Bool = true) -> CallVideoPausePolicy.Decision? {
        policy.update(now: TimeInterval(time), availableKbps: kbps, limitedByBandwidth: limited, rttMs: rtt,
                      outgoingLoss: loss, sending: sending, captureInterrupted: false)
    }
    expect(CallVideoPausePolicy.resumeGrace, 15, "15 s of grace")
    expect(CallVideoPausePolicy.congestedRttMs, 400, "congestion: a 400 ms round trip")
    expect(CallVideoPausePolicy.congestedLoss, 0.06, "or 6 % of our audio lost")

    // The camera's first sample starts the grace; then 5 s starved and
    // congested pause; the retries stay 10, 20, 40 and 60 s, each with its
    // own grace before it is judged again.
    var v = CallVideoPausePolicy()
    expect(run(&v, from: 0, through: 19, kbps: 120).compactMap { $0 }, [], "a camera that just started: 15 s grace, then a 5 s window")
    expect(at(&v, 20, kbps: 120), .pause, "starved and congested for 5 s after the grace: pause")
    check(v.paused, "paused")
    expect(v.failedTries, 1, "the first pause of the backoff")
    expect(run(&v, from: 21, through: 29, kbps: 500, limited: false).compactMap { $0 }, [], "paused: waits for its retry time")
    expect(at(&v, 30, kbps: 60), .resume, "first retry after 10 s")
    expect(run(&v, from: 31, through: 44, kbps: 60).compactMap { $0 }, [], "no pause within 15 s of a resume")
    expect(run(&v, from: 45, through: 49, kbps: 60).compactMap { $0 }, [], "then the usual 5 s window")
    expect(at(&v, 50, kbps: 60), .pause, "still starved and congested: pause again")
    expect(run(&v, from: 51, through: 69, kbps: 60).compactMap { $0 }, [], "second retry waits longer (20 s)")
    expect(at(&v, 70, kbps: 60), .resume, "second retry")
    expect(run(&v, from: 71, through: 89, kbps: 60).compactMap { $0 }, [], "its grace and window")
    expect(at(&v, 90, kbps: 60), .pause, "third pause")
    expect(run(&v, from: 91, through: 129, kbps: 60).compactMap { $0 }, [], "third retry waits 40 s")
    expect(at(&v, 130, kbps: 60), .resume, "third retry")
    _ = run(&v, from: 131, through: 149, kbps: 60)
    expect(at(&v, 150, kbps: 60), .pause, "fourth pause")
    expect(run(&v, from: 151, through: 209, kbps: 60).compactMap { $0 }, [], "fourth retry waits 60 s")
    expect(at(&v, 210, kbps: 60), .resume, "fourth retry")
    _ = run(&v, from: 211, through: 229, kbps: 60)
    expect(at(&v, 230, kbps: 60), .pause, "fifth pause")
    expect(run(&v, from: 231, through: 289, kbps: 60).compactMap { $0 }, [], "60 s stays the longest wait")
    expect(at(&v, 290, kbps: 60), .resume, "fifth retry")

    // A low estimate without congestion evidence is the encoder's choice or
    // a quiet LAN, never a reason to hide the picture (report 12 Nov).
    var lan = CallVideoPausePolicy()
    expect(run(&lan, from: 0, through: 120, kbps: 90, rtt: 5, loss: 0).compactMap { $0 }, [],
           "a low estimate on a 5 ms pair with no loss never pauses")
    var unknown = CallVideoPausePolicy()
    expect(run(&unknown, from: 0, through: 60, kbps: 90, rtt: nil, loss: nil).compactMap { $0 }, [],
           "no round trip and no loss reported: no evidence, no pause")
    var edge = CallVideoPausePolicy()
    expect(run(&edge, from: 0, through: 60, kbps: 90, rtt: 399, loss: 0.059).compactMap { $0 }, [],
           "just under both congestion steps: no pause")
    var lossy = CallVideoPausePolicy()
    expect(run(&lossy, from: 0, through: 19, kbps: 100, rtt: 5, loss: 0.06).compactMap { $0 }, [], "lossy: grace and window first")
    expect(at(&lossy, 20, kbps: 100, rtt: 5, loss: 0.06), .pause, "6 % of our audio lost with a low estimate: pause")
    // Our loss comes with the peer's receiver reports (1-7.5 s apart), and a
    // window without a new one reads none: a policed uplink that drops
    // instead of queueing (round trip 120 ms) must still pause.
    expect(CallVideoPausePolicy.lossEvidenceFor, 8, "a loss stays evidence for 8 s, past the longest report gap")
    var policed = CallVideoPausePolicy()
    func reported(_ time: Int) -> Double { time % 5 == 4 ? 0 : 0.1 }
    expect((0...19).compactMap { at(&policed, $0, kbps: 100, rtt: 120, loss: reported($0)) }, [], "policed: grace and window first")
    expect(at(&policed, 20, kbps: 100, rtt: 120, loss: reported(20)), .pause,
           "10 % loss seen in 4 samples of 5: pause after 5 s")
    var once = CallVideoPausePolicy()
    expect((0...40).compactMap { at(&once, $0, kbps: 100, rtt: 120, loss: $0 == 10 ? 0.1 : 0) }, [],
           "a loss more than 8 s old is no evidence any more")
    var mixed = CallVideoPausePolicy()
    _ = run(&mixed, from: 0, through: 17, kbps: 100)
    _ = at(&mixed, 18, kbps: 100, rtt: 20, loss: 0)
    expect(run(&mixed, from: 19, through: 23, kbps: 100).compactMap { $0 }, [], "a sample without congestion restarts the window")
    expect(at(&mixed, 24, kbps: 100), .pause, "5 s of a new congested window")

    // A blip above the threshold resets the 5 s window.
    var w = CallVideoPausePolicy()
    _ = run(&w, from: 0, through: 17, kbps: 100)
    _ = at(&w, 18, kbps: 400, limited: false)
    expect(run(&w, from: 19, through: 23, kbps: 100).compactMap { $0 }, [], "the low window restarts after a good sample")
    expect(at(&w, 24, kbps: 100), .pause, "5 s of a new low window")
    expect(w.networkChanged(at: 25), .resume, "a network change retries at once")
    expect(at(&w, 25, kbps: nil, limited: false), nil, "no estimate (reconnecting): no decision")
    expect(run(&w, from: 26, through: 44, kbps: 100).compactMap { $0 }, [], "the new path gets the grace too")
    expect(at(&w, 45, kbps: 100), .pause, "starved again after the switch")
    expect(at(&w, 46, kbps: nil, limited: false, sending: false), .resume, "camera off: the pause ends with it")
    expect(at(&w, 47, kbps: 100, sending: false), nil, "not sending: nothing to decide")
    var switched = CallVideoPausePolicy()
    _ = run(&switched, from: 0, through: 17, kbps: 100)
    expect(switched.networkChanged(at: 18), nil, "a network change while sending: nothing to resume")
    expect(run(&switched, from: 18, through: 37, kbps: 100).compactMap { $0 }, [], "but the estimate starts over: grace")
    expect(at(&switched, 38, kbps: 100), .pause, "then the usual window")

    // A low estimate the encoder is not held back by (a static scene) is the
    // encoder's choice, not the link's.
    var idle = CallVideoPausePolicy()
    expect(run(&idle, from: 0, through: 60, kbps: 90, limited: false).compactMap { $0 }, [], "an application-limited estimate never pauses video")
    _ = run(&idle, from: 61, through: 63, kbps: 90)
    expect(run(&idle, from: 64, through: 65, kbps: 90, limited: false).compactMap { $0 }, [], "a sample without the bandwidth limit restarts the window")
    expect(run(&idle, from: 66, through: 70, kbps: 90).compactMap { $0 }, [], "the new window needs its own 5 s")
    expect(at(&idle, 71, kbps: 90), .pause, "then a held-back encoder on a congested link pauses")

    // A stretch without an estimate (reconnecting, samples still every
    // second) breaks the window too: the first sample after it never pauses
    // on starvation measured before it.
    var reconnect = CallVideoPausePolicy()
    expect(run(&reconnect, from: 0, through: 16, kbps: 100).compactMap { $0 }, [], "grace, then two starved seconds")
    expect(run(&reconnect, from: 17, through: 24, kbps: nil, limited: false).compactMap { $0 }, [], "reconnecting: no estimate")
    expect(run(&reconnect, from: 25, through: 29, kbps: 100).compactMap { $0 }, [], "back: a fresh 5 s window")
    expect(at(&reconnect, 30, kbps: 100), .pause, "starved for 5 s after it: pause")

    // Back from the background: seconds before the gap do not count, and the
    // camera gets the grace again.
    var gap = CallVideoPausePolicy()
    _ = run(&gap, from: 0, through: 3, kbps: 100)
    expect(run(&gap, from: 20, through: 39, kbps: 100).compactMap { $0 }, [], "a sampling gap over 3 s starts the grace and the window again")
    expect(at(&gap, 40, kbps: 100), .pause, "grace and window after the gap")

    // The camera stopping or starting again starts the grace again.
    var capture = CallVideoPausePolicy()
    _ = run(&capture, from: 0, through: 17, kbps: 100)
    capture.captureChanged(at: 18)
    expect(run(&capture, from: 18, through: 37, kbps: 100).compactMap { $0 }, [], "a capture change: grace, then the window")
    expect(at(&capture, 38, kbps: 100), .pause, "5 s after the grace")
    var camera = CallVideoPausePolicy()
    _ = run(&camera, from: 0, through: 17, kbps: 100)
    expect(at(&camera, 18, kbps: 100, sending: false), nil, "camera off before any pause")
    expect(run(&camera, from: 19, through: 38, kbps: 100).compactMap { $0 }, [], "camera on again: its first sample starts the grace")
    expect(at(&camera, 39, kbps: 100), .pause, "then the window")

    // An interruption (another app took the camera, the app went away) sends
    // nothing, but the camera comes back by itself: the pause and its backoff
    // stay, so the peer keeps the avatar instead of a starved picture.
    var held = CallVideoPausePolicy()
    _ = run(&held, from: 0, through: 19, kbps: 100)
    expect(at(&held, 20, kbps: 100), .pause, "starved: pause")
    expect(run(&held, from: 21, through: 40, kbps: 100, interrupted: true).compactMap { $0 }, [],
           "interrupted: no decision, not even when the retry comes due")
    check(held.paused, "the pause survives the interruption")
    expect(at(&held, 41, kbps: 100), .resume, "the camera back: the retry that came due")
    expect(run(&held, from: 42, through: 60, kbps: 100).compactMap { $0 }, [], "the retry gets its grace and window")
    expect(at(&held, 61, kbps: 100), .pause, "still starved: pause again")
    expect(run(&held, from: 62, through: 80, kbps: 100).compactMap { $0 }, [], "the backoff survived: the next retry waits 20 s")
    expect(at(&held, 81, kbps: 100), .resume, "second retry")
}

// MARK: - Where the bandwidth estimate restarts

do {
    typealias Link = CallBitrateSeedPolicy.Link
    let wifi = CallMediaLadder.wifi
    let clean = Link(rttMs: 40, outgoingLoss: 0, availableKbps: 150)
    func seed(_ cause: CallBitrateSeedPolicy.Cause, _ link: Link?) -> Int? {
        CallBitrateSeedPolicy.seed(cause, profile: wifi, link: link)
    }
    for row in [CallMediaLadder.wifi, CallMediaLadder.cellular, CallMediaLadder.saving] {
        expect(CallBitrateSeedPolicy.seed(.newPath, profile: row, link: nil), row.startBitrate,
               "\(row.tier): a network or tier change seeds the row start, always")
        expect(CallBitrateSeedPolicy.seed(.cameraStart, profile: row, link: clean), row.startBitrate,
               "\(row.tier): a camera start on a clean link seeds the row start")
    }
    expect(seed(.newPath, Link(rttMs: 900, outgoingLoss: 0.3, availableKbps: 50)), 800_000,
           "a new path seeds whatever the old one measured")
    expect(seed(.cameraStart, clean), 800_000, "the user turning the camera back on: a fresh try on a clean link")
    expect(CallBitrateSeedPolicy.cleanRttMs, CallVideoPausePolicy.congestedRttMs, "clean means under the pause policy's congestion line")
    expect(seed(.cameraStart, Link(rttMs: 400, outgoingLoss: 0, availableKbps: 150)), nil, "a 400 ms round trip: no burst")
    expect(seed(.cameraStart, Link(rttMs: 399, outgoingLoss: 0, availableKbps: 150)), 800_000, "just under it: clean")
    expect(seed(.cameraStart, Link(rttMs: 40, outgoingLoss: 0.02, availableKbps: 150)), nil, "2 % of our audio lost: no burst")
    expect(seed(.cameraStart, Link(rttMs: 40, outgoingLoss: nil, availableKbps: 150)), 800_000,
           "no loss measured (DTX silence) is no evidence against the link")
    expect(seed(.cameraStart, Link(rttMs: nil, outgoingLoss: 0, availableKbps: 150)), nil, "no round trip measured: not known to be clean")
    expect(seed(.cameraStart, nil), nil, "no recent read: no seed")
    expect(seed(.cameraStart, Link(rttMs: 40, outgoingLoss: 0, availableKbps: 900)), nil, "a better estimate is never seeded down")
    expect(seed(.cameraStart, Link(rttMs: 40, outgoingLoss: 0, availableKbps: 800)), nil, "nor one at the row start")
    expect(seed(.cameraStart, Link(rttMs: 40, outgoingLoss: 0, availableKbps: nil)), nil, "no estimate: not known to be below")
    expect(seed(.retry, Link(rttMs: 450, outgoingLoss: 0.1, availableKbps: 120)), nil, "the timed retry on a starved link: no burst")
    expect(seed(.retry, Link(rttMs: 30, outgoingLoss: 0, availableKbps: 120)), nil,
           "nor when the link looks clean with video off: the retry resumes from the estimate the pause left")
}

// MARK: - Media diagnostics

do {
    expect(CallMediaDiagnosticsPolicy.limitationCode(nil), nil, "no reason reported: left out")
    expect(CallMediaDiagnosticsPolicy.limitationCode("none"), 0, "none 0")
    expect(CallMediaDiagnosticsPolicy.limitationCode("cpu"), 1, "cpu 1")
    expect(CallMediaDiagnosticsPolicy.limitationCode("bandwidth"), 2, "bandwidth 2")
    expect(CallMediaDiagnosticsPolicy.limitationCode("other"), 3, "anything else 3")

    var d = CallMediaDiagnosticsPolicy()
    check(!d.sizeStep(width: nil, height: nil), "no outgoing video: no step")
    check(d.sizeStep(width: 1280, height: 720), "the call's first encoded size")
    check(!d.sizeStep(width: 1280, height: 720), "the same size: no step")
    check(!d.sizeStep(width: nil, height: nil), "a read without a size changes nothing")
    check(!d.sizeStep(width: 1280, height: 720), "the same size after it: still no step")
    check(d.sizeStep(width: 320, height: 180), "a step down")
    var steps = 2
    for index in 0..<40 where d.sizeStep(width: Double(100 + index), height: 100) { steps += 1 }
    expect(steps, CallMediaDiagnosticsPolicy.sizeStepCap, "at most 20 size steps a call")

    // Every read notes the camera; only reads with diagnostics on record.
    func schedule(_ policy: inout CallMediaDiagnosticsPolicy, through: Int, connected: Bool = true,
                  cameraOn: (Int) -> Bool = { _ in false }, measured: (Int) -> Bool = { _ in true },
                  recording: (Int) -> Bool = { _ in true }) -> [Double: Double] {
        var records: [Double: Double] = [:]
        for time in 0...through {
            policy.noteCamera(cameraOn(time), at: Double(time), recording: recording(time))
            guard recording(time) else { continue }
            if let status = policy.playout(now: Double(time), connected: connected, measured: measured(time)) {
                records[Double(time)] = status
            }
        }
        return records
    }
    var periodic = CallMediaDiagnosticsPolicy()
    expect(schedule(&periodic, through: 40), [10: 0, 20: 0, 30: 0, 40: 0], "the playout delay every 10 s while connected")
    var away = CallMediaDiagnosticsPolicy()
    expect(schedule(&away, through: 40, connected: false), [:], "nothing while not connected")
    var waits = CallMediaDiagnosticsPolicy()
    expect(schedule(&waits, through: 12, measured: { $0 != 10 }), [11: 0], "a due record waits for a read with a playout delay")
    var capped = CallMediaDiagnosticsPolicy()
    expect(schedule(&capped, through: 1000).count, CallMediaDiagnosticsPolicy.playoutCap, "at most 30 periodic records a call")

    var camera = CallMediaDiagnosticsPolicy()
    expect(schedule(&camera, through: 31, cameraOn: { $0 >= 5 && $0 != 21 }),
           [8: 1, 10: 0, 13: 2, 20: 0, 25: 1, 30: 2, 31: 0],
           "3 s and 8 s after the camera turns on, each time; a camera mark goes before a periodic one")
    var late = CallMediaDiagnosticsPolicy()
    expect(schedule(&late, through: 10, cameraOn: { _ in true }, measured: { $0 >= 9 }), [9: 2, 10: 0],
           "the 8 s mark already due replaces the 3 s one")
    var toggles = CallMediaDiagnosticsPolicy()
    let marks = schedule(&toggles, through: 400, cameraOn: { $0 % 20 < 10 }).values.filter { $0 != 0 }.count
    expect(marks, CallMediaDiagnosticsPolicy.cameraCap, "at most 10 camera marks a call")

    // The camera is followed with diagnostics off too: the marks are timed
    // from the camera, never from diagnostics being turned on.
    var lateOn = CallMediaDiagnosticsPolicy()
    expect(schedule(&lateOn, through: 320, cameraOn: { _ in true }, recording: { $0 >= 300 }), [310: 0, 320: 0],
           "diagnostics turned on with the camera on for minutes: periodic records only")
    var soon = CallMediaDiagnosticsPolicy()
    expect(schedule(&soon, through: 9, cameraOn: { $0 >= 1 }, recording: { $0 >= 2 }), [4: 1, 9: 2],
           "diagnostics on a second after the camera: the marks still come 3 s and 8 s after the camera")
    var missed = CallMediaDiagnosticsPolicy()
    expect(schedule(&missed, through: 9, cameraOn: { _ in true }, recording: { $0 >= 5 }), [8: 2],
           "a mark that came due before diagnostics were on is skipped; the next one comes on time")
}

// The codec codes of phases 121 and 122, the audio path once a call (phase
// 147 status 3) and the audio negotiation (phase 148).
do {
    func code(_ mime: String?, _ fmtp: String? = nil) -> Double? {
        CallMediaDiagnosticsPolicy.videoCodecCode(mimeType: mime, fmtp: fmtp)
    }
    expect(code("video/H264", "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f"), 131,
           "H.264 Constrained High at level 3.1: 1 x 100 + 31")
    expect(code("video/H264", "profile-level-id=42e01f;packetization-mode=1"), 231, "Constrained Baseline at level 3.1")
    expect(code("video/H264", "profile-level-id=640c34"), 152, "Constrained High at level 5.2")
    expect(code("video/H264", "packetization-mode=1; profile-level-id=640C29"), 141, "level 4.1, the hex in either case")
    expect(code("video/H264", "profile-level-id=4d801f"), 231, "Constrained Baseline signaled as Main with constraint_set0")
    expect(code("video/H264", "profile-level-id=42001f"), 631, "plain Baseline: another profile")
    expect(code("video/H264", "profile-level-id=4d001f"), 631, "Main: another profile")
    expect(code("video/H264", "profile-level-id=64001f"), 631, "plain High: another profile")
    expect(code("video/H264", "packetization-mode=1"), 600, "H.264 without a profile-level-id")
    expect(code("video/VP8"), 300, "VP8")
    expect(code("video/VP9", "profile-id=0"), 400, "VP9")
    expect(code("video/AV1"), 500, "AV1")
    expect(code("video/rtx", "apt=96"), 0, "anything else 0")
    expect(code(nil), nil, "no codec: left out")

    var path = CallMediaDiagnosticsPolicy()
    check(!path.audioPathDue(connected: false), "the audio path waits for a connected read")
    check(path.audioPathDue(connected: true), "taken on the first connected read")
    check(!path.audioPathDue(connected: true), "once a call")

    let offer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 63 111 9\r\na=mid:0\r\na=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\n"
        + "a=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10; useinbandfec=1\r\na=rtpmap:9 G722/8000\r\n"
        + "m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\na=rtpmap:96 VP8/90000\r\na=rtpmap:97 red/90000\r\n"
    let peer = CallAudioNegotiation.parse(offer)
    expect(peer, CallAudioNegotiation(redFirst: true, inbandFec: true, dtx: false, redDistance: 1),
           "RED first, Opus asks for in-band FEC, one redundant copy (CRLF lines)")
    let answer = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111 63\na=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=1\n"
        + "a=rtpmap:63 red/48000/2\na=fmtp:63 111/111/111\n"
    let ours = CallAudioNegotiation.parse(answer)
    expect(ours, CallAudioNegotiation(redFirst: false, inbandFec: true, dtx: true, redDistance: 2),
           "Opus first with FEC and DTX, RED listed second with two copies (LF lines)")
    expect(CallAudioNegotiation.parse("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"),
           CallAudioNegotiation(), "plain Opus without a format line: nothing asked")
    expect(CallAudioNegotiation.parse("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 red/90000\r\n"), nil,
           "no audio section: nothing")
    let applied = CallAudioNegotiation.diagnostics(local: ours, remote: peer, appliedLocal: true)
    expect(applied["code"] ?? nil, 1, "our description applied: code 1")
    expect(applied["status"] ?? nil, 1, "RED first in the peer's: we send RED")
    expect(applied["direction"] ?? nil, 0, "Opus first in ours: the peer sends Opus")
    expect(applied["count"] ?? nil, 1 + 4 + 8, "bits: the peer asks for FEC (1), we ask for FEC (4) and DTX (8)")
    expect(applied["items"] ?? nil, 1, "RED's redundancy in the peer's description")
    let first = CallAudioNegotiation.diagnostics(local: nil, remote: peer, appliedLocal: false)
    expect(first["code"] ?? nil, 2, "the peer's description applied: code 2")
    check(first["direction"] == nil, "our side not applied yet: left out")
    check(CallAudioNegotiation.diagnostics(local: nil, remote: nil, appliedLocal: true).keys.sorted() == ["code"],
          "no audio section on either side: the code alone")
}

// MARK: - TURN credentials

expect(CallIceCredentialPolicy.ttl(nil), 3600, "a missing ttl_seconds means one hour")
expect(CallIceCredentialPolicy.ttl(1200), 1200, "the server's 1200 s")
expect(CallIceCredentialPolicy.ttl(0), 3600, "zero is treated as missing")
expect(CallIceCredentialPolicy.ttl(5), 60, "a tiny TTL is clamped (no refresh storm)")
expect(CallIceCredentialPolicy.refreshAt(fetchedAt: 1000, ttl: 1200), 1600, "refresh at half the TTL")
check(CallIceCredentialPolicy.isFresh(fetchedAt: 1000, ttl: 1200, now: 1599), "fresh until ttl/2")
check(!CallIceCredentialPolicy.isFresh(fetchedAt: 1000, ttl: 1200, now: 1600), "stale from ttl/2")
check(!CallIceCredentialPolicy.isFresh(fetchedAt: 1000, ttl: 1200, now: 900), "a clock that went backwards distrusts the cache")
check(CallIceCredentialPolicy.restartRefreshTimeout <= 2, "a restart never waits long for fresh credentials")
check(CallIceCredentialPolicy.refreshBeforeRestart(engineFetchedAt: nil, ttl: 1200, now: 1000),
      "unknown credentials in the connection: refresh before a restart")
check(!CallIceCredentialPolicy.refreshBeforeRestart(engineFetchedAt: 1000, ttl: 1200, now: 1599),
      "credentials in use with ttl/2 left: the restart needs no round trip")
check(CallIceCredentialPolicy.refreshBeforeRestart(engineFetchedAt: 1000, ttl: 1200, now: 1600),
      "from ttl/2 on, a restart refreshes first")
check(CallIceCredentialPolicy.refreshBeforeRestart(engineFetchedAt: 1000, ttl: 1200, now: 900),
      "a clock that went backwards refreshes first")

// MARK: - SDP details

do {
    let offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 63 111 9\r\na=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\na=rtpmap:9 G722/8000\r\n"
    let munged = CallSdpMunger.requestingOpusDtx(offer)
    check(munged.contains("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1\r\n"), "usedtx=1 added to the Opus format line")
    check(munged.contains("a=fmtp:63 111/111\r\n"), "the RED format line is untouched")
    expect(munged.replacingOccurrences(of: ";usedtx=1", with: ""), offer, "nothing else changes")
    expect(CallSdpMunger.requestingOpusDtx(munged), munged, "idempotent")
    let noFmtp = "v=0\r\na=rtpmap:111 opus/48000/2\r\na=rtpmap:0 PCMU/8000\r\n"
    expect(CallSdpMunger.requestingOpusDtx(noFmtp), "v=0\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 usedtx=1\r\na=rtpmap:0 PCMU/8000\r\n", "an Opus payload without a format line gets one")
    let noOpus = "v=0\r\na=rtpmap:0 PCMU/8000\r\n"
    expect(CallSdpMunger.requestingOpusDtx(noOpus), noOpus, "no Opus: unchanged")
    let explicitOff = "v=0\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0\n"
    expect(CallSdpMunger.requestingOpusDtx(explicitOff), explicitOff, "an existing usedtx value is respected")
}

do {
    let names = ["opus", "red", "G722", "PCMU", "telephone-event"]
    expect(CallAudioCodecPreference.order(names, preferRed: true), [1, 0, 2, 3, 4], "RED first, the rest in order")
    expect(CallAudioCodecPreference.order(names, preferRed: false), [0, 2, 3, 4], "saving: RED left out entirely")
    expect(CallAudioCodecPreference.order(["opus", "PCMU"], preferRed: true), [0, 1], "no RED available: unchanged")
}

if failures > 0 {
    print("\(failures) call network contract check(s) failed")
    exit(1)
}
print("Call network contract checks passed")
