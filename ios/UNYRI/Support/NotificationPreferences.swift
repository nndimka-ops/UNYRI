import Foundation

/// Presentation preferences for this installation. They do not pause message
/// ingestion, modify account privacy, or change CallKit/audio ownership.
struct UnyriNotificationPreferences: Codable, Equatable {
    var enabled = true
    var messages = true
    var reminders = true
    var sounds = true
    var previews = true
    var inAppBanners = true
    var inAppSounds = true
    var inAppVibration = true

    var allowsMessages: Bool { enabled && messages }
    var allowsReminders: Bool { enabled && reminders }
    var allowsMessageSound: Bool { allowsMessages && sounds }
    var allowsReminderSound: Bool { allowsReminders && sounds }
    var allowsInAppSound: Bool { allowsMessages && inAppSounds }
    var allowsInAppVibration: Bool { allowsMessages && inAppVibration }

    init() {}
    private enum CodingKeys: String, CodingKey {
        case enabled, messages, reminders, sounds, previews, inAppBanners, inAppSounds, inAppVibration
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try values.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        messages = try values.decodeIfPresent(Bool.self, forKey: .messages) ?? true
        reminders = try values.decodeIfPresent(Bool.self, forKey: .reminders) ?? true
        sounds = try values.decodeIfPresent(Bool.self, forKey: .sounds) ?? true
        previews = try values.decodeIfPresent(Bool.self, forKey: .previews) ?? true
        inAppBanners = try values.decodeIfPresent(Bool.self, forKey: .inAppBanners) ?? true
        inAppSounds = try values.decodeIfPresent(Bool.self, forKey: .inAppSounds) ?? true
        inAppVibration = try values.decodeIfPresent(Bool.self, forKey: .inAppVibration) ?? true
    }
}

/// Presentation only: ingestion and background APNs remain independent.
enum InAppNotificationPolicy {
    enum Event { case message, call }
    struct Decision: Equatable {
        let banner: Bool
        let sound: Bool
        let vibration: Bool
        static let silent = Decision(banner: false, sound: false, vibration: false)
    }
    static func decide(foreground: Bool, event: Event, sameChat: Bool, muted: Bool,
                       ownMessage: Bool = false, preferences: UnyriNotificationPreferences) -> Decision {
        guard foreground, event == .message, !sameChat, !muted, !ownMessage, preferences.allowsMessages else { return .silent }
        return Decision(banner: preferences.inAppBanners, sound: preferences.allowsInAppSound,
                        vibration: preferences.allowsInAppVibration)
    }
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
    /// The single file of earlier versions, shared by whichever account was signed in.
    /// Read (owner-checked) until this owner's own file is written, then removed.
    var legacyFile: URL? = nil

    /// One file per account: two signed-in accounts never overwrite each other's plan.
    static func accountArchive(owner: String) -> UnyriPausedReminderArchive {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let folder = support.appendingPathComponent("COCO/Notifications", isDirectory: true)
        let name = UUID(uuidString: owner).map { "paused-reminders-v1-" + $0.uuidString.lowercased() + ".json" }
            ?? "paused-reminders-v1-invalid.json"
        return UnyriPausedReminderArchive(file: folder.appendingPathComponent(name),
                                          legacyFile: folder.appendingPathComponent("paused-reminders-v1.json"))
    }

    func load(owner: String, server: String, now: Date = Date()) -> [UnyriPausedReminder] {
        guard UUID(uuidString: owner) != nil else { return [] }
        let source = FileManager.default.fileExists(atPath: file.path) ? file : (legacyFile ?? file)
        guard let value = Self.read(source), value.owner == owner, value.server == server else { return [] }
        return Self.bounded(value.entries, owner: owner, now: now)
    }

    private static func read(_ url: URL) -> Payload? {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size <= 65536,
              let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(Payload.self, from: data), value.version == 1 else { return nil }
        return value
    }

    /// The legacy file goes once it belongs to `owner` (its plan now lives in `file`).
    private func dropLegacy(owner: String?) {
        guard let legacyFile, FileManager.default.fileExists(atPath: legacyFile.path) else { return }
        if let owner, let value = Self.read(legacyFile), value.owner != owner { return }
        try? FileManager.default.removeItem(at: legacyFile)
    }

    @discardableResult
    func save(_ entries: [UnyriPausedReminder], owner: String, server: String, now: Date = Date()) -> Bool {
        guard UUID(uuidString: owner) != nil else { return false }
        defer { if FileManager.default.fileExists(atPath: file.path) { dropLegacy(owner: owner) } }
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

    /// Erases this owner's plan (and the legacy file only when it is this owner's,
    /// or unconditionally when `owner` is nil: the last account is leaving).
    func clear(owner: String? = nil) {
        try? FileManager.default.removeItem(at: file)
        dropLegacy(owner: owner)
    }

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

    /// The stored value belongs to the active account (UnyriAccountPreferences).
    func reload() {
        let stored = UnyriNotificationPreferencesStorage.load()
        if value != stored { value = stored }
    }

    func set(_ keyPath: WritableKeyPath<UnyriNotificationPreferences, Bool>, to enabled: Bool) {
        guard value[keyPath: keyPath] != enabled else { return }
        value[keyPath: keyPath] = enabled
        UnyriNotificationPreferencesStorage.save(value)
    }
}
#endif
