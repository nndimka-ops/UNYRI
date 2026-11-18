import Foundation

/// Pure SH-3 rules shared with the offline Swift contract runner.
enum SelfHostingPolicy {
    struct Invite: Equatable, CustomStringConvertible {
        let origin: String
        let code: String
        var description: String { "ServerInvite(redacted)" }
    }

    static func origin(_ raw: String, allowLocalHTTP: Bool = false) -> String? {
        guard raw.contains("://"), let value = PublicIdentityPolicy.origin(raw),
              let parts = URLComponents(string: value) else { return nil }
        if parts.scheme == "https" { return value }
        guard allowLocalHTTP, parts.scheme == "http", let host = parts.host else { return nil }
        let octets: [Int] = host.split(separator: ".").compactMap { Int($0) }
        let privateIP: Bool = octets.count == 4 && (octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && (16...31).contains(octets[1])))
        return host == "localhost" || host == "::1" || host == "[::1]" || host.hasSuffix(".local") || privateIP ? value : nil
    }

    static func parseInvite(_ raw: String, allowLocalHTTP: Bool = false) -> Invite? {
        guard raw.utf8.count <= 4096, !raw.contains("\\"),
              raw.unicodeScalars.allSatisfy({ $0.value > 32 && $0.value != 127 }),
              let parts = URLComponents(string: raw), parts.query == nil,
              parts.user == nil, parts.password == nil, parts.path.isEmpty || parts.path == "/",
              let fragment = parts.percentEncodedFragment, !fragment.contains("+") else { return nil }
        let pairs = fragment.split(separator: "&", omittingEmptySubsequences: false)
        guard pairs.count == 2 else { return nil }
        var values: [String: String] = [:]
        for pair in pairs {
            let fields = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard fields.count == 2, let key = String(fields[0]).removingPercentEncoding,
                  let value = String(fields[1]).removingPercentEncoding, values[key] == nil else { return nil }
            values[key] = value
        }
        guard Set(values.keys) == Set(["server", "invite_code"]), let source = values["server"],
              let server = origin(source, allowLocalHTTP: allowLocalHTTP), let code = values["invite_code"],
              (1...256).contains(code.utf8.count), code.utf8.allSatisfy({ (33...126).contains($0) }),
              !code.contains(where: { "&#?/%".contains($0) }) else { return nil }
        if parts.scheme?.lowercased() == "unyri" {
            guard parts.host == "server", parts.port == nil else { return nil }
        } else {
            // An HTTPS carrier must name the same server as its fragment.
            var carrier = parts; carrier.fragment = nil
            guard let base = carrier.string, origin(base, allowLocalHTTP: allowLocalHTTP) == server else { return nil }
        }
        return Invite(origin: server, code: code)
    }

    static func inviteLink(origin: String, code: String) -> String? {
        guard let server = Self.origin(origin) else { return nil }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        guard let escapedOrigin = server.addingPercentEncoding(withAllowedCharacters: allowed),
              let escapedCode = code.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        let link = server + "/#server=" + escapedOrigin + "&invite_code=" + escapedCode
        return parseInvite(link) == nil ? nil : link
    }

    static func inviteVisible(_ policy: RegistrationPolicy?) -> Bool { policy?.inviteRequired == true }
    static func staleRegistration(_ code: String) -> Bool {
        ["invite_required", "invite_invalid", "registration_closed", "conflict"].contains(code)
    }
    static func refusalKey(_ code: String) -> String? {
        switch code {
        case "invite_required": return "An invitation code is required."
        case "invite_invalid": return "The invitation code is invalid or no longer available."
        case "conflict", "registration_closed": return "Registration is temporarily closed by the server operator."
        case "setup_invalid": return "The owner code is incorrect. Check all 16 characters."
        case "rate_limited": return "Too many attempts. Wait before trying again."
        case "setup_not_available": return "Owner setup is no longer available on this server."
        default: return nil
        }
    }

    static func ownerInput(_ raw: String) -> String {
        let plain = raw.prefix(64).filter { $0 != "-" && !$0.isWhitespace }.uppercased()
        return String(plain.prefix(16))
    }
    static func ownerCode(_ raw: String) -> String? {
        guard raw.utf8.count <= 64, raw.unicodeScalars.allSatisfy({ $0.value < 128 }) else { return nil }
        let value = ownerInput(raw)
        let alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        let unbounded = raw.filter { $0 != "-" && !$0.isWhitespace }.uppercased()
        return unbounded.count == 16 && value.allSatisfy({ alphabet.contains($0) }) ? value : nil
    }
    static func ownerDisplay(_ plain: String) -> String {
        let chars = Array(plain.prefix(16))
        return stride(from: 0, to: chars.count, by: 4).map { String(chars[$0..<min($0 + 4, chars.count)]) }.joined(separator: "-")
    }
    static func displayOffset(_ offset: Int, count: Int) -> Int {
        let bounded = min(max(0, offset), count)
        return bounded + (bounded > 0 ? (bounded - 1) / 4 : 0)
    }
    static func plainOffset(_ offset: Int, count: Int) -> Int { min(max(0, offset - offset / 5), count) }
}

/// Bounded memory-only cache; expiry is checked on demand, never with a timer.
@MainActor
final class RegistrationPolicyCache {
    static let shared = RegistrationPolicyCache()
    private var entries: [String: (Date, RegistrationPolicy)] = [:]
    func cached(_ origin: String, now: Date = Date()) -> RegistrationPolicy? {
        guard let entry = entries[origin], (0..<60).contains(now.timeIntervalSince(entry.0)) else { return nil }
        return entry.1
    }
    func save(_ policy: RegistrationPolicy, origin: String) {
        if entries.count >= 16, entries[origin] == nil, let oldest = entries.min(by: { $0.value.0 < $1.value.0 })?.key {
            entries.removeValue(forKey: oldest)
        }
        entries[origin] = (Date(), policy)
    }
    func invalidate(_ origin: String) { entries.removeValue(forKey: origin) }
}
