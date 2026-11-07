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
    var renegotiating: Bool = false
    var renegotiationTarget: VideoMode = .audio
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
    var localCameraIsFrontFacing = true
    var pendingMuted = false
    var selectedAudioRoute: AudioRoute? = nil
    var observedAudioRoute: AudioRouteKind = .earpiece

    var audioControls: ConnectedState? {
        switch ui {
        case .connected(let c): return c
        case .reconnecting: return lastConnected
        case .outgoingRinging(let call), .connecting(let call):
            var c = ConnectedState(call: call)
            c.isMuted = pendingMuted
            c.audioRoute = observedAudioRoute
            c.isSpeakerOn = observedAudioRoute == .speaker
            return c
        default: return nil
        }
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

    // Reconnect
    case restartIceOfferRequested(callId: String)
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
    case produceOfferAndSend(callId: String)
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
}

/// Per-call ownership of automatic hands-free audio. Camera startup is not
/// proof of capture: only a ready local camera may enable the automatic route.
/// User choices and accessories always win; remote video is irrelevant.
struct CallAutomaticSpeakerPolicy {
    private(set) var callId: String?
    private(set) var manualRouteChosen = false
    private(set) var ownsSpeaker = false
    private var cameraWasOn = false
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
        let controls = state.audioControls
        let cameraOn = controls?.localVideoEnabled == true && controls?.localCameraState == .on
        let becameReady = cameraOn && !cameraWasOn
        cameraWasOn = cameraOn
        guard !manualRouteChosen else { return nil }
        if ownsSpeaker && !cameraOn {
            ownsSpeaker = false; lastAutomaticTarget = .earpiece
            return false
        }
        let route = controls?.audioRoute ?? state.observedAudioRoute
        if becameReady && route != .bluetooth && route != .wiredHeadset && !ownsSpeaker {
            ownsSpeaker = true; lastAutomaticTarget = .speaker
            return true
        }
        return nil
    }

    /// Only restore our own still-valid automatic choice after an audio
    /// interruption. A manual override or connected headset cannot be undone.
    mutating func restoreSpeakerIfNeeded(in state: CallReducerState) -> Bool {
        guard callId == state.activeCallId, !state.ui.isTerminal, ownsSpeaker, !manualRouteChosen,
              let controls = state.audioControls, controls.localVideoEnabled,
              controls.localCameraState == .on, controls.audioRoute == .earpiece else { return false }
        lastAutomaticTarget = .speaker
        return true
    }
}

/// Bounded ICE-restart policy (Android `CallController` DISCONNECTED handler):
/// at most `maxAttempts` `restartIce()` calls spread across a grace period; if
/// ICE is still disconnected afterwards the call fails. Pure so the attempt
/// sequence and timing are deterministic and testable.
enum IceRestartPolicy {
    static let maxAttempts = 2
    static let gracePeriodMs: UInt64 = 15_000
    static var retryDelayMs: UInt64 { gracePeriodMs / UInt64(maxAttempts) }

    struct Step: Equatable {
        let shouldRestart: Bool
        let shouldFail: Bool
    }

    /// One tick of the restart loop; `attempt` is the number of restarts
    /// already performed this reconnect.
    static func step(attempt: Int, isReconnecting: Bool) -> Step {
        guard isReconnecting else { return Step(shouldRestart: false, shouldFail: false) }
        if attempt < maxAttempts {
            return Step(shouldRestart: true, shouldFail: false)
        }
        return Step(shouldRestart: false, shouldFail: true)
    }
}

/// Pure reducer: `(newState, commands)`. A wrong-call-id event is ignored
/// (returns unchanged state + no commands) so stale/foreign callbacks can
/// never mutate the current call.
enum CallStateReducer {
    static func reduce(_ state: CallReducerState, _ event: CallEvent) -> (CallReducerState, [CallEffect]) {
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
            return (s, [.engineInitialize(mediaKind: call.mediaKind), .produceOfferAndSend(callId: callId)])

        // MARK: Signaling offer/answer/ice
        case .remoteOffer(let callId, let sdp):
            guard callId == state.activeCallId else { return (state, []) }
            switch state.ui {
            case .incomingRinging:
                var s = state
                s.pendingOfferSdp = sdp
                return (s, [])
            case .connecting where !state.remoteDescriptionSet:
                var s = state
                s.remoteDescriptionSet = true
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
                var next = connected
                next.videoMode = becomingAudio ? .audio : .video
                next.videoUpgrade = .idle
                if fullAudioDowngrade {
                    next.localVideoEnabled = false
                    next.localCameraState = .off
                    s.localVideoFrameReady = false
                    s.localCaptureInterrupted = false
                }
                next.remoteVideoEnabled = !becomingAudio && (state.remoteVideoSignaled ?? (connected.remoteVideoEnabled || connected.videoUpgrade == .upgrading))
                next.remoteVideoState = next.remoteVideoEnabled ? .waiting : .off
                s.ui = .connected(next)
                return (s, (fullAudioDowngrade ? [.engineStopLocalVideo] : [])
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
            if state.renegotiating, case .reconnecting = state.ui {
                var s = state
                s.renegotiating = false
                return (s, [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)])
            }
            if state.renegotiating, case .connected(var connected) = state.ui {
                var s = state
                connected.videoMode = state.renegotiationTarget
                connected.videoUpgrade = .idle
                s.ui = .connected(connected)
                s.renegotiating = false
                s.remoteDescriptionSet = true
                var effects: [CallEffect] = [.engineSetRemoteDescription(callId: callId, type: "answer", sdp: sdp)]
                effects += s.pendingRemoteIce.map { .engineAddIce(callId: callId, candidate: $0.candidate, sdpMid: $0.sdpMid, sdpMLineIndex: $0.sdpMLineIndex) }
                s.pendingRemoteIce = []
                return (s, effects)
            }
            guard case .connecting = state.ui, !state.remoteDescriptionSet else { return (state, []) }
            var s = state
            s.remoteDescriptionSet = true
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
                    let mode: VideoMode = call.mediaKind == .video ? .video : .audio
                    let videoAvailable = call.mediaKind == .video && !state.localVideoUnavailable
                    var connected = ConnectedState(call: call, videoMode: mode)
                    connected.localCameraIsFrontFacing = state.localCameraIsFrontFacing
                    connected.isMuted = state.pendingMuted
                    connected.audioRoute = state.observedAudioRoute
                    connected.isSpeakerOn = state.observedAudioRoute == .speaker
                    connected.localVideoEnabled = videoAvailable
                    connected.localCameraState = videoAvailable ? (state.localVideoFrameReady && !state.localCaptureInterrupted ? .on : .starting) : .off
                    connected.remoteVideoEnabled = state.remoteVideoSignaled ?? state.remoteVideoTrackAvailable
                    connected.remoteVideoState = connected.remoteVideoEnabled ? .waiting : .off
                    s.ui = .connected(connected)
                    return (s, [.restConnected(callId: callId), .startKeepAlive(callId: callId)]
                        + (s.localCaptureInterrupted && videoAvailable ? [.sendVideoState(callId: callId, enabled: false)] : [])
                        + (connected.remoteVideoEnabled ? [.engineExpectRemoteVideoFrame] : []))
                } else if case .reconnecting(let call) = state.ui {
                    // ICE recovered after a restart — restore the in-call
                    // sub-state (mute/speaker/video) that was preserved.
                    var s = state
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
            return (s, [.sendVideoState(callId: callId, enabled: !interrupted)])
        case .engineLocalFirstFrame(let callId):
            guard callId == state.activeCallId, !state.localCaptureInterrupted else { return (state, []) }
            var s = state
            if case .connecting(let call) = state.ui, call.mediaKind == .video, !state.localVideoUnavailable {
                s.localVideoFrameReady = true
            } else if case .connected(var connected) = state.ui, connected.localVideoEnabled {
                s.localVideoFrameReady = true
                connected.localCameraState = .on
                s.ui = .connected(connected)
            }
            return (s, [])
        case .mediaNegotiationFailed(let callId):
            guard callId == state.activeCallId else { return (state, []) }
            if case .connected(var c) = state.ui {
                var s = state
                c.videoUpgrade = .idle
                c.notice = .videoFailed
                s.renegotiating = false
                s.ui = .connected(c)
                return (s, [.cancelVideoUpgradeTimeout])
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
            guard var c = state.audioControls else { return (state, []) }
            c.audioRoute = route.kind
            c.isSpeakerOn = route.kind == .speaker
            var s = state
            // A system CallKit route change or a newly connected accessory is
            // authoritative. Do not replay a stale app speaker pick on the
            // next audio activation and pull sound away from that choice.
            if s.selectedAudioRoute?.kind != route.kind { s.selectedAudioRoute = nil }
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
            s.renegotiating = true
            s.renegotiationTarget = .video
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
            guard var c = state.audioControls, c.videoMode == .video else { return (state, []) }
            c.localVideoEnabled = enabled
            c.localCameraState = enabled ? .starting : .off
            var s = state
            s.updateAudioControls(c)
            return (s, [.engineSetVideoEnabled(enabled), .sendVideoState(callId: c.call.callId, enabled: enabled)])
        case .switchToAudio:
            guard case .connected(var c) = state.ui, c.videoMode == .video else { return (state, []) }
            c.videoMode = .audio
            c.localVideoEnabled = false
            c.remoteVideoEnabled = false
            c.localCameraState = .off
            c.remoteVideoState = .off
            c.videoUpgrade = .upgrading
            var s = state.withUI(.connected(c))
            s.renegotiating = true
            s.renegotiationTarget = .audio
            return (s, [.engineSetVideoNegotiationEnabled(false), .engineStopLocalVideo, .produceOfferAndSend(callId: c.call.callId)])

        // MARK: Reconnect
        case .restartIceOfferRequested(let callId):
            guard callId == state.activeCallId, case .reconnecting = state.ui else { return (state, []) }
            var s = state
            s.renegotiating = true
            s.renegotiationTarget = state.lastConnected?.videoMode ?? .audio
            return (s, [.engineSetVideoNegotiationEnabled(s.renegotiationTarget == .video), .produceOfferAndSend(callId: callId)])
        case .socketReconnected:
            guard state.ui.callInfo != nil, state.activeCallId != nil else { return (state, []) }
            guard case .connected(let c) = state.ui else { return (state, []) }
            var s = state
            s.lastConnected = c
            s.ui = .reconnecting(c.call)
            return (s, [.restGet(callId: state.activeCallId!)])
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
            guard case .reconnecting(let call) = state.ui else { return (state, []) }
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
