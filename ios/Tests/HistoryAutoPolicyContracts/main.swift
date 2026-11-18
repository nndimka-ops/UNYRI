import Foundation

@main struct HistoryAutoPolicyContracts {
    static func main() {
        precondition(!HistoryAutoPolicy.due(lastSuccess: 1000, now: 87399))
        precondition(HistoryAutoPolicy.due(lastSuccess: 1000, now: 87400))
        precondition(HistoryAutoPolicy.next(lastSuccess: 1000, now: 80000).timeIntervalSince1970 == 87400)
        precondition(HistoryAutoPolicy.next(lastSuccess: 1000, now: 81000).timeIntervalSince1970 == 87400)
        precondition(HistoryAutoPolicy.next(lastSuccess: 1000, now: 90000).timeIntervalSince1970 == 93600)
        precondition(HistoryAutoPolicy.shouldSchedule(enabled: true, uploadsAllowed: true, accountActive: true))
        precondition(!HistoryAutoPolicy.shouldSchedule(enabled: false, uploadsAllowed: true, accountActive: true))
        precondition(!HistoryAutoPolicy.shouldSchedule(enabled: true, uploadsAllowed: false, accountActive: true))
        precondition(!HistoryAutoPolicy.shouldSchedule(enabled: true, uploadsAllowed: true, accountActive: false))
        let p = HistoryAutoPreferences(server: UUID().uuidString, owner: UUID().uuidString)
        precondition(!p.enabled && p.wifiOnly)
        p.enabled = true; p.wifiOnly = false; p.lastSuccess = 1000
        precondition(p.enabled && !p.wifiOnly && p.lastSuccess == 1000)
        p.enabled = false; precondition(!p.enabled)
        for suffix in ["enabled", "wifi", "success"] { UserDefaults.standard.removeObject(forKey: p.prefix + suffix) }
        print("Automatic history policy contracts passed: opt-in, Wi-Fi default, absolute daily due date, bounded retry")
    }
}
