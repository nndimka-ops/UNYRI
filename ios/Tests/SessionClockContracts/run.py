#!/usr/bin/env python3
"""Access-token expiry must follow the token's own lifetime, not the phone clock.

A phone whose clock runs hours behind compared the server's absolute expiry with
its own time, kept an expired bearer "fresh" and never refreshed ("Authentication
required" forever, tester report 9 Nov 2026). This contract extracts
SessionStore.localExpiry and checks it against crafted JWTs.
"""
import pathlib, re, subprocess, sys, tempfile

root = pathlib.Path(__file__).resolve().parents[2]
source = (root / "UNYRI" / "Auth" / "SessionStore.swift").read_text(encoding="utf-8")
match = re.search(r"    static func localExpiry\(accessToken: String, serverExpiresAt: Date, receivedAt: Date = Date\(\)\) -> Date \{.*?\n    \}\n", source, re.S)
if not match:
    sys.exit("localExpiry not found in SessionStore.swift")

harness = """import Foundation
enum Store {
%s}
func jwt(_ claims: String) -> String {
    let body = Data(claims.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    return "eyJhbGciOiJIUzI1NiJ9." + body + ".sig"
}
var failures = 0
func check(_ ok: Bool, _ name: String) { if !ok { failures += 1; print("FAIL", name) } else { print("ok", name) } }
let now = Date(timeIntervalSince1970: 1_000_000)
// Server clock five hours ahead of the phone: the token says 15 minutes of life.
let serverExpiry = now.addingTimeInterval(5 * 3600 + 900)
let token = jwt("{\\"sub\\":\\"u\\",\\"iat\\":1018000,\\"exp\\":1018900}")
let local = Store.localExpiry(accessToken: token, serverExpiresAt: serverExpiry, receivedAt: now)
check(abs(local.timeIntervalSince(now) - 900) < 0.001, "lifetime measured on the phone clock")
// Phone clock ahead of the server: still 15 minutes from receipt, never already expired.
let late = Store.localExpiry(accessToken: token, serverExpiresAt: now.addingTimeInterval(-3600), receivedAt: now)
check(abs(late.timeIntervalSince(now) - 900) < 0.001, "phone ahead of server")
// Malformed tokens fall back to the server value.
check(Store.localExpiry(accessToken: "not-a-jwt", serverExpiresAt: serverExpiry, receivedAt: now) == serverExpiry, "fallback for malformed")
check(Store.localExpiry(accessToken: jwt("{\\"iat\\":5,\\"exp\\":4}"), serverExpiresAt: serverExpiry, receivedAt: now) == serverExpiry, "fallback for inverted claims")
check(Store.localExpiry(accessToken: jwt("{\\"iat\\":0,\\"exp\\":99999999}"), serverExpiresAt: serverExpiry, receivedAt: now) == serverExpiry, "fallback for absurd lifetime")
exit(failures == 0 ? 0 : 1)
""" % match.group(0).replace("static func", "static func")

with tempfile.TemporaryDirectory() as tmp:
    main = pathlib.Path(tmp) / "main.swift"
    main.write_text(harness, encoding="utf-8")
    binary = pathlib.Path(tmp) / "contract"
    subprocess.run(["swiftc", "-O", str(main), "-o", str(binary)], check=True)
    sys.exit(subprocess.run([str(binary)]).returncode)
