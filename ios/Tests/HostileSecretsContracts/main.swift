import Foundation
private func check(_ value: Bool) { precondition(value) }

private func rejects(_ operation: () throws -> Void) {
    do { try operation(); fatalError("Expected controlled error") } catch {}
}

@main struct Main {
    static func main() throws {
        let owner = "22222222-2222-4222-8222-222222222222"
        let server = "11111111-1111-4111-8111-111111111111"
        let a = AccountSecretScope(origin: "https://official.example", slot: "primary", owner: owner)
        let b = AccountSecretScope(origin: "https://hostile.example", slot: "s1234567890abcdef", owner: owner)
        precondition(a != b && a.historyPrefix != b.historyPrefix && a.adminPin != b.adminPin)
        precondition(a.historyPrefix != AccountSecretScope(origin: b.origin, slot: a.slot, owner: owner).historyPrefix)
        precondition(a == AccountSecretScope(origin: "HTTPS://OFFICIAL.EXAMPLE:443/", slot: "primary", owner: owner.uppercased()))
        precondition(a != AccountSecretScope(origin: "https://official.example:444", slot: "primary", owner: owner))
        precondition(a != AccountSecretScope(origin: a.origin, slot: b.slot, owner: owner))
        let suite = "unyri-hostile-secrets-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        AccountSecretScope.freezeLegacyOwners([a], defaults: defaults)
        AccountSecretScope.freezeLegacyOwners([b], defaults: defaults)
        precondition(a.ownsLegacy(defaults: defaults) && !b.ownsLegacy(defaults: defaults))
        let ambiguousSuite = "unyri-hostile-ambiguous-" + UUID().uuidString
        let ambiguous = UserDefaults(suiteName: ambiguousSuite)!
        defer { ambiguous.removePersistentDomain(forName: ambiguousSuite) }
        AccountSecretScope.freezeLegacyOwners([a, b], defaults: ambiguous)
        precondition(!a.ownsLegacy(defaults: ambiguous) && !b.ownsLegacy(defaults: ambiguous))

        var records: [String: Data] = [:]
        let ka = Data(repeating: 3, count: 32), kb = Data(repeating: 7, count: 32)
        func keys(_ scope: AccountSecretScope) throws -> HistoryRecoveryKeys {
            try HistoryRecoveryKeys(server: server, owner: owner, scope: scope,
                read: { records[$0] }, insert: { records[$0] = $1 })
        }
        try keys(a).saveConfirmed(ka); try keys(b).saveConfirmed(kb)
        records[a.adminPin] = Data("pin-A".utf8); records[b.adminPin] = Data("pin-B".utf8)
        let pa = HistoryAutoPreferences(server: server, owner: owner, scope: a, defaults: defaults)
        let pb = HistoryAutoPreferences(server: server, owner: owner, scope: b, defaults: defaults)
        pa.enabled = true; pa.lastSuccess = 123; pb.enabled = true; pb.lastSuccess = 456
        let canary = records.filter { $0.key.hasPrefix(a.historyPrefix) || $0.key == a.adminPin }
        let preferences = defaults.dictionaryRepresentation().filter { $0.key.hasPrefix(a.preferencePrefix) } as NSDictionary
        // Revoke/remove/re-add either origin repeatedly. Exact manifests may
        // select only their scope; the other origin remains byte-identical.
        for _ in 0..<3 {
            for name in b.historyRecords(in: Array(records.keys)) + b.pinRecords() { records[name] = nil }
            for name in b.preferenceRecords(in: Array(defaults.dictionaryRepresentation().keys)) { defaults.removeObject(forKey: name) }
            precondition(records.filter { $0.key.hasPrefix(a.historyPrefix) || $0.key == a.adminPin } == canary)
            precondition(preferences.isEqual(to: defaults.dictionaryRepresentation().filter { $0.key.hasPrefix(a.preferencePrefix) }))
            try keys(b).saveConfirmed(kb)
            records[b.adminPin] = Data("pin-B".utf8); pb.enabled = true; pb.lastSuccess = 456
        }
        check(try keys(a).load() == ka)
        let bCanary = records.filter { $0.key.hasPrefix(b.historyPrefix) || $0.key == b.adminPin }
        for name in a.historyRecords(in: Array(records.keys)) + a.pinRecords() { records[name] = nil }
        for name in a.preferenceRecords(in: Array(defaults.dictionaryRepresentation().keys)) { defaults.removeObject(forKey: name) }
        precondition(records.filter { $0.key.hasPrefix(b.historyPrefix) || $0.key == b.adminPin } == bCanary)
        precondition(pb.enabled && pb.lastSuccess == 456)
        try keys(a).saveConfirmed(ka)

        let oldPreference = "history-auto-v1:\(server):\(owner):enabled"
        defaults.set(true, forKey: oldPreference)
        defaults.removeObject(forKey: pb.prefix + "enabled")
        let hostilePreferences = HistoryAutoPreferences(server: server, owner: owner, scope: b, defaults: defaults)
        precondition(!hostilePreferences.enabled && defaults.bool(forKey: oldPreference))
        let migratedPreferences = HistoryAutoPreferences(server: server, owner: owner, scope: a, defaults: defaults)
        precondition(migratedPreferences.enabled && defaults.object(forKey: oldPreference) == nil)

        let old = "history-v1:\(server):\(owner)", new = a.historyPrefix + server
        var storage = [old: ka]
        rejects { _ = try AccountSecretScope.migrate(old: old, new: new, read: { storage[$0] }, insert: { _, _ in throw CocoaError(.fileWriteUnknown) }, remove: { storage[$0] = nil }) }
        precondition(storage[old] == ka && storage[new] == nil)
        rejects { _ = try AccountSecretScope.migrate(old: old, new: new, read: { storage[$0] }, insert: { _, _ in }, remove: { storage[$0] = nil }) }
        precondition(storage[old] == ka)
        rejects { _ = try AccountSecretScope.migrate(old: old, new: new, read: { storage[$0] }, insert: { storage[$0] = $1 }, remove: { _ in throw CocoaError(.fileWriteUnknown) }) }
        precondition(storage[old] == ka && storage[new] == ka)
        _ = try AccountSecretScope.migrate(old: old, new: new, read: { storage[$0] }, insert: { storage[$0] = $1 }, remove: { storage[$0] = nil })
        precondition(storage[old] == nil && storage[new] == ka)
        print("Hostile F8 secret isolation and migration contracts passed")
    }
}
