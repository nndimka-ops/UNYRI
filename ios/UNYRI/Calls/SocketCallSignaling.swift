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
                guard let self else { return }
                if state == .connected { self.flushPending() }
                self.connectionState.send(state == .connected)
            }
            .store(in: &cancellables)
    }

    func sendOffer(callId: String, sdp: String) { send(CallSignalingEncoder.offer(callId: callId, sdp: sdp), key: "offer:" + callId) }
    func sendAnswer(callId: String, sdp: String) { send(CallSignalingEncoder.answer(callId: callId, sdp: sdp), key: "answer:" + callId) }
    func sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        send(CallSignalingEncoder.ice(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex))
    }
    func sendVideoUpgradeRequest(callId: String) { send(CallSignalingEncoder.videoUpgradeRequest(callId: callId), key: "upgrade:" + callId) }
    func sendVideoUpgradeAccept(callId: String) { send(CallSignalingEncoder.videoUpgradeAccept(callId: callId), key: "upgrade:" + callId) }
    func sendVideoUpgradeReject(callId: String) { send(CallSignalingEncoder.videoUpgradeReject(callId: callId), key: "upgrade:" + callId) }
    func sendVideoUpgradeCancel(callId: String) { send(CallSignalingEncoder.videoUpgradeCancel(callId: callId), key: "upgrade:" + callId) }
    func sendVideoState(callId: String, enabled: Bool) { send(CallSignalingEncoder.videoState(callId: callId, enabled: enabled), key: "video:" + callId) }
    // Keepalive is periodic; a stale one is worthless after a reconnect.
    func sendKeepAlive(callId: String) { if isConnected { realtime.sendRaw(CallSignalingEncoder.keepAlive(callId: callId)) } }

    /// Frames written while the shared socket is reconnecting used to vanish (an ICE
    /// restart offer and its candidates during a Wi-Fi/cellular handover), so the
    /// restart window ran out. They wait here and go out, in order, on reconnect.
    /// Offer/answer/upgrade/video-state keep only their latest version.
    private struct Pending { let key: String?; let payload: String; let at: Date }
    private var pending: [Pending] = []
    private static let pendingLimit = 96
    private static let pendingLifetime: TimeInterval = 30

    private func send(_ payload: String, key: String? = nil) {
        if isConnected && pending.isEmpty { realtime.sendRaw(payload); return }
        if let key { pending.removeAll { $0.key == key } }
        pending.append(Pending(key: key, payload: payload, at: Date()))
        if pending.count > Self.pendingLimit { pending.removeFirst(pending.count - Self.pendingLimit) }
        if isConnected { flushPending() }
    }

    private func flushPending() {
        guard isConnected, !pending.isEmpty else { return }
        let now = Date()
        let ready = pending.filter { now.timeIntervalSince($0.at) < Self.pendingLifetime }
        pending.removeAll()
        for frame in ready { realtime.sendRaw(frame.payload) }
    }
}
