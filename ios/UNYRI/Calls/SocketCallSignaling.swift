import Combine
import Foundation

/// Adapts the shared authenticated messaging WebSocket (`RealtimeClient`) to
/// the `CallSignalingClient` seam — never opens a second socket (mirror of
/// Android's `CallSignalingClientImpl`).
@MainActor
final class SocketCallSignaling: CallSignalingClient {
    let events: PassthroughSubject<CallSignalingEvent, Never>
    let connectionState = PassthroughSubject<Bool, Never>()

    private let realtime: RealtimeClient
    private var cancellables = Set<AnyCancellable>()

    var isConnected: Bool { realtime.state == .connected }

    init(realtime: RealtimeClient) {
        self.realtime = realtime
        self.events = realtime.callEvents
        realtime.$state
            .removeDuplicates()
            .sink { [weak self] state in
                self?.connectionState.send(state == .connected)
            }
            .store(in: &cancellables)
    }

    func sendOffer(callId: String, sdp: String) { realtime.sendRaw(CallSignalingEncoder.offer(callId: callId, sdp: sdp)) }
    func sendAnswer(callId: String, sdp: String) { realtime.sendRaw(CallSignalingEncoder.answer(callId: callId, sdp: sdp)) }
    func sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        realtime.sendRaw(CallSignalingEncoder.ice(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex))
    }
    func sendVideoUpgradeRequest(callId: String) { realtime.sendRaw(CallSignalingEncoder.videoUpgradeRequest(callId: callId)) }
    func sendVideoUpgradeAccept(callId: String) { realtime.sendRaw(CallSignalingEncoder.videoUpgradeAccept(callId: callId)) }
    func sendVideoUpgradeReject(callId: String) { realtime.sendRaw(CallSignalingEncoder.videoUpgradeReject(callId: callId)) }
    func sendVideoUpgradeCancel(callId: String) { realtime.sendRaw(CallSignalingEncoder.videoUpgradeCancel(callId: callId)) }
    func sendVideoState(callId: String, enabled: Bool) { realtime.sendRaw(CallSignalingEncoder.videoState(callId: callId, enabled: enabled)) }
    func sendKeepAlive(callId: String) { realtime.sendRaw(CallSignalingEncoder.keepAlive(callId: callId)) }
}
