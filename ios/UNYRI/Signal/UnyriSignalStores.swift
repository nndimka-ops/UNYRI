import Foundation
import LibSignalClient

/// The libsignal `SessionStore` protocol. Aliased because the app already has
/// an unrelated auth-related `SessionStore` class in this module, which would
/// otherwise shadow the imported protocol.
typealias SignalSessionStore = LibSignalClient.SessionStore

/// Marker context passed through libsignal store callbacks. The concrete UNYRI
/// stores ignore its contents (there is exactly one per account/device), so a
/// single shared instance is sufficient.
struct UnyriStoreContext: StoreContext {
    static let shared = UnyriStoreContext()
    init() {}
}

// MARK: - Identity

/// Persistent implementation of libsignal's `IdentityKeyStore` (v0.86.5).
///
/// - The local identity key pair is read as opaque serialized bytes; if none
///   has been provisioned yet (key bootstrap is a later stage), the store
///   throws rather than fabricating an identity.
/// - Trusted remote identities are stored keyed by full `ProtocolAddress`.
/// - Trust follows libsignal's TOFU model: a first-seen identity is trusted,
///   any later *different* identity is not (and the change is reported via
///   `IdentityChange.replacedExisting` rather than silently dropped).
final class UnyriIdentityKeyStore: IdentityKeyStore {
    private let store: SignalStateStore
    private let metadata: SignalMetadataStore

    init(store: SignalStateStore, metadata: SignalMetadataStore) {
        self.store = store
        self.metadata = metadata
    }

    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        guard let data = try store.data(forKey: SignalStorageKeys.identityKeyPair) else {
            throw SignalError.invalidState("no identity key pair stored for this account/device")
        }
        return try IdentityKeyPair(bytes: data)
    }

    func localRegistrationId(context: StoreContext) throws -> UInt32 {
        guard let id = try metadata.registrationId, id >= 0 else {
            throw SignalError.invalidState("no local registration id stored for this account/device")
        }
        return UInt32(id)
    }

    func saveIdentity(
        _ identity: IdentityKey,
        for address: ProtocolAddress,
        context: StoreContext
    ) throws -> IdentityChange {
        let key = SignalStorageKeys.trustedIdentity(name: address.name, deviceId: address.deviceId)
        let existing = try self.identity(for: address, context: context)
        if let existing, existing != identity {
            try store.remove(forKey: "verified_identity." + SignalStorageKeys.encodeAddress(name: address.name, deviceId: address.deviceId))
        }
        try store.setData(identity.serialize(), forKey: key)
        if existing != identity { NotificationCenter.default.post(name: Notification.Name("COCOIdentityChanged"), object: nil) }
        if existing == nil {
            return .newOrUnchanged
        }
        if existing == identity {
            return .newOrUnchanged
        }
        // A different identity was recorded, but the change is surfaced to the
        // caller via `.replacedExisting` so it is never silently accepted.
        return .replacedExisting
    }

    func isTrustedIdentity(
        _ identity: IdentityKey,
        for address: ProtocolAddress,
        direction: Direction,
        context: StoreContext
    ) throws -> Bool {
        if let existing = try self.identity(for: address, context: context) {
            if existing != identity {
                let suffix = SignalStorageKeys.encodeAddress(name: address.name, deviceId: address.deviceId)
                let pendingKey = "pending_identity." + suffix
                let changed = try store.data(forKey: pendingKey) != identity.serialize()
                try store.remove(forKey: "verified_identity." + suffix)
                try store.setData(identity.serialize(), forKey: pendingKey)
                if changed { NotificationCenter.default.post(name: Notification.Name("COCOIdentityChanged"), object: nil) }
                return false
            }
            return true
        }
        return true // TOFU: first-seen identity is trusted
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let key = SignalStorageKeys.trustedIdentity(name: address.name, deviceId: address.deviceId)
        guard let data = try store.data(forKey: key) else { return nil }
        return try IdentityKey(bytes: data)
    }
}

// MARK: - PreKeys

final class UnyriPreKeyStore: PreKeyStore {
    private let store: SignalStateStore

    init(store: SignalStateStore) {
        self.store = store
    }

    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        let key = SignalStorageKeys.preKey(id: id)
        guard let data = try store.data(forKey: key) else {
            throw SignalError.invalidKeyIdentifier("no prekey with identifier \(id)")
        }
        return try PreKeyRecord(bytes: data)
    }

    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try store.setData(record.serialize(), forKey: SignalStorageKeys.preKey(id: id))
    }

    func removePreKey(id: UInt32, context: StoreContext) throws {
        try store.remove(forKey: SignalStorageKeys.preKey(id: id))
    }
}

// MARK: - SignedPreKeys

final class UnyriSignedPreKeyStore: SignedPreKeyStore {
    private let store: SignalStateStore

    init(store: SignalStateStore) {
        self.store = store
    }

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        let key = SignalStorageKeys.signedPreKey(id: id)
        guard let data = try store.data(forKey: key) else {
            throw SignalError.invalidKeyIdentifier("no signed prekey with identifier \(id)")
        }
        return try SignedPreKeyRecord(bytes: data)
    }

    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try store.setData(record.serialize(), forKey: SignalStorageKeys.signedPreKey(id: id))
    }
}

// MARK: - KyberPreKeys

final class UnyriKyberPreKeyStore: KyberPreKeyStore {
    private let store: SignalStateStore

    init(store: SignalStateStore) {
        self.store = store
    }

    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        let key = SignalStorageKeys.kyberPreKey(id: id)
        guard let data = try store.data(forKey: key) else {
            throw SignalError.invalidKeyIdentifier("no kyber prekey with identifier \(id)")
        }
        return try KyberPreKeyRecord(bytes: data)
    }

    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try store.setData(record.serialize(), forKey: SignalStorageKeys.kyberPreKey(id: id))
    }

    func markKyberPreKeyUsed(
        id: UInt32,
        signedPreKeyId: UInt32,
        baseKey: PublicKey,
        context: StoreContext
    ) throws {
        // Mirrors libsignal's in-memory store: reject a base key already seen
        // for this (kyber id, signed prekey id) pair, then record it and mark
        // the kyber prekey as used.
        let seenKey = SignalStorageKeys.kyberBaseKeySeen(
            id: id,
            signedPreKeyId: signedPreKeyId,
            baseKey: baseKey.serialize()
        )
        if try store.contains(seenKey) {
            throw SignalError.invalidMessage("reused base key")
        }
        try store.setData(Data([1]), forKey: seenKey)
        try store.setData(Data([1]), forKey: SignalStorageKeys.kyberPreKeyUsed(id: id))
    }
}

// MARK: - Sessions

final class UnyriSessionStore: SignalSessionStore {
    private let store: SignalStateStore

    init(store: SignalStateStore) {
        self.store = store
    }

    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        let key = SignalStorageKeys.session(name: address.name, deviceId: address.deviceId)
        guard let data = try store.data(forKey: key) else { return nil }
        return try SessionRecord(bytes: data)
    }

    func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        return try addresses.map { address in
            if let session = try loadSession(for: address, context: context) {
                return session
            }
            throw SignalError.sessionNotFound("no session for \(address.name):\(address.deviceId)")
        }
    }

    func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        let key = SignalStorageKeys.session(name: address.name, deviceId: address.deviceId)
        try store.setData(record.serialize(), forKey: key)
    }
}

// MARK: - Container / factory

/// Owns the five persistent libsignal stores for the current account/device.
/// Construct one per authenticated account; all stores share the same
/// account/device-namespaced `SignalStateStore`.
final class UnyriSignalStoreContainer {
    let stateStore: SignalStateStore
    let metadataStore: SignalMetadataStore
    let identityKeyStore: UnyriIdentityKeyStore
    let preKeyStore: UnyriPreKeyStore
    let signedPreKeyStore: UnyriSignedPreKeyStore
    let kyberPreKeyStore: UnyriKyberPreKeyStore
    let sessionStore: UnyriSessionStore
    let context = UnyriStoreContext.shared

    init(stateStore: SignalStateStore) {
        self.stateStore = stateStore
        let metadata = SignalMetadataStore(store: stateStore)
        self.metadataStore = metadata
        self.identityKeyStore = UnyriIdentityKeyStore(store: stateStore, metadata: metadata)
        self.preKeyStore = UnyriPreKeyStore(store: stateStore)
        self.signedPreKeyStore = UnyriSignedPreKeyStore(store: stateStore)
        self.kyberPreKeyStore = UnyriKyberPreKeyStore(store: stateStore)
        self.sessionStore = UnyriSessionStore(store: stateStore)
    }

    /// Builds a store container namespaced by the current authenticated account
    /// (user id) and installation, so Signal material never leaks across
    /// accounts or across reinstalls. Only call once a user is authenticated.
    static func forCurrentAccount(sessionStore: SessionStore) throws -> UnyriSignalStoreContainer {
        // Freeze ownership for the lifetime of this container, including awaits.
        guard let user = try sessionStore.checkedServerContinuityUserID() else {
            throw SignalError.invalidState("no authenticated account")
        }
        let owner = SignalNamespace.make(userId: user,
            installationId: try sessionStore.durableInstallationId())
        let keychain = SignalKeychainStore()
        _ = try keychain.keys() // Locked/inaccessible storage must not look like a new install.
        let namespace = try SignalNamespaceBinding.resolve(origin: AppConfig.storageOrigin,
            owner: owner, legacyPrefix: AppConfig.signalServerPrefix,
            read: { try keychain.data(forKey: $0) }, write: { try keychain.setData($1, forKey: $0) })
        let state = SignalStateStore(keychain: keychain, namespace: { namespace })
        return UnyriSignalStoreContainer(stateStore: state)
    }
}
