import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
final class MemoryRecord {
    var bytes: Data?; var failWrites = false; var readbackFails = false
    func open(origin: String = "https://one.invalid", user: String = "owner") -> LocalSecurityDecisions {
        LocalSecurityDecisions(origin: origin, userID: user, read: { self.readbackFails ? nil : self.bytes },
            write: { if self.failWrites { throw LocalSecurityDecisions.Failure.persistence }; self.bytes = $0 }, remove: { self.bytes = nil })
    }
}
import Security
final class KeychainProbe {
    var records: [String: Data] = [:]
    func store() -> SignalKeychainStore {
        SignalKeychainStore(serviceSuffix: "no-real-keychain", operations: SignalKeychainOperations(
            matching: { q in
                if let key = q[kSecAttrAccount as String] as? String {
                    guard let bytes = self.records[key] else { return (errSecItemNotFound, nil) }
                    return (errSecSuccess, bytes as CFData)
                }
                let rows = self.records.keys.map { [kSecAttrAccount as String: $0] }
                return rows.isEmpty ? (errSecItemNotFound, nil) : (errSecSuccess, rows as CFArray)
            }, update: { q, a in
                let key = q[kSecAttrAccount as String] as! String
                guard self.records[key] != nil else { return errSecItemNotFound }
                self.records[key] = a[kSecValueData as String] as? Data; return errSecSuccess
            }, add: { q in
                check(q[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
                self.records[q[kSecAttrAccount as String] as! String] = q[kSecValueData as String] as? Data; return errSecSuccess
            }, delete: { q in self.records[q[kSecAttrAccount as String] as! String] = nil; return errSecSuccess }))
    }
}
enum KeychainStore {
    static let defaultService = "controlled-never-real"
    static var values: [String: String] = [:]
    static func checkedValue(forKey key:String, service:String) throws -> String? { values[service + key] }
    static func set(_ value:String, forKey key:String, service:String) -> Bool { values[service + key] = value; return true }
    static func remove(_ key:String, service:String) { values[service + key] = nil }
}
@main struct Main {
    static func main() {
        do {
            let disk=MemoryRecord(), security=disk.open(), raw=KeychainProbe(), keychain=raw.store()
            let state=SignalStateStore(keychain:keychain, localSecurity:security, namespace:{ "active" })
            let suffix=SignalStorageKeys.encodeAddress(name:"peer",deviceId:1), old=Data([5,1]), changed=Data([5,2])
            try state.setData(old,forKey:"trusted_identity." + suffix)
            try state.setData(Data("old safety number".utf8),forKey:"verified_identity." + suffix)
            check(try disk.open().pins().first?.verified == true)
            raw.records.removeAll() // Session service erased on forced sign-out.
            let relogin=SignalStateStore(keychain:keychain, localSecurity:disk.open(), namespace:{ "new-session" })
            try restorePins(relogin,security:disk.open())
            check(try relogin.data(forKey:"trusted_identity." + suffix) == old)
            try relogin.setData(changed,forKey:"pending_identity." + suffix)
            check(try relogin.hasVerifiedIdentityChange(user:"peer"))
            check(!DeviceTrustPolicy.mayUse(trusted:old,pending:changed,presented:changed))
            try relogin.setData(changed,forKey:"trusted_identity." + suffix) // Exact UI acceptance pins the new key.
            try relogin.remove(forKey:"pending_identity." + suffix)
            check(try !relogin.hasVerifiedIdentityChange(user:"peer"))
            check(try disk.open().pins().first?.verified == false)
            let cold=KeychainProbe(), coldStore=cold.store(), origin="https://cold.invalid"
            let ns="server." + Data(origin.utf8).base64EncodedString() + ".cold-owner:install"
            cold.records[ns + ":trusted_identity." + suffix] = old
            cold.records[ns + ":verified_identity." + suffix] = Data([1])
            try SignOutSubject.preserveIdentityPins(origin:origin,storageOrigin:origin,userID:"cold-owner",installationID:"install",keychain:coldStore)
            check(try LocalSecurityDecisions.account(origin:origin,userID:"cold-owner").pins().first?.verified == true)
            let legacyOrigin="http://192.168.0.106:8080", legacyUser="legacy-owner"
            cold.records[legacyUser + ":install:trusted_identity." + suffix] = old
            cold.records[legacyUser + ":install:identity.key_pair"] = Data([1])
            let staleCanonical="server." + Data(legacyOrigin.utf8).base64EncodedString() + "." + legacyUser + ":install"
            cold.records[staleCanonical + ":trusted_identity." + suffix] = changed
            cold.records["namespace_binding:" + staleCanonical] = Data((legacyUser + ":install").utf8)
            try SignOutSubject.preserveIdentityPins(origin:legacyOrigin,storageOrigin:legacyOrigin,userID:legacyUser,installationID:"install",keychain:coldStore)
            check(try LocalSecurityDecisions.account(origin:legacyOrigin,userID:legacyUser).pins().first?.identityKey == old)
            // Exercise the real bounded writer using a valid persisted full-cap record.
            let cap=MemoryRecord(), seeded=cap.open(); try seeded.pin(user:"verified",signal:1,identity:old,verified:true)
            var json=try JSONSerialization.jsonObject(with:cap.bytes!) as! [String:Any]
            let sample=(json["pins"] as! [[String:Any]])[0]
            var pins=[[String:Any]]()
            for i in 0..<LocalSecurityDecisions.pinLimit { var pin=sample; pin["userId"]="peer-" + String(i); pin["verified"]=i == 0; pin["firstSeenAtMs"]=i; pins.append(pin) }
            json["pins"]=pins; cap.bytes=try JSONSerialization.data(withJSONObject:json)
            try cap.open().pin(user:"new-peer",signal:1,identity:changed)
            let bounded=try cap.open().pins(); check(bounded.count == LocalSecurityDecisions.pinLimit)
            check(bounded.contains { $0.userId == "peer-0" && $0.verified })
            check(!bounded.contains { $0.userId == "peer-1" })
            json=try JSONSerialization.jsonObject(with:cap.bytes!) as! [String:Any]
            pins=(json["pins"] as! [[String:Any]]).map { entry in var pin=entry; pin["verified"]=true; return pin }
            json["pins"]=pins; cap.bytes=try JSONSerialization.data(withJSONObject:json)
            rejects { try cap.open().pin(user:"another-new-peer",signal:1,identity:changed) }
            check(try cap.open().pins().count == LocalSecurityDecisions.pinLimit)
            try cap.open().erase(); check(try cap.open().pins().isEmpty)
        } catch { preconditionFailure("Pin continuity fixture failed: \(error)") }
        print("PASS H1: actual protected mirroring, startup restore, cold 401 export, changed verified key block, bounded eviction and explicit erase")
    }
}
