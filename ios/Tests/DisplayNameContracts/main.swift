import Foundation

// The server's shared vectors (server/src/display_name.rs, client_config.rs).
// Sanitizing/reserved-name vectors stay shared; iOS edits are now limited to 32 scalars.
var checks = 0
func check(_ ok: Bool, _ why: String) { precondition(ok, why); checks += 1 }
func same(_ a: String, _ b: String) -> Bool { a.unicodeScalars.elementsEqual(b.unicodeScalars) }

let sanitized: [(String, String)] = [
    ("  Bob   Smith  ", "Bob Smith"),
    ("Bob\u{00A0}\u{2003}Smith", "Bob Smith"),
    ("\u{202E}nhoJ", "nhoJ"),
    ("Al\u{200B}ice", "Alice"),
    ("\u{2066}Eve\u{2069}", "Eve"),
    ("A\u{200D}B", "AB"),
    ("a\u{200C}b", "ab"),
    ("A\u{E0041}", "A"),
    ("\u{FE0F}Bob", "Bob"),
    ("e\u{0301}", "\u{00E9}"),
    ("\u{F8FF}Apple", "Apple"),
    ("a \u{0301} b", "a b"),
    ("Tab\tand\nline", "Tab and line"),
]
for (raw, expected) in sanitized {
    check(same(DisplayNamePolicy.sanitize(raw), expected), "sanitize \(raw.debugDescription)")
}
for unchanged in [
    "👨\u{200D}👩\u{200D}👧",
    "🏳\u{FE0F}\u{200D}🌈",
    "می\u{200C}خواهم",
    "ශ්\u{200D}රී",
    "🏴\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}",
] {
    check(same(DisplayNamePolicy.sanitize(unchanged), unchanged), "unchanged \(unchanged.debugDescription)")
}
check(DisplayNamePolicy.sanitize("e\u{0301}").unicodeScalars.count == 1, "NFC composes")
let stacked = DisplayNamePolicy.sanitize("Z" + String(repeating: "\u{0301}", count: 10))
check(stacked.unicodeScalars.count == 4 && stacked.unicodeScalars.first == "\u{0179}", "at most 4 marks, then NFC")

for invalid in ["\u{2800}\u{2800}", "\u{3164}", "\u{0301}", "   ", "", String(repeating: "a", count: 33)] {
    check(!DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize(invalid)), "invalid \(invalid.debugDescription)")
}
check(DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize(String(repeating: "a", count: 32) + "\u{200B}")), "32 visible scalars")
check(Validation.displayNameError(String(repeating: "👨‍👩‍👧‍👦", count: 10)) != nil, "scalars, not graphemes")
check(Validation.displayNameError("  Bob  ") == nil, "plain name")

for name in [
    "UNYRI", "unyri", "Unyri Support", "Поддержка UNYRI", "UNYRI Поддержка", "U N Y R I", "U.N.Y.R.I",
    "u_n_y_r_i", "ＵＮＹＲＩ", "𝐔𝐍𝐘𝐑𝐈", "ᴜɴʏʀɪ", "UNYRİ", "Ünyrí", "UNУRI", "UИYRI", "UNYR1", "UNYRl",
    "unyri.app", "Юнири", "Support", "Поддержка", "Служба поддержки", "Пoддepжкa", "Admin", "official",
] {
    check(DisplayNamePolicy.isReserved(name), "reserved \(name)")
}
for name in ["Unity", "Yuri", "Sunny Rick", "Bunny Rich", "Support Team", "Dmytro", "Мария", "Администратор Петя"] {
    check(!DisplayNamePolicy.isReserved(name), "allowed \(name)")
}
check(DisplayNamePolicy.key("UNYRI") == "unyri" && DisplayNamePolicy.key("UИYRI") == "unyri", "skeleton")
check(DisplayNamePolicy.key("official") == "officiai", "l folds to i")
check(DisplayNamePolicy.error(for: "UNYRI Support", official: false) == DisplayNamePolicy.reservedError, "reserved error")
check(DisplayNamePolicy.error(for: "UNYRI Support", official: true) == nil, "officials may")
check(DisplayNamePolicy.error(for: "\u{2800}", official: true) == DisplayNamePolicy.lengthError, "length error first")
check(DisplayNamePolicy.removesHiddenCharacters("Al\u{200B}ice"), "hidden character noticed")
check(!DisplayNamePolicy.removesHiddenCharacters("  Bob   Smith "), "spaces alone are not hidden characters")

check(DisplayNamePolicy.maximumScalars == 32, "one iOS limit for registration, profile and counters")
check(Validation.displayNameError(String(repeating: "Я", count: 32)) == nil, "32 Cyrillic characters")
check(Validation.displayNameError(String(repeating: "Я", count: 33)) == DisplayNamePolicy.lengthError, "33 Cyrillic characters")
let legacy = String(repeating: "A", count: 64)
check(DisplayNamePolicy.profileError(for: legacy, original: legacy) == DisplayNamePolicy.lengthError,
      "An untouched legacy name is displayed intact but cannot bypass the save limit")
check(legacy.count == 64, "reading/sizing never clips the existing value")
check(DisplayNamePolicy.profileError(for: String(repeating: "A", count: 32), original: legacy) == nil,
      "shortening a legacy name allows saving")
check(DisplayNamePolicy.profileError(for: "UNYRI", original: "UNYRI") == nil,
      "unchanged reserved-name ownership still belongs to the server")
check(AppVersion.localizedDisplay(shortVersion: "0.4.17", build: "62") == "Version 0.4.17 (62)", "drawer uses Settings version/build format")

// Version policy (server client_config::parse_version / compare_versions).
check(AppVersion.components("0.4.13") == [0, 4, 13, 0], "pads to 4")
check(AppVersion.components("1.2.3.4") == [1, 2, 3, 4], "4 parts")
check(AppVersion.components("0.2.1-alpha.1") == [0, 2, 1, 0], "suffix ignored")
for bad in ["", "1..2", "v1", "1.2.3.4.5", "1234567.0", "1.0-", "1.0 beta", "1.0-" + String(repeating: "a", count: 33)] {
    check(AppVersion.components(bad) == nil, "invalid version \(bad.debugDescription)")
}
check(AppVersion.compare("0.4.13", "0.4.9") == .orderedDescending, "numeric order")
check(AppVersion.compare("1.0", "1.0.0") == .orderedSame, "padding")
check(AppVersion.compare("0.2.1-alpha", "0.2.1") == .orderedSame, "suffix never orders")
check(AppVersion.compare("garbage", "1.0") == nil, "nil never blocks")
check(AppVersion.trustedUpdateURL("https://apps.apple.com/app/id123") != nil, "App Store")
check(AppVersion.trustedUpdateURL("https://testflight.apple.com/join/abc") != nil, "TestFlight")
for untrusted in ["http://apps.apple.com/app/id1", "https://apps.apple.com.evil.example/x", "https://user@apps.apple.com/x",
                  "https://apps.apple.com:8443/x", "https://example.com/update", nil] as [String?] {
    check(AppVersion.trustedUpdateURL(untrusted) == nil, "untrusted \(untrusted ?? "nil")")
}

print("Display-name and version contracts: \(checks) passed")
