import Foundation

struct HistoryAutoPreferences {
    let prefix: String
    private let defaults: UserDefaults
    init(server: String, owner: String, scope: AccountSecretScope? = nil, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let old = "history-auto-v1:\(server):\(owner):"
        prefix = scope.map { $0.preferencePrefix + "history:" + server.lowercased() + ":" } ?? old
        if let scope, scope.ownsLegacy(defaults: defaults) {
            for suffix in ["enabled", "wifi", "uploadsAllowed", "success"] where defaults.object(forKey: prefix + suffix) == nil {
                if let value = defaults.object(forKey: old + suffix) {
                    defaults.set(value, forKey: prefix + suffix)
                    if let saved = defaults.object(forKey: prefix + suffix) as? NSObject, saved.isEqual(value) {
                        defaults.removeObject(forKey: old + suffix)
                    }
                }
            }
        }
    }
    var enabled: Bool {
        get { defaults.bool(forKey: prefix + "enabled") }
        nonmutating set { defaults.set(newValue, forKey: prefix + "enabled") }
    }
    var wifiOnly: Bool {
        get { defaults.object(forKey: prefix + "wifi") as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: prefix + "wifi") }
    }
    var uploadsAllowed: Bool {
        get { defaults.object(forKey: prefix + "uploadsAllowed") as? Bool ?? false }
        nonmutating set { defaults.set(newValue, forKey: prefix + "uploadsAllowed") }
    }
    var lastSuccess: Double {
        get { defaults.double(forKey: prefix + "success") }
        nonmutating set { defaults.set(newValue, forKey: prefix + "success") }
    }
    var due: Bool { Date().timeIntervalSince1970 - lastSuccess >= 86400 }
}

enum HistoryAutoPolicy {
    static func shouldSchedule(enabled: Bool, uploadsAllowed: Bool, accountActive: Bool) -> Bool { enabled && uploadsAllowed && accountActive }
    static func due(lastSuccess: Double, now: Double) -> Bool { now - lastSuccess >= 86400 }
    static func next(lastSuccess: Double, now: Double) -> Date {
        Date(timeIntervalSince1970: lastSuccess + 86400 > now ? lastSuccess + 86400 : now + 3600)
    }
}
