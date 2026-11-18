import Foundation

// MARK: - Pure call state machine (mirror Android CallController, Этап J)

/// One buffered ICE candidate keyed to the call it belongs to.
struct IceCandidate: Equatable {
    let candidate: String
    let sdpMid: String?
    let sdpMLineIndex: Int?
}

/// Everything the controller reduces over, including the ICE-before-SDP queue
/// (Android's `pendingRemoteIce`/`forwardedIce`/`remoteDescriptionSet`).
struct CallReducerState: Equatable {
    var ui: CallUiState = .idle
    var activeCallId: String? = nil
    var remoteDescriptionSet: Bool = false
    var pendingRemoteIce: [IceCandidate] = []
    var pendingOfferSdp: String? = nil
    /// Our offer of an established call awaits its answer. There is never more
    /// than one (B3): a second offer made while the first was unanswered got the
    /// first one's answer, and the call could not recover.
    var renegotiating: Bool = false
    var renegotiationTarget: VideoMode = .audio
    /// The offer in flight is an ICE restart.
    var pendingOfferIsRestart = false
    /// Counts local offers, so the controller sends (and resends) only the
    /// offer the reducer still waits for.
    var offerGeneration: UInt64 = 0
    /// A renegotiation to offer once the exchange in flight completes: a video
    /// upgrade accepted during an ICE restart, or our offer rolled back by glare.
    var deferredOfferTarget: VideoMode? = nil
    /// `o=` line of the newest remote description applied. The peer raises its
    /// version with every description it makes, so an older answer belongs to
    /// an earlier round (or is a duplicate) and is never applied.
    var remoteSdpOrigin: CallSdpOrigin? = nil
    /// The peer announced that it resolves crossing offers
    /// (`CallSdpCapabilities`): only then may the callee send it restart offers.
    var peerResolvesGlare = false
    /// Buffered so a camera failure during `.connecting` (where the UI state
    /// carries no per-camera field) still lands in the `.connected` state.
    var localVideoUnavailable: Bool = false
    var localCaptureInterrupted: Bool = false
    /// A local preview can produce its first frame before ICE connects.
    /// Keep readiness for this call instead of waiting for a second one-shot
    /// renderer callback that may never arrive.
    var localVideoFrameReady: Bool = false
    // Unified Plan announces its receiver while applying remote SDP, often
    // before ICE connects. Keep that fact independently of the visible UI.
    var remoteVideoTrackAvailable: Bool = false
    var remoteVideoSignaled: Bool? = nil
    /// The last in-call sub-state (mute/speaker/video mode) so a transient
    /// `.reconnecting` never silently drops it (Android N.1A §21).
    var lastConnected: ConnectedState? = nil
    /// Media (ICE) is down. While set, only the engine reporting `.connected` may
    /// restore the call: a REST reconcile or keepalive saying "connected" used to
    /// end an in-progress ICE restart and show "Connected" with dead audio.
    var iceInterrupted = false
    var localCameraIsFrontFacing = true
    var pendingMuted = false
    var selectedAudioRoute: AudioRoute? = nil
    /// The OS output route last reported for this call, in every phase,
    /// ringing included. `.earpiece` is only the default before the first
    /// report; `CallReduction` reads the route again whenever the call enters
    /// a new phase, so no decision is ever taken on that default.
    var observedAudioRoute: AudioRouteKind = .earpiece

    var audioControls: ConnectedState? {
        switch ui {
        case .connected(let c): return c
        case .reconnecting: return lastConnected
        case .outgoingRinging(let call), .connecting(let call): return pendingControls(for: call)
        default: return nil
        }
    }

    /// The controls of a call that is ringing out or connecting, built exactly
    /// as `.connected` will carry them: a call placed or answered as video has
    /// its video controls (camera intent, preview, remote receiver) from the
    /// first frame, and connecting changes nothing but the phase.
    func pendingControls(for call: CallInfo) -> ConnectedState {
        let video = call.mediaKind == .video
        let cameraAvailable = video && !localVideoUnavailable
        var c = ConnectedState(call: call, videoMode: video ? .video : .audio)
        c.localCameraIsFrontFacing = localCameraIsFrontFacing
        c.isMuted = pendingMuted
        c.audioRoute = observedAudioRoute
        c.isSpeakerOn = observedAudioRoute == .speaker
        c.localVideoEnabled = cameraAvailable
        c.localCameraState = cameraAvailable ? (localVideoFrameReady && !localCaptureInterrupted ? .on : .starting) : .off
        c.remoteVideoEnabled = remoteVideoSignaled ?? remoteVideoTrackAvailable
        c.remoteVideoState = c.remoteVideoEnabled ? .waiting : .off
        return c
    }

    mutating func updateAudioControls(_ c: ConnectedState) {
        pendingMuted = c.isMuted
        observedAudioRoute = c.audioRoute
        switch ui {
        case .connected: ui = .connected(c)
        case .reconnecting: lastConnected = c
        default: break
        }
    }
}

enum CallEvent: Equatable {
    // User actions
    case startCall(CallInfo)
    case createSucceeded(CallInfo)
    case createFailed(CallFailureReason)
    case cancelOutgoing
    case acceptIncoming
    case acceptSucceeded(CallInfo)
    case acceptFailed
    case rejectIncoming
    case hangup
    case permissionDenied
    case toggleMute
    case toggleSpeaker
    case setAudioRoute(AudioRoute)
    case setCameraEnabled(Bool)
    case requestVideoUpgrade
    case acceptVideoUpgrade
    case rejectVideoUpgrade
    case cancelVideoUpgrade
    case switchToAudio
    case videoUpgradeTimeout

    // Signaling (server -> client)
    case remoteOffer(callId: String, sdp: String)
    case remoteAnswer(callId: String, sdp: String)
    case remoteIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    case remoteAccepted(callId: String)
    case remoteAnsweredElsewhere(callId: String)
    case remoteEnded(callId: String, reason: String)
    case remoteVideoUpgradeRequest(callId: String)
    case remoteVideoUpgradeAccept(callId: String)
    case remoteVideoUpgradeReject(callId: String)
    case remoteVideoUpgradeCancel(callId: String)
    case remoteVideoState(callId: String, enabled: Bool)

    // Engine
    case engineConnectionState(callId: String, state: PeerConnectionState)
    case engineRemoteVideoEnabled(callId: String, enabled: Bool)
    case engineRemoteFirstFrame(callId: String)
    case engineLocalFirstFrame(callId: String)
    case engineLocalCaptureInterrupted(callId: String, interrupted: Bool)
    case engineAudioRouteChanged(AudioRoute, userInitiated: Bool = false)

    // Local camera (imperative engine result folded back into the reducer)
    case mediaNegotiationFailed(callId: String)
    case localCameraFacingChanged(Bool)
    case localVideoFailed
    case cameraDenied
    /// The toast for this notice has been on screen long enough.
    case noticeExpired(CallNotice)
    /// The link cannot carry video: stop (or resume) sending it.
    case outgoingVideoPaused(callId: String, paused: Bool)

    // Reconnect
    case restartIceOfferRequested(callId: String)
    /// Media is back, but our offer was never answered: roll it back.
    case localOfferAbandoned(callId: String)
    /// Our answer to the peer's offer went out.
    case localAnswerSent(callId: String)
    case socketReconnected
    case reconnectReconcileSucceeded(CallDTO?, localDeviceId: UUID? = nil)
    case reconnectReconcileFailed
}

/// Side effects the controller executes against its seams. Kept granular so
/// deterministic tests can assert the exact command sequence.
enum CallEffect: Equatable {
    case restCreate(remoteUserId: String, mediaKind: CallMediaKind)
    case restAccept(callId: String)
    case restReject(callId: String)
    case restCancel(callId: String)
    case restHangup(callId: String)
    case restFailed(callId: String)
    case restConnected(callId: String)
    case restGet(callId: String)
    case engineInitialize(mediaKind: CallMediaKind)
    case engineSetRemoteDescription(callId: String, type: String, sdp: String)
    case engineAddIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    case engineSetMuted(Bool)
    case engineSetSpeaker(Bool)
    case engineSetAudioRoute(AudioRoute)
    case engineSetVideoEnabled(Bool)
    case engineStartLocalVideo
    case enginePrepareLocalVideo
    case engineStopLocalVideo
    case engineSetVideoNegotiationEnabled(Bool)
    case engineSwitchCamera
    case engineDispose
    /// Back to the last stable description: drops a local offer that will not
    /// be answered (a no-op when none is pending).
    case engineRollbackLocalOffer
    /// Encode and send the camera (false while the link cannot carry video).
    case engineSetVideoSendActive(Bool)
    case produceOfferAndSend(callId: String)
    /// A new offer with fresh ICE credentials (`restartIce()` first).
    case produceIceRestartOfferAndSend(callId: String)
    case produceAnswerAndSend(callId: String)
    case sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    case sendVideoUpgradeRequest(callId: String)
    case sendVideoUpgradeAccept(callId: String)
    case sendVideoUpgradeReject(callId: String)
    case sendVideoUpgradeCancel(callId: String)
    case sendVideoState(callId: String, enabled: Bool)
    case startKeepAlive(callId: String)
    case stopKeepAlive
    case armVideoUpgradeTimeout
    case cancelVideoUpgradeTimeout
    case dismissIncomingPush(callId: String)
    case engineExpectRemoteVideoFrame
    /// The own preview must report its next frame again (the camera restarted).
    case engineExpectLocalVideoFrame
}

/// Per-call ownership of automatic hands-free audio. A call placed as video
/// uses the speaker from its outgoing ring (the ringback too), one answered
/// as video from `.connecting` on (FaceTime/Telegram); an audio call upgraded
/// to video switches once this user's camera is running.
/// Once owned, the speaker stays for the rest of the video call: turning the
/// own camera off, a camera failure or the peer's camera never pull audio
/// back to the receiver. Only leaving video mode releases it. User choices
/// and connected Bluetooth/wired accessories always win.
struct CallAutomaticSpeakerPolicy {
    private(set) var callId: String?
    private(set) var manualRouteChosen = false
    private(set) var ownsSpeaker = false
    private var lastAutomaticTarget: AudioRouteKind? = .earpiece

    @discardableResult
    mutating func reset() -> Bool {
        let release = ownsSpeaker
        self = CallAutomaticSpeakerPolicy()
        return release
    }

    mutating func update(event: CallEvent, after state: CallReducerState) -> Bool? {
        guard let current = state.activeCallId, !state.ui.isTerminal else {
            return reset() ? false : nil
        }
        if callId != current { _ = reset(); callId = current }
        switch event {
        case .setAudioRoute, .toggleSpeaker:
            manualRouteChosen = true; ownsSpeaker = false; lastAutomaticTarget = nil
        case .engineAudioRouteChanged(let route, let userInitiated):
            if route.kind == .bluetooth || route.kind == .wiredHeadset {
                ownsSpeaker = false
                if userInitiated { manualRouteChosen = true }
                lastAutomaticTarget = nil
            } else if userInitiated && route.kind != lastAutomaticTarget {
                manualRouteChosen = true; ownsSpeaker = false; lastAutomaticTarget = nil
            }
        default: break
        }
        guard !manualRouteChosen else { return nil }
        if ownsSpeaker && !Self.inVideoMode(state) {
            ownsSpeaker = false; lastAutomaticTarget = .earpiece
            return false
        }
        let route = state.audioControls?.audioRoute ?? state.observedAudioRoute
        if !ownsSpeaker && Self.engagesSpeaker(state) && route != .bluetooth && route != .wiredHeadset {
            ownsSpeaker = true; lastAutomaticTarget = .speaker
            return true
        }
        return nil
    }

    /// Only restore our own still-valid automatic choice after an audio
    /// interruption. A manual override or connected headset cannot be undone.
    mutating func restoreSpeakerIfNeeded(in state: CallReducerState) -> Bool {
        guard callId == state.activeCallId, !state.ui.isTerminal, ownsSpeaker, !manualRouteChosen,
              Self.inVideoMode(state), let controls = state.audioControls,
              controls.audioRoute == .earpiece else { return false }
        lastAutomaticTarget = .speaker
        return true
    }

    /// The call is (still) a video call: placed as video while ringing out,
    /// placed/answered as video while connecting, or negotiated video mode
    /// afterwards. An incoming ring is CallKit's own tone, not ours.
    static func inVideoMode(_ state: CallReducerState) -> Bool {
        switch state.ui {
        case .outgoingRinging(let call), .connecting(let call): return call.mediaKind == .video
        case .connected(let c): return c.videoMode == .video
        case .reconnecting(let call): return (state.lastConnected?.videoMode ?? (call.mediaKind == .video ? .video : .audio)) == .video
        default: return false
        }
    }

    /// When the automatic speaker may switch on. A video call does so at once;
    /// an upgraded audio call only once this user's own camera runs (the person
    /// may still hold the phone to the ear while the peer's video arrives).
    static func engagesSpeaker(_ state: CallReducerState) -> Bool {
        guard inVideoMode(state), let call = state.ui.callInfo else { return false }
        if call.mediaKind == .video { return true }
        let controls = state.audioControls
        return controls?.localVideoEnabled == true && controls?.localCameraState == .on
    }
}

/// One controller step over the pure parts: the reducer, then the per-call
/// automatic speaker. The speaker policy decides on phase transitions (a video
/// call ringing out, an answer, connect), so when the call enters a new phase
/// the OS route is read first and folded in before the policy runs. Answering
/// on AirPods used to look like "no headset" to the policy (route reports were
/// dropped while ringing, the state kept its `.earpiece` default) and forced
/// the speaker override, which beats Bluetooth.
enum CallReduction {
    static func apply(_ state: CallReducerState, _ event: CallEvent,
                      speaker: inout CallAutomaticSpeakerPolicy,
                      currentRoute: () -> AudioRoute) -> (CallReducerState, [CallEffect]) {
        var (next, effects) = CallStateReducer.reduce(state, event)
        if entersLivePhase(from: state.ui, to: next.ui) {
            next = CallStateReducer.reduce(next, .engineAudioRouteChanged(currentRoute())).0
        }
        if let on = speaker.update(event: event, after: next) { effects.append(.engineSetSpeaker(on)) }
        return (next, effects)
    }

    /// A different live phase than before (not a mute, an ICE candidate or a
    /// camera change inside the same phase). Reading the route is cheap but
    /// not free, so it happens a handful of times per call, not per event.
    static func entersLivePhase(from old: CallUiState, to new: CallUiState) -> Bool {
        guard let phase = livePhase(new) else { return false }
        return livePhase(old) != phase
    }

    private static func livePhase(_ ui: CallUiState) -> Int? {
        switch ui {
        case .outgoingRinging: return 0
        case .incomingRinging: return 1
        case .connecting: return 2
        case .connected: return 3
        case .reconnecting: return 4
        case .idle, .ended, .failed: return nil
        }
    }
}

/// The `o=` line of a session description: whose it is and how many
/// descriptions that peer had made by then. libwebrtc (iOS and Android alike)
/// raises the version with every offer or answer it creates, rollback
/// included, so of two descriptions from one peer the older is stale. This
/// numbers ICE-restart rounds without any new signalling field.
struct CallSdpOrigin: Equatable {
    let session: String
    let version: UInt64

    /// `o=<username> <sess-id> <sess-version> <nettype> <addrtype> <address>`
    static func parse(_ sdp: String) -> CallSdpOrigin? {
        // `isNewline`: "\r\n" is a single Character in Swift.
        for raw in sdp.split(whereSeparator: \.isNewline) where raw.hasPrefix("o=") {
            let fields = raw.dropFirst(2).split(separator: " ")
            guard fields.count >= 3, let version = UInt64(fields[2]) else { return nil }
            return CallSdpOrigin(session: String(fields[1]), version: version)
        }
        return nil
    }
}

/// What this client announces in the descriptions it sends. The marker is
/// added to the sent text only, never set on our own connection; libwebrtc
/// ignores an unknown session attribute, so peers without it (the current
/// Android app, older iOS builds) negotiate exactly as before.
enum CallSdpCapabilities {
    /// Crossing offers are resolved (perfect negotiation, the caller
    /// impolite), and an ICE-restart offer is answered in any phase of an
    /// established call without disturbing the picture. Only a peer announcing
    /// it receives restart offers from a callee, or before ICE reports trouble:
    /// an older client could end the call when a callee offer crossed its own,
    /// and the current Android app hides the video after an offer that arrives
    /// while it is still connected.
    static let perfectNegotiation = "a=x-unyri-perfect-negotiation"

    static func advertises(_ sdp: String) -> Bool {
        sdp.split(whereSeparator: \.isNewline).contains { $0.trimmingCharacters(in: .whitespaces) == perfectNegotiation }
    }

    /// The marker once, at session level (before the first media section).
    static func advertising(_ sdp: String) -> String {
        guard !advertises(sdp) else { return sdp }
        let separator = sdp.contains("\r\n") ? "\r\n" : "\n"
        if let media = sdp.range(of: separator + "m=") {
            return sdp.replacingCharacters(in: media.lowerBound..<media.lowerBound, with: separator + perfectNegotiation)
        }
        return (sdp.hasSuffix(separator) ? sdp : sdp + separator) + perfectNegotiation + separator
    }
}

/// Pure reducer: `(newState, commands)`. A wrong-call-id event is ignored
/// (returns unchanged state + no commands) so stale/foreign callbacks can
/// never mutate the current call.
enum CallStateReducer {
    static func reduce(_ state: CallReducerState, _ event: CallEvent) -> (CallReducerState, [CallEffect]) {
        let (next, effects) = reduceEvent(state, event)
        return (next, effects + frameExpectations(from: state, to: next, already: effects))
    }

    /// One rule for the first-frame detectors instead of a re-arm in each
    /// case. They fire once per arm, so a frame that came while the state
    /// could not take it was lost for good: the self-view stayed hidden (and
    /// Flip disabled), or the avatar stayed over a live picture, for the rest
    /// of the call. Whenever an established call's own camera enters (or is
    /// restored into) `.starting`, or the peer's video enters (or is restored
    /// into) `.waiting`, the matching renderer reports its next frame again.
    static func frameExpectations(from old: CallReducerState, to new: CallReducerState,
                                  already effects: [CallEffect]) -> [CallEffect] {
        guard new.ui.isInCallMedia, let now = new.audioControls else { return [] }
        let restored: Bool = {
            if case .reconnecting = old.ui, case .connected = new.ui { return true }
            return false
        }()
        let before = old.audioControls.flatMap { $0.call.callId == now.call.callId ? $0 : nil }
        var added: [CallEffect] = []
        if now.localVideoEnabled, now.localCameraState == .starting,
           restored || before?.localVideoEnabled != true || before?.localCameraState != .starting
            || old.localCaptureInterrupted != new.localCaptureInterrupted,
           !effects.contains(.engineExpectLocalVideoFrame) {
            added.append(.engineExpectLocalVideoFrame)
        }
        if now.remoteVideoEnabled, now.remoteVideoState == .waiting,
           restored || before?.remoteVideoEnabled != true || before?.remoteVideoState != .waiting,
           !effects.contains(.engineExpectRemoteVideoFrame) {
            added.append(.engineExpectRemoteVideoFrame)
        }
        return added
    }

    private static func reduceEvent(_ state: CallReducerState, _ event: CallEvent) -> (CallReducerState, [CallEffect]) {
        switch event {
        // MARK: Outgoing
        case .startCall(let info):
            guard state.ui == .idle else { return (state, []) }
            var s = state
            s.ui = .outgoingRinging(info)
            s.activeCallId = info.callId
            return (s, [])
        case .createSucceeded(let info):
            guard state.ui == .idle else { return (state, []) }
            var s = state
            s.ui = .outgoingRinging(info)
            s.activeCallId = info.callId
            s.remoteDescriptionSet = false
            s.pendingRemoteIce = []
            return (s, [])
        case .createFailed(let reason):
            guard state.ui == .idle else { return (state, []) }
            var s = state
            s.ui = .failed(nil, reason: reason)
            return (s, [])
        case .cancelOutgoing:
            guard case .outgoingRinging(let call) = state.ui else { return (state, []) }
            return (state, [.restCancel(callId: call.callId)])

        // MARK: Incoming
        case .acceptIncoming:
            guard case .incomingRinging(let call) = state.ui else { return (state, []) }
            return (state, [.restAccept(callId: call.callId)])
        case .acceptSucceeded(let call):
            guard case .incomingRinging(let current) = state.ui, current.callId == call.callId else { return (state, []) }
            var s = state
            s.ui = .connecting(call)
            var effects: [CallEffect] = [.engineInitialize(mediaKind: call.mediaKind)]
            if let sdp = s.pendingOfferSdp {
                s.pendingOfferSdp = nil
                s.remoteDescriptionSet = true
                s.noteRemote(sdp)
                effects += [.engineSetRemoteDescription(callId: call.callId, type: "offer", sdp: sdp),
                            .produceAnswerAndSend(callId: call.callId)]
                effects += s.pendingRemoteIce.map { .engineAddIce(callId: call.callId, candidate: $0.candidate, sdpMid: $0.sdpMid, sdpMLineIndex: $0.sdpMLineIndex) }
                s.pendingRemoteIce = []
            }
            return (s, effects)
        case .acceptFailed:
            guard case .incomingRinging(let call) = state.ui else { return (state, []) }
            var s = CallReducerState()
            s.ui = .ended(call, reason: .failed)
            return (s, [.engineDispose, .stopKeepAlive, .dismissIncomingPush(callId: call.callId)])
        case .rejectIncoming:
            guard case .incomingRinging(let call) = state.ui else { return (state, []) }
            return (state, [.restReject(callId: call.callId)])
        case .permissionDenied:
            guard case .incomingRinging(let call) = state.ui else { return (state, []) }
            var s = CallReducerState()
            s.ui = .ended(call, reason: .rejected)
            return (s, [.engineDispose, .stopKeepAlive, .dismissIncomingPush(callId: call.callId), .restReject(callId: call.callId)])

        // MARK: Termination
        case .hangup:
            guard let call = state.ui.callInfo, !state.ui.isTerminal else { return (state, []) }
            return (state, [.restHangup(callId: call.callId)])
        case .remoteEnded(let callId, let reason):
            guard callId == state.activeCallId else { return (state, []) }
            guard let call = state.ui.callInfo else { return (CallReducerState(), [.dismissIncomingPush(callId: callId)]) }
            var s = CallReducerState()
            s.ui = .ended(call, reason: CallStateReducer.mapEndReason(reason))
            return (s, [.engineDispose, .stopKeepAlive]
                + (call.direction == .incoming ? [.dismissIncomingPush(callId: callId)] : []))
        case .remoteAnsweredElsewhere(let callId):
            guard callId == state.activeCallId || state.ui == .idle else { return (state, []) }
            if case .incomingRinging(let call) = state.ui, call.callId == callId {
                var s = CallReducerState()
                s.ui = .ended(call, reason: .answeredElsewhere)
                return (s, [.engineDispose, .stopKeepAlive, .dismissIncomingPush(callId: callId)])
            }
            return (state, []) // ringing a different call: ignore

        // MARK: Accepted (caller side) — callee accepted, caller connects
        case .remoteAccepted(let callId):
            guard callId == state.activeCallId, case .outgoingRinging(let call) = state.ui else { return (state, []) }
            var s = state
            s.ui = .connecting(call)
            s.offerGeneration &+= 1
            return (s, [.engineInitialize(mediaKind: call.mediaKind), .produceOfferAndSend(callId: callId)])

        // MARK: Signaling offer/answer/ice
        case .remoteOffer(let callId, let sdp):
            guard callId == state.activeCallId else { return (state, []) }
            // Older than a description already applied: a reordered or replayed
            // offer. A resend of the same offer is answered again (our first
            // answer may be the one that was lost).
            if state.isStaleRemote(sdp, answer: false) { return (state, []) }
            if state.renegotiating, state.ui.isInCallMedia {
                // Glare: both sides offered. Perfect negotiation with the
                // callee as the polite side: the caller keeps its own offer
                // (the callee will answer it), the callee rolls its offer back,
                // answers, and offers again afterwards what its offer carried.
                guard state.ui.callInfo?.direction == .incoming else { return (state, []) }
                var s = state
                let reoffer: VideoMode? = s.pendingOfferIsRestart ? s.deferredOfferTarget : s.renegotiationTarget
                s.renegotiating = false
                s.pendingOfferIsRestart = false
                s.deferredOfferTarget = nil
                var (next, effects) = reduce(s, .remoteOffer(callId: callId, sdp: sdp))
                guard !effects.isEmpty else { return (state, []) }
                next.deferredOfferTarget = reoffer
                effects.insert(.engineRollbackLocalOffer, at: 0)
                return (next, effects)
            }
            switch state.ui {
            case .incomingRinging:
                var s = state
                s.pendingOfferSdp = sdp
                return (s, [])
            case .connecting where !state.remoteDescriptionSet:
                var s = state
                s.remoteDescriptionSet = true
                s.noteRemote(sdp)
                var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "offer", sdp: sdp),
                                             .produceAnswerAndSend(callId: callId)]
                effects += s.pendingRemoteIce.map { .engineAddIce(callId: callId, candidate: $0.candidate, sdpMid: $0.sdpMid, sdpMLineIndex: $0.sdpMLineIndex) }
                s.pendingRemoteIce = []
                return (s, effects)
            case .connected(let connected):
                // Renegotiation offer (upgrade/downgrade recipient)
                var s = state
                let activeVideo = CallSdpVideoPolicy.hasActiveVideo(sdp)
                let mode: VideoMode = activeVideo.map { $0 ? .video : .audio }
                    ?? (connected.videoUpgrade == .upgrading ? .video : connected.videoMode)
                let becomingAudio = mode == .audio
                let fullAudioDowngrade = activeVideo == false
                let endsPause = fullAudioDowngrade && connected.outgoingVideoPaused
                var next = connected
                next.videoMode = becomingAudio ? .audio : .video
                next.videoUpgrade = .idle
                if fullAudioDowngrade {
                    next.localVideoEnabled = false
                    next.localCameraState = .off
                    next.outgoingVideoPaused = false
                    s.localVideoFrameReady = false
                    s.localCaptureInterrupted = false
                }
                next.remoteVideoEnabled = !becomingAudio && (state.remoteVideoSignaled ?? (connected.remoteVideoEnabled || connected.videoUpgrade == .upgrading))
                // An offer that keeps the peer's video (an ICE restart, a change
                // on our side) keeps the picture: frames go on arriving on the
                // same track, and no new first frame would be reported to end a
                // `.waiting` that covers the video with the avatar.
                let keepsPicture = next.remoteVideoEnabled && connected.remoteVideoEnabled && connected.remoteVideoState == .on
                next.remoteVideoState = next.remoteVideoEnabled ? (keepsPicture ? .on : .waiting) : .off
                s.ui = .connected(next)
                s.noteRemote(sdp)
                // A pause ends with the video it held back: the engine sends
                // again, after the camera is gone, whenever video comes back.
                return (s, (fullAudioDowngrade ? [.engineStopLocalVideo] : [])
                         + (endsPause ? [.engineSetVideoSendActive(true)] : [])
                         + [.engineSetVideoNegotiationEnabled(!becomingAudio), .engineSetRemoteDescription(callId: callId, type: "offer", sdp: sdp),
                           .produceAnswerAndSend(callId: callId)])
            case .reconnecting:
                guard let previous = state.lastConnected else { return (state, []) }
                var temporary = state
                temporary.ui = .connected(previous)
                var (next, effects) = reduce(temporary, .remoteOffer(callId: callId, sdp: sdp))
                if case .connected(let c) = next.ui { next.lastConnected = c }
                next.ui = state.ui
                return (next, effects)
            default:
                return (state, [])
            }
        case .remoteAnswer(let callId, let sdp):
            guard callId == state.activeCallId else { return (state, []) }
            // B3: not newer than the newest remote description: the answer to an
            // earlier round, or a duplicate. Applying it would pair this offer
            // with stale ICE credentials.
            if state.isStaleRemote(sdp, answer: true) { return (state, []) }
            if state.renegotiating, state.pendingOfferIsRestart, state.ui.isInCallMedia {
                // An ICE-restart round completes; the call's video state is not
                // part of it (an upgrade may be waiting behind it).
                var s = state
                s.noteRemote(sdp)
                var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)]
                effects += s.finishOffer()
                return (s, effects)
            }
            if state.renegotiating, case .reconnecting = state.ui {
                var s = state
                s.noteRemote(sdp)
                var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)]
                effects += s.finishOffer()
                return (s, effects)
            }
            if state.renegotiating, case .connected(var connected) = state.ui {
                var s = state
                connected.videoMode = state.renegotiationTarget
                connected.videoUpgrade = .idle
                s.ui = .connected(connected)
                s.remoteDescriptionSet = true
                s.noteRemote(sdp)
                var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)]
                effects += s.pendingRemoteIce.map { .engineAddIce(callId: callId, candidate: $0.candidate, sdpMid: $0.sdpMid, sdpMLineIndex: $0.sdpMLineIndex) }
                s.pendingRemoteIce = []
                effects += s.finishOffer()
                return (s, effects)
            }
            guard case .connecting = state.ui, !state.remoteDescriptionSet else { return (state, []) }
            var s = state
            s.remoteDescriptionSet = true
            s.noteRemote(sdp)
            var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)]
            effects += s.pendingRemoteIce.map { .engineAddIce(callId: callId, candidate: $0.candidate, sdpMid: $0.sdpMid, sdpMLineIndex: $0.sdpMLineIndex) }
            s.pendingRemoteIce = []
            return (s, effects)
        case .remoteIce(let callId, let candidate, let sdpMid, let index):
            guard callId == state.activeCallId else { return (state, []) }
            guard state.ui.callInfo != nil else { return (state, []) }
            if state.remoteDescriptionSet {
                return (state, [.engineAddIce(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index)])
            }
            var s = state
            s.pendingRemoteIce.append(IceCandidate(candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index))
            return (s, [])

        // MARK: Connect / video upgrade / in-call controls
        case .engineConnectionState(let callId, let peerState):
            guard callId == state.activeCallId else { return (state, []) }
            switch peerState {
            case .connected:
                if case .connecting(let call) = state.ui {
                    var s = state
                    // The same controls the connecting screen already showed.
                    let connected = state.pendingControls(for: call)
                    s.ui = .connected(connected)
                    return (s, [.restConnected(callId: callId), .startKeepAlive(callId: callId)]
                        + (s.localCaptureInterrupted && connected.localVideoEnabled ? [.sendVideoState(callId: callId, enabled: false)] : [])
                        + (connected.remoteVideoEnabled ? [.engineExpectRemoteVideoFrame] : []))
                } else if case .reconnecting(let call) = state.ui {
                    // ICE recovered after a restart — restore the in-call
                    // sub-state (mute/speaker/video) that was preserved.
                    var s = state
                    s.iceInterrupted = false
                    let restored = Self.restoredAfterReconnect(s.lastConnected
                        ?? ConnectedState(call: call, videoMode: call.mediaKind == .video ? .video : .audio))
                    s.ui = .connected(restored)
                    return (s, restored.remoteVideoEnabled ? [.engineExpectRemoteVideoFrame] : [])
                }
                return (state, []) // duplicate connected event
            case .failed:
                // ICE unrecoverable (or restart attempts exhausted) — end the
                // call locally as failed and self-report to the backend.
                guard !state.ui.isTerminal, let call = state.ui.callInfo else { return (state, []) }
                var s = CallReducerState()
                s.ui = .ended(call, reason: .failed)
                return (s, [.restFailed(callId: callId), .engineDispose, .stopKeepAlive])
            case .disconnected:
                guard case .connected(let c) = state.ui else { return (state, []) }
                var s = state
                s.lastConnected = c
                s.iceInterrupted = true
                s.ui = .reconnecting(c.call)
                return (s, [])
            default:
                return (state, [])
            }
        case .engineRemoteVideoEnabled(let callId, let enabled):
            guard callId == state.activeCallId, !state.ui.isTerminal else { return (state, []) }
            var s = state
            s.remoteVideoTrackAvailable = enabled
            let cameraEnabled = enabled && (s.remoteVideoSignaled ?? true)
            if case .connected(var connected) = s.ui {
                connected.remoteVideoEnabled = cameraEnabled
                connected.remoteVideoState = cameraEnabled ? .waiting : .off
                s.ui = .connected(connected)
            } else if s.lastConnected != nil {
                s.lastConnected?.remoteVideoEnabled = cameraEnabled
                s.lastConnected?.remoteVideoState = cameraEnabled ? .waiting : .off
            }
            return (s, cameraEnabled ? [.engineExpectRemoteVideoFrame] : [])
        case .engineRemoteFirstFrame(let callId):
            guard callId == state.activeCallId, case .connected(var connected) = state.ui,
                  connected.remoteVideoEnabled else { return (state, []) }
            connected.remoteVideoState = .on
            var s = state
            s.ui = .connected(connected)
            return (s, [])
        case .engineLocalCaptureInterrupted(let callId, let interrupted):
            guard callId == state.activeCallId else { return (state, []) }
            var s = state
            s.localCaptureInterrupted = interrupted
            s.localVideoFrameReady = false
            var current: ConnectedState?
            if case .connected(let c) = s.ui { current = c } else { current = s.lastConnected }
            guard var c = current, c.localVideoEnabled else { return (s, []) }
            c.localCameraState = .starting
            if case .connected = s.ui { s.ui = .connected(c) } else { s.lastConnected = c }
            // Preserve camera intent; iOS can temporarily hand capture to another app.
            return (s, [.sendVideoState(callId: callId, enabled: !interrupted && !c.outgoingVideoPaused)])
        case .engineLocalFirstFrame(let callId):
            guard callId == state.activeCallId, !state.localCaptureInterrupted else { return (state, []) }
            var s = state
            switch state.ui {
            case .outgoingRinging(let call), .connecting(let call):
                // Kept for `.connected`, whose controls are built from it.
                guard call.mediaKind == .video, !state.localVideoUnavailable else { return (state, []) }
                s.localVideoFrameReady = true
            case .connected, .reconnecting:
                // Also while reconnecting (into the kept controls): this frame
                // is the only proof the camera runs, and one dropped here hid
                // the self-view and disabled Flip for the rest of the call.
                guard var c = state.audioControls, c.localVideoEnabled else { return (state, []) }
                s.localVideoFrameReady = true
                c.localCameraState = .on
                s.updateAudioControls(c)
            default:
                return (state, [])
            }
            return (s, [])
        case .mediaNegotiationFailed(let callId):
            guard callId == state.activeCallId else { return (state, []) }
            if case .connected(var c) = state.ui {
                var s = state
                if state.pendingOfferIsRestart {
                    // A failed restart round is not a failed video change: no
                    // notice; recovery decides whether to try again.
                    let effects: [CallEffect] = [.engineRollbackLocalOffer] + s.finishOffer()
                    return (s, effects)
                }
                c.videoUpgrade = .idle
                c.notice = .videoFailed
                s.renegotiating = false
                s.deferredOfferTarget = nil
                s.ui = .connected(c)
                return (s, [.engineRollbackLocalOffer, .cancelVideoUpgradeTimeout])
            }
            if case .reconnecting = state.ui {
                // One failed round while media is down (an offer or answer the
                // engine refused). The recovery budget decides when to give up.
                var s = state
                let effects: [CallEffect] = [.engineRollbackLocalOffer] + s.finishOffer()
                return (s, effects)
            }
            return reduce(state, .engineConnectionState(callId: callId, state: .failed))
        case .localCameraFacingChanged(let isFront):
            var s = state
            s.localCameraIsFrontFacing = isFront
            if var c = s.audioControls {
                c.localCameraIsFrontFacing = isFront
                s.updateAudioControls(c)
            }
            return (s, [])
        case .localVideoFailed:
            if case .connecting = state.ui {
                // Buffer for the .connected transition (Android shows OFF,
                // not FAILED, when the initial camera setup degrades).
                var s = state
                s.localVideoUnavailable = true
                s.localVideoFrameReady = false
                return (s, [])
            }
            guard var c = state.audioControls else { return (state, []) }
            c.localVideoEnabled = false
            c.localCameraState = .failed
            c.videoUpgrade = .idle
            c.notice = .cameraFailed
            var s = state
            s.localVideoFrameReady = false
            s.updateAudioControls(c)
            return (s, [])
        case .cameraDenied:
            guard case .connected(var c) = state.ui else { return (state, []) }
            c.notice = .cameraDenied
            return (state.withUI(.connected(c)), [])
        case .noticeExpired(let notice):
            // Only the notice that timed out; a newer one keeps its own time.
            guard var c = state.audioControls, c.notice == notice else { return (state, []) }
            c.notice = nil
            var s = state
            s.updateAudioControls(c)
            return (s, [])
        case .outgoingVideoPaused(let callId, let paused):
            guard callId == state.activeCallId, state.ui.isInCallMedia, var c = state.audioControls,
                  c.videoMode == .video, c.outgoingVideoPaused != paused else { return (state, []) }
            c.outgoingVideoPaused = paused
            var s = state
            s.updateAudioControls(c)
            // The peer shows the avatar instead of a frozen picture.
            let tellPeer = c.localVideoEnabled && !s.localCaptureInterrupted
            return (s, [.engineSetVideoSendActive(!paused)] + (tellPeer ? [.sendVideoState(callId: callId, enabled: !paused)] : []))

        // MARK: In-call controls
        case .toggleMute:
            guard var c = state.audioControls else { return (state, []) }
            c.isMuted.toggle()
            var s = state
            s.updateAudioControls(c)
            return (s, [.engineSetMuted(c.isMuted)])
        case .toggleSpeaker:
            guard let c = state.audioControls else { return (state, []) }
            let (s, _) = reduce(state, .setAudioRoute(AudioRoute(kind: c.isSpeakerOn ? .earpiece : .speaker, label: c.isSpeakerOn ? "Phone" : "Speaker")))
            return (s, [.engineSetSpeaker(!c.isSpeakerOn)])
        case .setAudioRoute(let route):
            guard var c = state.audioControls else { return (state, []) }
            c.audioRoute = route.kind
            c.isSpeakerOn = route.kind == .speaker
            var s = state
            s.selectedAudioRoute = route
            s.updateAudioControls(c)
            return (s, [.engineSetAudioRoute(route)])
        case .engineAudioRouteChanged(let route, _):
            guard state.activeCallId != nil, state.ui.callInfo != nil, !state.ui.isTerminal else { return (state, []) }
            var s = state
            // A system CallKit route change or a newly connected accessory is
            // authoritative. Do not replay a stale app pick (another headset
            // included) on the next audio activation and pull sound away.
            if let selected = s.selectedAudioRoute, !selected.isSameOutput(as: route) { s.selectedAudioRoute = nil }
            guard var c = state.audioControls else {
                // Ringing in: no controls on screen, but the route is kept for
                // the moment the call is answered.
                s.observedAudioRoute = route.kind
                return (s, [])
            }
            c.audioRoute = route.kind
            c.isSpeakerOn = route.kind == .speaker
            s.updateAudioControls(c)
            return (s, [])

        // MARK: Video upgrade
        case .requestVideoUpgrade:
            guard case .connected(var c) = state.ui, c.videoMode == .audio, c.videoUpgrade == .idle else { return (state, []) }
            c.videoUpgrade = .requesting
            c.localVideoEnabled = true
            c.localCameraState = .starting
            return (state).withUI(.connected(c)).withEffects([.engineSetVideoNegotiationEnabled(true), .engineStartLocalVideo, .sendVideoUpgradeRequest(callId: c.call.callId), .armVideoUpgradeTimeout])
        case .acceptVideoUpgrade:
            guard case .connected(var c) = state.ui, c.videoUpgrade == .incomingRequest else { return (state, []) }
            c.videoUpgrade = .upgrading
            c.localCameraState = .off
            return (state).withUI(.connected(c)).withEffects([.engineSetVideoNegotiationEnabled(true), .enginePrepareLocalVideo, .engineSetVideoEnabled(false), .sendVideoUpgradeAccept(callId: c.call.callId), .cancelVideoUpgradeTimeout])
        case .rejectVideoUpgrade:
            guard case .connected(var c) = state.ui, c.videoUpgrade == .incomingRequest else { return (state, []) }
            c.videoUpgrade = .idle
            return (state).withUI(.connected(c)).withEffects([.sendVideoUpgradeReject(callId: c.call.callId), .cancelVideoUpgradeTimeout])
        case .cancelVideoUpgrade:
            guard case .connected(var c) = state.ui, c.videoUpgrade == .requesting else { return (state, []) }
            c.videoUpgrade = .idle
            c.localVideoEnabled = false
            return (state).withUI(.connected(c)).withEffects([.sendVideoUpgradeCancel(callId: c.call.callId), .engineStopLocalVideo, .cancelVideoUpgradeTimeout])
        case .remoteVideoUpgradeRequest(let callId):
            guard callId == state.activeCallId, case .connected(var c) = state.ui, c.videoUpgrade == .idle else { return (state, []) }
            c.videoUpgrade = .incomingRequest
            return (state).withUI(.connected(c)).withEffects([.armVideoUpgradeTimeout])
        case .remoteVideoUpgradeAccept(let callId):
            guard callId == state.activeCallId, case .connected(var c) = state.ui, c.videoUpgrade == .requesting else { return (state, []) }
            c.videoUpgrade = .upgrading
            var s = state.withUI(.connected(c))
            if s.renegotiating {
                // B3: one offer at a time. The upgrade follows the exchange in flight.
                s.deferredOfferTarget = .video
                return (s, [.cancelVideoUpgradeTimeout])
            }
            s.renegotiating = true
            s.renegotiationTarget = .video
            s.offerGeneration &+= 1
            return (s, [.produceOfferAndSend(callId: callId), .cancelVideoUpgradeTimeout])
        case .remoteVideoUpgradeReject(let callId):
            guard callId == state.activeCallId, case .connected(var c) = state.ui, c.videoUpgrade == .requesting else { return (state, []) }
            c.videoUpgrade = .idle
            c.notice = .videoRequestDeclined
            return (state).withUI(.connected(c)).withEffects([.engineStopLocalVideo, .cancelVideoUpgradeTimeout])
        case .remoteVideoUpgradeCancel(let callId):
            guard callId == state.activeCallId, case .connected(var c) = state.ui, c.videoUpgrade == .incomingRequest else { return (state, []) }
            c.videoUpgrade = .idle
            return (state.withUI(.connected(c)), [.cancelVideoUpgradeTimeout])
        case .videoUpgradeTimeout:
            guard case .connected(var c) = state.ui else { return (state, []) }
            switch c.videoUpgrade {
            case .requesting:
                c.videoUpgrade = .idle
                c.localVideoEnabled = false
                c.notice = .videoRequestTimeout
                return (state).withUI(.connected(c)).withEffects([.engineStopLocalVideo])
            case .incomingRequest:
                c.videoUpgrade = .idle
                return (state.withUI(.connected(c)), [])
            default:
                return (state, [])
            }
        case .remoteVideoState(let callId, let enabled):
            guard callId == state.activeCallId, !state.ui.isTerminal else { return (state, []) }
            var s = state
            s.remoteVideoSignaled = enabled
            if case .connected(var c) = s.ui {
                if c.remoteVideoEnabled == enabled { return (s, []) }
                c.remoteVideoEnabled = enabled
                c.remoteVideoState = enabled ? .waiting : .off
                s.ui = .connected(c)
            } else if s.lastConnected != nil {
                s.lastConnected?.remoteVideoEnabled = enabled
                s.lastConnected?.remoteVideoState = enabled ? .waiting : .off
            }
            return (s, enabled ? [.engineExpectRemoteVideoFrame] : [])
        case .setCameraEnabled(let enabled):
            // Before connect the camera button only shows the call's intent:
            // nothing would carry a change made there into `.connected`.
            guard state.ui.isInCallMedia, var c = state.audioControls, c.videoMode == .video else { return (state, []) }
            c.localVideoEnabled = enabled
            c.localCameraState = enabled ? .starting : .off
            // Turning the camera on again is a fresh try on the current link.
            let wasPaused = c.outgoingVideoPaused
            c.outgoingVideoPaused = false
            var s = state
            s.updateAudioControls(c)
            let resume: [CallEffect] = wasPaused ? [.engineSetVideoSendActive(true)] : []
            // End a pause only after the camera is off; reactivating its
            // encoding first would briefly send frames after the user's tap.
            let camera: [CallEffect] = enabled
                ? resume + [.engineSetVideoEnabled(true)]
                : [.engineSetVideoEnabled(false)] + resume
            return (s, camera + [.sendVideoState(callId: c.call.callId, enabled: enabled)])
        case .switchToAudio:
            guard case .connected(var c) = state.ui, c.videoMode == .video else { return (state, []) }
            c.videoMode = .audio
            c.localVideoEnabled = false
            c.remoteVideoEnabled = false
            c.localCameraState = .off
            c.remoteVideoState = .off
            // A pause ends with the video it held back: the engine sends again
            // whenever video comes back.
            let resume: [CallEffect] = c.outgoingVideoPaused ? [.engineSetVideoSendActive(true)] : []
            c.outgoingVideoPaused = false
            c.videoUpgrade = .upgrading
            var s = state.withUI(.connected(c))
            if s.renegotiating {
                s.deferredOfferTarget = .audio
                return (s, [.engineStopLocalVideo] + resume)
            }
            s.renegotiating = true
            s.renegotiationTarget = .audio
            s.offerGeneration &+= 1
            return (s, [.engineSetVideoNegotiationEnabled(false), .engineStopLocalVideo] + resume
                + [.produceOfferAndSend(callId: c.call.callId)])

        // MARK: Reconnect
        case .restartIceOfferRequested(let callId):
            // B3: never a second offer while one is unanswered; the controller
            // resends that one instead.
            guard callId == state.activeCallId, !state.renegotiating else { return (state, []) }
            let target: VideoMode
            switch state.ui {
            case .reconnecting: target = state.lastConnected?.videoMode ?? .audio
            // A network switch restarts before ICE notices; not in the middle
            // of a video change, which owns the next offer.
            case .connected(let c) where c.videoUpgrade == .idle: target = c.videoMode
            default: return (state, [])
            }
            var s = state
            let effects: [CallEffect] = s.beginOffer(callId: callId, target: target, restart: true)
            return (s, effects)
        case .localOfferAbandoned(let callId):
            guard callId == state.activeCallId, state.renegotiating, state.ui.isInCallMedia else { return (state, []) }
            var s = state
            var effects: [CallEffect] = [.engineRollbackLocalOffer]
            if !s.pendingOfferIsRestart, var c = s.audioControls, c.videoUpgrade != .idle {
                c.videoUpgrade = .idle
                c.notice = .videoFailed
                s.updateAudioControls(c)
                effects.append(.cancelVideoUpgradeTimeout)
            }
            effects += s.finishOffer()
            return (s, effects)
        case .localAnswerSent(let callId):
            guard callId == state.activeCallId, !state.renegotiating, state.ui.isInCallMedia,
                  let target = state.deferredOfferTarget else { return (state, []) }
            var s = state
            s.deferredOfferTarget = nil
            let effects: [CallEffect] = s.beginOffer(callId: callId, target: target, restart: false)
            return (s, effects)
        case .socketReconnected:
            // A signalling-socket blip says nothing about media: reconcile in the
            // background without flashing "Reconnecting…" or remounting video.
            guard state.ui.callInfo != nil, let callId = state.activeCallId else { return (state, []) }
            guard case .connected = state.ui else { return (state, []) }
            return (state, [.restGet(callId: callId)])
        case .reconnectReconcileSucceeded(let dto, let localDeviceId):
            // Stale/foreign reconcile rows must never mutate the current call.
            guard let dto, dto.id.uuidString.lowercased() == state.activeCallId else { return (state, []) }
            guard let call = state.ui.callInfo else { return (CallReducerState(), []) }
            switch CallReconciliation.resolve(local: state.ui, server: dto, localDeviceId: localDeviceId) {
            case .keepCurrent:
                return (state, [])
            case .acceptOutgoing:
                // The same transition as call.accepted over WS. Once it is
                // connecting, a later WS echo/REST result cannot make a second
                // engine or SDP offer.
                return reduce(state, .remoteAccepted(callId: call.callId))
            case .restoreConnected:
                // The server row cannot vouch for media; an ICE restart owns recovery.
                if state.iceInterrupted { return (state, []) }
                var s = state
                let restored = Self.restoredAfterReconnect(s.lastConnected
                    ?? ConnectedState(call: call, videoMode: call.mediaKind == .video ? .video : .audio))
                s.ui = .connected(restored)
                return (s, restored.remoteVideoEnabled ? [.engineExpectRemoteVideoFrame] : [])
            case .endLocally(let reason):
                var s = CallReducerState()
                s.ui = .ended(call, reason: CallStateReducer.mapEndReason(reason))
                return (s, [.engineDispose, .stopKeepAlive]
                        + (call.direction == .incoming ? [.dismissIncomingPush(callId: call.callId)] : []))
            }
        case .reconnectReconcileFailed:
            guard case .reconnecting(let call) = state.ui, !state.iceInterrupted else { return (state, []) }
            var s = state
            let restored = Self.restoredAfterReconnect(s.lastConnected
                ?? ConnectedState(call: call, videoMode: call.mediaKind == .video ? .video : .audio))
            s.ui = .connected(restored)
            return (s, restored.remoteVideoEnabled ? [.engineExpectRemoteVideoFrame] : [])

        default:
            return (state, [])
        }
    }

    /// Runtime Correction A: after a reconnect the renderer is freshly
    /// mounted, so a previously `.on` remote video must restart from `.waiting`
    /// until the first fresh frame arrives — never restore a stale `.on`.
    static func restoredAfterReconnect(_ restored: ConnectedState) -> ConnectedState {
        var r = restored
        r.remoteVideoState = restored.remoteVideoEnabled ? .waiting : .off
        return r
    }

    static func mapEndReason(_ reason: String) -> CallEndReason {
        switch reason {
        case "hangup": return .hangup
        case "rejected": return .rejected
        case "cancelled": return .cancelled
        case "missed": return .missed
        case "timeout": return .timeout
        case "failed": return .failed
        case "answered_elsewhere": return .answeredElsewhere
        default: return .remoteEnded
        }
    }
}

private extension CallUiState {
    var isTerminal: Bool {
        if case .ended = self { return true }
        if case .failed = self { return true }
        return false
    }
}

private extension CallReducerState {
    func withUI(_ ui: CallUiState) -> CallReducerState {
        var s = self
        s.ui = ui
        return s
    }

    mutating func noteRemote(_ sdp: String) {
        if let origin = CallSdpOrigin.parse(sdp) { remoteSdpOrigin = origin }
        if CallSdpCapabilities.advertises(sdp) { peerResolvesGlare = true }
    }

    /// From the same peer session and older than the newest applied remote
    /// description. An answer must be strictly newer (each answer the peer
    /// makes has a new version); an offer may repeat the version (a resend).
    func isStaleRemote(_ sdp: String, answer: Bool) -> Bool {
        guard let applied = remoteSdpOrigin, let origin = CallSdpOrigin.parse(sdp),
              origin.session == applied.session else { return false }
        return answer ? origin.version <= applied.version : origin.version < applied.version
    }

    /// A local offer of the established call (B3: only when none is pending).
    mutating func beginOffer(callId: String, target: VideoMode, restart: Bool) -> [CallEffect] {
        renegotiating = true
        pendingOfferIsRestart = restart
        renegotiationTarget = target
        offerGeneration &+= 1
        return [.engineSetVideoNegotiationEnabled(target == .video),
                restart ? .produceIceRestartOfferAndSend(callId: callId) : .produceOfferAndSend(callId: callId)]
    }

    /// The offer in flight is answered, rolled back or failed. A renegotiation
    /// waiting behind it is offered now.
    mutating func finishOffer() -> [CallEffect] {
        renegotiating = false
        pendingOfferIsRestart = false
        guard let target = deferredOfferTarget, let callId = activeCallId, ui.isInCallMedia else { return [] }
        deferredOfferTarget = nil
        return beginOffer(callId: callId, target: target, restart: false)
    }
    func withEffects(_ effects: [CallEffect]) -> (CallReducerState, [CallEffect]) {
        (self, effects)
    }
}

/// Effective per-media direction; recvonly/sendonly remain video-capable.
private enum CallSdpVideoPolicy {
    static func hasActiveVideo(_ sdp: String) -> Bool? {
        var sessionDirection = "a=sendrecv"
        var mediaKind: String?
        var mediaPort: Int?
        var mediaDirection: String?
        var hasMedia = false
        var uncertain = false
        var activeVideo = false
        func finishMedia() {
            if mediaKind == "video", let port = mediaPort, port > 0,
               (mediaDirection ?? sessionDirection) != "a=inactive" { activeVideo = true }
        }
        for raw in sdp.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("m=") {
                finishMedia()
                let fields = line.dropFirst(2).split(whereSeparator: { $0.isWhitespace })
                hasMedia = true
                mediaKind = fields.first.map(String.init)
                mediaPort = fields.count > 1 ? fields[1].split(separator: "/").first.flatMap { Int($0) } : nil
                if mediaPort == nil || !(0...65535).contains(mediaPort ?? -1) { uncertain = true; mediaPort = nil }
                mediaDirection = nil
            } else if ["a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive"].contains(line) {
                if hasMedia { mediaDirection = line } else { sessionDirection = line }
            }
        }
        finishMedia()
        return !hasMedia || (uncertain && !activeVideo) ? nil : activeVideo
    }
}
