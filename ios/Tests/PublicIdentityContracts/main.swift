import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ label: String) {
    checks += 1
    if !condition() { fatalError("Public identity contract failed: " + label) }
}
let validNumbers = ["1", "99999999", "100000000", "999999999"]
for value in validNumbers { check(PublicIdentityPolicy.number(value) == value, "valid number") }
for value in ["", "0", "01", "1234567890", "-1", "+1", "1e8", "１２３", "١٢٣", "123 456 789", " 123"] {
    check(PublicIdentityPolicy.number(value) == nil, "reject number")
}
check(PublicIdentityPolicy.formattedNumber("123456789") == "123 456 789", "display grouping")
check(PublicIdentityPolicy.formattedNumber("12") == "12", "short alias display")
check(PublicIdentityPolicy.input(" 123 456 789 ") == .number("123456789"), "field grouping")
check(PublicIdentityPolicy.input("@Alice_1") == .username("alice_1"), "handle input")
check(PublicIdentityPolicy.input("alice_1") == nil, "never guess private login")
for value in ["abcd", "1alice", "a__bc", "alice.name", "@alice", "Alice ", "аlice", String(repeating: "a", count: 33)] {
    check(PublicIdentityPolicy.username(value) == nil, "reject handle")
}
check(PublicIdentityPolicy.username("Alice_1") == "alice_1", "canonical handle")
check(PublicIdentityPolicy.username(String(repeating: "a", count: 32)) != nil, "32-byte handle")
let recoveryFixture = "R1-" + Array(repeating: "00000", count: 8).joined(separator: "-")
check(PublicIdentityPolicy.isRecoveryCode(recoveryFixture), "bounded recovery envelope")
check(!PublicIdentityPolicy.isRecoveryCode(recoveryFixture + "0"), "reject oversized recovery envelope")
check(!PublicIdentityPolicy.isRecoveryCode(recoveryFixture.replacingOccurrences(of: "00000", with: "zzzzz")), "reject malformed recovery envelope")

let origins: [(String, String)] = [
    ("Chat.Example:8443", "https://chat.example:8443"),
    ("https://CHAT.example:443/", "https://chat.example"),
    ("http://127.0.0.1:80", "http://127.0.0.1"),
    ("[2001:0db8:0:0:0:0:0:1]:8443", "https://[2001:db8::1]:8443"),
    ("https://bücher.example", "https://xn--bcher-kva.example")
]
for (raw, expected) in origins { check(PublicIdentityPolicy.origin(raw) == expected, "origin canonicalization") }
for value in ["", "chat.example:", "chat.example:0", "chat.example:65536", "chat.example:-1", "chat.example:+1",
              "https://@chat.example", "https://user:pass@chat.example", "ftp://chat.example", "https://chat.example/path",
              "https://chat.example//", "chat.example?x=1", "chat.example#x", "chat.example\\evil", "chat.example%2F",
              "chat.example\n", "2130706433", "127.1", "0177.0.0.1", "0x7f.0.0.1", "[fe80::1%en0]", "[:::1]", "https://"] {
    check(PublicIdentityPolicy.origin(value) == nil, "reject unsafe origin")
}
for (raw, expected) in origins {
    let link = PublicIdentityPolicy.link(number: "123456789", origin: raw)!
    check(PublicIdentityPolicy.parse(link) == .init(origin: expected, target: .number("123456789")), "round-trip link")
}
let prefix = "unyri://u/n/123456789?server="
check(PublicIdentityPolicy.parse("UNYRI://U/@Alice_1?server=chat.example") == .init(origin: "https://chat.example", target: .username("alice_1")), "case and handle normalization")
check(PublicIdentityPolicy.parse("unyri://u/n/1?server=chat.example")?.target == .number("1"), "vanity alias")
check(PublicIdentityPolicy.parse(prefix + "http%3A%2F%2F127.0.0.1%3A8080")?.origin == "http://127.0.0.1:8080", "HTTP preserved")
for value in ["unyri://user/alice", "unyri://u/n/123456789", "unyri://u:123/n/123456789?server=chat.example",
              "unyri://x@u/n/123456789?server=chat.example", "unyri://u/N/123456789?server=chat.example",
              "unyri://u/n/123456789/?server=chat.example", "unyri://u/n/%31?server=chat.example",
              "unyri://u/@alice%5F1?server=chat.example", "unyri://u/n/0123?server=chat.example",
              prefix + "chat.example&server=evil.example", prefix + "chat.example&x=1", prefix + "chat.example#x",
              prefix + "chat%252Eexample", prefix + "chat%2Eexample%", prefix + "%FF", prefix + "chat%+1example",
              prefix + "chat.example+evil", prefix + "user%40chat.example", prefix + "%5Cevil", prefix + "%00evil",
              prefix + String(repeating: "a", count: 4096)] {
    check(PublicIdentityPolicy.parse(value) == nil, "strict malformed link rejection")
}
check(PublicIdentityPolicy.parse(prefix + "chat.example:8443") != PublicIdentityPolicy.parse(prefix + "chat.example:9443"), "ports are separate identities")
check(PublicIdentityPolicy.parse(prefix + "http%3A%2F%2Fchat.example") != PublicIdentityPolicy.parse(prefix + "chat.example"), "transport is separate identity")

let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
func object(_ patch: Patch<String>) throws -> [String: Any] {
    let bytes = try encoder.encode(IdentityUpdate(expectedRevision: 7, publicUsername: patch))
    return try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
}
let absent = try object(.absent), clear = try object(.clear), set = try object(.set("Alice_1"))
check(absent["public_username"] == nil, "omission preserves handle")
check(clear["public_username"] is NSNull, "null removes handle")
check(set["public_username"] as? String == "Alice_1", "server validates claim")
check(absent["expected_revision"] as? Int == 7, "optimistic revision")
let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
let profile = try decoder.decode(DiscoveredProfile.self, from: Data("""
{"id":"00000000-0000-0000-0000-000000000001","unyri_number":"123456789","public_username":null,"display_name":"Test","bio":null,"avatar_url":null,"has_avatar":false,"profile_version":1,"avatar_version":0}
""".utf8))
check(profile.publicUsername == nil && profile.contact.username.isEmpty, "discovery requires no login")
check(profile.contact.publicIdentityLabel == "123456789", "authorized discovery supplies the public caption")
let oldPublicUser = try decoder.decode(PublicUser.self, from: Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"username\":\"private_fixture\",\"display_name\":\"Fixture\",\"bio\":null,\"avatar_url\":null,\"has_avatar\":false,\"profile_version\":1,\"avatar_version\":0}".utf8))
check(oldPublicUser.publicIdentityLabel.isEmpty, "legacy login is never a public caption")
check(IdentityErrorText.key(status: 409, code: "unavailable") == "Public username is unavailable", "claim conflict")
check(IdentityErrorText.key(status: 503, code: "unavailable") != "Public username is unavailable", "outage is not a taken name")
check(IdentityErrorText.key(status: 409, code: "conflict").contains("Reloaded"), "revision error")
check(IdentityErrorText.key(status: 400, code: "validation_error").contains("5–32"), "grammar error")
check(IdentityErrorText.key(status: 429, code: "rate_limited").contains("7 days"), "cooldown and quota")

// Final server 1bb7c691: registration adds only an optional invite_code.
let registration = RegisterRequest(inviteCode: "synthetic-invite", username: "fixture_login", password: "fixture-only-passphrase",
    displayName: "Fixture", installationId: "00000000-0000-0000-0000-000000000002",
    devicePlatform: "ios", deviceName: "Fixture", osVersion: "contracts", appVersion: "contracts")
let registrationJSON = try JSONSerialization.jsonObject(with: encoder.encode(registration)) as! [String: Any]
check(registrationJSON["invite_code"] as? String == "synthetic-invite", "invite request uses final wire key")
var openRegistration = registration
openRegistration.inviteCode = nil
let openJSON = try JSONSerialization.jsonObject(with: encoder.encode(openRegistration)) as! [String: Any]
check(openJSON["invite_code"] == nil, "open registration omits invite")
let invited = try decoder.decode(RegistrationPolicy.self, from: Data("{\"registration_open\":true,\"registration_mode\":\"invite_only\",\"invite_required\":true}".utf8))
check(invited.requiresInvite && invited.registrationOpen == true, "final invite-only policy")
let legacyPolicy = try decoder.decode(RegistrationPolicy.self, from: Data("{}".utf8))
check(!legacyPolicy.requiresInvite && legacyPolicy.registrationOpen == nil, "older policy remains decodable")
check(PublicIdentityPolicy.passwordStrengthKey("short").contains("8–256"), "out-of-range strength is guidance")
check(PublicIdentityPolicy.passwordStrengthKey("abcdefgh").hasSuffix("weak"), "short valid password guidance")
check(PublicIdentityPolicy.passwordStrengthKey("abcdefghijkl").hasSuffix("fair"), "longer password guidance")
check(PublicIdentityPolicy.passwordStrengthKey("a long plain passphrase").hasSuffix("strong"), "passphrase needs no forced punctuation")
let finalOwner = try decoder.decode(OwnerIdentity.self, from: Data("{\"user_id\":\"00000000-0000-0000-0000-000000000001\",\"unyri_number\":\"123456789\",\"public_username\":null,\"discoverability\":\"everyone\",\"revision\":0,\"username_changed_at\":null,\"public_origin\":\"https://chat.example:8443\"}".utf8))
check(finalOwner.link?.contains("123456789") == true && finalOwner.revision == 0, "final owner identity and origin")

let ownerProfile = try decoder.decode(UserProfile.self, from: Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"username\":\"private_fixture\",\"display_name\":\"Fixture\",\"bio\":null,\"avatar_url\":null,\"profile_version\":1,\"avatar_version\":0,\"has_avatar\":false,\"created_at\":\"2026-11-18T00:00:00Z\",\"updated_at\":\"2026-11-18T00:00:00Z\",\"unyri_number\":\"123456789\",\"public_username\":null,\"discoverability\":\"everyone\"}".utf8))
check(ownerProfile.unyriNumber == "123456789" && ownerProfile.discoverability == "everyone", "owner profile optional identity additions")


for malformedProfile in ["12345678", "012345678", "1234567890"] {
    let data = Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"unyri_number\":\"\(malformedProfile)\",\"public_username\":null,\"display_name\":\"Fixture\",\"bio\":null,\"avatar_url\":null,\"has_avatar\":false,\"profile_version\":1,\"avatar_version\":0}".utf8)
    check((try? decoder.decode(DiscoveredProfile.self, from: data)) == nil, "reject malformed canonical profile number")
}


let encodedPublicContact = try JSONSerialization.jsonObject(with: encoder.encode(profile.contact)) as! [String: Any]
check(encodedPublicContact["unyri_number"] == nil && encodedPublicContact["public_username"] == nil, "authorized peer captions are not persisted in legacy DTO caches")
print("PASS: \(checks) final public identity and auth contracts")
