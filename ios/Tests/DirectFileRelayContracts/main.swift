import Foundation

@main struct DirectFileRelayContracts {
    static func main() {
        precondition(CallPeerToPeer.resolved(stored: nil) == .contacts)
        for setting in CallPeerToPeer.allCases {
            precondition(CallPeerToPeer.resolved(stored: setting.rawValue) == setting,
                "Explicit earlier choices must survive the new default")
            for contact in [true, false] {
                let relay = CallRelayPolicy.relayOnly(setting, peerIsContact: contact)
                precondition(DirectFilePeerPolicy.selectedRouteAllowed(local: "relay", remote: "relay", relayOnly: relay) == relay)
                precondition(DirectFilePeerPolicy.selectedRouteAllowed(local: "host", remote: "host", relayOnly: relay) == !relay)
                precondition(DirectFilePeerPolicy.selectedRouteAllowed(local: "host", remote: "relay", relayOnly: relay) == !relay,
                    "Direct-capable peers accept the protected side's relay without allocating their own")
                precondition(!DirectFilePeerPolicy.selectedRouteAllowed(local: "unknown", remote: "relay", relayOnly: relay))
            }
        }
        let host = "candidate:1 1 udp 2122260223 192.168.1.2 4000 typ host"
        let relay = "candidate:2 1 udp 1677734911 203.0.113.10 5000 typ relay raddr 192.168.1.2 rport 4000 ufrag ABCD"
        precondition(DirectFilePeerPolicy.transportCandidate(host))
        precondition(DirectFilePeerPolicy.transportCandidate(relay))
        precondition(!DirectFilePeerPolicy.directCandidate(relay), "Direct-only callers keep their stricter validator")
        precondition(!DirectFilePeerPolicy.transportCandidate(host, relayOnly: true))
        precondition(DirectFilePeerPolicy.publishedCandidate(host, relayOnly: true) == nil)
        precondition(DirectFilePeerPolicy.publishedCandidate(relay, relayOnly: false) == nil)
        let safe = DirectFilePeerPolicy.publishedCandidate(relay, relayOnly: true)!
        precondition(!safe.contains("192.168.1.2") && !safe.contains("raddr") && !safe.contains("rport"))
        precondition(DirectFilePeerPolicy.transportCandidate(safe, relayOnly: true))
        let fingerprint = Array(repeating: "AB", count: 32).joined(separator: ":")
        let sdp = "v=0\r\no=- 1 1 IN IP4 192.168.1.2\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 192.168.1.2\r\na=ice-ufrag:ABCD\r\na=ice-pwd:secret123\r\na=fingerprint:sha-256 \(fingerprint)\r\na=sctp-port:5000\r\na=\(host)\r\na=\(relay)\r\n"
        precondition(DirectFilePeerPolicy.dataOnlyDescription(sdp), "Authenticated file wire SDP supports both routes")
        let protected = DirectFilePeerPolicy.publishedDescription(sdp, relayOnly: true)
        precondition(!protected.contains("192.168.1.2") && !protected.contains("typ host"))
        precondition(protected.contains("typ relay") && protected.contains("c=IN IP4 0.0.0.0"))
        precondition(DirectFilePeerPolicy.dataOnlyDescription(protected))
        precondition(DirectFilePeerPolicy.turnURLs(["stun:example.org", "turn:example.org", "turns:example.org?transport=tcp", "https://bad", "turn:bad host"]) == ["turn:example.org", "turns:example.org?transport=tcp"])
        let mixedServers = ["stun:example.org", "stuns:secure.example.org", "turn:example.org", "turns:example.org?transport=tcp", "https://bad"]
        precondition(DirectFilePeerPolicy.iceServerURLs(mixedServers, relayOnly: false) == ["stun:example.org", "stuns:secure.example.org"])
        precondition(DirectFilePeerPolicy.iceServerURLs(mixedServers, relayOnly: true) == ["turn:example.org", "turns:example.org?transport=tcp"])
        let configured = [DirectFileIceConfiguration.Server(urls: mixedServers, username: "turn-user", credential: "turn-password")]
        let directConfiguration = try! DirectFileIceConfiguration(servers: configured, relayOnly: false)
        precondition(!directConfiguration.relayOnly && directConfiguration.servers.count == 1)
        precondition(directConfiguration.servers[0].urls == ["stun:example.org", "stuns:secure.example.org"])
        precondition(directConfiguration.servers[0].username == nil && directConfiguration.servers[0].credential == nil,
            "Direct-capable native peers receive no relay credentials")
        let relayConfiguration = try! DirectFileIceConfiguration(servers: configured, relayOnly: true)
        precondition(relayConfiguration.relayOnly && relayConfiguration.servers.count == 1)
        precondition(relayConfiguration.servers[0].urls == ["turn:example.org", "turns:example.org?transport=tcp"])
        precondition(relayConfiguration.servers[0].username == "turn-user" && relayConfiguration.servers[0].credential == "turn-password")
        let stunOnly = [DirectFileIceConfiguration.Server(urls: ["stun:example.org"], username: nil, credential: nil)]
        precondition((try? DirectFileIceConfiguration(servers: stunOnly, relayOnly: false)) != nil)
        do {
            _ = try DirectFileIceConfiguration(servers: stunOnly, relayOnly: true)
            preconditionFailure("Protected native peer must fail before engine setup without TURN")
        } catch DirectFileIceConfiguration.Failure.relayUnavailable { }
        catch { preconditionFailure("Unexpected ICE configuration failure") }
        for required in [false, true] {
            precondition(CallRelayFallbackPolicy.canInitialize(relayOnly: required, urls: ["stun:example.org"]) == !required)
            precondition(CallRelayFallbackPolicy.canInitialize(relayOnly: required, urls: ["turn:example.org"]))
            precondition(CallRelayFallbackPolicy.canInitialize(relayOnly: required, urls: []) == !required)
        }
        for reason in ["relay_unavailable", "peer_relay_unavailable"] {
            precondition(DirectFileRecoveryPolicy.hardStop(reason), "Relay failures must stop automatic reconnect hints and native admission")
        }
        for reason in DirectFileRecoveryPolicy.hardReasons {
            precondition(!DirectFileRecoveryPolicy.byteEligible(manualEligible: true, reason: reason),
                "A hard failure must block all automatic RTC/byte admission")
        }
        precondition(DirectFileRecoveryPolicy.byteEligible(manualEligible: true, reason: nil))
        precondition(DirectFileRecoveryPolicy.byteEligible(manualEligible: true, reason: "connection_lost"))
        precondition(!DirectFileRecoveryPolicy.byteEligible(manualEligible: false, reason: nil),
            "Retrying a network failure cannot undo a manual hold")
        precondition(DirectFileRecoveryPolicy.receivedPauseReason("relay_unavailable") == "peer_relay_unavailable")
        precondition(DirectFileRecoveryPolicy.pauseReasonForPeer("relay_unavailable") == "peer_relay_unavailable")
        precondition(DirectFileRecoveryPolicy.pauseReasonForPeer("peer_relay_unavailable") == "peer_relay_unavailable")
        precondition(DirectFileRecoveryPolicy.receivedPauseReason("peer_relay_unavailable") == "peer_relay_unavailable")
        precondition(DirectFileRecoveryPolicy.receivedPauseReason("connection_lost") == "connection_lost")
        precondition(!DirectFileRecoveryPolicy.hardStop("connection_lost") && !DirectFileRecoveryPolicy.hardStop(nil))
        precondition(!DirectFilePeerPolicy.dataOnlyDescription(sdp.replacingOccurrences(of: "typ relay", with: "typ unknown")))
        print("PASS: direct-file relay privacy contracts")
    }
}
