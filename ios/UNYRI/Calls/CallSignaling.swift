#if canImport(Combine)
import Combine
#endif
import Foundation

// MARK: - Server -> client call signaling events (mirror websocket/protocol.rs)

enum CallSignalingEvent: Equatable {
    case ringing(callId: String, callerUserId: String, mediaKind: String)
    case offer(callId: String, sdp: String)
    case answer(callId: String, sdp: String)
    case ice(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    case accepted(callId: String)
    case answeredElsewhere(callId: String)
    case ended(callId: String, reason: String)
    case videoUpgradeRequest(callId: String)
    case videoUpgradeAccept(callId: String)
    case videoUpgradeReject(callId: String)
    case videoUpgradeCancel(callId: String)
    case videoState(callId: String, enabled: Bool)
}

/// Parses server->client `call.*` WebSocket frames. Snake_case keys match the
/// backend exactly; the backend never parses SDP/ICE content, only relays it.
enum CallSignalingEventParser {
    private struct Envelope: Decodable {
        let type: String
    }

    static func parse(_ data: Data) -> CallSignalingEvent? {
        guard let env = try? JSONDecoder().decode(Envelope.self, from: data) else { return nil }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        switch env.type {
        case "call.ringing":
            struct P: Decodable { let callId: String; let callerUserId: String; let mediaKind: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .ringing(callId: p.callId, callerUserId: p.callerUserId, mediaKind: p.mediaKind)
        case "call.offer":
            struct P: Decodable { let callId: String; let sdp: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .offer(callId: p.callId, sdp: p.sdp)
        case "call.answer":
            struct P: Decodable { let callId: String; let sdp: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .answer(callId: p.callId, sdp: p.sdp)
        case "call.ice":
            struct P: Decodable { let callId: String; let candidate: String; let sdpMid: String?; let sdpMLineIndex: Int? }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .ice(callId: p.callId, candidate: p.candidate, sdpMid: p.sdpMid, sdpMLineIndex: p.sdpMLineIndex)
        case "call.accepted":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .accepted(callId: p.callId)
        case "call.answered_elsewhere":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .answeredElsewhere(callId: p.callId)
        case "call.ended":
            struct P: Decodable { let callId: String; let reason: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .ended(callId: p.callId, reason: p.reason)
        case "call.video_upgrade_request":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .videoUpgradeRequest(callId: p.callId)
        case "call.video_upgrade_accept":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .videoUpgradeAccept(callId: p.callId)
        case "call.video_upgrade_reject":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .videoUpgradeReject(callId: p.callId)
        case "call.video_upgrade_cancel":
            struct P: Decodable { let callId: String }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .videoUpgradeCancel(callId: p.callId)
        case "call.video_state":
            struct P: Decodable { let callId: String; let enabled: Bool }
            guard let p = try? decoder.decode(P.self, from: data) else { return nil }
            return .videoState(callId: p.callId, enabled: p.enabled)
        default:
            return nil
        }
    }
}

/// Encodes client -> server `call.*` WebSocket frames (snake_case, matching
/// the backend `ClientMessage` enum exactly).
enum CallSignalingEncoder {
    static func offer(callId: String, sdp: String) -> String {
        frame(["type": "call.offer", "call_id": callId, "sdp": sdp])
    }
    static func answer(callId: String, sdp: String) -> String {
        frame(["type": "call.answer", "call_id": callId, "sdp": sdp])
    }
    static func ice(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) -> String {
        var dict: [String: Any] = ["type": "call.ice", "call_id": callId, "candidate": candidate]
        if let sdpMid { dict["sdp_mid"] = sdpMid }
        if let sdpMLineIndex { dict["sdp_m_line_index"] = sdpMLineIndex }
        return frame(dict)
    }
    static func videoUpgradeRequest(callId: String) -> String {
        frame(["type": "call.video_upgrade_request", "call_id": callId])
    }
    static func videoUpgradeAccept(callId: String) -> String {
        frame(["type": "call.video_upgrade_accept", "call_id": callId])
    }
    static func videoUpgradeReject(callId: String) -> String {
        frame(["type": "call.video_upgrade_reject", "call_id": callId])
    }
    static func videoUpgradeCancel(callId: String) -> String {
        frame(["type": "call.video_upgrade_cancel", "call_id": callId])
    }
    static func videoState(callId: String, enabled: Bool) -> String {
        frame(["type": "call.video_state", "call_id": callId, "enabled": enabled])
    }
    static func keepAlive(callId: String) -> String {
        frame(["type": "call.keep_alive", "call_id": callId])
    }

    private static func frame(_ dict: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: dict, options: [])
        return String(decoding: data, as: UTF8.self)
    }
}

// MARK: - Call signaling client seam (mirror Android CallSignalingClient)

/// Logically separate signaling channel for opaque SDP/ICE relay. Physically
/// reuses the existing authenticated messaging WebSocket (never a second
/// socket). The controller only ever talks to this interface.
#if canImport(Combine)
@MainActor
protocol CallSignalingClient: AnyObject {
    var events: PassthroughSubject<CallSignalingEvent, Never> { get }
    /// True while the underlying messaging WebSocket is connected. The
    /// controller observes transitions for reconnect reconciliation.
    var connectionState: PassthroughSubject<Bool, Never> { get }
    var isConnected: Bool { get }

    func sendOffer(callId: String, sdp: String)
    func sendAnswer(callId: String, sdp: String)
    func sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    func sendVideoUpgradeRequest(callId: String)
    func sendVideoUpgradeAccept(callId: String)
    func sendVideoUpgradeReject(callId: String)
    func sendVideoUpgradeCancel(callId: String)
    func sendVideoState(callId: String, enabled: Bool)
    func sendKeepAlive(callId: String)
}

#endif
