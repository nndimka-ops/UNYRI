import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#elseif os(Windows)
import WinSDK
#endif

/// Peer captions must never infer a public handle from a private sign-in login.
/// Legacy servers expose only display names; their username can be a private login.
enum PeerIdentityPolicy {
    static func label(publicUsername: String?, unyriNumber: String?, legacyUsername: String? = nil,
                      legacyServer: Bool = false) -> String {
        guard !legacyServer else { return "" }
        func handle(_ raw: String?) -> String? {
            guard let raw else { return nil }
            let name = raw.trimmingCharacters(in: .whitespacesAndNewlines).drop(while: { $0 == "@" })
            return name.isEmpty ? nil : "@" + String(name)
        }
        if let publicHandle = handle(publicUsername) { return publicHandle }
        if let number = unyriNumber?.trimmingCharacters(in: .whitespacesAndNewlines), !number.isEmpty {
            return PublicIdentityPolicy.formattedNumber(number)
        }
        return ""
    }

    /// B-051 (owner, 19 Nov: «почему я не вижу персональный номер людей в контактах»): a
    /// Contacts row always names the person's public number, grouped by three, and adds the
    /// public @username when one is set («604 743 993 · @alice»). Legacy servers expose
    /// neither, so the caption stays empty there.
    static func contactCaption(publicUsername: String?, unyriNumber: String?, legacyServer: Bool = false) -> String {
        guard !legacyServer else { return "" }
        let number = unyriNumber?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let handle = label(publicUsername: publicUsername, unyriNumber: nil)
        let parts = [number.isEmpty ? "" : PublicIdentityPolicy.formattedNumber(number), handle].filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }
}

/// Public identifiers never accept private logins or internal UUIDs.
enum PublicIdentityPolicy {
    enum Target: Equatable, Sendable {
        case number(String), username(String)
        var value: String { switch self { case .number(let s), .username(let s): return s } }
        var queryName: String { switch self { case .number: return "number"; case .username: return "username" } }
    }
    struct Link: Equatable, Identifiable, Sendable {
        let origin: String
        let target: Target
        var id: String { origin + "|" + target.queryName + "|" + target.value }
    }
    enum ContactsSearch: Equatable, Sendable {
        case none, directory(String), exact(Target), profileLink(String), invalidIdentity
        var showsServerSection: Bool { self != .none }
    }
    static func boundedContactsInput(_ raw: String) -> String {
        guard raw.utf8.count > 4096 else { return raw }
        var bytes = Array(raw.utf8.prefix(4096))
        // A valid string's byte prefix can end inside at most one four-byte
        // scalar. Remove only that incomplete suffix, preserving all prior text.
        for _ in 0...3 {
            if let value = String(bytes: bytes, encoding: .utf8) { return value }
            bytes.removeLast()
        }
        return ""
    }
    /// Keep public identifiers exact; an invalid @name must never fall back to
    /// the broad directory, and a server-bearing link needs the link presenter.
    static func contactsSearch(_ raw: String) -> ContactsSearch {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return .none }
        let lower = value.lowercased()
        if lower.hasPrefix("unyri:") || lower.hasPrefix("https:") || lower.hasPrefix("http:") {
            return .profileLink(value)
        }
        if let target = input(value) { return .exact(target) }
        if value.hasPrefix("@") { return value.count >= 6 ? .invalidIdentity : .none }
        if value.allSatisfy({ $0.isNumber || $0.isWhitespace }) { return .invalidIdentity }
        return value.count >= 3 ? .directory(value) : .none
    }
    /// Partial public numbers are valid explicit team aliases, but must not
    /// consume an exact-lookup request on every pause while typing nine digits.
    /// A server-bearing link is presented only after an explicit user action.
    static func contactsSearchRunsAutomatically(_ raw: String) -> Bool {
        switch contactsSearch(raw) {
        case .directory(_), .exact(.username(_)): return true
        case .exact(.number(let value)): return value.count == 9
        case .none, .profileLink, .invalidIdentity: return false
        }
    }
    static func number(_ raw: String) -> String? {
        guard (1...9).contains(raw.utf8.count), raw.first != "0",
              raw.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
        return raw
    }
    static func formattedNumber(_ raw: String) -> String {
        guard raw.count == 9, number(raw) != nil else { return raw }
        let chars = Array(raw)
        return stride(from: 0, to: 9, by: 3).map { String(chars[$0..<($0 + 3)]) }.joined(separator: " ")
    }
    /// The server's sign-up denylist (server/src/auth/common_passwords.txt, same normalization:
    /// trimmed, lower-cased), checked while the password is typed so sign-up says it at once;
    /// the server stays the authority (B-048). PublicIdentityContracts keeps it equal to the
    /// server file; Android has the same set in IdentityPolicy.commonPasswords.
    static let commonPasswords: Set<String> = [
        "12345678", "123456789", "1234567890", "123123123", "11111111", "00000000", "87654321",
        "password", "password1", "password123", "password1234", "password123!", "qwertyui", "qwertyuiop",
        "qwerty123", "qwerty12345", "1q2w3e4r", "1qaz2wsx", "1234qwer", "abcd1234", "abc12345", "iloveyou",
        "sunshine", "princess", "football", "baseball", "superman", "trustno1", "letmein1", "welcome1",
        "welcome123", "admin123", "admin1234", "administrator", "passw0rd", "пароль123",
    ]
    static func isCommonPassword(_ password: String) -> Bool {
        commonPasswords.contains(password.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
    static let commonPasswordKey = "This password is too common. Choose a different password."

    /// Guidance only, matching Android; the server decides password acceptance.
    static func passwordStrengthKey(_ password: String) -> String {
        let count: Int = password.unicodeScalars.count
        guard (8...256).contains(count) else { return "8–256 characters. Use a long password that you do not use elsewhere." }
        let categories: [Bool] = [password.rangeOfCharacter(from: .uppercaseLetters) != nil,
            password.rangeOfCharacter(from: .lowercaseLetters) != nil,
            password.rangeOfCharacter(from: .decimalDigits) != nil,
            password.rangeOfCharacter(from: CharacterSet.alphanumerics.inverted) != nil]
        let variety: Int = categories.filter { $0 }.count
        if count >= 20 || (count >= 12 && variety >= 3) { return "Password strength: strong" }
        if count >= 12 { return "Password strength: fair" }
        return "Password strength: weak"
    }

    static func isRecoveryCode(_ raw: String) -> Bool {
        guard raw.utf8.count == 50 else { return false }
        let parts = raw.split(separator: "-", omittingEmptySubsequences: false)
        return parts.count == 9 && parts.first == "R1" && parts.dropFirst().allSatisfy { part in
            part.utf8.count == 5 && part.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) }
        }
    }
    static func username(_ raw: String) -> String? {
        let bytes = Array(raw.utf8)
        func letter(_ b: UInt8) -> Bool { (65...90).contains(b) || (97...122).contains(b) }
        guard (5...32).contains(bytes.count), let first = bytes.first, letter(first),
              bytes.allSatisfy({ letter($0) || (48...57).contains($0) || $0 == 95 }), !raw.contains("__") else { return nil }
        return raw.lowercased()
    }
    /// Only the contact text field permits grouping spaces. URI paths remain strict.
    static func input(_ raw: String) -> Target? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("@") { return username(String(value.dropFirst())).map(Target.username) }
        return number(value.replacingOccurrences(of: " ", with: "")).map(Target.number)
    }
    static func origin(_ raw: String) -> String? {
        guard !raw.isEmpty, raw.utf8.count <= 2048,
              !raw.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }),
              !raw.contains("\\"), !raw.contains("%"), !raw.contains("@") else { return nil }
        let candidate = raw.contains("://") ? raw : "https://" + raw
        guard let parts = URLComponents(string: candidate), let scheme = parts.scheme?.lowercased(),
              ["http", "https"].contains(scheme), parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/",
              let url = parts.url, let encodedHost = url.host, !encodedHost.isEmpty else { return nil }
        // Inspect the literal authority as URL parsers can erase empty ports or
        // normalize ambiguous integer/octal IPv4 into a different host.
        let authority = candidate[candidate.range(of: "://")!.upperBound...].split(separator: "/", omittingEmptySubsequences: false)[0]
        let host: String
        let portText: Substring?
        if authority.hasPrefix("[") {
            guard let end = authority.firstIndex(of: "]") else { return nil }
            let literal = String(authority[authority.index(after: authority.startIndex)..<end])
            var address = in6_addr()
            guard literal.withCString({ inet_pton(AF_INET6, $0, &address) }) == 1 else { return nil }
            var buffer = [CChar](repeating: 0, count: 46)
            guard inet_ntop(AF_INET6, &address, &buffer, 46) != nil else { return nil }
            host = "[" + String(cString: buffer).lowercased() + "]"
            let suffix = authority[authority.index(after: end)...]
            guard suffix.isEmpty || suffix.hasPrefix(":") else { return nil }
            portText = suffix.isEmpty ? nil : suffix.dropFirst()
        } else {
            let segments = authority.split(separator: ":", omittingEmptySubsequences: false)
            guard segments.count <= 2, let literal = segments.first, !literal.isEmpty else { return nil }
            portText = segments.count == 2 ? segments[1] : nil
            let labels = literal.split(separator: ".", omittingEmptySubsequences: false)
            let numericEnd = labels.last.map { Int($0) != nil || $0.lowercased().hasPrefix("0x") } ?? false
            if numericEnd {
                guard labels.count == 4, labels.allSatisfy({ label in
                    !label.isEmpty && label.utf8.allSatisfy { (48...57).contains($0) } &&
                    (label.count == 1 || label.first != "0") && Int(label).map { (0...255).contains($0) } == true
                }) else { return nil }
                host = String(literal)
            } else {
                host = encodedHost.lowercased()
                guard host.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 46 }),
                      host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy({ !$0.isEmpty && $0.first != "-" && $0.last != "-" }) else { return nil }
            }
        }
        var port: Int?
        if let portText {
            guard !portText.isEmpty, portText.utf8.allSatisfy({ (48...57).contains($0) }),
                  let value = Int(portText), (1...65535).contains(value) else { return nil }
            if !((scheme == "http" && value == 80) || (scheme == "https" && value == 443)) { port = value }
        }
        return scheme + "://" + host + (port.map { ":\($0)" } ?? "")
    }
    static func parse(_ raw: String) -> Link? {
        guard raw.utf8.count <= 4096, raw.unicodeScalars.allSatisfy({ $0.value > 32 && $0.value != 127 }),
              !raw.contains("\\"), !raw.contains("#"), let separator = raw.range(of: "://") else { return nil }
        if raw[..<separator.lowerBound].lowercased() == "https" {
            guard let parts = URLComponents(string: raw), parts.query == nil, parts.fragment == nil,
                  parts.user == nil, parts.password == nil,
                  let slash = raw[separator.upperBound...].firstIndex(of: "/"),
                  let server = origin(String(raw[..<slash])) else { return nil }
            // Inspect the literal path; percent escapes and parser-normalized paths
            // cannot change which public identity is requested.
            let path = String(raw[slash...])
            let target: Target
            if path.hasPrefix("/u/n/"), let value = number(String(path.dropFirst(5))) { target = .number(value) }
            else if path.hasPrefix("/u/@"), let value = username(String(path.dropFirst(4))) { target = .username(value) }
            else { return nil }
            return Link(origin: server, target: target)
        }
        guard raw[..<separator.lowerBound].lowercased() == "unyri" else { return nil }
        let remainder = raw[separator.upperBound...]
        guard let slash = remainder.firstIndex(of: "/"), remainder[..<slash].lowercased() == "u" else { return nil }
        let body = remainder[slash...].split(separator: "?", omittingEmptySubsequences: false)
        guard body.count == 2, !body[0].contains("%"), body[0].utf8.allSatisfy({ $0 < 128 }) else { return nil }
        let path = String(body[0]); let target: Target
        if path.hasPrefix("/n/"), let value = number(String(path.dropFirst(3))) { target = .number(value) }
        else if path.hasPrefix("/@"), let value = username(String(path.dropFirst(2))) { target = .username(value) }
        else { return nil }
        let query = String(body[1])
        guard query.hasPrefix("server="), !query.contains("&"), let decoded = decode(String(query.dropFirst(7))),
              let server = origin(decoded) else { return nil }
        return Link(origin: server, target: target)
    }
    private static func decode(_ raw: String) -> String? {
        let bytes = Array(raw.utf8); var decoded: [UInt8] = []; var index = 0
        while index < bytes.count {
            if bytes[index] == 37 {
                guard index + 2 < bytes.count,
                      bytes[(index + 1)...(index + 2)].allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }),
                      let value = UInt8(String(bytes: bytes[(index + 1)...(index + 2)], encoding: .utf8) ?? "", radix: 16) else { return nil }
                decoded.append(value); index += 3
            } else { decoded.append(bytes[index]); index += 1 }
        }
        return String(bytes: decoded, encoding: .utf8)
    }
    static func link(number rawNumber: String, origin rawOrigin: String) -> String? {
        guard let canonicalNumber = Self.number(rawNumber), let serverOrigin = Self.origin(rawOrigin) else { return nil }
        let server = serverOrigin.hasPrefix("https://") ? String(serverOrigin.dropFirst(8)) : serverOrigin
        let unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        guard let escaped = server.addingPercentEncoding(withAllowedCharacters: unreserved) else { return nil }
        return "unyri://u/n/" + canonicalNumber + "?server=" + escaped
    }
}
