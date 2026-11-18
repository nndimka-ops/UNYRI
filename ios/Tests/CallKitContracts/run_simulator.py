"""Execute production simulator dispatch/audio methods using Foundation seams.

No CallKit, AVFoundation, WebRTC, device, account or network is accessed.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / "ios/UNYRI/Calls/CallKitCoordinator.swift").read_text(encoding="utf-8")


def member(text, marker):
    start = text.index(marker)
    opening = text.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end].replace("private func ", "func ")


seams = r'''
import Foundation
@MainActor enum AudioProbe {
    static var events: [String] = []
    static var failActivation = false
}
@MainActor final class AVAudioSession {
    enum PortOverride { case none }
    struct SetActiveOptions: OptionSet {
        let rawValue: Int
        static let notifyOthersOnDeactivation = Self(rawValue: 1)
    }
    static let shared = AVAudioSession()
    static func sharedInstance() -> AVAudioSession { shared }
    func setActive(_ active: Bool, options: SetActiveOptions = []) throws {
        if active && AudioProbe.failActivation { throw NSError(domain: "SyntheticAudio", code: 1) }
        AudioProbe.events.append(active ? "AV on" : "AV off notify=\(options == .notifyOthersOnDeactivation)")
    }
}
@MainActor final class RTCAudioSession {
    static let shared = RTCAudioSession()
    static func sharedInstance() -> RTCAudioSession { shared }
    var isAudioEnabled = false { didSet { AudioProbe.events.append("RTC enabled=\(isAudioEnabled)") } }
    var useManualAudio = true
    func audioSessionDidActivate(_ session: AVAudioSession) { AudioProbe.events.append("RTC on") }
    func audioSessionDidDeactivate(_ session: AVAudioSession) { AudioProbe.events.append("RTC off") }
}
@MainActor enum UnyriCallAudioSession {
    static func configure(outputOverride: AVAudioSession.PortOverride?) throws { AudioProbe.events.append("configure") }
}
@MainActor enum CallMediaPermissions {
    enum PermissionError: Error { case cameraDenied }
    static var hook: (() async throws -> Void)?
    static func request(for kind: CallMediaKind) async throws { try await hook?() }
}
final class CXHandle { enum Kind { case generic }; init(type: Kind, value: String) {} }
final class CXStartCallAction {
    var isVideo = false; var contactIdentifier: String?
    init(call: UUID, handle: CXHandle) {}
}
final class CXAnswerCallAction { init(call: UUID) {} }
struct CXTransaction { let action: Any }
@MainActor final class TransactionProbe {
    var requests = 0
    func request(_ transaction: CXTransaction, completion: @escaping (Error?) -> Void) { requests += 1 }
}
final class CXCallUpdate {
    var remoteHandle: CXHandle?; var localizedCallerName: String?; var hasVideo = false
}
@MainActor final class ProviderProbe {
    var reports = 0
    func reportCall(with uuid: UUID, updated: CXCallUpdate) { reports += 1 }
}
@MainActor final class UnyriCallKitProvider {
    static let shared = UnyriCallKitProvider()
    var reports = 0
    func adopt(_ uuid: UUID, coordinator: CoordinatorProbe) -> Bool { false }
    func hasReport(_ uuid: UUID) -> Bool { false }
    func reportIncoming(_ call: CallInfo, coordinator: CoordinatorProbe, completion: @escaping (Error?) -> Void) { reports += 1 }
    func detach(_ coordinator: CoordinatorProbe, calls: Set<UUID>) {}
}
@MainActor final class ControllerProbe {
    var state = CallUiState.idle { didSet { onState?(state) } }
    var onState: ((CallUiState) -> Void)?
    var generation: UInt64 = 1
    var starts = 0, answers = 0, cancels = 0, rejects = 0, hangups = 0, confirmations = 0
    var createHook: (() async -> Void)?
    var afterCreateHook: (() async -> Void)?
    var answerHook: (() async -> Void)?
    var failCreate = false
    func presentBlockedStartIfNeeded() -> Bool { state != .idle }
    func startCall(remoteUserId: String, remoteDisplayName: String, mediaKind: CallMediaKind) async {
        starts += 1
        await createHook?()
        if failCreate { state = .failed(nil, reason: .generic); return }
        state = .outgoingRinging(CallInfo(callId: UUID().uuidString.lowercased(), remoteUserId: remoteUserId,
            remoteDisplayName: remoteDisplayName, mediaKind: mediaKind, direction: .outgoing))
        await afterCreateHook?()
    }
    func acceptIncoming() async {
        answers += 1
        let call = state.callInfo!
        await answerHook?()
        guard case .incomingRinging = state else { return }
        state = .connected(ConnectedState(call: call))
    }
    func cancelOutgoing() async { cancels += 1; state = .ended(state.callInfo!, reason: .cancelled) }
    func rejectIncoming() async { rejects += 1; state = .ended(state.callInfo!, reason: .rejected) }
    func hangup() async { hangups += 1; state = .ended(state.callInfo!, reason: .hangup) }
    func synchronizeAudioRoute() {}
    func restoreAudioControls() { AudioProbe.events.append("restore controls") }
    func setCallAudioActive(_ active: Bool) { AudioProbe.events.append("controller active=\(active)") }
    func incomingCallReportCompleted(callId: String, succeeded: Bool) { if succeeded { confirmations += 1 } }
    func failCall(callId: String) { state = .ended(state.callInfo!, reason: .failed) }
    func dismissIncomingLocally(callId: String) { state = .ended(state.callInfo!, reason: .failed) }
}
@MainActor final class CoordinatorProbe {
    static var systemCallUI = true
    struct StartingCall: Equatable { let remoteUserId: String; let name: String; let isVideo: Bool }
    var startingCall: StartingCall?
    let controller = ControllerProbe()
    var isShutDown = false
    var generation: UInt64 = 1
    var apiGeneration: UInt64 { controller.generation }
    var pendingOutgoing: (uuid: UUID, remoteUserId: String, name: String, isVideo: Bool)?
    var outgoingStart = CallKitOutgoingStartGate()
    var cancelledOutgoingUUID: UUID?
    var preparingAnswerCallID: String?
    var manualAudioActive = false
    var lastState = CallUiState.idle
    var callKitUUIDByBackendId: [String: UUID] = [:]
    var backendIdByCallKitUUID: [UUID: String] = [:]
    var tracker = CallKitReportTracker()
    var actionError: String?
    var cancellables = Set<Int>()
    let callKit = TransactionProbe()
    let provider = ProviderProbe()
    init() { controller.onState = { [weak self] in self?.stateChanged($0) } }
    func recordAudioActivation(enteredAt: TimeInterval) {}
    func configureCapabilities(_ update: CXCallUpdate) {}
    func observeIncomingReport(_ uuid: UUID, callId: String) {}
    func cancelIncomingAnswer(callUUID: UUID? = nil) {}
    func reportOutgoingProgress(_ state: CallUiState) {}
    func reportTerminal(_ call: CallInfo, reason: CallEndReason) {}
    func syncMuteToSystem(callId: String, muted: Bool) {}
    var isHandlingMuteAction = false
    __MEMBERS__
}
'''
members = ["func shutdown()", "func startOutgoing(", "func cancelStarting()", "func answerIncoming()",
           "private func startWithoutSystemUI(", "private func activateAudio(",
           "private func activateManualAudio(", "private func deactivateManualAudio()",
           "private func reportIncoming(", "private func stateChanged(", "private func reportError("]
seams = seams.replace("__MEMBERS__", "\n".join(member(source, m) for m in members))
seams += "\n" + member((root / "ios/UNYRI/Support/PeerDisplayNames.swift").read_text(encoding="utf-8"), "extension CallInfo {")

with tempfile.TemporaryDirectory(prefix="unyri-simulator-call-contracts-") as directory:
    folder = Path(directory)
    adapter = folder / "Adapters.swift"
    adapter.write_text(seams, encoding="utf-8")
    files = [root / p for p in ["ios/UNYRI/Support/AppLanguage.swift", "ios/UNYRI/Support/HistoryArchive.swift",
        "ios/UNYRI/Calls/CallModels.swift", "ios/UNYRI/Calls/CallStateReducer.swift",
        "ios/UNYRI/Calls/CallReconciliation.swift", "ios/UNYRI/Calls/CallKitPolicy.swift",
        "ios/UNYRI/Support/DisplayNameSanitizer.swift"]]
    # Compile the real localization enum without its SwiftUI View extension
    # on every platform: this contract needs Foundation only.
    language = folder / "Language.swift"
    language.write_text("import Foundation\n" + member(files[0].read_text(encoding="utf-8"), "enum UnyriL10n {"), encoding="utf-8")
    files[0] = language
    if "--check-sources" in sys.argv:
        print("PASS: simulator contract extracted all production methods; Swift execution still required")
        sys.exit(0)
    executable = folder / "simulator-calls.exe"
    subprocess.run(["swiftc", "-parse-as-library", *map(str, files), str(adapter),
                    str(Path(__file__).with_name("Simulator.swift")), "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=30)
