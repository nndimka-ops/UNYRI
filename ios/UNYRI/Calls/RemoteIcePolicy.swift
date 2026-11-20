import Foundation

/// Numeric parsing only. mDNS .local names retain the existing native behavior.
enum RemoteIcePolicy {
    static func allows(_ candidate: String, relayOnly: Bool) -> Bool {
        guard candidate.utf8.count <= 2048, !candidate.contains(where: { $0.isNewline }) else { return false }
        var value = candidate.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("a=") { value = String(value.dropFirst(2)) }
        let fields = value.split(whereSeparator: { $0.isWhitespace })
        guard fields.count >= 8, fields[0].hasPrefix("candidate:"), fields[6] == "typ" else { return false }
        return allowsAddress(String(fields[4]), relayOnly: relayOnly)
    }
    static func allowsAddress(_ address: String, relayOnly: Bool) -> Bool {
        let value = address.lowercased()
        if value.hasSuffix(".local"), value.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 46 }) { return true }
        func v4(_ bytes: [UInt8]) -> Bool {
            let a = bytes[0], b = bytes[1]
            guard a != 0, a != 127, !(a == 169 && b == 254), a < 224 else { return false }
            return !relayOnly || !(a == 10 || a == 172 && (16...31).contains(b) || a == 192 && b == 168 || a == 100 && (64...127).contains(b))
        }
        if !value.contains(":") { return ipv4(value).map(v4) ?? false }
        guard let bytes = ipv6(value) else { return false }
        if bytes.allSatisfy({ $0 == 0 }) || (bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes.last == 1) { return false }
        if bytes[0] == 255 || (bytes[0] == 254 && bytes[1] & 0xc0 == 0x80) { return false }
        let mapped = bytes.prefix(10).allSatisfy({ $0 == 0 }) && bytes[10] == 255 && bytes[11] == 255
        let compatible = bytes.prefix(12).allSatisfy({ $0 == 0 })
        let nat64 = Array(bytes.prefix(12)) == [0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0]
        if mapped || compatible || nat64 { return v4(Array(bytes.suffix(4))) }
        return !relayOnly || !(bytes[0] & 0xfe == 0xfc || bytes[0] == 254 && bytes[1] & 0xc0 == 0xc0)
    }
    private static func ipv4(_ value: String) -> [UInt8]? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var result = [UInt8]()
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.utf8.allSatisfy({ (48...57).contains($0) }), let byte = UInt8(part) else { return nil }
            result.append(byte)
        }
        return result
    }
    private static func ipv6(_ raw: String) -> [UInt8]? {
        var value = raw
        if value.hasPrefix("["), value.hasSuffix("]") { value = String(value.dropFirst().dropLast()) }
        if value.contains(".") {
            guard let colon = value.lastIndex(of: ":"), let tail = ipv4(String(value[value.index(after: colon)...])) else { return nil }
            value = String(value[...colon]) + String(UInt16(tail[0]) * 256 + UInt16(tail[1]), radix: 16) + ":" + String(UInt16(tail[2]) * 256 + UInt16(tail[3]), radix: 16)
        }
        let sides = value.components(separatedBy: "::")
        guard sides.count <= 2 else { return nil }
        func words(_ side: String) -> [UInt16]? {
            if side.isEmpty { return [] }
            let parts = side.split(separator: ":", omittingEmptySubsequences: false)
            var result = [UInt16]()
            for part in parts {
                guard !part.isEmpty, part.count <= 4, part.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }), let word = UInt16(part, radix: 16) else { return nil }
                result.append(word)
            }
            return result
        }
        guard let left = words(sides[0]), let right = words(sides.count == 2 ? sides[1] : "") else { return nil }
        let count = left.count + right.count
        guard sides.count == 2 ? count < 8 : count == 8 else { return nil }
        return (left + Array(repeating: UInt16(0), count: 8 - count) + right).flatMap { [UInt8($0 >> 8), UInt8($0 & 255)] }
    }
    static func filterSDP(_ sdp: String, relayOnly: Bool) -> String {
        sdp.components(separatedBy: .newlines).filter { line in
            !line.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("a=candidate:") || allows(line, relayOnly: relayOnly)
        }.filter { !$0.isEmpty }.joined(separator: "\r\n") + "\r\n"
    }
}
