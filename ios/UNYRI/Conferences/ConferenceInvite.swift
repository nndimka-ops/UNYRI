import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
#if canImport(CryptoKit)
import CryptoKit
#endif

/// A capability and a media key are separate values. Active state stays in RAM.
/// Explicit chat sharing stores only ConferenceInvitationMetadata inside the
/// existing account-owned Signal content, never in diagnostics or API requests.
struct ConferenceInvite: Equatable, CustomStringConvertible, CustomDebugStringConvertible {
    let server: URL
    let roomID: UUID
    let secret: String
    let mediaKey: String
    var description: String { "ConferenceInvite(<redacted>)" }
    var debugDescription: String { description }

    enum Failure: Error, LocalizedError {
        case invalid, differentServer, unavailable, busy, closed, encryption, admissionLocked, ownerLimit
        var errorDescription: String? {
            switch self {
            case .invalid: return "Invalid conference invitation. Paste the complete link including its fragment."
            case .differentServer: return "This invitation belongs to a different server. Sign in to that server explicitly before joining."
            case .unavailable: return "Conferences are unavailable on this server."
            case .busy: return "End the current call or recording before joining another room."
            case .closed: return "This conference is closed or unavailable."
            case .encryption: return "The encrypted conference could not be established."
            case .admissionLocked: return "The creator closed admission to this room. Try this invitation again after admission reopens."
            case .ownerLimit: return "End one of your rooms before creating another."
            }
        }
    }

    static func origin(_ url: URL) throws -> URL {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/", (1...65535).contains(parts.port ?? 443),
              !host.contains("%") else { throw Failure.invalid }
        parts.scheme = "https"; parts.host = host.lowercased(); parts.path = ""
        if parts.port == 443 { parts.port = nil }
        guard let result = parts.url else { throw Failure.invalid }
        return result
    }

    static func decodeKey(_ value: String) throws -> Data {
        guard value.count == 43, value.utf8.allSatisfy({
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }), let data = Data(base64Encoded: value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + "="),
              data.count == 32, encode(data) == value else { throw Failure.invalid }
        return data
    }

    static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func randomNonce() throws -> String {
        #if canImport(CryptoKit)
        return encode(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        #else
        // Portable contract tests inject a synthetic nonce explicitly. There
        // is no production non-cryptographic random fallback.
        throw Failure.unavailable
        #endif
    }

    static func parse(_ text: String, currentServer: URL) throws -> ConferenceInvite {
        guard text.utf8.count <= 2048 else { throw Failure.invalid }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.utf8.count <= 2048 else { throw Failure.invalid }
        if trimmed.hasPrefix("unyri://c#") || trimmed.hasPrefix("coco://c#") { return try parseCompact(trimmed, currentServer: currentServer) }
        guard let parts = URLComponents(string: trimmed),
              ["unyri", "coco"].contains(parts.scheme?.lowercased() ?? ""), parts.host == "conference", parts.path.isEmpty,
              parts.user == nil, parts.password == nil, parts.port == nil,
              let query = parts.queryItems, query.count == 2,
              Set(query.map(\.name)) == Set(["server", "room"]),
              let serverText = query.first(where: { $0.name == "server" })?.value,
              let url = URL(string: serverText), let idText = query.first(where: { $0.name == "room" })?.value,
              let id = UUID(uuidString: idText), let fragment = parts.fragment,
              let fragmentItems = URLComponents(string: "https://invite.invalid/?" + fragment)?.queryItems,
              fragmentItems.count == 3, Set(fragmentItems.map(\.name)) == Set(["v", "i", "k"]),
              fragmentItems.first(where: { $0.name == "v" })?.value == "1",
              let secret = fragmentItems.first(where: { $0.name == "i" })?.value,
              let key = fragmentItems.first(where: { $0.name == "k" })?.value else { throw Failure.invalid }
        let canonical = try origin(url)
        guard canonical == (try origin(currentServer)) else { throw Failure.differentServer }
        _ = try decodeKey(secret); _ = try decodeKey(key)
        guard secret != key else { throw Failure.invalid }
        return ConferenceInvite(server: canonical, roomID: id, secret: secret, mediaKey: key)
    }

    var legacyLink: URL {
        var parts = URLComponents()
        parts.scheme = "coco"; parts.host = "conference"
        parts.queryItems = [URLQueryItem(name: "server", value: server.absoluteString),
                            URLQueryItem(name: "room", value: roomID.uuidString.lowercased())]
        parts.fragment = "v=1&i=\(secret)&k=\(mediaKey)"
        return parts.url!
    }

    /// Compact fragment-only v2. The full public-origin fingerprint binds the
    /// shorthand to the configured server without a remote lookup/directory.
    /// New public links use the current app scheme. Stored/encrypted v1 payloads
    /// retain the exact legacy writer below for already deployed readers.
    var link: URL {
        var parts = URLComponents(url: wireLink, resolvingAgainstBaseURL: false)!
        parts.scheme = "unyri"
        return parts.url!
    }

    var wireLink: URL {
        var bytes = Data([2])
        let publicOrigin = try? Self.origin(URL(string: AppConfig.publicDefaultOrigin)!)
        if server == publicOrigin {
            bytes.append(0)
            #if canImport(CryptoKit)
            bytes.append(contentsOf: SHA256.hash(data: Data(server.absoluteString.utf8)))
            #else
            return legacyLink
            #endif
        } else {
            let origin = Data(server.absoluteString.utf8)
            guard !origin.isEmpty, origin.count <= 256 else { return legacyLink }
            bytes.append(1); bytes.append(UInt8(origin.count >> 8)); bytes.append(UInt8(origin.count & 255)); bytes.append(origin)
        }
        var uuid = roomID.uuid
        withUnsafeBytes(of: &uuid) { bytes.append(contentsOf: $0) }
        guard let capability = try? Self.decodeKey(secret), let key = try? Self.decodeKey(mediaKey),
              capability.contains(where: { $0 != 0 }), key.contains(where: { $0 != 0 }), capability != key else { return legacyLink }
        bytes.append(capability); bytes.append(key)
        return URL(string: "coco://c#" + Self.encode(bytes))!
    }

    private static func parseCompact(_ text: String, currentServer: URL) throws -> ConferenceInvite {
        let prefix = text.hasPrefix("unyri://c#") ? "unyri://c#" : "coco://c#"
        let encoded = String(text.dropFirst(prefix.count))
        guard !encoded.isEmpty, encoded.utf8.allSatisfy({
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }), let bytes = Data(base64Encoded: encoded.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + String(repeating: "=", count: (4 - encoded.count % 4) % 4)),
              encode(bytes) == encoded, bytes.count >= 2, bytes[0] == 2 else { throw Failure.invalid }
        let canonical: URL
        var offset: Int
        switch bytes[1] {
        case 0:
            guard bytes.count == 114,
                  let configured = URL(string: AppConfig.publicDefaultOrigin) else { throw Failure.invalid }
            canonical = try origin(configured)
            #if canImport(CryptoKit)
            guard Data(bytes[2..<34]) == Data(SHA256.hash(data: Data(canonical.absoluteString.utf8))) else { throw Failure.invalid }
            offset = 34
            #else
            throw Failure.invalid
            #endif
        case 1:
            guard bytes.count >= 4 else { throw Failure.invalid }
            let length = Int(bytes[2]) << 8 | Int(bytes[3])
            guard (1...256).contains(length), bytes.count == 4 + length + 80,
                  let raw = String(data: bytes.subdata(in: 4..<(4 + length)), encoding: .utf8),
                  raw.utf8.allSatisfy({ $0 < 128 }), let url = URL(string: raw) else { throw Failure.invalid }
            canonical = try origin(url)
            guard canonical.absoluteString == raw else { throw Failure.invalid }
            offset = 4 + length
        default: throw Failure.invalid
        }
        guard bytes.count == offset + 80 else { throw Failure.invalid }
        let idBytes = Array(bytes[offset..<(offset + 16)])
        let id = UUID(uuid: (idBytes[0], idBytes[1], idBytes[2], idBytes[3], idBytes[4], idBytes[5], idBytes[6], idBytes[7],
            idBytes[8], idBytes[9], idBytes[10], idBytes[11], idBytes[12], idBytes[13], idBytes[14], idBytes[15]))
        let secret = bytes.subdata(in: (offset + 16)..<(offset + 48))
        let key = bytes.subdata(in: (offset + 48)..<(offset + 80))
        guard secret.contains(where: { $0 != 0 }), key.contains(where: { $0 != 0 }), secret != key else { throw Failure.invalid }
        guard canonical == (try origin(currentServer)) else { throw Failure.differentServer }
        return ConferenceInvite(server: canonical, roomID: id, secret: encode(secret), mediaKey: encode(key))
    }

    #if canImport(CryptoKit)
    static func randomCredentials() -> (secret: String, mediaKey: String, hash: String) {
        // Two independent CSPRNG invocations, never derived from each other.
        let secret = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let hash = SHA256.hash(data: secret).map { String(format: "%02x", $0) }.joined()
        return (encode(secret), encode(key), hash)
    }
    #endif
}

extension ConferenceInvitationMetadata {
    func validated(currentServer: URL) throws -> ConferenceInvite {
        guard hasValidShape, let url = URL(string: server),
              try ConferenceInvite.origin(url).absoluteString == server else { throw ConferenceInvite.Failure.invalid }
        let parsed = try ConferenceInvite.parse(invite, currentServer: currentServer)
        guard parsed.server.absoluteString == server, parsed.roomID.uuidString.lowercased() == roomID else { throw ConferenceInvite.Failure.invalid }
        return parsed
    }
}

/// Arrival order is stable for the duration of a room; speaking never moves
/// tiles. Pin is a separate focus view and pages retain their stable order.
enum ConferenceGridPolicy {
    static func visibleCount(_ selected: Int, total: Int, fitEveryone: Bool, landscape: Bool, accessibility: Bool) -> Int {
        let bounded = min(30, max(1, total))
        if fitEveryone { return bounded }
        return min(bounded, selected == 0 ? pageSize(landscape: landscape, accessibility: accessibility) : min(30, max(1, selected)))
    }
    static func chunks<T>(_ values: [T], count: Int) -> [[T]] {
        let size = min(30, max(1, count))
        return stride(from: 0, to: values.count, by: size).map { Array(values[$0..<min($0 + size, values.count)]) }
    }
    /// Geometry is in the clipped stage, not in global screen coordinates.
    /// Partial cells from two scroll sections never double the decode budget.
    static func visibleIDs(_ frames: [String: CGRect], viewport: CGRect, limit: Int) -> Set<String> {
        var candidates: [(id: String, area: CGFloat)] = []
        candidates.reserveCapacity(frames.count)
        for (id, frame) in frames {
            let overlap: CGRect = frame.intersection(viewport)
            guard !overlap.isNull, overlap.width > 1, overlap.height > 1 else { continue }
            candidates.append((id: id, area: overlap.width * overlap.height))
        }
        candidates.sort { lhs, rhs in
            if lhs.area == rhs.area { return lhs.id < rhs.id }
            return lhs.area > rhs.area
        }
        let count = min(30, max(1, limit))
        return Set(candidates.prefix(count).map { $0.id })
    }
    static func pageSize(landscape: Bool, accessibility: Bool = false) -> Int {
        accessibility ? (landscape ? 3 : 2) : (landscape ? 9 : 6)
    }
    static func pages<T>(_ values: [T], landscape: Bool, accessibility: Bool = false) -> [[T]] {
        let size = pageSize(landscape: landscape, accessibility: accessibility)
        return stride(from: 0, to: values.count, by: size).map { Array(values[$0..<min($0 + size, values.count)]) }
    }
}
