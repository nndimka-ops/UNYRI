import Foundation

func check(_ condition: @autoclosure () -> Bool, _ message: String, file: StaticString = #file, line: UInt = #line) {
    guard condition() else { fatalError(message, file: file, line: line) }
}

let number = "123456789"
let grouped = PublicIdentityPolicy.formattedNumber(number)
check(PeerIdentityPolicy.label(publicUsername: "alice", unyriNumber: number,
    legacyUsername: "private_login", legacyServer: true).isEmpty, "Legacy mode uses only the display name")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: nil,
    legacyUsername: "private_login", legacyServer: true).isEmpty, "Legacy server never exposes a peer login")
check(PeerIdentityPolicy.label(publicUsername: "alice", unyriNumber: number,
    legacyUsername: "private_login") == "@alice", "Login2 uses the public username before the number")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: number,
    legacyUsername: "private_login") == grouped, "Login2 without a username uses the grouped number")
check(PeerIdentityPolicy.label(publicUsername: "", unyriNumber: number,
    legacyUsername: "") == grouped, "Empty compatibility username does not hide the number")
check(PeerIdentityPolicy.label(publicUsername: "  ", unyriNumber: number) == grouped, "Whitespace-only public username falls back to number")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: number) == grouped, "Number-only peer")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: nil,
    legacyUsername: "private_login").isEmpty, "Missing identity mode fails closed")
check(PeerIdentityPolicy.label(publicUsername: "", unyriNumber: nil,
    legacyUsername: "private_login", legacyServer: false).isEmpty, "Login2 never falls back to login")
check(PeerIdentityPolicy.label(publicUsername: "@", unyriNumber: nil,
    legacyUsername: "", legacyServer: true).isEmpty, "Never render a bare at sign")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: "",
    legacyUsername: "   ", legacyServer: true).isEmpty, "Empty legacy identity stays empty")
check(PeerIdentityPolicy.label(publicUsername: "@alice", unyriNumber: nil) == "@alice", "Never duplicate an at sign")
check(PeerIdentityPolicy.label(publicUsername: nil, unyriNumber: "42") == "42", "Short public aliases keep their format")
print("Peer identity policy contracts passed")
