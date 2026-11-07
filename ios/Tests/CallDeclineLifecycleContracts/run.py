"""Execute production decline/controller/CallKit methods with OS/media seams.

The fake provider/actions expose ordering and completion counts. They cannot
prove physical ringtone playback or the actual system call UI on an iPhone.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
controller = (root / "ios/UNYRI/Calls/CallController.swift").read_text(encoding="utf-8")
coordinator = (root / "ios/UNYRI/Calls/CallKitCoordinator.swift").read_text(encoding="utf-8")


def member(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func ", "func ")


seams = r'''
import Foundation
@MainActor final class Gate {
    var entered = false; var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class AVAudioPlayer {
    static var starts = 0, stops = 0
    var numberOfLoops = 0; var volume: Float = 1; var isPlaying = false
    init(data: Data) throws {}
    func play() -> Bool { isPlaying = true; Self.starts += 1; return true }
    func stop() { isPlaying = false; Self.stops += 1 }
}
@MainActor final class EngineProbe {
    var disposals = 0, stops = 0
    func dispose() { disposals += 1 }
    func stopLocalVideo() { stops += 1 }
    func setMuted(_ value: Bool) {}
    func setSpeakerOn(_ value: Bool) {}
    func setAudioRoute(_ route: AudioRoute) {}
}
@MainActor final class CallsRepository {
    enum Action { case reject, hangup, cancel }
    var currentSessionGeneration: UInt64 = 1
    var ownerId: UUID? = UUID(uuidString: "33333333-3333-3333-3333-333333333333")
    var requests: [(Action, UUID)] = []
    var gate: Gate?
    func perform(_ action: Action, callId: UUID) async throws -> Bool {
        requests.append((action, callId)); if let gate { await gate.wait() }; return true
    }
}
@MainActor final class ControllerProbe {
    var state = CallUiState.idle { willSet { onState?(newValue) } }
    var onState: ((CallUiState) -> Void)?
    var reducerState = CallReducerState()
    var terminalCalls = CallKitReportTracker()
    var isShutDown = false
    var generation: UInt64 = 1
    let api = CallsRepository()
    var apiGeneration: UInt64 { api.currentSessionGeneration }
    var currentSessionGeneration: UInt64 { apiGeneration }
    let engine = EngineProbe()
    var automaticSpeaker = CallAutomaticSpeakerPolicy()
    let effectQueue = CallEffectSerialQueue()
    var keepAliveTask: Task<Void, Never>?
    var upgradeTimeoutTask: Task<Void, Never>?
    var connectedAt: Date?
    var ringbackPlayer: AVAudioPlayer?
    var callAudioActive = false
    lazy var ringbackData = CallRingbackPolicy.waveData()
    var dismissed: [String] = []
    var executed: [CallEffect] = []
    var resolveCallerName: (String) async -> String? = { _ in nil }
    var recoveryStarts = 0, recoveryStops = 0
    var externalMediaBusy: () -> Bool = { false }
    func interruptExternalCapture() {}
    func startIncomingRecovery(_ id: String) { recoveryStarts += 1 }
    func stopIncomingRecovery() { recoveryStops += 1 }
    func cancelIceRestart() {}
    func onDismissIncomingPush(_ id: String) { dismissed.append(id) }
    func acceptVideoUpgrade() {}
    func failCall(callId: String) { apply(.engineConnectionState(callId: callId, state: .failed)) }
    func cancelOutgoing() async {
        guard case .outgoingRinging(let info) = state else { return }
        apply(.remoteEnded(callId: info.callId, reason: "cancelled"))
    }
    func execute(_ effect: CallEffect) async -> Bool { executed.append(effect); return true }
    __CONTROLLER__
}
@MainActor class CXAction {
    let uuid = UUID(); let callUUID: UUID
    var timeoutDate: Date; var isComplete = false; var failed = 0, fulfilled = 0
    init(call: UUID, expires: Date = Date().addingTimeInterval(60)) { callUUID = call; timeoutDate = expires }
    func fail() { precondition(!isComplete); isComplete = true; failed += 1 }
    func fulfill() { precondition(!isComplete); isComplete = true; fulfilled += 1 }
}
@MainActor final class CXAnswerCallAction: CXAction {}
@MainActor final class CXEndCallAction: CXAction {}
@MainActor final class CXStartCallAction: CXAction {}
@MainActor final class CXHandle { enum Kind { case generic }; init(type: Kind, value: String) {} }
@MainActor final class CXCallUpdate {
    var remoteHandle: CXHandle?; var localizedCallerName: String?; var hasVideo = false
}
@MainActor final class ProviderProbe {
    var reports: [(UUID, (Error?) -> Void)] = []
    var endings: [UUID] = []
    var updates = 0
    func reportNewIncomingCall(with uuid: UUID, update: CXCallUpdate, completion: @escaping (Error?) -> Void) { reports.append((uuid, completion)) }
    func reportCall(with uuid: UUID, updated: CXCallUpdate) { updates += 1 }
    func reportCall(with uuid: UUID, endedAt: Date, reason: CallKitEndReasonKind) { endings.append(uuid) }
}
@MainActor final class CoordinatorProbe {
    let controller: ControllerProbe
    var generation: UInt64 = 1
    var apiGeneration: UInt64 { controller.currentSessionGeneration }
    var isShutDown = false
    var preparingAnswerCallID: String?
    var incomingAnswer: (action: CXAnswerCallAction, task: Task<Void, Never>)?
    var callKitUUIDByBackendId: [String: UUID] = [:]
    var backendIdByCallKitUUID: [UUID: String] = [:]
    var tracker = CallKitReportTracker()
    var outgoingStart = CallKitOutgoingStartGate()
    var cancelledOutgoingUUID: UUID?
    let provider = ProviderProbe()
    var errors = 0
    init(_ controller: ControllerProbe) { self.controller = controller }
    func configureCapabilities(_ update: CXCallUpdate) {}
    func reportError(_ error: Error) { errors += 1 }
    func cxReason(_ reason: CallKitEndReasonKind) -> CallKitEndReasonKind { reason }
    __COORDINATOR__
}
'''
controller_members = ["func rejectIncoming()", "func hangup()", "func acknowledgeEnded()",
                      "private func handleSignaling(", "private func apply(",
                      "func setCallAudioActive(", "private func updateRingback()"]
coordinator_members = ["private func cancelIncomingAnswer(", "private func reportIncoming(",
                       "private func reportTerminal(", "private func performEnd(",
                       "private func endExpiredCall(", "private func handleActionTimeout("]
seams = seams.replace("__CONTROLLER__", "\n".join(member(controller, m) for m in controller_members))
seams = seams.replace("__COORDINATOR__", "\n".join(member(coordinator, m) for m in coordinator_members))
seams += "\n" + member(controller, "private extension CallUiState {")

tests = r'''
import Foundation
@main struct Run {
    @MainActor static var checks = 0
    @MainActor static func check(_ value: Bool, _ message: String) { precondition(value, message); checks += 1 }
    @MainActor static func drain() async { for _ in 0..<60 { await Task.yield() } }
    @MainActor static func main() async throws {
        let id = "11111111-1111-4111-8111-111111111111"
        let next = "44444444-4444-4444-8444-444444444444"
        let peer = "22222222-2222-4222-8222-222222222222"
        func ring(_ id: String) -> CallSignalingEvent { .ringing(callId: id, callerUserId: peer, mediaKind: "audio") }
        do {
            let p = ControllerProbe(); let gate = Gate(); p.api.gate = gate
            p.handleSignaling(ring(id)); await p.rejectIncoming()
            check(p.state == .ended(p.state.callInfo!, reason: .rejected), "Decline ends local UI before HTTP response")
            check(p.engine.disposals == 1 && p.dismissed == [id], "Existing terminal bridge immediately releases media and incoming presentation")
            p.acknowledgeEnded(); check(p.state == .idle, "Normal terminal acknowledgement releases app interaction")
            p.handleSignaling(ring(id)); p.handleSignaling(ring(id.uppercased()))
            check(p.state == .idle && p.recoveryStarts == 1, "Late WS and REST recovery duplicates cannot resurrect declined UUID after idle")
            p.handleSignaling(ring(next))
            check(p.state.callInfo?.callId == next, "A different call still rings while old Reject request is pending")
            p.handleSignaling(.ended(callId: id, reason: "rejected"))
            check(p.state.callInfo?.callId == next && CallKitPolicy.keepsSignalingInBackground(p.state), "Late terminal echo from declined call cannot end successor")
            await drain(); gate.open(); await drain()
            check(p.api.requests.count == 1 && p.api.requests[0].1 == UUID(uuidString: id), "Exactly one Reject is sent for the original target")
            check(AVAudioPlayer.starts == 0, "Incoming calls never start the outgoing ringback player")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.onState = { state in
                switch state {
                case .incomingRinging(let call): kit.reportIncoming(call)
                case .ended(let call, let reason): kit.reportTerminal(call, reason: reason)
                default: break
                }
            }
            p.handleSignaling(ring(id))
            check(kit.provider.reports.count == 1, "Incoming call registers with system exactly once")
            await p.rejectIncoming(); p.acknowledgeEnded()
            p.handleSignaling(ring(id))
            check(kit.provider.reports.count == 1 && p.state == .idle, "Decline during pending native registration cannot re-register system ringtone")
            check(kit.tracker.isTerminal(id), "Deferred terminal remains fenced before registration completion")
            kit.provider.reports[0].1(nil); await drain()
            check(kit.provider.endings == [UUID(uuidString: id)!], "Pending registration completion delivers exactly one terminal to system")
            check(kit.callKitUUIDByBackendId.isEmpty && kit.backendIdByCallKitUUID.isEmpty, "Registration/end maps are released after deferred termination")
            kit.reportIncoming(CallInfo(callId: id, remoteUserId: peer, remoteDisplayName: "Late", mediaKind: .audio, direction: .incoming))
            check(kit.provider.reports.count == 1, "Platform adapter independently ignores terminal incoming duplicates")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.handleSignaling(ring(id)); kit.reportIncoming(p.state.callInfo!)
            await p.rejectIncoming(); p.acknowledgeEnded()
            kit.provider.reports[0].1(NSError(domain: "SyntheticOldRegistration", code: 1)); await drain()
            check(kit.errors == 0 && p.state == .idle, "Late registration failure cannot publish an error or reopen a declined call")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            let action = CXAnswerCallAction(call: UUID(uuidString: id)!)
            kit.preparingAnswerCallID = id
            kit.incomingAnswer = (action, Task { @MainActor in await drain() })
            kit.cancelIncomingAnswer(callUUID: UUID(uuidString: id))
            check(action.failed == 1 && action.isComplete && kit.incomingAnswer == nil, "Cancelling an in-flight Answer also completes its native action")
            check(kit.preparingAnswerCallID == nil, "Cancellation releases permission-preflight owner for next call")
            kit.cancelIncomingAnswer(); check(action.failed == 1, "Answer cancellation is idempotent")
            let timeout = CXAnswerCallAction(call: UUID(uuidString: id)!)
            kit.incomingAnswer = (timeout, Task { @MainActor in })
            kit.cancelIncomingAnswer(callUUID: timeout.callUUID, actionTimedOut: true)
            check(!timeout.isComplete && timeout.failed == 0, "timedOutPerforming never fails or fulfills its expired action")
            let expired = CXAnswerCallAction(call: UUID(uuidString: id)!, expires: Date().addingTimeInterval(-1))
            kit.incomingAnswer = (expired, Task { @MainActor in })
            kit.cancelIncomingAnswer()
            check(!expired.isComplete, "Already expired answer is not completed even if timeout delegate is late")
            let done = CXAnswerCallAction(call: UUID(uuidString: id)!); done.fulfill()
            kit.incomingAnswer = (done, Task { @MainActor in })
            kit.cancelIncomingAnswer(); check(done.failed == 0 && done.fulfilled == 1, "Already fulfilled Answer is never completed twice")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            let gate = Gate(); p.api.gate = gate
            p.handleSignaling(ring(id)); kit.reportIncoming(p.state.callInfo!)
            let answer = CXAnswerCallAction(call: UUID(uuidString: id)!)
            kit.preparingAnswerCallID = id
            kit.incomingAnswer = (answer, Task { @MainActor in await drain() })
            let action = CXEndCallAction(call: UUID(uuidString: id)!)
            kit.performEnd(action); await drain()
            check(answer.failed == 1 && kit.incomingAnswer == nil, "Native Decline releases a previously pending Answer exactly once")
            check(action.fulfilled == 1 && p.state == .ended(p.state.callInfo!, reason: .hangup), "Native End fulfills and dismisses locally while its server request is blocked")
            check(p.engine.disposals == 1 && p.api.requests.count == 1, "Decline during Answer uses one shared media teardown and one server termination")
            if case .hangup = p.api.requests[0].0 { checks += 1 } else { preconditionFailure("Accept race requires Hangup instead of Reject") }
            gate.open(); await drain()
            let nextAnswer = CXAnswerCallAction(call: UUID(uuidString: next)!)
            kit.preparingAnswerCallID = next
            kit.incomingAnswer = (nextAnswer, Task { @MainActor in await drain() })
            kit.cancelIncomingAnswer(callUUID: UUID(uuidString: id))
            check(kit.preparingAnswerCallID == next && kit.incomingAnswer?.action.uuid == nextAnswer.uuid && !nextAnswer.isComplete, "A stale call's cancellation cannot release the next call's Answer/preflight")
            kit.cancelIncomingAnswer()
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.handleSignaling(ring(id)); kit.reportIncoming(p.state.callInfo!)
            let action = CXEndCallAction(call: UUID(uuidString: id)!)
            kit.performEnd(action)
            p.apply(.remoteEnded(callId: id, reason: "cancelled")); p.acknowledgeEnded(); p.handleSignaling(ring(next))
            await drain()
            check(p.state.callInfo?.callId == next && CallKitPolicy.keepsSignalingInBackground(p.state), "Deferred native End for old call cannot reject its successor")
            check(p.api.requests.isEmpty && action.fulfilled == 1, "Stale end task fulfills only its old system action without contacting successor")
        }
        do {
            let p = ControllerProbe(); p.handleSignaling(ring(id)); p.setCallAudioActive(true)
            await p.rejectIncoming(); p.acknowledgeEnded(); p.setCallAudioActive(true)
            check(p.ringbackPlayer == nil, "Late audio activation cannot restart feedback after incoming decline")
            let outgoing = CallInfo(callId: next, remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio, direction: .outgoing)
            p.apply(.startCall(outgoing)); p.setCallAudioActive(true)
            check(p.ringbackPlayer?.isPlaying == true, "New outgoing call may play its own ringback")
            await p.hangup()
            check(p.ringbackPlayer == nil && AVAudioPlayer.stops == 1, "Existing common terminal update stops outgoing ringback exactly once")
        }
        // A system End must complete before its deferred MainActor task can
        // miss the deadline; local suppression alone cannot stop system UI.
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.onState = { state in
                switch state {
                case .incomingRinging(let call): kit.reportIncoming(call)
                case .ended(let call, let reason): kit.reportTerminal(call, reason: reason)
                default: break
                }
            }
            p.handleSignaling(ring(id)); kit.provider.reports[0].1(nil); await drain()
            let end = CXEndCallAction(call: UUID(uuidString: id)!)
            kit.performEnd(end)
            check(end.fulfilled == 1, "Native End completes synchronously before deferred teardown can miss its deadline")
            end.timeoutDate = Date().addingTimeInterval(-1)
            kit.handleActionTimeout(end); await drain()
            check(p.state == .ended(p.state.callInfo!, reason: .rejected), "Expired deferred task still tears down its original incoming call")
            check(end.fulfilled == 1 && end.failed == 0 && kit.provider.endings.isEmpty,
                  "A fulfilled End neither completes twice nor needs a duplicate native terminal")
            check(p.api.requests.count == 1 && kit.callKitUUIDByBackendId.isEmpty,
                  "Immediate End fulfillment retains exactly one server termination and map cleanup")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.onState = { state in
                if case .ended(let call, let reason) = state { kit.reportTerminal(call, reason: reason) }
            }
            p.handleSignaling(ring(id)); kit.reportIncoming(p.state.callInfo!)
            kit.provider.reports[0].1(nil); await drain()
            let expired = CXEndCallAction(call: UUID(uuidString: id)!, expires: Date().addingTimeInterval(-1))
            kit.performEnd(expired); kit.handleActionTimeout(expired); await drain()
            check(!expired.isComplete, "An already expired End is never fulfilled or failed")
            check(p.state == .ended(p.state.callInfo!, reason: .hangup) && kit.provider.endings == [expired.callUUID],
                  "An expired incoming End closes through the existing terminal reporter exactly once")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            let native = UUID()
            p.apply(.startCall(CallInfo(callId: id, remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio, direction: .outgoing)))
            kit.backendIdByCallKitUUID[native] = id; kit.callKitUUIDByBackendId[id] = native
            let expired = CXEndCallAction(call: native, expires: Date().addingTimeInterval(-1))
            kit.performEnd(expired); await drain()
            check(p.api.requests.count == 1 && p.api.requests[0].1 == UUID(uuidString: id),
                  "Expired outgoing End resolves its mapped backend UUID instead of the distinct native UUID")
            check(!expired.isComplete, "Mapped outgoing recovery does not complete an expired native action")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            let native = UUID(); _ = kit.outgoingStart.begin(native)
            kit.handleActionTimeout(CXEndCallAction(call: native, expires: Date().addingTimeInterval(-1)))
            check(kit.cancelledOutgoingUUID == native && p.api.requests.isEmpty,
                  "Expired End during unbound outgoing creation cancels only its pending native attempt")
        }
        do {
            let p = ControllerProbe(); let kit = CoordinatorProbe(p)
            p.handleSignaling(ring(id))
            kit.performEnd(CXEndCallAction(call: UUID(uuidString: id)!, expires: Date().addingTimeInterval(-1)))
            p.apply(.remoteEnded(callId: id, reason: "cancelled")); p.acknowledgeEnded(); p.handleSignaling(ring(next))
            await drain()
            check(p.state.callInfo?.callId == next && p.api.requests.isEmpty,
                  "Expired End recovery for an old call cannot terminate a successor")
        }
        print("PASS: \(checks) production decline/controller/CallKit race contracts; no physical audio claim")
    }
}
'''
with tempfile.TemporaryDirectory(prefix="coco-decline-contracts-") as directory:
    folder = Path(directory)
    adapter = folder / "Adapters.swift"
    adapter.write_text(seams, encoding="utf-8")
    main = folder / "Tests.swift"
    main.write_text(tests, encoding="utf-8")
    executable = folder / "decline.exe"
    files = [root / p for p in ["ios/UNYRI/Support/AppLanguage.swift", "ios/UNYRI/Support/HistoryArchive.swift",
        "ios/UNYRI/Calls/CallModels.swift", "ios/UNYRI/Calls/CallSignaling.swift", "ios/UNYRI/Calls/CallReconciliation.swift",
        "ios/UNYRI/Calls/CallStateReducer.swift", "ios/UNYRI/Calls/CallKitPolicy.swift"]]
    subprocess.run(["swiftc", "-parse-as-library", *map(str, files), str(adapter), str(main), "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=30)
