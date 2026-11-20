import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@main struct Main {
    static func candidate(_ address: String) -> String { "candidate:1 1 udp 123 " + address + " 12345 typ host" }
    static func main() {
        for address in ["127.0.0.1", "0.0.0.0", "169.254.1.1", "224.0.0.1", "::", "::1", "fe80::1234", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "::127.0.0.1", "example.invalid", "bad:::1", "fe80::1%en0"] {
            check(!RemoteIcePolicy.allows(candidate(address), relayOnly: false))
            check(!RemoteIcePolicy.allows(candidate(address), relayOnly: true))
        }
        for address in ["10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "fd00::1", "fc00::1", "::ffff:192.168.1.1", "64:ff9b::c0a8:101"] {
            check(RemoteIcePolicy.allows(candidate(address), relayOnly: false))
            check(!RemoteIcePolicy.allows(candidate(address), relayOnly: true))
        }
        for address in ["8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111", "peer.local"] {
            check(RemoteIcePolicy.allows(candidate(address), relayOnly: false))
            check(RemoteIcePolicy.allows(candidate(address), relayOnly: true))
        }
        let sdp = "v=0\r\na=fingerprint:sha-256 ABC\r\na=x-unyri-sas-commit:kept\r\na=" + candidate("127.0.0.1") + "\r\na=" + candidate("8.8.8.8") + "\r\n"
        let filtered = RemoteIcePolicy.filterSDP(sdp, relayOnly: false)
        check(!filtered.contains("127.0.0.1") && filtered.contains("8.8.8.8") && filtered.contains("x-unyri-sas-commit:kept"))
        // Direct files wire their runtime relayOnly value into both filters.
        for relayOnly in [false, true] {
            let mixed = sdp + "a=" + candidate("192.168.1.1") + "\r\n"
            let value = RemoteIcePolicy.filterSDP(mixed, relayOnly: relayOnly)
            check(value.contains("192.168.1.1") == !relayOnly)
            check(RemoteIcePolicy.allows(candidate("192.168.1.1"), relayOnly: relayOnly) == !relayOnly)
            check(value.contains("8.8.8.8") && !value.contains("127.0.0.1"))
        }
        let call = CallInfo(callId: "call", remoteUserId: "peer", remoteDisplayName: "Peer", mediaKind: .audio, direction: .incoming)
        var state = CallReducerState(); state.ui = .incomingRinging(call); state.activeCallId = call.callId
        for _ in 0..<1000 { state = CallStateReducer.reduce(state, .remoteIce(callId: "call", candidate: candidate("8.8.8.8"), sdpMid: nil, sdpMLineIndex: 0)).0 }
        check(state.pendingRemoteIce.count == 64)
        let large = "candidate:a" + String(repeating: "\u{0301}", count: 5000) + " 1 udp 1 8.8.8.8 1 typ host"
        check(large.count < 2048 && large.utf8.count > 2048)
        check(!RemoteIcePolicy.allows(large, relayOnly: false))
        state.pendingRemoteIce = []
        check(CallStateReducer.reduce(state, .remoteIce(callId: "call", candidate: large, sdpMid: nil, sdpMLineIndex: 0)).0.pendingRemoteIce.isEmpty)
        state.remoteDescriptionSet = true
        check(CallStateReducer.reduce(state, .remoteIce(callId: "call", candidate: String(repeating: "x", count: 2049), sdpMid: nil, sdpMLineIndex: 0)).1.isEmpty)
        print("PASS A2/private ICE: actual reducer bounds, Unicode bound, numeric aliases, SDP and mDNS policy")
    }
}
