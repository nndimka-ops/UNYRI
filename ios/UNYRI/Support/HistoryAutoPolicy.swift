import Foundation

struct HistoryAutoPreferences {
    let prefix: String
    init(server: String, owner: String) { prefix = "history-auto-v1:\(server):\(owner):" }
    var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: prefix + "enabled") }
        nonmutating set { UserDefaults.standard.set(newValue, forKey: prefix + "enabled") }
    }
    var wifiOnly: Bool {
        get { UserDefaults.standard.object(forKey: prefix + "wifi") as? Bool ?? true }
        nonmutating set { UserDefaults.standard.set(newValue, forKey: prefix + "wifi") }
    }
    var uploadsAllowed: Bool {
        get { UserDefaults.standard.object(forKey: prefix + "uploadsAllowed") as? Bool ?? false }
        nonmutating set { UserDefaults.standard.set(newValue, forKey: prefix + "uploadsAllowed") }
    }
    var lastSuccess: Double {
        get { UserDefaults.standard.double(forKey: prefix + "success") }
        nonmutating set { UserDefaults.standard.set(newValue, forKey: prefix + "success") }
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
