#!/usr/bin/env python3
"""Integration wiring assertions; cryptographic behavior runs in main.swift."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
source = lambda name: (root / "ios" / "UNYRI" / name).read_text(encoding="utf-8")
key = source("Auth/DeviceBoundKey.swift")
session = source("Auth/DeviceBoundSession.swift")
repo = source("Auth/AuthRepository.swift")
registry = source("Auth/AccountRegistry.swift")
store = source("Auth/SessionStore.swift")
ws = source("Networking/RealtimeClient.swift")
api = source("Networking/APIClient.swift")


def block(text, marker):
    start = text.index(marker)
    end, depth = text.index("{", start) + 1, 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


logout_fixture = (root / "ios" / "Tests" / "OfflineLogoutContracts" / "main.swift").read_text(encoding="utf-8")
for marker in ["    static func refreshMargin(", "    static func accessLifetime("]:
    assert block(logout_fixture, marker) == block(store, marker), f"Offline logout fixture drifted from SessionStore: {marker}"
assert "SecureEnclave.isAvailable" in key
assert "kSecAttrTokenIDSecureEnclave" in key
assert "kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly" in key
assert "kSecAttrSynchronizable as String] = false" in key
assert "kSecAttrIsExtractable as String] = false" in key
assert "SecKeyCopyExternalRepresentation(privateKey" not in key
assert "do { return (id, try registration(key(id))) }" in key
assert "guard try mayReplaceMissing() else" in key
assert "guard newSession else { return false }" in session
assert "return try self.checkedServerContinuityUserID() == nil" in session
assert "catch DeviceBoundProof.Failure.missingKey" in session
assert 'code: "session_revoked"' in session
assert session.count("for attempt in 0...1") == 3
assert "request.deviceKey = binding.key" in session
assert "refreshWithDeviceKey(request, api: api)" in repo
assert "refreshWithDeviceKey(request, api: api)" in registry
assert "loginWithDeviceKey(request, api: api)" in repo
assert "registerWithDeviceKey(request, api: api)" in repo
assert "try store.eraseDeviceBinding()" in repo
assert "try SessionStore.bound(to: account.slot).eraseDeviceBinding()" in registry
assert "func deviceBindingID() throws" in store
assert "let credentials = try await auth.webSocketCredentials()" in ws
assert "store.deviceBindingSessionID() != DeviceBoundProof.accessSession(token)" in repo
assert "refreshMargin = SessionStore.refreshMargin(accessToken: tokens.access)" in repo
assert "refreshMargin = SessionStore.refreshMargin(accessToken: tokens.access)" in registry
assert 'request.setValue(deviceProof, forHTTPHeaderField: DeviceBoundProof.header)' in ws
assert "deviceProofClockRetried" in ws and "deviceProofClockBlocked" in ws
assert "willPerformHTTPRedirection response: HTTPURLResponse" in ws
assert 'detail.code == "device_proof_stale", let serverTime = detail.serverTime' in api
assert "DeviceBoundClockChallenge(serverTime: serverTime)" in api
assert "Timer(" not in key + session and "Task.sleep" not in key + session
project = (root / "ios" / "UNYRI.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8")
for name in ["DeviceBoundProof.swift", "DeviceBoundKey.swift", "DeviceBoundSession.swift"]:
    assert project.count(name + " in Sources") == 2, name
print("PASS: device-binding lifecycle and transport wiring")
