import Foundation
import Security
import CryptoKit
import LibSignalClient

// Controlled SecItem boundary only. Never queries real Keychain.
enum ControlledSecItem {
    static var records: [String: Data] = [:]
    static var target = "", mode = "normal"
    static var writes = 0, deletes = 0, failed = 0
    static func dict(_ value: CFDictionary) -> [String: Any] { unsafeBitCast(value, to: NSDictionary.self) as! [String: Any] }
    static func id(_ value: [String: Any]) -> String { (value[kSecAttrService as String] as! String) + "|" + (value[kSecAttrAccount as String] as! String) }
    static func matches(_ value: [String: Any]) -> Bool {
        let key = value[kSecAttrAccount as String] as? String ?? ""
        if target == "[kyber-base]" { return key.contains(":kyber_prekey.base_seen.") }
        if target == "[kyber-used]" { return key.contains(":kyber_prekey.used.") }
        return !target.isEmpty && key == target
    }
    static func clearFault() { mode = "normal"; target = "" }
}
func OwnSecItemUpdate(_ query: CFDictionary, _ attributes: CFDictionary) -> OSStatus {
    let q = ControlledSecItem.dict(query), a = ControlledSecItem.dict(attributes)
    if ControlledSecItem.matches(q) {
        if ControlledSecItem.mode == "update" { ControlledSecItem.failed += 1; return errSecInteractionNotAllowed }
        if ControlledSecItem.mode == "add" { return errSecItemNotFound }
    }
    let key = ControlledSecItem.id(q)
    guard ControlledSecItem.records[key] != nil else { return errSecItemNotFound }
    ControlledSecItem.records[key] = a[kSecValueData as String] as? Data; ControlledSecItem.writes += 1
    return errSecSuccess
}
func OwnSecItemAdd(_ attributes: CFDictionary, _ result: UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus {
    let a = ControlledSecItem.dict(attributes)
    if ControlledSecItem.matches(a), ControlledSecItem.mode == "add" { ControlledSecItem.failed += 1; return errSecNotAvailable }
    let key = ControlledSecItem.id(a)
    guard ControlledSecItem.records[key] == nil else { return errSecDuplicateItem }
    ControlledSecItem.records[key] = a[kSecValueData as String] as? Data; ControlledSecItem.writes += 1
    return errSecSuccess
}
func OwnSecItemCopyMatching(_ query: CFDictionary, _ result: UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus {
    let q = ControlledSecItem.dict(query)
    if q[kSecMatchLimit as String] as? String == kSecMatchLimitAll as String {
        if ControlledSecItem.mode == "bad-keys" { result?.pointee = [[kSecAttrService as String:"wrong"]] as CFArray; return errSecSuccess }
        let prefix = (q[kSecAttrService as String] as! String) + "|"
        let rows = ControlledSecItem.records.keys.filter { $0.hasPrefix(prefix) }.map { [kSecAttrAccount as String:String($0.dropFirst(prefix.count))] }
        guard !rows.isEmpty else { return errSecItemNotFound }; result?.pointee = rows as CFArray; return errSecSuccess
    }
    if ControlledSecItem.matches(q) {
        if ControlledSecItem.mode == "read" { ControlledSecItem.failed += 1; return errSecInteractionNotAllowed }
        if ControlledSecItem.mode == "bad-data" { result?.pointee = "not opaque Data" as CFString; return errSecSuccess }
    }
    guard let value = ControlledSecItem.records[ControlledSecItem.id(q)] else { return errSecItemNotFound }
    result?.pointee = value as CFData; return errSecSuccess
}
func OwnSecItemDelete(_ query: CFDictionary) -> OSStatus {
    let q = ControlledSecItem.dict(query)
    if ControlledSecItem.matches(q), ControlledSecItem.mode == "delete" { ControlledSecItem.failed += 1; return errSecInteractionNotAllowed }
    let old = ControlledSecItem.records.removeValue(forKey: ControlledSecItem.id(q)); ControlledSecItem.deletes += 1
    return old == nil ? errSecItemNotFound : errSecSuccess
}

// Unused account factory and verification-owner boundary only.
enum AppConfig { static var storageOrigin = "https://unused.invalid"; static var signalServerPrefix = "" }
final class KeysRepository { func verificationOwner() throws -> String { "owned-test-only" } }
final class NotificationCount: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    func increment() { lock.lock(); count += 1; lock.unlock() }
}

@main enum CheckedPersistenceHost {
    struct Pair {
        let prefix: String, sender: UnyriSignalStoreContainer, receiver: UnyriSignalStoreContainer
        let name: String, recipient: SignalRecipientDevice
        var sessionKey: String { SignalStorageKeys.session(name: name, deviceId: 1) }
    }
    static var assertions = 0
    static func check(_ value: Bool, _ label: String) { assertions += 1; precondition(value, label) }
    static func expectedStatus(_ code: OSStatus, _ body: () throws -> Void) {
        do { try body(); preconditionFailure("storage failure escaped as success") }
        catch SignalStorageError.keychain(let actual) { check(actual == code,"original OSStatus propagated") }
        catch { preconditionFailure("unexpected error class: \(type(of:error))") }
    }
    static func malformed(_ body: () throws -> Void) {
        do { try body(); preconditionFailure("malformed storage accepted") }
        catch SignalStorageError.malformedResult { assertions += 1 }
        catch SignalStorageError.corruptMetadata { assertions += 1 }
        catch { preconditionFailure("unexpected malformed error class") }
    }
    static func cancelled(_ body: () throws -> Void) {
        do { try body(); preconditionFailure("invalidated owner admitted") }
        catch is CancellationError { assertions += 1 }
        catch { preconditionFailure("unexpected ownership error") }
    }
    static func container(_ prefix: String, registration: Int32) throws -> UnyriSignalStoreContainer {
        let state = SignalStateStore(keychain: SignalKeychainStore(serviceSuffix:"controlled-no-real-SecItem"),namespace:{prefix})
        let value = UnyriSignalStoreContainer(stateStore:state)
        try state.setData(IdentityKeyPair.generate().serialize(),forKey:SignalStorageKeys.identityKeyPair)
        try value.metadataStore.setRegistrationId(registration)
        return value
    }
    static func reopen(_ prefix: String) -> UnyriSignalStoreContainer {
        UnyriSignalStoreContainer(stateStore:SignalStateStore(keychain:SignalKeychainStore(serviceSuffix:"controlled-no-real-SecItem"),namespace:{prefix}))
    }
    static func prepare(_ label: String, sender existing: UnyriSignalStoreContainer? = nil, prefix existingPrefix: String? = nil, oneTime: Bool = false) throws -> Pair {
        let prefix = existingPrefix ?? "owned-" + UUID().uuidString.lowercased()
        let sender = try existing ?? container(prefix+"-sender",registration:101)
        let receiver = try container(prefix+"-"+label,registration:102)
        let identity = try receiver.identityKeyStore.identityKeyPair(context:receiver.context)
        let signedPair = PrivateKey.generate()
        let signedSignature = identity.privateKey.generateSignature(message:signedPair.publicKey.serialize())
        let signed = try SignedPreKeyRecord(id:1,timestamp:1,privateKey:signedPair,signature:signedSignature)
        let kyberPair = KEMKeyPair.generate()
        let kyberSignature = identity.privateKey.generateSignature(message:kyberPair.publicKey.serialize())
        let kyber = try KyberPreKeyRecord(id:1,timestamp:1,keyPair:kyberPair,signature:kyberSignature)
        try receiver.signedPreKeyStore.storeSignedPreKey(signed,id:1,context:receiver.context)
        try receiver.kyberPreKeyStore.storeKyberPreKey(kyber,id:1,context:receiver.context)
        let address = try ProtocolAddress(name:label,deviceId:1)
        let bundle: PreKeyBundle
        if oneTime {
            let key = PrivateKey.generate()
            try receiver.preKeyStore.storePreKey(PreKeyRecord(id:1,privateKey:key),id:1,context:receiver.context)
            bundle = try PreKeyBundle(registrationId:102,deviceId:1,prekeyId:1,prekey:key.publicKey,
                signedPrekeyId:1,signedPrekey:signed.publicKey(),signedPrekeySignature:signedSignature,
                identity:IdentityKey(bytes:identity.publicKey.serialize()),kyberPrekeyId:1,kyberPrekey:kyberPair.publicKey,kyberPrekeySignature:kyberSignature)
        } else {
            bundle = try PreKeyBundle(registrationId:102,deviceId:1,signedPrekeyId:1,signedPrekey:signed.publicKey(),
                signedPrekeySignature:signedSignature,identity:IdentityKey(bytes:identity.publicKey.serialize()),
                kyberPrekeyId:1,kyberPrekey:kyberPair.publicKey,kyberPrekeySignature:kyberSignature)
        }
        try processPreKeyBundle(bundle,for:address,sessionStore:sender.sessionStore,identityStore:sender.identityKeyStore,context:sender.context)
        return Pair(prefix:prefix,sender:sender,receiver:receiver,name:label,recipient:SignalRecipientDevice(deviceId:UUID().uuidString.lowercased(),signalDeviceId:1,addressName:label))
    }
    @MainActor static func encrypt(_ pair: Pair, sender: UnyriSignalStoreContainer? = nil) throws -> EnvelopeInput {
        let manager = SignalSessionManager(container:sender ?? pair.sender,keysRepository:KeysRepository())
        return try manager.encrypt(plaintext:Data("owned-public-normal-control".utf8),recipients:[pair.recipient])[0]
    }
    @MainActor static func decrypt(_ envelope: EnvelopeInput, pair: Pair) throws -> Data {
        try SignalSessionManager(container:pair.receiver,keysRepository:KeysRepository()).decrypt(ciphertextBase64:envelope.ciphertext,
            messageType:envelope.messageType,senderUserId:"owned-sender",senderSignalDeviceId:1)
    }
    @MainActor static func normalAndWrites() throws -> [[String:Any]] {
        var rows: [[String:Any]] = []
        for mode in ["normal","update","add"] {
            let pair = try prepare("owned-receiver")
            let before = try pair.sender.stateStore.data(forKey:pair.sessionKey)!
            ControlledSecItem.target = pair.prefix+"-sender:"+pair.sessionKey; ControlledSecItem.mode = mode
            if mode == "normal" {
                let envelope = try encrypt(pair)
                check(try decrypt(envelope,pair:pair) == Data("owned-public-normal-control".utf8),"normal actual decrypt")
                check(try pair.sender.stateStore.data(forKey:pair.sessionKey) != before,"normal advances session")
                rows.append(["mode":mode,"normalEnvelopeAndDecrypt":true])
            } else {
                expectedStatus(mode == "update" ? errSecInteractionNotAllowed : errSecNotAvailable) { _ = try encrypt(pair) }
                check(try pair.sender.stateStore.data(forKey:pair.sessionKey) == before,"failed mutation preserves last durable blob")
                ControlledSecItem.clearFault()
                let fresh = reopen(pair.prefix+"-sender")
                let envelope = try encrypt(pair,sender:fresh)
                check(try decrypt(envelope,pair:pair) == Data("owned-public-normal-control".utf8),"fresh-load continuation decrypts")
                rows.append(["mode":mode,"noEnvelopeReturned":true,"originalStatusPropagated":true,"lastDurableBlobUnchanged":true,"freshStoreContinuation":true])
            }
            ControlledSecItem.clearFault()
        }
        return rows
    }
    @MainActor static func readsMetadataOwnership() throws {
        let pair = try prepare("read-receiver")
        let state = pair.sender.stateStore, prefix = pair.prefix+"-sender:"
        let before = ControlledSecItem.records
        ControlledSecItem.target = prefix+pair.sessionKey; ControlledSecItem.mode = "read"
        expectedStatus(errSecInteractionNotAllowed) { _ = try encrypt(pair) }
        check(ControlledSecItem.records == before,"read failure makes no mutation")
        ControlledSecItem.mode = "bad-data"
        malformed { _ = try encrypt(pair) }
        check(ControlledSecItem.records == before,"wrong Security result does not create a session")
        ControlledSecItem.target = prefix+SignalStorageKeys.identityKeyPair; ControlledSecItem.mode = "read"
        expectedStatus(errSecInteractionNotAllowed) { try SignalKeyManager(container:pair.sender,keysRepository:KeysRepository()).ensureIdentity() }
        check(ControlledSecItem.records == before,"identity read failure cannot regenerate")
        ControlledSecItem.clearFault()
        check(try state.data(forKey:"absent") == nil,"only absent stays nil")
        try state.remove(forKey:"absent")
        for bytes in [Data([0xff]),Data("no".utf8),Data("-1".utf8),Data("999999999999999999999".utf8)] {
            try state.setData(bytes,forKey:"metadata.registration_id")
            malformed { _ = try pair.sender.metadataStore.registrationId }
        }
        try state.setData(Data("2".utf8),forKey:"metadata.initial_upload_completed")
        malformed { _ = try pair.sender.metadataStore.initialUploadCompleted }
        try state.remove(forKey:"metadata.initial_upload_completed")
        check(try !pair.sender.metadataStore.initialUploadCompleted,"missing initial flag remains false")
        try state.setData(Data(String(Int32.max).utf8),forKey:"metadata.next_otp_id")
        let keysBefore = try state.keys(withPrefix:"prekey.")
        do { _ = try SignalKeyManager(container:pair.sender,keysRepository:KeysRepository()).generateOneTimePreKeys(count:20); preconditionFailure("exhausted ID accepted") }
        catch SignalStorageError.exhaustedMetadata { assertions += 1 }
        check(try state.keys(withPrefix:"prekey.") == keysBefore,"exhaustion generates no prekeys")
        ControlledSecItem.mode = "bad-keys"
        malformed { _ = try state.keys(withPrefix:"") }
        ControlledSecItem.clearFault()
        state.invalidate()
        let retired = ControlledSecItem.records
        cancelled { _ = try state.data(forKey:"identity.key_pair") }
        cancelled { try state.setData(Data([2]),forKey:"identity.key_pair") }
        cancelled { try state.remove(forKey:"identity.key_pair") }
        cancelled { _ = try state.contains("identity.key_pair") }
        cancelled { _ = try state.keys(withPrefix:"") }
        cancelled { _ = try encrypt(pair) }
        check(ControlledSecItem.records == retired,"invalidated operations recreate nothing")
    }
    @MainActor static func decryptFailures() throws -> [[String:Any]] {
        var rows:[[String:Any]]=[]
        for kind in ["delete","kyber-base","kyber-used"] {
            let pair = try prepare("failure-receiver",oneTime:true)
            let envelope = try encrypt(pair)
            ControlledSecItem.mode = kind == "delete" ? "delete" : "add"
            ControlledSecItem.target = kind == "delete" ? pair.prefix+"-failure-receiver:prekey.1" : kind == "kyber-base" ? "[kyber-base]" : "[kyber-used]"
            expectedStatus(kind == "delete" ? errSecInteractionNotAllowed : errSecNotAvailable) { _ = try decrypt(envelope,pair:pair) }
            ControlledSecItem.clearFault()
            let keys = try pair.receiver.stateStore.keys(withPrefix:"")
            let baseSeen = keys.contains{$0.hasPrefix("kyber_prekey.base_seen.")}
            let used = keys.contains("kyber_prekey.used.1")
            let otp = keys.contains("prekey.1")
            if kind == "kyber-base" { check(!baseSeen && !used,"failed first Kyber write leaves neither marker") }
            if kind == "kyber-used" { check(baseSeen && !used,"failed second Kyber write retains first durable marker") }
            if kind == "delete" { check(otp,"failed OTP delete retains prekey") }
            rows.append(["kind":kind,"noPlaintextReturned":true,"baseSeenDurable":baseSeen,"usedDurable":used,"otpDurable":otp,
                         "sessionDurable":keys.contains("session.001.owned-sender"),"automaticRetryOrRollbackClaimed":false])
        }
        return rows
    }
    @MainActor static func recipientAndTrust() throws {
        let first = try prepare("first-recipient")
        let second = try prepare("second-recipient",sender:first.sender,prefix:first.prefix)
        let beforeFirst = try first.sender.stateStore.data(forKey:first.sessionKey)!
        let beforeSecond = try first.sender.stateStore.data(forKey:second.sessionKey)!
        ControlledSecItem.mode = "update"; ControlledSecItem.target = first.prefix+"-sender:"+second.sessionKey
        expectedStatus(errSecInteractionNotAllowed) {
            _ = try SignalSessionManager(container:first.sender,keysRepository:KeysRepository()).encrypt(
                plaintext:Data("owned-public-multiple".utf8),recipients:[first.recipient,second.recipient])
        }
        check(try first.sender.stateStore.data(forKey:first.sessionKey) != beforeFirst,"first callback durable before second fails")
        check(try first.sender.stateStore.data(forKey:second.sessionKey) == beforeSecond,"second callback stays durable-old")
        ControlledSecItem.clearFault()
        let address = try ProtocolAddress(name:"new-trust-recipient",deviceId:1)
        let identity = try IdentityKey(bytes:IdentityKeyPair.generate().publicKey.serialize())
        let count = NotificationCount()
        let observer = NotificationCenter.default.addObserver(forName:Notification.Name("COCOIdentityChanged"),object:nil,queue:nil){_ in count.increment()}
        defer { NotificationCenter.default.removeObserver(observer) }
        ControlledSecItem.mode = "add"
        ControlledSecItem.target = first.prefix+"-sender:"+SignalStorageKeys.trustedIdentity(name:address.name,deviceId:1)
        expectedStatus(errSecNotAvailable) { _ = try first.sender.identityKeyStore.saveIdentity(identity,for:address,context:first.sender.context) }
        check(count.value == 0,"no trust notification before failed persistence")
        ControlledSecItem.clearFault()
        _ = try first.sender.identityKeyStore.saveIdentity(identity,for:address,context:first.sender.context)
        check(count.value == 1,"normal trust notification after confirmed write")
    }
    @MainActor static func partialBootstrap() throws {
        let prefix = "bootstrap-" + UUID().uuidString.lowercased()
        let value = reopen(prefix)
        let manager = SignalKeyManager(container:value,keysRepository:KeysRepository())
        ControlledSecItem.target = prefix+":metadata.registration_id"; ControlledSecItem.mode = "update"
        expectedStatus(errSecInteractionNotAllowed) { try manager.ensureIdentity() }
        ControlledSecItem.clearFault()
        let identity = try value.stateStore.data(forKey:SignalStorageKeys.identityKeyPair)
        let registration = try value.metadataStore.registrationId
        check(identity != nil && registration == nil,"partial bootstrap identity durable/registration missing")
        try manager.ensureIdentity()
        check(try value.stateStore.data(forKey:SignalStorageKeys.identityKeyPair) == identity,"bootstrap retry reuses existing identity")
        check(try value.metadataStore.registrationId != nil,"bootstrap retry repairs only missing registration")
    }
    static func namespaceFailures() throws {
        let keychain = SignalKeychainStore(serviceSuffix:"namespace-controlled")
        let owner = UUID().uuidString.lowercased()+":"+UUID().uuidString.lowercased()
        let origin = "https://owned.example.invalid"
        let canonical = "server."+Data(origin.utf8).base64EncodedString()+"."+owner
        let pin = "namespace_binding:"+canonical
        ControlledSecItem.target = pin; ControlledSecItem.mode = "read"
        expectedStatus(errSecInteractionNotAllowed) { _ = try SignalNamespaceBinding.resolve(origin:origin,owner:owner,legacyPrefix:"",read:{try keychain.data(forKey:$0)},write:{try keychain.setData($1,forKey:$0)}) }
        ControlledSecItem.mode = "add"
        expectedStatus(errSecNotAvailable) { _ = try SignalNamespaceBinding.resolve(origin:origin,owner:owner,legacyPrefix:"",read:{try keychain.data(forKey:$0)},write:{try keychain.setData($1,forKey:$0)}) }
        ControlledSecItem.clearFault()
        check(try keychain.data(forKey:pin) == nil,"failed namespace pin never fabricates binding")
        let selected = try SignalNamespaceBinding.resolve(origin:origin,owner:owner,legacyPrefix:"",read:{try keychain.data(forKey:$0)},write:{try keychain.setData($1,forKey:$0)})
        check(selected == canonical,"normal canonical namespace unchanged")
    }
    final class AuthMemory {
        enum Failure: Error { case itemRead }
        var values: [String:String] = [:]
        var denied = Set<String>()
        var rejectWrite = false
        func read(_ key: String) throws -> String? { if denied.contains(key) { throw Failure.itemRead }; return values[key] }
        func write(_ value: String, _ key: String) -> Bool { if rejectWrite { return false }; values[key] = value; return true }
    }
    @MainActor static func factoryCases() throws -> [[String:Any]] {
        var rows: [[String:Any]] = []
        let user = "00000000-0000-4000-8000-000000000001"
        let installation = "00000000-0000-4000-8000-000000000002"
        for scenario in ["installation_read","installation_write","snapshot_read","snapshot_corrupt","missing_auth","normal_canonical","normal_legacy","normal_legacy_auth","normal_new_install"] {
            ControlledSecItem.records = [:]; ControlledSecItem.clearFault()
            let memory = AuthMemory()
            let raw:[String:Any] = ["accessToken":"synthetic-access","expiresAt":0,"refreshToken":"synthetic-refresh",
                "user":["id":user,"username":"owned","displayName":"Owned"],"deviceId":"00000000-0000-4000-8000-000000000003"]
            memory.values = ["session_snapshot_v1":String(decoding:try JSONSerialization.data(withJSONObject:raw),as:UTF8.self),"installation_id":installation]
            if scenario == "installation_read" { memory.denied.insert("installation_id") }
            if scenario == "installation_write" { memory.values["installation_id"] = nil; memory.rejectWrite = true }
            if scenario == "snapshot_read" { memory.denied.insert("session_snapshot_v1") }
            if scenario == "snapshot_corrupt" { memory.values["session_snapshot_v1"] = "{broken" }
            if scenario == "missing_auth" { memory.values["session_snapshot_v1"] = nil }
            if scenario == "normal_legacy_auth" { memory.values["session_snapshot_v1"] = nil; memory.values["refresh_token"] = "synthetic-refresh"; memory.values["user_id"] = user }
            if scenario == "normal_new_install" { memory.values["installation_id"] = nil }
            AppConfig.storageOrigin = scenario == "normal_legacy" ? "http://192.168.0.106:8080" : "https://owned.example.invalid"
            let owner = SignalNamespace.make(userId:user,installationId:installation)
            let namespace = scenario == "normal_legacy" ? owner : "server."+Data(AppConfig.storageOrigin.utf8).base64EncodedString()+"."+owner
            let seeded = UnyriSignalStoreContainer(stateStore:SignalStateStore(keychain:SignalKeychainStore(),namespace:{namespace}))
            let identity = IdentityKeyPair.generate().serialize()
            if scenario != "normal_new_install" {
                try seeded.stateStore.setData(identity,forKey:SignalStorageKeys.identityKeyPair)
                try seeded.metadataStore.setRegistrationId(101)
            }
            let beforeWrites = ControlledSecItem.writes
            let auth = SessionStore(read:{try memory.read($0)},write:{memory.write($0,$1)})
            var errorType = "", returned = false, matched = false
            do {
                let value = try UnyriSignalStoreContainer.forCurrentAccount(sessionStore:auth)
                returned = true
                try SignalKeyManager(container:value,keysRepository:KeysRepository()).ensureIdentity()
                matched = try value.stateStore.data(forKey:SignalStorageKeys.identityKeyPair) == identity
            } catch { errorType = String(describing:type(of:error)) }
            let writes = ControlledSecItem.writes-beforeWrites
            let normal = scenario.hasPrefix("normal")
            if scenario == "normal_new_install" {
                let saved = memory.values["installation_id"] ?? ""
                let selected = "server."+Data(AppConfig.storageOrigin.utf8).base64EncodedString()+"."+user+":"+saved
                let key = try SignalKeychainStore().data(forKey:selected+":"+SignalStorageKeys.identityKeyPair)
                check(returned && UUID(uuidString:saved) != nil && key != nil && errorType.isEmpty,"genuinely missing installation persists before new Signal material")
            } else if normal {
                check(returned && matched && errorType.isEmpty,"normal canonical/legacy owner preserves identity")
            } else {
#if FACTORY_EXPECT_VULNERABLE
                check(returned && !matched && writes > 0,"old factory unexpectedly rejected faulty auth storage")
#else
                check(!returned && !errorType.isEmpty && writes == 0,"faulty account/install evidence must precede all Signal writes")
#endif
            }
            rows.append(["scenario":scenario,"factoryReturned":returned,"existingIdentityRetained":matched,"signalWrites":writes,"errorType":errorType])
        }
        return rows
    }
    @MainActor static func main() throws {
        let factory = try factoryCases()
#if FACTORY_ONLY
        let result:[String:Any] = ["factoryCases":factory,"assertions":assertions,"scope":"actual factory and actual Auth SessionStore read/checked helper bodies; injected read/write effects only; no real Keychain/accounts"]
        let bytes = try JSONSerialization.data(withJSONObject:result,options:[.prettyPrinted,.sortedKeys])
        try bytes.write(to:URL(fileURLWithPath:CommandLine.arguments[1]),options:.atomic)
        print(String(decoding:bytes,as:UTF8.self))
#else
        let mutations = try normalAndWrites()
        try readsMetadataOwnership()
        let partial = try decryptFailures()
        try recipientAndTrust()
        try namespaceFailures()
        try partialBootstrap()
        let result:[String:Any] = ["status":"PASS","assertions":assertions,"factoryCases":factory,"mutationCases":mutations,"decryptFailureDurableState":partial,
            "scope":"actual bundled libsignal and production checked stores/exact manager methods, only controlled SecItem effects; no real Keychain/network/Main",
            "noPermanentUnhealthyLatch":true,"partialWritesTransactional":false,"physicalSecItemClaim":false]
        let bytes = try JSONSerialization.data(withJSONObject:result,options:[.prettyPrinted,.sortedKeys])
        try bytes.write(to:URL(fileURLWithPath:CommandLine.arguments[1]),options:.atomic)
        print(String(decoding:bytes,as:UTF8.self))
#endif
    }
}
