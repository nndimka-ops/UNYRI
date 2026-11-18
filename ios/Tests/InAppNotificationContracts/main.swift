import Foundation

var preferences = UnyriNotificationPreferences()
func decision(foreground: Bool = true, event: InAppNotificationPolicy.Event = .message,
              sameChat: Bool = false, muted: Bool = false, own: Bool = false) -> InAppNotificationPolicy.Decision {
    InAppNotificationPolicy.decide(foreground: foreground, event: event, sameChat: sameChat,
                                   muted: muted, ownMessage: own, preferences: preferences)
}
let all = InAppNotificationPolicy.Decision(banner: true, sound: true, vibration: true)
precondition(decision() == all, "Another chat / chat list / settings: in-app presentation")
precondition(decision(sameChat: true) == .silent, "Visible conversation: message only")
precondition(decision(muted: true) == .silent)
precondition(decision(own: true) == .silent)
precondition(decision(event: .call) == .silent, "Call UI has its own presentation")
precondition(decision(foreground: false) == .silent, "Background leaves system push unchanged")
preferences.inAppBanners = false
precondition(decision() == .init(banner: false, sound: true, vibration: true), "Feedback does not depend on banner visibility")
preferences.inAppSounds = false
precondition(decision() == .init(banner: false, sound: false, vibration: true))
preferences.inAppVibration = false
precondition(decision() == .silent)
preferences = UnyriNotificationPreferences()
preferences.sounds = false
precondition(decision() == all && !preferences.allowsMessageSound, "In-app sound and background notification sound are independent")
preferences.messages = false
precondition(decision() == .silent)
preferences.messages = true; preferences.enabled = false
precondition(decision() == .silent)

let legacy = Data(#"{"enabled":true,"messages":true,"reminders":false,"sounds":false,"previews":false}"#.utf8)
let migrated = try JSONDecoder().decode(UnyriNotificationPreferences.self, from: legacy)
precondition(migrated.inAppBanners && migrated.inAppSounds && migrated.inAppVibration)
precondition(!migrated.reminders && !migrated.sounds && !migrated.previews, "Migration preserves existing off choices")
var roundtrip = migrated
roundtrip.inAppBanners = false; roundtrip.inAppSounds = false; roundtrip.inAppVibration = false
let restored = try JSONDecoder().decode(UnyriNotificationPreferences.self, from: JSONEncoder().encode(roundtrip))
precondition(restored == roundtrip)
print("In-app notification decision and preference migration contracts passed")
