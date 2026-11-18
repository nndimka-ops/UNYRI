import Foundation

@main struct SimulatorCallContracts {
    @MainActor static func main() async {
        var checks = 0
        func check(_ value: Bool, _ message: String) { precondition(value, message); checks += 1 }
        func drain() async { for _ in 0..<100 { await Task.yield() } }
        func start(_ p: CoordinatorProbe) {
            p.startOutgoing(remoteUserId: UUID().uuidString, displayName: "Synthetic", isVideo: false)
        }
        func incoming(_ p: CoordinatorProbe) -> CallInfo {
            let call = CallInfo(callId: UUID().uuidString.lowercased(), remoteUserId: UUID().uuidString,
                                remoteDisplayName: "Synthetic", mediaKind: .audio, direction: .incoming)
            p.controller.state = .incomingRinging(call)
            return call
        }
        #if targetEnvironment(simulator)
        check(CallKitPolicy.systemCallUI == (ProcessInfo.processInfo.environment["UNYRI_WALK_CALLKIT"] == "1"),
              "simulator compile selects the environment override")
        #else
        check(CallKitPolicy.systemCallUI, "non-simulator compile always selects the existing system path")
        #endif
        for simulator in [false, true] {
            for override: String? in [nil, "", "0", "1", "true"] {
                let system = CallKitPolicy.usesSystemCallUI(isSimulator: simulator, callKitOverride: override)
                check(system == (!simulator || override == "1"), "only simulator + exact override changes UI ownership")
                CoordinatorProbe.systemCallUI = system
                AudioProbe.events = []
                let p = CoordinatorProbe()
                start(p); await drain()
                check(p.callKit.requests == (system ? 1 : 0), "start requests a transaction only with a system host")
                check(p.controller.starts == (system ? 0 : 1), "simulator starts through the controller")
                if !system {
                    check(p.manualAudioActive && RTCAudioSession.sharedInstance().isAudioEnabled,
                          "manual audio is active during ringing")
                    check(AudioProbe.events == ["configure", "AV on", "RTC on", "RTC enabled=true", "restore controls", "controller active=true"],
                          "manual activation uses the didActivate handoff after AV activation")
                    check(p.startingCall == nil && p.pendingOutgoing == nil && p.outgoingStart.activeUUID == nil,
                          "direct start releases all attempt ownership")
                    await p.controller.cancelOutgoing()
                    check(!p.manualAudioActive && !RTCAudioSession.sharedInstance().isAudioEnabled,
                          "cancelling ringing deactivates manual audio")
                    check(AudioProbe.events.contains("AV off notify=true") && AudioProbe.events.last == "RTC off",
                          "end releases AVAudioSession and informs WebRTC")
                } else { check(AudioProbe.events.isEmpty, "system start leaves audio activation to CallKit") }
                check(p.callKitUUIDByBackendId.isEmpty && p.backendIdByCallKitUUID.isEmpty,
                      "direct start creates no CallKit UUID mappings")

                let answer = CoordinatorProbe()
                let call = incoming(answer)
                // Device in-app Answer retains the existing mapped-UUID guard.
                if system { answer.callKitUUIDByBackendId[call.callId] = UUID(uuidString: call.callId) }
                answer.answerIncoming(); await drain()
                check(answer.callKit.requests == (system ? 1 : 0), "answer requests a transaction only with a system host")
                check(answer.controller.answers == (system ? 0 : 1), "simulator answers directly")
                if !system {
                    check(answer.controller.confirmations == 1, "incoming ringing is confirmed without a system report")
                    let decline = CoordinatorProbe(); _ = incoming(decline)
                    await decline.controller.rejectIncoming()
                    check(decline.controller.rejects == 1 && decline.callKit.requests == 0, "decline uses the existing controller path")
                }
                await answer.controller.hangup()
                check(!answer.manualAudioActive, "end releases manual audio and leaves device ownership alone")
                check(answer.callKit.requests == (system ? 1 : 0), "end adds no CX transaction in either mode (existing app end path)")
            }
        }
        check(UnyriCallKitProvider.shared.reports == 6, "only system mode reports incoming calls to the provider")
        CoordinatorProbe.systemCallUI = false
        let named = CoordinatorProbe()
        named.startOutgoing(remoteUserId: UUID().uuidString, displayName: "Pe\u{FFF0}er", isVideo: false)
        await drain()
        check(named.controller.state.callInfo?.remoteDisplayName == "Peer", "Outgoing names cross the sanitized admission boundary")
        let namedIncoming = CallInfo(callId: UUID().uuidString, remoteUserId: UUID().uuidString,
            remoteDisplayName: "Pe\u{202E}er", mediaKind: .audio, direction: .incoming)
        check(namedIncoming.visibleRemoteName == "Peer" && namedIncoming.remoteDisplayName == "Pe\u{202E}er",
              "Incoming presentation sanitizes the name and preserves the stored call payload")
        named.shutdown()
        let cancelled = CoordinatorProbe()
        cancelled.controller.createHook = { cancelled.cancelStarting() }
        start(cancelled); await drain()
        check(cancelled.controller.cancels == 1 && cancelled.outgoingStart.activeUUID == nil && !cancelled.manualAudioActive,
              "cancel during create cancels the resulting call and releases audio/gate")
        let fastAnswer = CoordinatorProbe()
        fastAnswer.controller.afterCreateHook = {
            fastAnswer.cancelStarting()
            fastAnswer.controller.state = .connected(ConnectedState(call: fastAnswer.controller.state.callInfo!))
        }
        start(fastAnswer); await drain()
        check(fastAnswer.controller.hangups == 1 && !fastAnswer.manualAudioActive,
              "a cancellation racing a fast peer answer hangs up instead of leaving media live")
        let failed = CoordinatorProbe(); failed.controller.failCreate = true
        start(failed); await drain()
        check(!failed.manualAudioActive && failed.startingCall == nil, "failed create releases audio without an ended transition")
        AudioProbe.failActivation = true
        let audioFailure = CoordinatorProbe(); start(audioFailure); await drain()
        check(audioFailure.controller.starts == 0 && audioFailure.actionError != nil && audioFailure.outgoingStart.activeUUID == nil,
              "audio activation failure makes no backend call and releases the attempt")
        AudioProbe.failActivation = false
        let denied = CoordinatorProbe()
        CallMediaPermissions.hook = { throw NSError(domain: "SyntheticPermission", code: 1) }
        start(denied); await drain()
        check(denied.controller.starts == 0 && denied.callKit.requests == 0 && denied.outgoingStart.activeUUID == nil,
              "permission denial starts neither controller nor system call")
        CallMediaPermissions.hook = nil
        let endedDuringAnswer = CoordinatorProbe(); _ = incoming(endedDuringAnswer)
        endedDuringAnswer.controller.answerHook = { await endedDuringAnswer.controller.hangup() }
        endedDuringAnswer.answerIncoming(); await drain()
        check(!endedDuringAnswer.manualAudioActive && endedDuringAnswer.preparingAnswerCallID == nil,
              "an end during accept releases audio and answer ownership")
        let doubleTap = CoordinatorProbe(); start(doubleTap); start(doubleTap); await drain()
        check(doubleTap.controller.starts == 1, "simulator double tap creates one call")
        doubleTap.shutdown()
        check(!doubleTap.manualAudioActive && doubleTap.isShutDown, "production shutdown releases manual activation")
        print("PASS: \(checks) Foundation simulator/device dispatch and audio lifecycle contracts; no physical media/UI proof")
    }
}
