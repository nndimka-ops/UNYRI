import Foundation

/// Value-only inputs; produced from an authoritative presence snapshot, never directory online hints.
struct ContactListPerson: Equatable, Sendable {
    let id: String
    let name: String
    let username: String
    let online: Bool
}

struct ContactListSection: Equatable, Identifiable, Sendable {
    /// "online" is localized by the view; letters and # are literal.
    let id: String
    let people: [ContactListPerson]
}

enum ContactsPresentation {
    static func personID(_ id: String) -> String { "person-" + id }
    static func sectionID(_ id: String) -> String { "section-" + id }

    static func query(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("@") ? String(trimmed.dropFirst()) : trimmed
    }

    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "ru_RU"))
    }

    static func matches(_ person: ContactListPerson, query raw: String) -> Bool {
        let term = fold(query(raw))
        return term.isEmpty || fold(person.name).contains(term) || fold(person.username).contains(term)
    }

    static func letter(_ name: String) -> String {
        let first = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1)).uppercased()
        if cyrillic.contains(first), !first.isEmpty { return first }
        let latin = fold(first).uppercased()
        return latin.count == 1 && "ABCDEFGHIJKLMNOPQRSTUVWXYZ".contains(latin) ? latin : "#"
    }

    private static let cyrillic = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    private static func rank(_ section: String) -> Int {
        if section == "online" { return -1 }
        if let index = cyrillic.firstIndex(where: { String($0) == section }) {
            return cyrillic.distance(from: cyrillic.startIndex, to: index)
        }
        if let scalar = section.unicodeScalars.first, section != "#" { return 100 + Int(scalar.value) }
        return 1000
    }

    static func sections(_ people: [ContactListPerson], query: String) -> [ContactListSection] {
        var seen = Set<String>()
        let matching = people.filter { seen.insert($0.id).inserted && matches($0, query: query) }
        let grouped = Dictionary(grouping: matching) { $0.online ? "online" : letter($0.name) }
        return grouped.keys.sorted { rank($0) < rank($1) }.map { key in
            ContactListSection(id: key, people: grouped[key, default: []].sorted { a, b in
                let firstRank = rank(letter(a.name)), secondRank = rank(letter(b.name))
                if firstRank != secondRank { return firstRank < secondRank }
                let order = a.name.compare(b.name, options: [.caseInsensitive, .diacriticInsensitive, .numeric],
                                           locale: Locale(identifier: "ru_RU"))
                if order != .orderedSame { return order == .orderedAscending }
                let handle = fold(a.username).compare(fold(b.username))
                return handle == .orderedSame ? a.id < b.id : handle == .orderedAscending
            })
        }
    }

    /// Also removes overlap between offset pages. Preserve server ranking.
    static func serverIDs(_ ids: [String], excluding contacts: Set<String>) -> [String] {
        var seen = contacts
        return ids.filter { seen.insert($0).inserted }
    }
}

/// Device choice shared by the renderer, previews, parallax and native pan travel.
enum DrawerChoice: String, CaseIterable, Sendable {
    case a, c
    static let defaultValue = Self.a
    init(storedValue: String) { self = Self(rawValue: storedValue) ?? Self.defaultValue }

    /// Owner, 14 Nov 2026: drawer A takes 40% of the screen width. Larger (non-accessibility)
    /// text widens it up to 1.5x; never narrower than 150 pt, never closer than 64 pt to the
    /// trailing edge. Accessibility sizes keep the full-width large layout. C is unchanged.
    func width(screenWidth: Double, accessibility: Bool, textScale: Double = 1) -> Double {
        guard screenWidth.isFinite else { return 0 }
        if accessibility { return max(0, screenWidth - 32) }
        switch self {
        case .a:
            let scale = textScale.isFinite ? min(max(textScale, 1), 1.5) : 1
            return max(0, min(max(screenWidth * 0.4 * scale, 150), screenWidth - 64))
        case .c:
            return max(0, min(318, screenWidth - 72))
        }
    }

    /// A long generated first label (a cloud instance id) keeps 8 characters and the
    /// registrable domain: "8c86b74c…scw.cloud". Ordinary hosts are unchanged.
    static func narrowHostLabel(_ host: String) -> String {
        let labels = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard labels.count > 3, let first = labels.first, first.count > 12 else { return host }
        return String(first.prefix(8)) + "…" + labels.suffix(2).joined(separator: ".")
    }
}

/// Shared measured name, account and badge rules for both drawers.
enum DrawerCPolicy {
    static func width(screenWidth: Double, accessibility: Bool) -> Double {
        DrawerChoice.c.width(screenWidth: screenWidth, accessibility: accessibility)
    }

    static let usernameMinimumScale = 11.0 / 13.0

    struct NameLayout: Equatable, Sendable {
        let pointSize: Double
        let effectivePointSize: Double
        let lines: Int
    }
    /// Measure at 20pt (or its Dynamic Type equivalent), then use the actual column width.
    /// A single line shrinks smoothly to 16pt; only then does it become two 16pt lines.
    static func nameLayout(measuredWidth: Double, availableWidth: Double) -> NameLayout {
        guard measuredWidth.isFinite, availableWidth.isFinite, availableWidth > 0 else {
            return .init(pointSize: 16, effectivePointSize: 16, lines: 2)
        }
        guard measuredWidth > 0 else { return .init(pointSize: 20, effectivePointSize: 20, lines: 1) }
        let scale = availableWidth / measuredWidth
        if scale >= 0.8 { return .init(pointSize: 20, effectivePointSize: 20 * min(1, scale), lines: 1) }
        return .init(pointSize: 16, effectivePointSize: 16, lines: 2)
    }

    enum AccountRow: Equatable, Sendable { case other, add, none }
    static func accountRow(signedInCount: Int, hasOther: Bool, canAdd: Bool, limit: Int = 2) -> AccountRow {
        if signedInCount >= 2 && hasOther { return .other }
        return signedInCount == 1 && !hasOther && limit > 1 && canAdd ? .add : .none
    }
    enum Badge: Equatable, Sendable { case accent, danger, neutral }
    static func badge(for id: String) -> Badge {
        id == "chats" ? .accent : id == "calls" ? .danger : .neutral
    }
    static func count(_ value: Int, capped: Bool = true) -> String? {
        guard value > 0 else { return nil }
        return capped && value > 99 ? "99+" : String(value)
    }
}

/// Unknown/unsubscribed is not an authoritative offline classification.
struct ContactClassifications: Equatable, Sendable {
    private var known: [String: Bool] = [:]
    mutating func apply(id: String, online: Bool?) {
        if let online { known[id] = online }
    }
    func onlineIDs(in contacts: Set<String>) -> Set<String> {
        Set(contacts.filter { known[$0] == true })
    }
}

enum ContactsRowPolicy {
    static func statusLines(accessibility: Bool) -> Int { accessibility ? 6 : 1 }
    static func statusHeight(lineHeight: Double, accessibility: Bool) -> Double {
        max(1, lineHeight) * Double(statusLines(accessibility: accessibility))
    }
}

enum ContactsDirectoryPolicy {
    static func retryResets(failedOffset: Int64?) -> Bool { (failedOffset ?? 0) <= 0 }
}

struct ContactScrollAnchor: Equatable, Sendable {
    let id: String
    let viewportOffset: Double
}

enum ContactsScrollPolicy {
    static func restoredOffset(rowMinY: Double, viewportOffset: Double, minimum: Double, maximum: Double) -> Double {
        guard rowMinY.isFinite, viewportOffset.isFinite, minimum.isFinite, maximum.isFinite else { return 0 }
        return min(max(minimum, maximum), max(minimum, rowMinY - viewportOffset))
    }
}

struct ContactPresenceDisplay: Equatable, Sendable {
    let online: Bool
    let isLive: Bool
    let label: String
}

/// Only an unexpired, personally confirmed lease survives a temporary handoff.
/// Directory/cache online hints never create this state or extend its deadline.
struct ContactPresenceValue: Equatable, Sendable {
    private(set) var online = false
    private(set) var isLive = false
    private(set) var label = ""
    private var fallback = ""
    private var expiresAtUptime: TimeInterval = 0

    init(serverLastSeen: String = "") { fallback = serverLastSeen; label = serverLastSeen }

    var nextOnlineExpiry: TimeInterval? { online && isLive ? expiresAtUptime : nil }

    func display(at uptime: TimeInterval) -> ContactPresenceDisplay {
        if online && (!uptime.isFinite || uptime >= expiresAtUptime) {
            return .init(online: false, isLive: false, label: fallback)
        }
        return .init(online: online, isLive: isLive, label: label)
    }

    mutating func invalidate() {
        expiresAtUptime = 0
        apply(liveOnline: nil, uptime: 0)
    }

    mutating func apply(liveOnline: Bool?, label liveLabel: String = "", lastSeen: String = "",
                        expiresAtUptime deadline: TimeInterval = 0, uptime: TimeInterval = 0) {
        guard let liveOnline else {
            if online && isLive && uptime.isFinite && uptime < expiresAtUptime { return }
            isLive = false
            online = false
            label = fallback
            return
        }
        guard !liveOnline || (deadline.isFinite && uptime.isFinite && deadline > uptime) else {
            apply(liveOnline: nil, uptime: uptime)
            return
        }
        expiresAtUptime = deadline
        isLive = true
        online = liveOnline
        // An authoritative hidden last-seen clears any previous timestamp.
        fallback = lastSeen
        label = liveLabel
    }
}
