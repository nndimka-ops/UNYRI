import Foundation

let suite = "COCO-notification-contract-" + UUID().uuidString
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
let initial = UnyriNotificationPreferencesStorage.load(defaults: defaults)
precondition(initial.allowsMessages && initial.allowsReminders && initial.allowsMessageSound && initial.previews)
var value = initial
value.messages = false
precondition(!value.allowsMessages && !value.allowsMessageSound && value.allowsReminders)
value.messages = true; value.reminders = false
precondition(value.allowsMessages && !value.allowsReminders && !value.allowsReminderSound)
value.reminders = true; value.sounds = false; value.previews = false
precondition(value.allowsMessages && value.allowsReminders && !value.allowsMessageSound && !value.allowsReminderSound)
UnyriNotificationPreferencesStorage.save(value, defaults: defaults)
precondition(UnyriNotificationPreferencesStorage.load(defaults: defaults) == value, "Device preferences must survive reopening")
value.enabled = false
precondition(!value.allowsMessages && !value.allowsReminders)
value.enabled = true
precondition(!value.previews && !value.sounds, "Master off/on must preserve individual choices")
defaults.set(Data("corrupt fixture".utf8), forKey: UnyriNotificationPreferencesStorage.key)
precondition(UnyriNotificationPreferencesStorage.load(defaults: defaults) == initial)
let archiveDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("coco-reminder-contract-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: archiveDirectory) }
let archive = UnyriPausedReminderArchive(file: archiveDirectory.appendingPathComponent("reminders.json"))
let owner = UUID().uuidString, other = UUID().uuidString, server = "https://contract.invalid"
let now = Date()
let future = UnyriPausedReminder(chat: UUID().uuidString, message: UUID().uuidString, due: now.addingTimeInterval(900))
let expired = UnyriPausedReminder(chat: UUID().uuidString, message: UUID().uuidString, due: now.addingTimeInterval(-10))
precondition(archive.save([future, expired, future], owner: owner, server: server, now: now))
precondition(archive.load(owner: owner, server: server, now: now) == [future], "Pause/resume keeps exact absolute due date and deduplicates")
precondition(archive.load(owner: other, server: server, now: now).isEmpty, "Another account cannot resume reminders")
precondition(archive.load(owner: owner, server: "https://other.invalid", now: now).isEmpty, "Another server cannot resume reminders")
precondition(archive.load(owner: owner, server: server, now: future.due).isEmpty, "Missed reminders must not replay after re-enabling")
let many = (0..<80).map { offset in UnyriPausedReminder(chat: UUID().uuidString, message: UUID().uuidString, due: now.addingTimeInterval(Double(offset + 1))) }
precondition(archive.save(many, owner: owner, server: server, now: now))
let bounded = archive.load(owner: owner, server: server, now: now)
precondition(bounded.count == 64 && bounded.first?.due == many.first?.due, "Paused routing metadata is bounded")
archive.clear()
precondition(archive.load(owner: owner, server: server, now: now).isEmpty, "Logout erases paused reminder metadata")
precondition(UnyriReminderCommitPolicy.action(contextIsCurrent: true, cancelled: false, allowsReminders: false) == .pause,
             "OFF during an awaited sound replacement must preserve the plan before removing its system request")
precondition(UnyriReminderCommitPolicy.action(contextIsCurrent: true, cancelled: true, allowsReminders: false) == .discard,
             "Done during the replacement must not archive or resurrect the cancelled reminder")
precondition(UnyriReminderCommitPolicy.action(contextIsCurrent: false, cancelled: false, allowsReminders: false) == .discard,
             "Logout during replacement must not archive the previous account's reminder")
precondition(UnyriReminderCommitPolicy.action(contextIsCurrent: true, cancelled: false, allowsReminders: true) == .keep,
             "A current enabled reminder remains scheduled")
print("Notification preferences contracts passed: device persistence, independent toggles, master preservation, corrupt storage")
print("Paused reminder archive contracts passed: owner/server fences, exact due, expiry, deduplication, bound64 and erasure")
print("Post-await reminder commit policy passed: OFF pauses; Done/logout discard; enabled keeps. This is policy, not OS callback execution.")
