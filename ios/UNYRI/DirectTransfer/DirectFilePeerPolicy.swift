import Foundation

/// This connection is data-only and intentionally has no relationship to the
/// call engine, AVAudioSession, camera, microphone or CallKit.
enum DirectFilePeerPolicy {
    static let label = "coco-file-v1"
    static let maxFrameBytes = 32 * 1024
    static let highWaterBytes: UInt64 = 512 * 1024
    static let lowWaterBytes: UInt64 = 128 * 1024

    static func directCandidate(_ value: String) -> Bool {
        candidate(value, relayOnly: false, allowRelay: false)
    }

    /// Wire validation permits relay candidates; each native peer separately
    /// enforces its account's transport policy before gathering any candidates.
    static func transportCandidate(_ value: String, relayOnly: Bool = false) -> Bool {
        candidate(value, relayOnly: relayOnly, allowRelay: true)
    }

    private static func candidate(_ value: String, relayOnly: Bool, allowRelay: Bool) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 4096,
              !value.contains("\r"), !value.contains("\n"),
              value.hasPrefix("candidate:") || value.hasPrefix("a=candidate:") else { return false }
        let fields = value.split(whereSeparator: { $0 == " " || $0 == "\t" })
        guard fields.count >= 8, (fields.count - 8).isMultiple(of: 2), fields[6] == "typ",
              fields[1] == "1", ["udp", "tcp"].contains(fields[2].lowercased()),
              UInt32(fields[3]) != nil, let port = UInt16(fields[5]), port > 0,
              (relayOnly ? ["relay"] : allowRelay ? ["host", "srflx", "prflx", "relay"] : ["host", "srflx", "prflx"]).contains(String(fields[7])),
              fields[4].range(of: "^[A-Za-z0-9:.%_\\-]+$", options: .regularExpression) != nil else { return false }
        let foundation = fields[0].replacingOccurrences(of: "a=", with: "").dropFirst("candidate:".count)
        guard !foundation.isEmpty, foundation.count <= 64,
              foundation.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "/") }) else { return false }
        var seen = Set<String>()
        for index in stride(from: 8, to: fields.count, by: 2) {
            let key = String(fields[index]), item = String(fields[index + 1])
            guard seen.insert(key).inserted,
                  ["raddr", "rport", "tcptype", "generation", "ufrag", "network-id", "network-cost"].contains(key),
                  item.range(of: "^[A-Za-z0-9:.%_+\\-/]+$", options: .regularExpression) != nil else { return false }
            if key == "rport", UInt16(item) == nil { return false }
            if key == "tcptype", !["active", "passive", "so"].contains(item) { return false }
        }
        return true
    }

    static func dataOnlyDescription(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 32 * 1024,
              value.unicodeScalars.allSatisfy({ $0.value == 9 || $0.value == 10 || $0.value == 13 || (32...126).contains($0.value) }) else { return false }
        let lines = value.components(separatedBy: .newlines).filter { !$0.isEmpty }
        let media = lines.filter { $0.hasPrefix("m=") }
        let fingerprints = lines.filter { $0.hasPrefix("a=fingerprint:") }
        guard media.count == 1,
              media[0].range(of: "^m=application [0-9]{1,5} (UDP/DTLS/SCTP|TCP/DTLS/SCTP) webrtc-datachannel$", options: .regularExpression) != nil,
              !fingerprints.isEmpty, Set(fingerprints.map { $0.lowercased() }).count == 1,
              fingerprints.allSatisfy({ $0.range(of: "^a=fingerprint:sha-256 ([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}$", options: .regularExpression) != nil }),
              lines.contains(where: { $0.hasPrefix("a=ice-ufrag:") && $0.count > 12 }),
              lines.contains(where: { $0.hasPrefix("a=ice-pwd:") && $0.count > 10 }),
              lines.contains(where: { $0.range(of: "^a=sctp-port:[0-9]{1,5}$", options: .regularExpression) != nil }) else { return false }
        return lines.filter { $0.hasPrefix("a=candidate:") }.allSatisfy { transportCandidate($0) }
    }

    static func stunURLs(_ urls: [String]) -> [String] {
        Array(Set(urls.filter {
            $0.utf8.count <= 512 && !$0.contains(where: { $0.isWhitespace }) &&
                ($0.lowercased().hasPrefix("stun:") || $0.lowercased().hasPrefix("stuns:"))
        })).sorted()
    }

    static func turnURLs(_ urls: [String]) -> [String] {
        Array(Set(urls.filter {
            $0.utf8.count <= 512 && !$0.contains(where: { $0.isWhitespace }) &&
                ($0.lowercased().hasPrefix("turn:") || $0.lowercased().hasPrefix("turns:"))
        })).sorted()
    }

    /// Only the protected side may allocate a relay. Direct-capable senders
    /// stay STUN-only, while still accepting a protected peer's remote relay.
    static func iceServerURLs(_ urls: [String], relayOnly: Bool) -> [String] {
        relayOnly ? turnURLs(urls) : stunURLs(urls)
    }

    static func selectedRouteAllowed(local: String, remote: String, relayOnly: Bool) -> Bool {
        let supported = ["host", "srflx", "prflx", "relay"]
        return (relayOnly ? local == "relay" : ["host", "srflx", "prflx"].contains(local)) && supported.contains(remote)
    }

    /// TURN candidates can carry the device's related address. Never publish it
    /// (or SDP default addresses) when this account requires IP protection.
    static func publishedCandidate(_ value: String, relayOnly: Bool) -> String? {
        guard (relayOnly ? transportCandidate(value, relayOnly: true) : directCandidate(value)) else { return nil }
        guard relayOnly else { return value }
        let fields = value.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
        var safe = Array(fields.prefix(8))
        for index in stride(from: 8, to: fields.count, by: 2) {
            if fields[index] == "raddr" || fields[index] == "rport" { continue }
            safe.append(contentsOf: [fields[index], fields[index + 1]])
        }
        return safe.joined(separator: " ")
    }

    static func publishedDescription(_ value: String, relayOnly: Bool) -> String {
        guard relayOnly else { return value }
        return value.components(separatedBy: .newlines).compactMap { line -> String? in
            if line.isEmpty { return nil }
            if line.hasPrefix("a=candidate:") { return publishedCandidate(line, relayOnly: true) }
            if line.hasPrefix("a=remote-candidates:") || line.hasPrefix("a=rtcp:") { return nil }
            if line.hasPrefix("c=IN ") { return "c=IN IP4 0.0.0.0" }
            if line.hasPrefix("o=") {
                var fields = line.split(separator: " ").map(String.init)
                if fields.count == 6 { fields[4] = "IP4"; fields[5] = "0.0.0.0"; return fields.joined(separator: " ") }
            }
            return line
        }.joined(separator: "\r\n") + "\r\n"
    }
}

/// The complete native ICE input, computed before constructing a factory.
/// Native code consumes these URLs, credentials and transport mode unchanged.
struct DirectFileIceConfiguration: Equatable {
    struct Server: Equatable {
        let urls: [String]
        let username: String?
        let credential: String?
    }
    enum Failure: Error { case relayUnavailable }

    let servers: [Server]
    let relayOnly: Bool

    init(servers: [Server], relayOnly: Bool) throws {
        let resolved = servers.compactMap { server -> Server? in
            let urls = DirectFilePeerPolicy.iceServerURLs(server.urls, relayOnly: relayOnly)
            guard !urls.isEmpty else { return nil }
            return Server(urls: urls, username: relayOnly ? server.username : nil,
                credential: relayOnly ? server.credential : nil)
        }
        guard !relayOnly || !resolved.isEmpty else { throw Failure.relayUnavailable }
        self.servers = resolved
        self.relayOnly = relayOnly
    }
}

/// One stop list for RTC admission, automatic recovery and optional reconnect
/// hints. An explicit retry can reconsider local network configuration.
enum DirectFileRecoveryPolicy {
    static let hardReasons: Set<String> = ["prefix_mismatch", "source_missing", "source_changed", "disk_full", "storage_error",
        "integrity_failed", "integrity_mismatch", "verification_failed", "security_error", "permission_denied", "unsupported",
        "claimed_elsewhere", "transfer_unavailable", "invalid_control", "access_unavailable", "expired",
        "relay_unavailable", "peer_relay_unavailable"]

    static func hardStop(_ reason: String?) -> Bool { reason.map { hardReasons.contains($0) } ?? false }
    static func byteEligible(manualEligible: Bool, reason: String?) -> Bool {
        manualEligible && !hardStop(reason)
    }
    static func receivedPauseReason(_ reason: String?) -> String? {
        reason == "relay_unavailable" ? "peer_relay_unavailable" : reason
    }
    static func pauseReasonForPeer(_ reason: String?) -> String? {
        receivedPauseReason(reason)
    }
}

