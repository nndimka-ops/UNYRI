import Foundation

/// Presentation preferences for this installation. They do not pause message
/// ingestion, modify account privacy, or change CallKit/audio ownership.
struct UnyriNotificationPreferences: Codable, Equatable {
    var enabled = true
    var messages = true
    var reminders = true
    var sounds = true
    var previews = true

    var allowsMessages: Bool { enabled && messages }
    var allowsReminders: Bool { enabled && reminders }
    var allowsMessageSound: Bool { allowsMessages && sounds }
    var allowsReminderSound: Bool { allowsReminders && sounds }
}

/// Latest state wins after a system scheduling await. Explicit cancellation or
/// an ended account must never create paused work; disabling alerts preserves
/// the plan instead of deleting it with the just-replaced system request.
enum UnyriReminderCommitPolicy {
    enum Action { case keep, pause, discard }
    static func action(contextIsCurrent: Bool, cancelled: Bool, allowsReminders: Bool) -> Action {
        if !contextIsCurrent || cancelled { return .discard }
        return allowsReminders ? .keep : .pause
    }
}

enum UnyriNotificationPreferencesStorage {
    static let key = "coco.notifications.preferences.v1"
    static let changed = Notification.Name("COCONotificationPreferencesChanged")

    static func load(defaults: UserDefaults = .standard) -> UnyriNotificationPreferences {
        guard let data = defaults.data(forKey: key),
              let preferences = try? JSONDecoder().decode(UnyriNotificationPreferences.self, from: data) else {
            return UnyriNotificationPreferences()
        }
        return preferences
    }

    static func save(_ preferences: UnyriNotificationPreferences, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(preferences) else { return }
        defaults.set(data, forKey: key)
        NotificationCenter.default.post(name: changed, object: nil)
    }
}

/// Only future scheduled-reminder routing metadata, never message text. This
/// file belongs to the removable app-container account cache, not Keychain.
struct UnyriPausedReminder: Codable, Equatable {
    let chat: String
    let message: String
    let due: Date
    func identifier(owner: String) -> String { "reminder:\(owner):\(chat):\(message)" }
    var valid: Bool { UUID(uuidString: chat) != nil && UUID(uuidString: message) != nil && due.timeIntervalSince1970.isFinite }
}

struct UnyriPausedReminderArchive {
    private struct Payload: Codable {
        let version: Int
        let owner: String
        let server: String
        let entries: [UnyriPausedReminder]
    }
    let file: URL
    static var accountArchive: UnyriPausedReminderArchive {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return UnyriPausedReminderArchive(file: support.appendingPathComponent("COCO/Notifications/paused-reminders-v1.json"))
    }

    func load(owner: String, server: String, now: Date = Date()) -> [UnyriPausedReminder] {
        guard UUID(uuidString: owner) != nil,
              let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size <= 65536,
              let data = try? Data(contentsOf: file),
              let value = try? JSONDecoder().decode(Payload.self, from: data), value.version == 1,
              value.owner == owner, value.server == server else { return [] }
        return Self.bounded(value.entries, owner: owner, now: now)
    }

    @discardableResult
    func save(_ entries: [UnyriPausedReminder], owner: String, server: String, now: Date = Date()) -> Bool {
        guard UUID(uuidString: owner) != nil else { return false }
        let value = Payload(version: 1, owner: owner, server: server, entries: Self.bounded(entries, owner: owner, now: now))
        do {
            var directory = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            #if os(iOS) || os(macOS)
            var attributes = URLResourceValues(); attributes.isExcludedFromBackup = true
            try directory.setResourceValues(attributes)
            #endif
            let data = try JSONEncoder().encode(value)
            #if os(iOS)
            try data.write(to: file, options: [.atomic, .completeFileProtection])
            #else
            try data.write(to: file, options: .atomic)
            #endif
            return true
        } catch { return false }
    }

    func clear() { try? FileManager.default.removeItem(at: file) }

    private static func bounded(_ entries: [UnyriPausedReminder], owner: String, now: Date) -> [UnyriPausedReminder] {
        var unique: [String: UnyriPausedReminder] = [:]
        for entry in entries where entry.valid && entry.due > now { unique[entry.identifier(owner: owner)] = entry }
        return Array(unique.values.sorted { a, b in
            a.due == b.due ? a.identifier(owner: owner) < b.identifier(owner: owner) : a.due < b.due
        }.prefix(64))
    }
}

#if canImport(Combine)
import Combine

/// Cached on MainActor: no file/keychain reads on the message rendering path.
@MainActor
final class UnyriNotificationPreferencesStore: ObservableObject {
    static let shared = UnyriNotificationPreferencesStore()
    @Published private(set) var value: UnyriNotificationPreferences
    @Published var reminderIssue: String?

    private init() { value = UnyriNotificationPreferencesStorage.load() }

    func set(_ keyPath: WritableKeyPath<UnyriNotificationPreferences, Bool>, to enabled: Bool) {
        guard value[keyPath: keyPath] != enabled else { return }
        value[keyPath: keyPath] = enabled
        UnyriNotificationPreferencesStorage.save(value)
    }
}
#endif
