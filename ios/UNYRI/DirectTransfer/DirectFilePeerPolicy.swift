import Foundation

/// This connection is data-only and intentionally has no relationship to the
/// call engine, AVAudioSession, camera, microphone or CallKit.
enum DirectFilePeerPolicy {
    static let label = "coco-file-v1"
    static let maxFrameBytes = 32 * 1024
    static let highWaterBytes: UInt64 = 512 * 1024
    static let lowWaterBytes: UInt64 = 128 * 1024

    static func directCandidate(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 4096,
              !value.contains("\r"), !value.contains("\n"),
              value.hasPrefix("candidate:") || value.hasPrefix("a=candidate:") else { return false }
        let fields = value.split(whereSeparator: { $0 == " " || $0 == "\t" })
        guard fields.count >= 8, (fields.count - 8).isMultiple(of: 2), fields[6] == "typ",
              fields[1] == "1", ["udp", "tcp"].contains(fields[2].lowercased()),
              UInt32(fields[3]) != nil, let port = UInt16(fields[5]), port > 0,
              ["host", "srflx", "prflx"].contains(String(fields[7])),
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
        return lines.filter { $0.hasPrefix("a=candidate:") }.allSatisfy(directCandidate)
    }

    static func stunURLs(_ urls: [String]) -> [String] {
        Array(Set(urls.filter {
            $0.utf8.count <= 512 && !$0.contains(where: { $0.isWhitespace }) &&
                ($0.lowercased().hasPrefix("stun:") || $0.lowercased().hasPrefix("stuns:"))
        })).sorted()
    }
}

