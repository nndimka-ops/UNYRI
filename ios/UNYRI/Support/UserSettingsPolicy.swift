import Foundation

enum PrivacyVisibility: String, Codable, CaseIterable { case everyone, nobody }

struct UserPrivacy: Codable, Equatable {
    var onlineVisibility: PrivacyVisibility = .everyone
    var lastSeenVisibility: PrivacyVisibility = .everyone
    var alwaysAllow: [String] = []
    var neverAllow: [String] = []
    var sendReadReceipts = true
    var revision: Int64 = 0

    func normalized(owner: String) -> UserPrivacy {
        var result = self
        func ids(_ values: [String]) -> [String] {
            Array(Set(values.compactMap { UUID(uuidString: $0)?.uuidString.lowercased() }
                .filter { $0 != owner.lowercased() })).sorted()
        }
        result.alwaysAllow = ids(alwaysAllow)
        result.neverAllow = ids(neverAllow)
        return result
    }
    func permits(_ peer: String, online: Bool, blocked: Bool = false) -> Bool {
        guard !blocked, !neverAllow.contains(peer.lowercased()) else { return false }
        return alwaysAllow.contains(peer.lowercased()) || (online ? onlineVisibility : lastSeenVisibility) == .everyone
    }
}

/// Compare-and-swap wire body deliberately excludes the response revision key.
struct UpdateUserPrivacy: Encodable {
    let onlineVisibility: PrivacyVisibility
    let lastSeenVisibility: PrivacyVisibility
    let alwaysAllow: [String]
    let neverAllow: [String]
    let sendReadReceipts: Bool
    let expectedRevision: Int64
    init(_ value: UserPrivacy) {
        onlineVisibility = value.onlineVisibility; lastSeenVisibility = value.lastSeenVisibility
        alwaysAllow = value.alwaysAllow; neverAllow = value.neverAllow
        sendReadReceipts = value.sendReadReceipts; expectedRevision = value.revision
    }
}

enum AutomaticMediaKind: String, Codable, CaseIterable {
    case photo, video, voice, file
    static func classify(mediaType: String, mime: String) -> Self {
        if mediaType == "voice" { return .voice }
        if mime.hasPrefix("video/") { return .video }
        if mediaType == "image" { return .photo }
        return .file
    }
    var title: String {
        switch self { case .photo: return "Photos"; case .video: return "Videos"; case .voice: return "Voice messages"; case .file: return "Files" }
    }
}
struct AutomaticMediaRule: Codable, Equatable {
    var enabled: Bool
    var maxMiB: Int
}
struct AutomaticMediaSettings: Codable, Equatable {
    var wifi: [String: AutomaticMediaRule]
    var cellular: [String: AutomaticMediaRule]
    /// Mapping of the old single Wi-Fi / mobile data switches (all kinds alike).
    static func defaults(wifiEnabled: Bool, cellularEnabled: Bool) -> Self {
        Self(wifi: Dictionary(uniqueKeysWithValues: AutomaticMediaKind.allCases.map { ($0.rawValue, AutomaticMediaRule(enabled: wifiEnabled, maxMiB: 50)) }),
             cellular: Dictionary(uniqueKeysWithValues: AutomaticMediaKind.allCases.map { ($0.rawValue, AutomaticMediaRule(enabled: cellularEnabled, maxMiB: 5)) }))
    }
    /// Fresh-install defaults (Telegram-like): photos and voice messages load on
    /// any network, so one tap opens or plays them; files load on Wi-Fi only and
    /// videos on tap. Limits are values the settings picker offers (1/5/10/25/50/100 MB).
    static let recommended = AutomaticMediaSettings(
        wifi: [AutomaticMediaKind.photo.rawValue: AutomaticMediaRule(enabled: true, maxMiB: 50),
               AutomaticMediaKind.voice.rawValue: AutomaticMediaRule(enabled: true, maxMiB: 50),
               // Videos stay manual until an automatic download survives scrolling past
               // the bubble (it restarts from zero today; audit 10 Nov).
               AutomaticMediaKind.video.rawValue: AutomaticMediaRule(enabled: false, maxMiB: 25),
               AutomaticMediaKind.file.rawValue: AutomaticMediaRule(enabled: true, maxMiB: 25)],
        cellular: [AutomaticMediaKind.photo.rawValue: AutomaticMediaRule(enabled: true, maxMiB: 5),
                   AutomaticMediaKind.voice.rawValue: AutomaticMediaRule(enabled: true, maxMiB: 5),
                   AutomaticMediaKind.video.rawValue: AutomaticMediaRule(enabled: false, maxMiB: 5),
                   AutomaticMediaKind.file.rawValue: AutomaticMediaRule(enabled: false, maxMiB: 5)])
    /// Saved per-kind rules win. The old global switches are mapped only when one
    /// of them was actually stored; otherwise the recommended defaults apply.
    static func resolved(saved: Self?, legacyWifi: Bool?, legacyCellular: Bool?) -> Self {
        if let saved { return saved }
        guard legacyWifi != nil || legacyCellular != nil else { return recommended }
        return defaults(wifiEnabled: legacyWifi ?? false, cellularEnabled: legacyCellular ?? false)
    }
    func allows(kind: AutomaticMediaKind, wifi: Bool, connected: Bool, bytes: Int64) -> Bool {
        guard connected, bytes > 0, let rule = (wifi ? self.wifi : cellular)[kind.rawValue], rule.enabled,
              (1...100).contains(rule.maxMiB) else { return false }
        return bytes <= Int64(rule.maxMiB) * 1024 * 1024
    }
}

struct MediaCachePolicy: Codable, Equatable {
    var maximumGiB = 0 // 0 means unlimited; existing installations never silently delete media.
    var retentionDays = 0 // 0 means forever.
    var maximumBytes: Int64? { maximumGiB > 0 ? Int64(maximumGiB) * 1024 * 1024 * 1024 : nil }
}

struct AppLockPolicy: Equatable {
    var enabled = false
    var timeout: TimeInterval = 0
    private(set) var locked = true
    private(set) var backgroundUptime: TimeInterval?
    mutating func background(at uptime: TimeInterval) { backgroundUptime = uptime; if enabled && timeout == 0 { locked = true } }
    mutating func activate(at uptime: TimeInterval) {
        if enabled, let backgroundUptime, uptime - backgroundUptime >= timeout { locked = true }
        backgroundUptime = nil
    }
    mutating func unlock() { locked = false }
    mutating func invalidate() { locked = true; backgroundUptime = nil }
}

/// Reading requires content the current account can actually see. Transport
/// delivery and already queued durable read cursors have separate lifetimes.
enum UnyriContentVisibilityPolicy {
    static func allowsRead(sessionCurrent: Bool, foreground: Bool, shielded: Bool) -> Bool {
        sessionCurrent && foreground && !shielded
    }
}
