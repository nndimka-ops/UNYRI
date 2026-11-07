import Foundation

var checks = 0
func expect(_ value: @autoclosure () throws -> Bool, _ message: String) rethrows {
    let result = try value(); precondition(result, message); checks += 1
}
let server = URL(string: "https://coco.example")!
let room = UUID(uuidString: "12345678-1234-4234-8234-123456789abc")!
let secret = ConferenceInvite.encode(Data(0..<32))
let key = ConferenceInvite.encode(Data(32..<64))
let invite = ConferenceInvite(server: server, roomID: room, secret: secret, mediaKey: key)
let link = invite.legacyLink.absoluteString
try expect(ConferenceInvite.parse(invite.link.absoluteString, currentServer: server) == invite, "new compact invitation round trip")
expect(invite.link.absoluteString.hasPrefix("unyri://c#"), "new public producers use UNYRI compact v2")
try expect(ConferenceInvite.parse(link, currentServer: server) == invite, "complete invitation round trip")
try expect(ConferenceInvite.decodeKey(secret) == Data(0..<32), "canonical 32-byte secret")
try expect(ConferenceInvite.origin(URL(string: "https://COCO.example:443/")!) == server, "canonical origin")

func rejects(_ text: String, server selected: URL = server) {
    do { _ = try ConferenceInvite.parse(text, currentServer: selected); preconditionFailure("invalid invitation accepted") }
    catch { checks += 1 }
}
rejects(link, server: URL(string: "https://other.example")!)
rejects(link.replacingOccurrences(of: "https", with: "http"))
rejects(link.replacingOccurrences(of: "coco://conference?", with: "coco://conference/path?"))
rejects(link.replacingOccurrences(of: "coco://conference?", with: "https://conference?"))
rejects(link.replacingOccurrences(of: "coco://conference?", with: "coco://user@conference?"))
rejects(link.replacingOccurrences(of: "coco://conference?", with: "coco://conference:443?"))
rejects(link.replacingOccurrences(of: "#v=1", with: "&k=\(key)#v=1"))
rejects(link.replacingOccurrences(of: "#v=1", with: "&server=https%3A%2F%2Fcoco.example#v=1"))
rejects(link.replacingOccurrences(of: "#v=1", with: "#v=2"))
rejects(link + "&v=1")
rejects(link + "&extra=value")
rejects(link + "&i=\(secret)")
rejects(link + "&k=\(key)")
rejects(link.components(separatedBy: "#").first!)
rejects(link.replacingOccurrences(of: key, with: secret))
rejects(link.replacingOccurrences(of: key, with: key + "="))
rejects(link.replacingOccurrences(of: key, with: String(key.dropLast())))
rejects(link.replacingOccurrences(of: key, with: String(key.dropLast()) + "+"))
// Non-zero pad bits must not create a second spelling of the same key.
let last = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
let index = last.firstIndex(of: key.last!)!
rejects(link.replacingOccurrences(of: key, with: String(key.dropLast()) + String(last[index + 1])))
rejects(String(repeating: "x", count: 2049))
for origin in ["http://coco.example", "https://user:password@coco.example", "https://coco.example/path", "https://coco.example?x=y", "https://coco.example#x"] {
    var parts = URLComponents(url: invite.link, resolvingAgainstBaseURL: false)!
    parts.queryItems = [URLQueryItem(name: "server", value: origin), URLQueryItem(name: "room", value: room.uuidString)]
    rejects(parts.url!.absoluteString)
}
for count in 0...30 {
    let values = Array(0..<count)
    for landscape in [false, true] {
        let pages = ConferenceGridPolicy.pages(values, landscape: landscape)
        expect(pages.flatMap { $0 } == values, "stable order with every participant reachable")
        expect(pages.allSatisfy { $0.count <= (landscape ? 9 : 6) }, "bounded render page")
        let accessible = ConferenceGridPolicy.pages(values, landscape: landscape, accessibility: true)
        expect(accessible.flatMap { $0 } == values, "accessible pages retain every stable participant")
        expect(accessible.allSatisfy { $0.count <= (landscape ? 3 : 2) }, "large text leaves room for prominent leave controls")
    }
}
#if canImport(CryptoKit)
for _ in 0..<10 {
    let credentials = ConferenceInvite.randomCredentials()
    try expect(ConferenceInvite.decodeKey(credentials.secret).count == 32, "random secret length")
    try expect(ConferenceInvite.decodeKey(credentials.mediaKey).count == 32, "random key length")
    expect(credentials.secret != credentials.mediaKey, "independent credentials")
    expect(credentials.hash.count == 64 && credentials.hash.allSatisfy { $0.isHexDigit && !$0.isUppercase }, "canonical raw-secret hash")
}
#endif
print("PASS: \(checks) conference invitation and bounded layout contracts")
