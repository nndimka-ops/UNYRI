"""Compile production logout erasure/fencing against an in-memory Security boundary.

No real Keychain, credentials, simulator or device data are accessed. Native
Security availability and protection classes still require Xcode/device QA.
"""
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--swiftc', default='swiftc')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[3]
session = (root/'ios/UNYRI/Auth/SessionStore.swift').read_text(encoding='utf-8')
purger = session[session.index('enum LocalAccountCachePurger {'):]
signal = (root/'ios/UNYRI/Signal/SignalStateStore.swift').read_text(encoding='utf-8')
state = signal[signal.index('final class SignalStateStore {'):]
harness = r'''
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
typealias CFDictionary = [String: Any]
typealias CFTypeRef = AnyObject
let kSecClass = "class", kSecClassGenericPassword = "generic"
let kSecAttrService = "service", kSecAttrAccount = "account"
let kSecReturnAttributes = "attributes", kSecMatchLimit = "limit", kSecMatchLimitAll = "all"
let kSecAttrSynchronizable = "synchronizable", kSecAttrSynchronizableAny = "any"
let errSecSuccess: Int32 = 0, errSecItemNotFound: Int32 = -25300
let NSOSStatusErrorDomain = "SecurityDouble"
var services: [String: Set<String>] = [:]
var denied = false
func SecItemDelete(_ query: CFDictionary) -> Int32 {
    if denied { return -25293 }
    let service = query[kSecAttrService] as! String
    if let name = query[kSecAttrAccount] as? String { services[service]?.remove(name) }
    else { services.removeValue(forKey: service) }
    return errSecSuccess
}
func SecItemCopyMatching(_ query: CFDictionary, _ item: inout CFTypeRef?) -> Int32 {
    if denied { return -25293 }
    guard let names = services[query[kSecAttrService] as! String] else { return errSecItemNotFound }
    item = names.map { [kSecAttrAccount: $0] } as AnyObject
    return errSecSuccess
}
final class SignalKeychainStore {
    var dataByKey: [String: Data] = [:]
    func data(forKey key: String) -> Data? { dataByKey[key] }
    func setData(_ data: Data, forKey key: String) { dataByKey[key] = data }
    func remove(forKey key: String) { dataByKey.removeValue(forKey: key) }
    func contains(_ key: String) -> Bool { dataByKey[key] != nil }
    func keys() throws -> [String] { Array(dataByKey.keys) }
}
PURGER
STATE
let bundle = Bundle.main.bundleIdentifier ?? "com.messenger.coco"
services = [bundle: ["installation_id", "pending_logouts", "session_snapshot_v1", "offline_profile", "offline_calls.history.since"],
            bundle + ".signal": ["private_identity", "trusted_identity", "session"],
            bundle + ".history-recovery": ["private_recovery_key"], "unrelated-service": ["unrelated-record"]]
try LocalAccountCachePurger.erasePrivateKeychain()
precondition(services[bundle] == ["installation_id", "pending_logouts"])
precondition(services[bundle + ".signal"] == nil && services[bundle + ".history-recovery"] == nil)
precondition(services["unrelated-service"] == ["unrelated-record"])
denied = true
do { try LocalAccountCachePurger.erasePrivateKeychain(); fatalError("Keychain failure swallowed") } catch {}
denied = false
let defaults = UserDefaults(suiteName: "coco-privacy-contract-" + UUID().uuidString)!
defaults.set("owner", forKey: "history-auto-server:server:owner")
defaults.set(true, forKey: "history-auto-v1:server:owner:enabled")
defaults.set(["private-chat"], forKey: "chatPins|server|owner")
defaults.set("owner", forKey: "openHistoryBackupForAccount")
defaults.set("ru", forKey: "appLanguage")
LocalAccountCachePurger.eraseAccountPreferences(defaults: defaults)
precondition(defaults.string(forKey: "appLanguage") == "ru")
precondition(!defaults.dictionaryRepresentation().keys.contains { $0.hasPrefix("history-auto-") || $0.hasPrefix("chatPins|") || $0 == "openHistoryBackupForAccount" })
let keychain = SignalKeychainStore()
let old = SignalStateStore(keychain: keychain, namespace: { "retired-owner" })
try old.setData(Data([1]), forKey: "identity")
let existed = try old.contains("identity")
precondition(existed)
old.invalidate()
keychain.dataByKey.removeAll()
do { try old.setData(Data([2]), forKey: "identity"); fatalError("Retired write admitted") } catch is CancellationError {}
do { _ = try old.data(forKey: "identity"); fatalError("Retired read became missing") } catch is CancellationError {}
do { try old.remove(forKey: "identity"); fatalError("Retired removal admitted") } catch is CancellationError {}
do { _ = try old.contains("identity"); fatalError("Retired existence became missing") } catch is CancellationError {}
precondition(keychain.dataByKey.isEmpty)
do { _ = try old.keys(withPrefix: ""); fatalError("Retired keys enumerated") } catch is CancellationError {}
print("Logout privacy contracts passed: owned credential erasure, fail-closed errors, revocation-only queue, preference cleanup, Signal ownership fence")
'''.replace('PURGER', purger).replace('STATE', state)
with tempfile.TemporaryDirectory() as folder:
    path = pathlib.Path(folder)
    source = path/'main.swift'
    source.write_text(harness, encoding='utf-8')
    exe = path/'contracts.exe'
    subprocess.run([args.swiftc, str(source), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
