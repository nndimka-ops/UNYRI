import Foundation

precondition(Validation.bioError(String(repeating: "я", count: 160)) == nil)
precondition(Validation.bioError(String(repeating: "я", count: 161)) != nil)
precondition(Validation.bioError(" \n" + String(repeating: "a", count: 160) + "\n ") == nil)
// Rust counts Unicode scalars, not Swift extended grapheme clusters.
precondition(Validation.bioError(String(repeating: "👨‍👩‍👧‍👦", count: 24)) != nil)
precondition(Validation.displayNameError(String(repeating: "👨‍👩‍👧‍👦", count: 10)) != nil)

func json(_ bio: Patch<String>) throws -> [String: Any] {
    let request = UpdateProfileRequest(username: "tester", displayName: "Test", bio: bio, avatarUrl: .absent)
    return try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as! [String: Any]
}
let unchanged = try json(.absent)
precondition(unchanged["bio"] == nil && unchanged["avatarUrl"] == nil)
let cleared = try json(.clear)
let updated = try json(.set("About me"))
precondition(cleared["bio"] is NSNull)
precondition(updated["bio"] as? String == "About me")

// App version formatting (Phase 6 §20/§21): real metadata, safe fallbacks.
precondition(AppVersion.display(shortVersion: "0.1.0", build: "42") == "Version 0.1.0 (42)")
precondition(AppVersion.display(shortVersion: nil, build: nil) == "Version 0.0.0 (0)")
precondition(AppVersion.display(shortVersion: "", build: "") == "Version 0.0.0 (0)")

// Root navigation policy (Phase 8 §50/§51).
precondition(RootNavigation.defaultShowCalls == true, "Show Calls defaults ON")
precondition(RootNavigation.visibleDestinations(showCalls: true) == [.chats, .calls, .rooms, .search, .settings], "calls on includes rooms")
precondition(RootNavigation.visibleDestinations(showCalls: false) == [.chats, .rooms, .search, .settings], "calls off retains rooms")
precondition(RootNavigation.fallbackSelected(.calls, showCalls: false) == .chats, "hidden calls falls back to chats")
precondition(RootNavigation.fallbackSelected(.search, showCalls: false) == .search, "other selection preserved")
precondition(RootNavigation.fallbackSelected(.settings, showCalls: false) == .settings, "settings preserved")

print("Profile validation and patch contracts passed")
