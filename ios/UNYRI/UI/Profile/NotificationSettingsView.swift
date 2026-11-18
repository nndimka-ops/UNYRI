import SwiftUI
import UserNotifications
import UIKit
import AudioToolbox

struct UnyriNotificationSettingsView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var preferences = UnyriNotificationPreferencesStore.shared
    @ObservedObject private var push = UnyriPushNotificationCoordinator.shared
    @State private var authorization: UNAuthorizationStatus = .notDetermined
    @State private var systemSounds: UNNotificationSetting = .notSupported
    @State private var requesting = false
    @State private var permissionError: String?

    var body: some View {
        UnyriSettingsPage("Notifications") {
            UnyriSettingsGroup {
                notificationToggle("Messages & reminders", icon: "bell", keyPath: \.enabled, identifier: "notifications-enabled")
            }
            UnyriSettingsNote("These settings apply to this device. Messages still arrive and are saved when alerts are off.")

            UnyriSettingsGroup("Messages") {
                notificationToggle("Message notifications", icon: "bubble.left", keyPath: \.messages, identifier: "notifications-messages")
                UnyriSettingsDivider()
                notificationToggle("Message preview", icon: "text.alignleft", keyPath: \.previews, identifier: "notifications-previews")
                    .disabled(!preferences.value.messages)
            }.disabled(!preferences.value.enabled)
            UnyriSettingsNote("Turn preview off to show “New message” instead of message text.")

            UnyriSettingsGroup("In app") {
                notificationToggle("In-app banners", icon: "rectangle.topthird.inset.filled", keyPath: \.inAppBanners, identifier: "notifications-inapp-banners")
                UnyriSettingsDivider()
                notificationToggle("In-app sound", icon: "speaker.wave.2", keyPath: \.inAppSounds, identifier: "notifications-inapp-sound")
                UnyriSettingsDivider()
                notificationToggle("In-app vibration", icon: "iphone.radiowaves.left.and.right", keyPath: \.inAppVibration, identifier: "notifications-inapp-vibration")
            }.disabled(!preferences.value.allowsMessages)

            UnyriSettingsGroup {
                notificationToggle("Reminders", icon: "alarm", keyPath: \.reminders, identifier: "notifications-reminders")
                UnyriSettingsDivider()
                notificationToggle("Notification sounds", icon: "speaker.wave.2", keyPath: \.sounds, identifier: "notifications-sounds")
            }.disabled(!preferences.value.enabled)
            UnyriSettingsNote("Reminders are paused while off. Future reminders resume when enabled; missed reminders are not replayed.")
            if let issue = preferences.reminderIssue { UnyriSettingsNote(issue) }

            UnyriSettingsGroup("iPhone permission") {
                UnyriSettingsRow(title: permissionTitle, icon: permissionIcon, tint: permissionTint,
                                subtitle: permissionSubtitle, showsChevron: false)
                    .accessibilityIdentifier("notifications-system-status")
                UnyriSettingsDivider()
                if authorization == .notDetermined {
                    Button { Task { await requestPermission() } } label: {
                        UnyriSettingsRow(title: requesting ? "Requesting permission…" : "Allow in iOS", icon: "bell.badge",
                                        tint: Color(hex: 0x7BC9EC), showsChevron: false)
                    }.disabled(requesting).accessibilityIdentifier("notifications-request-permission")
                } else {
                    Button(action: openSystemSettings) {
                        UnyriSettingsRow(title: "Open iPhone settings", icon: "gearshape", tint: Color(hex: 0x7BC9EC))
                    }.accessibilityIdentifier("notifications-open-system-settings")
                }
            }
            if let permissionError { UnyriSettingsNote(permissionError) }
            UnyriSettingsNote("iOS permission controls system alerts and message sounds. Calls keep their own system controls.")

            backgroundNotifications

            UnyriSettingsGroup("Calls") {
                UnyriSettingsRow(title: "Native iPhone call controls", icon: "phone", tint: Color(hex: 0x83D9B7),
                    subtitle: "Calls keep their system controls. These switches do not reject calls or change call audio.", showsChevron: false)
            }
            UnyriSettingsGroup("Without internet") {
                UnyriSettingsRow(title: "While UNYRI is open", icon: "wifi", tint: Color(hex: 0x7BC9EC),
                    subtitle: "Messages and calls can arrive over Wi-Fi if the selected UNYRI server is reachable on that network.", showsChevron: false)
                UnyriSettingsDivider()
                UnyriSettingsRow(title: "When iPhone is locked or UNYRI is closed", icon: "iphone.slash", tint: Color(hex: 0xE6BD73),
                    subtitle: "This build does not support new background messages or calls over a local network. Scheduled local reminders can still appear without internet.", showsChevron: false)
            }
        }
        .task { await refreshPermission() }
        .onChange(of: scenePhase) { phase in if phase == .active { Task { await refreshPermission() } } }
    }

    private var backgroundNotifications: some View {
        VStack(alignment: .leading, spacing: 12) {
            UnyriSettingsGroup("Background notifications") {
                UnyriSettingsRow(title: push.connection == "ready" ? "Notifications are connected" :
                    push.connection == "waiting" ? "Connecting notifications…" : "Notifications need to reconnect",
                    icon: "bell.badge", tint: .secondary, showsChevron: false)
                if push.connection == "unavailable" {
                    UnyriSettingsDivider()
                    Button { push.refreshRegistration() } label: {
                        UnyriSettingsRow(title: "Reconnect notifications", icon: "arrow.clockwise", tint: .secondary, showsChevron: false)
                    }.accessibilityIdentifier("notifications-reconnect")
                }
            }
            UnyriSettingsNote("Background alerts need internet and a server with notification support. Background alerts do not contain message text.")
        }
    }

    private func notificationToggle(_ title: String, icon: String,
        keyPath: WritableKeyPath<UnyriNotificationPreferences, Bool>, identifier: String) -> some View {
        Toggle(isOn: Binding(get: { preferences.value[keyPath: keyPath] }, set: { value in
            UnyriHaptics.selection()
            preferences.set(keyPath, to: value)
            UnyriLocalNotificationPreferences.reconcile()
        })) {
            UnyriSettingsRow(title: title, icon: icon, tint: Color(hex: 0xAFA4F7), showsChevron: false)
        }
        .tint(UnyriColors.accent).padding(.trailing, 14)
        .accessibilityIdentifier(identifier)
    }

    private var permissionTitle: String {
        switch authorization {
        case .authorized, .ephemeral: return "Allowed by iOS"
        case .provisional: return "Quiet notifications allowed"
        case .denied: return "Disabled in iOS"
        default: return "Permission not requested"
        }
    }
    private var permissionSubtitle: String {
        if authorization == .denied { return "Enable notifications for UNYRI in iPhone settings." }
        if authorization == .notDetermined { return "Allow reminders and message sounds when you are ready." }
        if systemSounds != .enabled { return "System sounds are off. Check iPhone notification settings and Focus." }
        return "Focus and silent mode can still silence alerts."
    }
    private var permissionIcon: String { authorization == .denied ? "bell.slash" : "bell.badge" }
    private var permissionTint: Color { authorization == .denied ? Color(hex: 0xE6BD73) : Color(hex: 0x83D9B7) }

    private func refreshPermission() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authorization = settings.authorizationStatus
        systemSounds = settings.soundSetting
        await push.refreshAuthorization()
    }

    private func requestPermission() async {
        guard !requesting else { return }
        requesting = true; permissionError = nil
        defer { requesting = false }
        await push.requestAuthorization()
        await refreshPermission()
    }

    private func openSystemSettings() {
        UnyriHaptics.selection()
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

}

/// Pending reminders are owned by iOS after scheduling. Apply changes to those
/// requests now, so disabling alerts/sound also works while UNYRI is suspended.
/// One serialized worker converges to the latest preference after any await;
/// quick off/on changes cannot leave an older sound update as the final write.
@MainActor
enum UnyriLocalNotificationPreferences {
    private static var revision: UInt64 = 0
    private static var task: Task<Void, Never>?
    private static var context: (owner: String, isCurrent: () -> Bool)?
    private static var cancelled = Set<String>()

    static func attach(owner: String, isCurrent: @escaping () -> Bool) {
        context = (owner, isCurrent)
        reconcile()
    }

    /// `owner`: the account whose session ends. Only its paused plan is erased; the
    /// other signed-in account keeps its own. `lastAccount` also erases the legacy
    /// single-owner file whoever it belonged to.
    static func resetForLogout(owner: String?, lastAccount: Bool) {
        context = nil
        revision &+= 1
        cancelled.removeAll()
        UnyriForegroundMessageSound.resetForLogout()
        if let owner { UnyriPausedReminderArchive.accountArchive(owner: owner).clear(owner: lastAccount ? nil : owner) }
    }

    static func cancelReminder(_ identifier: String) {
        if task != nil { cancelled.insert(identifier) }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        if let context, context.isCurrent() {
            let archive = UnyriPausedReminderArchive.accountArchive(owner: context.owner)
            let entries = archive.load(owner: context.owner, server: AppConfig.storageOrigin)
                .filter { $0.identifier(owner: context.owner) != identifier }
            _ = archive.save(entries, owner: context.owner, server: AppConfig.storageOrigin)
        }
        reconcile()
    }

    static func reconcile() {
        revision &+= 1
        guard task == nil else { return }
        task = Task {
            let center = UNUserNotificationCenter.current()
            while true {
                guard let context, context.isCurrent() else { task = nil; return }
                let token = revision
                let preferences = UnyriNotificationPreferencesStore.shared.value
                let requests = await center.pendingNotificationRequests()
                if token != revision { continue }
                guard context.isCurrent() else { task = nil; return }
                let archive = UnyriPausedReminderArchive.accountArchive(owner: context.owner)
                for request in requests {
                    if token != revision || !context.isCurrent() { break }
                    if request.content.userInfo["kind"] as? String == "chat_reminder" {
                        // The other signed-in account keeps its reminders while inactive.
                        if let owner = request.content.userInfo["owner"] as? String,
                           !NotificationRecipientPolicy.matches(owner: owner, current: context.owner),
                           UnyriAccountManager.shared.account(owner: owner) != nil { continue }
                        if cancelled.contains(request.identifier) || !NotificationRecipientPolicy.matches(
                            owner: request.content.userInfo["owner"] as? String, current: context.owner) {
                            center.removePendingNotificationRequests(withIdentifiers: [request.identifier])
                        } else if !preferences.allowsReminders {
                            if let entry = pausedEntry(request), entry.due > Date() {
                                var entries = archive.load(owner: context.owner, server: AppConfig.storageOrigin)
                                entries.removeAll { $0.identifier(owner: context.owner) == request.identifier }
                                entries.append(entry)
                                // Preserve a plan before cancelling its system request.
                                // No plaintext or attachment data is copied.
                                if !archive.save(entries, owner: context.owner, server: AppConfig.storageOrigin) {
                                    UnyriNotificationPreferencesStore.shared.reminderIssue = "Could not preserve paused reminders. Alerts are off; schedule reminders again when ready."
                                }
                            }
                            center.removePendingNotificationRequests(withIdentifiers: [request.identifier])
                        } else if (request.content.sound != nil) != preferences.allowsReminderSound,
                                  let content = request.content.mutableCopy() as? UNMutableNotificationContent {
                            // Reusing a relative trigger would restart its full
                            // interval. Keep the original due date instead.
                            let due = (request.content.userInfo["scheduled_at_ms"] as? NSNumber)
                                .map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
                                ?? nextDate(request.trigger)
                            guard let due, due > Date(), request.trigger?.repeats == false else { continue }
                            content.sound = preferences.allowsReminderSound ? .default : nil
                            content.userInfo["scheduled_at_ms"] = due.timeIntervalSince1970 * 1000
                            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, due.timeIntervalSinceNow), repeats: false)
                            try? await center.add(UNNotificationRequest(identifier: request.identifier, content: content, trigger: trigger))
                            switch UnyriReminderCommitPolicy.action(contextIsCurrent: context.isCurrent(),
                                cancelled: cancelled.contains(request.identifier),
                                allowsReminders: UnyriNotificationPreferencesStore.shared.value.allowsReminders) {
                            case .keep: break
                            case .discard:
                                center.removePendingNotificationRequests(withIdentifiers: [request.identifier])
                            case .pause:
                                // OFF can arrive while replacing the sound.
                                // Preserve its exact plan before removal: the
                                // next pass cannot discover a deleted request.
                                if let entry = pausedEntry(request), entry.due > Date() {
                                    var entries = archive.load(owner: context.owner, server: AppConfig.storageOrigin)
                                    entries.removeAll { $0.identifier(owner: context.owner) == request.identifier }
                                    entries.append(entry)
                                    if !archive.save(entries, owner: context.owner, server: AppConfig.storageOrigin) {
                                        UnyriNotificationPreferencesStore.shared.reminderIssue = "Could not preserve paused reminders. Alerts are off; schedule reminders again when ready."
                                    }
                                }
                                center.removePendingNotificationRequests(withIdentifiers: [request.identifier])
                            }
                        }
                    } else if request.content.userInfo["kind"] as? String == "foreground_message_sound",
                              !preferences.allowsMessageSound {
                        center.removePendingNotificationRequests(withIdentifiers: [request.identifier])
                    }
                }
                if preferences.allowsReminders && token == revision && context.isCurrent() {
                    let planned = archive.load(owner: context.owner, server: AppConfig.storageOrigin)
                    var occupied = Set(requests.map(\.identifier))
                    for entry in planned {
                        if token != revision || !context.isCurrent() { break }
                        let id = entry.identifier(owner: context.owner)
                        if !occupied.contains(id) && !cancelled.contains(id) && entry.due > Date() && occupied.count < 64 {
                            let content = UNMutableNotificationContent()
                            content.title = UnyriL10n.string("Remind me")
                            content.body = UnyriL10n.string("Return to the message you marked")
                            content.sound = preferences.allowsReminderSound ? .default : nil
                            content.userInfo = ["kind": "chat_reminder", "owner": context.owner, "chat": entry.chat,
                                                "message": entry.message, "scheduled_at_ms": entry.due.timeIntervalSince1970 * 1000]
                            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, entry.due.timeIntervalSinceNow), repeats: false)
                            do {
                                try await center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
                                if !context.isCurrent() || cancelled.contains(id) ||
                                    !UnyriNotificationPreferencesStore.shared.value.allowsReminders {
                                    center.removePendingNotificationRequests(withIdentifiers: [id])
                                } else { occupied.insert(id) }
                            } catch { continue }
                        }
                        if occupied.contains(id) && context.isCurrent() {
                            let remaining = archive.load(owner: context.owner, server: AppConfig.storageOrigin)
                                .filter { $0.identifier(owner: context.owner) != id }
                            _ = archive.save(remaining, owner: context.owner, server: AppConfig.storageOrigin)
                        }
                    }
                }
                let delivered = await center.deliveredNotifications()
                if token != revision { continue }
                guard context.isCurrent() else { task = nil; return }
                let hidden = delivered.filter {
                    let kind = $0.request.content.userInfo["kind"] as? String
                    return kind == "chat_reminder" && !preferences.allowsReminders ||
                        kind == "foreground_message_sound" && !preferences.allowsMessages
                }.map { $0.request.identifier }
                center.removeDeliveredNotifications(withIdentifiers: hidden)
                guard token == revision else { continue }
                cancelled.removeAll()
                task = nil
                return
            }
        }
    }

    private static func pausedEntry(_ request: UNNotificationRequest) -> UnyriPausedReminder? {
        guard let chat = request.content.userInfo["chat"] as? String,
              let message = request.content.userInfo["message"] as? String else { return nil }
        let due = (request.content.userInfo["scheduled_at_ms"] as? NSNumber)
            .map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } ?? nextDate(request.trigger)
        guard let due else { return nil }
        let entry = UnyriPausedReminder(chat: chat, message: message, due: due)
        return entry.valid ? entry : nil
    }

    private static func nextDate(_ trigger: UNNotificationTrigger?) -> Date? {
        if let interval = trigger as? UNTimeIntervalNotificationTrigger { return interval.nextTriggerDate() }
        if let calendar = trigger as? UNCalendarNotificationTrigger { return calendar.nextTriggerDate() }
        return nil
    }
}

/// Finite foreground feedback. No local notification, audio-session mutation or
/// background task; the bundled system sound respects the iPhone silent switch.
@MainActor
enum UnyriForegroundMessageSound {
    private static var context: (owner: String, generation: UInt64, canPlay: () -> Bool)?
    private static var chime: SystemSoundID = 0

    static func resetForLogout() {
        context = nil
        if chime != 0 { AudioServicesDisposeSystemSoundID(chime); chime = 0 }
    }
    static func bind(owner: String, generation: UInt64, canPlay: @escaping () -> Bool) {
        context = (owner, generation, canPlay)
    }
    static func permitsPresentation(owner: String?, generation: UInt64?) -> Bool {
        guard let context, owner == context.owner, generation == context.generation else { return false }
        return UIApplication.shared.applicationState == .active && context.canPlay()
    }
    static func present(owner: String, generation: UInt64, chatId: String, messageId: String, stillCurrent: @escaping () -> Bool) {
        guard UnyriNotificationPreferencesStore.shared.value.allowsInAppSound,
              permitsPresentation(owner: owner, generation: generation), stillCurrent() else { return }
        play()
    }
    static func presentInactive(stillCurrent: () -> Bool) {
        guard let context, permitsPresentation(owner: context.owner, generation: context.generation), stillCurrent() else { return }
        play()
    }
    private static func play() {
        if chime == 0, let url = Bundle.main.url(forResource: "unyri_message", withExtension: "caf") {
            AudioServicesCreateSystemSoundID(url as CFURL, &chime)
        }
        if chime != 0 { AudioServicesPlaySystemSound(chime) }
    }
}
