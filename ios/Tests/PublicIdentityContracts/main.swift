import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ label: String, file: StaticString = #file, line: UInt = #line) {
    checks += 1
    if !condition() { fatalError("Public identity contract failed: " + label, file: file, line: line) }
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
check(PublicIdentityPolicy.contactsSearch("123 456 789") == .exact(.number("123456789")), "Contacts public number is exact")
check(PublicIdentityPolicy.contactsSearch("@Alice_1") == .exact(.username("alice_1")), "Contacts @username is exact")
check(PublicIdentityPolicy.contactsSearch("Alice") == .directory("Alice"), "display-name directory remains available")
check(PublicIdentityPolicy.contactsSearch("@ab") == .none, "partial public username sends nothing")
check(PublicIdentityPolicy.contactsSearch("@bad__name") == .invalidIdentity, "invalid handle cannot fall back to discovery")
check(PublicIdentityPolicy.contactsSearch("000000000") == .invalidIdentity, "invalid public number cannot fall back to discovery")
for suffix in ["я", "€", "🦊"] {
    let input = String(repeating: "a", count: 4095) + suffix
    let bounded = PublicIdentityPolicy.boundedContactsInput(input)
    check(bounded.utf8.count == 4095 && bounded == String(repeating: "a", count: 4095), "trim only an incomplete UTF8 suffix")
}
check(PublicIdentityPolicy.boundedContactsInput(String(repeating: "я", count: 3000)).utf8.count == 4096, "multibyte input obeys byte limit")
check(PublicIdentityPolicy.boundedContactsInput("a🦊") == "a🦊", "short Unicode input is unchanged")
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
check(PublicIdentityPolicy.parse("https://Chat.Example:8443/u/n/123456789") == .init(origin: "https://chat.example:8443", target: .number("123456789")), "HTTPS number profile link preserves origin")
check(PublicIdentityPolicy.parse("https://chat.example/u/@Alice_1") == .init(origin: "https://chat.example", target: .username("alice_1")), "HTTPS public username profile link")
for value in ["http://chat.example/u/n/123456789", "https://user:pass@chat.example/u/n/123456789",
              "https://chat.example/u/n/%31", "https://chat.example/u/n/1?server=evil.example",
              "https://chat.example/u/n/1/", "https://chat.example/u/@Alice_1#fragment"] {
    check(PublicIdentityPolicy.parse(value) == nil, "strict HTTPS profile path and origin")
}
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
check(profile.contact.publicIdentityLabel == "123 456 789", "authorized discovery supplies the grouped public caption")
let oldPublicUser = try decoder.decode(PublicUser.self, from: Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"username\":\"private_fixture\",\"display_name\":\"Fixture\",\"bio\":null,\"avatar_url\":null,\"has_avatar\":false,\"profile_version\":1,\"avatar_version\":0}".utf8))
check(oldPublicUser.publicIdentityLabel.isEmpty, "legacy login is never a public caption")
check(oldPublicUser.username.isEmpty, "legacy peer login is discarded on decode")
let cleanPeerBytes = try encoder.encode(oldPublicUser)
check(!String(decoding: cleanPeerBytes, as: UTF8.self).contains("private_fixture"), "contact/directory JSON never persists peer login")
let legacyDirectoryBytes = Data("{\"scope\":\"fixture\",\"users\":[]}".utf8)
let legacyDirectory = try decoder.decode(DirectoryIdentityCache.self, from: legacyDirectoryBytes)
check(legacyDirectory.peerPrivacyVersion == nil, "pre-B-033 directory cache requires one-time scrub")
let directoryFile = FileManager.default.temporaryDirectory.appendingPathComponent("unyri-b033-directory-\(UUID().uuidString).json")
defer { try? FileManager.default.removeItem(at: directoryFile) }
let oldDirectoryPeer: [String: Any] = ["id": oldPublicUser.id, "username": "private_fixture", "displayName": "Fixture",
    "profileVersion": 1, "avatarVersion": 0, "hasAvatar": false, "publicUsername": "public_fixture", "unyriNumber": "123456789"]
try JSONSerialization.data(withJSONObject: ["scope": "fixture", "users": [oldDirectoryPeer]]).write(to: directoryFile)
let cleanedDirectory = DirectoryCachePolicy.load(file: directoryFile, scope: "fixture", query: "")
check(cleanedDirectory.first?.username.isEmpty == true && cleanedDirectory.first?.publicUsername == "public_fixture",
      "directory upgrade preserves public identity and discards login")
let cleanedDirectoryBytes = try Data(contentsOf: directoryFile)
check(!String(decoding: cleanedDirectoryBytes, as: UTF8.self).contains("private_fixture"), "directory upgrade removes login bytes from disk")
check(DirectoryCachePolicy.load(file: directoryFile, scope: "fixture", query: "") == cleanedDirectory, "directory upgrade is idempotent")
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
check(ownerProfile.username == "private_fixture", "own authentication/settings profile retains private login")


for malformedProfile in ["12345678", "012345678", "1234567890"] {
    let data = Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"unyri_number\":\"\(malformedProfile)\",\"public_username\":null,\"display_name\":\"Fixture\",\"bio\":null,\"avatar_url\":null,\"has_avatar\":false,\"profile_version\":1,\"avatar_version\":0}".utf8)
    check((try? decoder.decode(DiscoveredProfile.self, from: data)) == nil, "reject malformed canonical profile number")
}


let encodedPublicContact = try JSONSerialization.jsonObject(with: encoder.encode(profile.contact)) as! [String: Any]
check(encodedPublicContact["unyri_number"] as? String == "123456789", "public identity survives peer DTO caches")
// B-048: common passwords are refused while typed; the set equals the server denylist.
for common in ["password123", "12345678", "Password1", "  QWERTY123 ", "пароль123"] {
    check(PublicIdentityPolicy.isCommonPassword(common), "common password refused: " + common)
}
check(!PublicIdentityPolicy.isCommonPassword("river-lamp-42"), "an ordinary password passes")
if let serverList = try? String(contentsOfFile: "server/src/auth/common_passwords.txt", encoding: .utf8) {
    let server = Set(serverList.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    check(server.count >= 30 && server == PublicIdentityPolicy.commonPasswords, "common passwords equal the server denylist")
} else {
    check(false, "server denylist readable (run from the repository root)")
}
// B-051 (owner, 19 Nov): a Contacts row always names the grouped public number, then the
// public @username when one is set; legacy servers expose neither.
check(PeerIdentityPolicy.contactCaption(publicUsername: nil, unyriNumber: "604743993") == "604 743 993", "B-051: contact without @username shows the grouped number")
check(PeerIdentityPolicy.contactCaption(publicUsername: "alice_7", unyriNumber: " 604743993 ") == "604 743 993 · @alice_7", "B-051: number first, then the public @username")
check(PeerIdentityPolicy.contactCaption(publicUsername: "@alice_7", unyriNumber: nil) == "@alice_7", "B-051: only the @username when no number arrived")
check(PeerIdentityPolicy.contactCaption(publicUsername: "", unyriNumber: "") == "", "B-051: nothing public, nothing shown")
check(PeerIdentityPolicy.contactCaption(publicUsername: "alice_7", unyriNumber: "604743993", legacyServer: true) == "", "B-051: legacy servers expose no public identity")
print("PASS: \(checks) final public identity and auth contracts")
