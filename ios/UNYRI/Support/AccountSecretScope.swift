import Foundation

/// Remote identifiers are data inside a locally selected origin and slot.
/// Length-delimited encoding prevents delimiter/port/path ambiguity.
struct AccountSecretScope: Equatable {
    let origin: String
    let slot: String
    let owner: String
    init(origin: String, slot: String, owner: String) {
        var parts = URLComponents(string: origin)
        let scheme = parts?.scheme?.lowercased(), host = parts?.host?.lowercased()
        parts?.scheme = scheme; parts?.host = host
        if (parts?.scheme == "https" && parts?.port == 443) || (parts?.scheme == "http" && parts?.port == 80) { parts?.port = nil }
        if parts?.path == "/" { parts?.path = "" }
        self.origin = parts?.string ?? origin
        self.slot = slot; self.owner = owner.lowercased()
    }
    var id: String { Self.encode([origin, slot, owner]) }
    static func encode(_ fields: [String]) -> String {
        Data(fields.map { "\($0.utf8.count):\($0)" }.joined().utf8).base64EncodedString()
    }
    var historyPrefix: String { "history-v2:" + id + ":" }
    var preferencePrefix: String { "account-secret-v2:" + id + ":" }
    var adminPin: String { "admin-public-pin-v2:" + id }
    func historyRecords(in names: [String], legacy: [String] = []) -> [String] {
        let legacy = Set(legacy)
        return names.filter { $0.hasPrefix(historyPrefix) || legacy.contains($0) }
    }
    func pinRecords(legacyOwner: String? = nil) -> [String] {
        let old = "admin-public-pin:" + origin + "|" + (legacyOwner ?? owner)
        return [adminPin, adminPin + "|pending", old, old + "|pending"]
    }
    func preferenceRecords(in names: [String], legacyHistoryPrefixes: [String] = [],
                           legacyMapping: String? = nil, directSend: String? = nil, legacyOwner: String? = nil) -> [String] {
        let pins = "chatPins|" + origin + "|" + (legacyOwner ?? owner)
        return names.filter { name in
            name.hasPrefix(preferencePrefix) || name == pins || name == legacyMapping || name == directSend ||
                legacyHistoryPrefixes.contains(where: { name.hasPrefix($0) })
        }
    }

    /// Snapshot once, before networking or adding an account. Ambiguous legacy
    /// owners stay unclaimed; neither colliding account may adopt their keys.
    static func freezeLegacyOwners(_ scopes: [Self], defaults: UserDefaults) {
        let key = "account-secret-v2.legacyOwners"
        guard defaults.object(forKey: key) == nil else { return }
        let groups = Dictionary(grouping: scopes, by: \.owner)
        defaults.set(groups.compactMapValues { values in Set(values.map(\.id)).count == 1 ? values.first?.id : nil }, forKey: key)
    }
    func ownsLegacy(defaults: UserDefaults) -> Bool {
        (defaults.dictionary(forKey: "account-secret-v2.legacyOwners")?[owner] as? String) == id
    }

    /// Verify the replacement before removing the source. Failed writes and
    /// failed readback retain the only recoverable copy.
    static func migrate(old: String, new: String, read: (String) throws -> Data?,
                        insert: (String, Data) throws -> Void, remove: (String) throws -> Void) throws -> Data? {
        if let value = try read(new) {
            if try read(old) == value { try remove(old) }
            return value
        }
        guard let value = try read(old) else { return nil }
        try insert(new, value)
        guard try read(new) == value else { throw CocoaError(.fileWriteUnknown) }
        try remove(old)
        return value
    }
}
