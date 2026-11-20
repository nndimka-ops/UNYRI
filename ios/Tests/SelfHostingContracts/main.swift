import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ name: String) {
    checks += 1
    if !condition() { fatalError("SH-3 contract failed: " + name) }
}
let server = "https://example.test"
for (language, file) in [
    ("ru", "SELF_HOSTING.md"), ("ru-RU", "SELF_HOSTING.md"),
    ("uk", "SELF_HOSTING_UK.md"), ("uk_UA", "SELF_HOSTING_UK.md"),
    ("en", "SELF_HOSTING_EN.md"), ("de", "SELF_HOSTING_EN.md"), ("", "SELF_HOSTING_EN.md")
] {
    let url = SelfHostingGuidePolicy.guideURL(language: language)
    check(url.scheme == "https" && url.host == "github.com", "official HTTPS guide host")
    check(url.path == "/nndimka-ops/UNYRI/blob/main/docs/" + file, "guide language routing")
    check(url.absoluteString.hasPrefix(SelfHostingGuidePolicy.repositoryURL.absoluteString + "/"), "guide uses the official repository constant")
}
check(SelfHostingGuidePolicy.releasesURL.absoluteString == SelfHostingGuidePolicy.repositoryURL.absoluteString + "/releases", "official downloads destination")
let link = "https://example.test/#server=https%3A%2F%2Fexample.test&invite_code=opaque.secret"
let parsed = SelfHostingPolicy.parseInvite(link)
check(parsed?.origin == server && parsed?.code == "opaque.secret", "fragment import")
check(parsed?.description == "ServerInvite(redacted)", "redacted description")
check(SelfHostingPolicy.inviteLink(origin: server, code: "opaque.secret") == link, "fragment export")
check(SelfHostingPolicy.parseInvite("unyri://server/#server=https%3A%2F%2Fexample.test&invite_code=opaque.secret") == parsed, "registered custom scheme")
check(SelfHostingPolicy.parseInvite(link.replacingOccurrences(of: "example.test/#", with: "EXAMPLE.test:443/#")) == parsed, "canonical carrier")
for invalid in [
    "https://example.test/?server=https%3A%2F%2Fexample.test&invite_code=opaque.secret",
    link.replacingOccurrences(of: "/#", with: "/?invite_code=leak#"),
    link.replacingOccurrences(of: "https://example.test/", with: "https://other.test/"),
    link.replacingOccurrences(of: "https://example.test/", with: "http://example.test/"),
    link.replacingOccurrences(of: "https://example.test/", with: "https://user@example.test/"),
    link.replacingOccurrences(of: "/#", with: "/join#"),
    link + "&invite_code=second", link + "&extra=value", link + "%", link + "%0A",
    link.replacingOccurrences(of: "opaque.secret", with: ""),
    link.replacingOccurrences(of: "opaque.secret", with: "%2525"),
    link.replacingOccurrences(of: "opaque.secret", with: "a+b"),
    link.replacingOccurrences(of: "opaque.secret", with: "a%26b"),
    link.replacingOccurrences(of: "https%3A%2F%2Fexample.test", with: "example.test"),
    link.replacingOccurrences(of: "https%3A%2F%2Fexample.test", with: "https%3A%2F%2Fexample.test%2Fpath"),
    link.replacingOccurrences(of: "https%3A%2F%2Fexample.test", with: "https%3A%2F%2Fexample.test%3Fsecret%3Dx"),
    "unyri://user/#server=https%3A%2F%2Fexample.test&invite_code=x",
    "unyri://server:123/#server=https%3A%2F%2Fexample.test&invite_code=x",
    " " + link, link + " ", link + String(repeating: "a", count: 4096)
] { check(SelfHostingPolicy.parseInvite(invalid) == nil, "reject malformed or credential-leaking link") }
let local = "http://localhost:18080/#server=http%3A%2F%2Flocalhost%3A18080&invite_code=x"
check(SelfHostingPolicy.parseInvite(local) == nil, "release HTTPS only")
check(SelfHostingPolicy.parseInvite(local, allowLocalHTTP: true)?.origin == "http://localhost:18080", "debug loopback")
for origin in ["http://example.test", "https://u:p@example.test", "https://example.test/path", "https://127.1", "https://0127.0.0.1", "https://example.test:0", "https://example.test:65536", "https://example.test/#x", "https://example.test/?x=y"] {
    check(SelfHostingPolicy.origin(origin, allowLocalHTTP: true) == nil, "strict origin")
}
check(SelfHostingPolicy.origin("http://192.168.1.20:8080", allowLocalHTTP: true) != nil, "existing debug private LAN allowance")
check(SelfHostingPolicy.origin("https://example.test:443/") == server, "canonical default port")
check(SelfHostingPolicy.ownerCode(" abcd-efgh-jklm-npqr\n") == "ABCDEFGHJKLMNPQR", "case and separators")
check(SelfHostingPolicy.ownerInput("abcd efgh-jklm-npqrst") == "ABCDEFGHJKLMNPQR", "plain input bounded to sixteen")
for raw in ["", "ABCD", "ABCDEFGHJKLMNPQRS", "ABCD-EFGH-JKLM-NPQ0", "ABCD-EFGH-JKLM-NPQ1", "ABCD-EFGH-JKLM-NPQI", "ABCD-EFGH-JKLM-NPQО", String(repeating: " ", count: 65)] {
    check(SelfHostingPolicy.ownerCode(raw) == nil, "invalid owner code")
}
check(SelfHostingPolicy.ownerDisplay("ABCDEFGHJKLMNPQR") == "ABCD-EFGH-JKLM-NPQR", "display groups only")
for count in 0...16 {
    let raw = String("ABCDEFGHJKLMNPQR".prefix(count))
    let shown = SelfHostingPolicy.ownerDisplay(raw)
    for offset in 0...count {
        let visual = SelfHostingPolicy.displayOffset(offset, count: count)
        check(visual <= shown.count, "display offset bounded")
        check(SelfHostingPolicy.plainOffset(visual, count: count) == offset, "cursor round trip, including fifth character")
    }
    for offset in 0...shown.count {
        check((0...count).contains(SelfHostingPolicy.plainOffset(offset, count: count)), "all visual offsets bounded")
    }
}
check(SelfHostingPolicy.displayOffset(4, count: 16) == 4, "caret before first separator")
check(SelfHostingPolicy.displayOffset(5, count: 16) == 6, "fifth character visual offset")
check(SelfHostingPolicy.plainOffset(5, count: 16) == 4, "separator maps to plain boundary")
check(!SelfHostingPolicy.inviteVisible(nil), "unknown policy hides field")
for required in [nil, false, true] as [Bool?] {
    for mode in [nil, "open", "invite_only"] as [String?] {
        for open in [nil, false, true] as [Bool?] {
            let policy = RegistrationPolicy(registrationOpen: open, registrationMode: mode, inviteRequired: required)
            check(SelfHostingPolicy.inviteVisible(policy) == (required == true), "visibility follows explicit flag only")
            check(policy.requiresInvite == (required == true), "registration payload follows same rule")
        }
    }
}
let expected: [String: String] = [
    "invite_required": "An invitation code is required.",
    "invite_invalid": "The invitation code is invalid or no longer available.",
    "conflict": "Registration is temporarily closed by the server operator.",
    "registration_closed": "Registration is temporarily closed by the server operator.",
    "setup_invalid": "The owner code is incorrect. Check all 16 characters.",
    "rate_limited": "Too many attempts. Wait before trying again.",
    "setup_not_available": "Owner setup is no longer available on this server."
]
for (code, key) in expected { check(SelfHostingPolicy.refusalKey(code) == key, "localized refusal mapping") }
for code in ["invite_required", "invite_invalid", "conflict", "registration_closed"] { check(SelfHostingPolicy.staleRegistration(code), "stale form refresh") }
check(SelfHostingPolicy.refusalKey("unknown") == nil, "unknown refusal")
check(!SelfHostingPolicy.staleRegistration("username_taken"), "unrelated error keeps policy")
print("SelfHostingContracts: \(checks) checks passed")
