import Foundation
import LibSignalClient

/// Generates and persists the local Signal device keys, constructs the
/// `POST /keys` upload request, and uploads it. This is the only component
/// that generates Signal material; it never touches sessions, bundles,
/// encryption, or decryption.
///
/// Generation is strictly one-time per account/device:
/// - the identity key pair and registration id are created only if missing;
/// - the signed prekey (id 1) and kyber prekey (id 1) are created only if
///   missing;
/// - one-time prekeys are allocated monotonic ids and replenished when low.
/// If a network upload fails after generation, the already-persisted material
/// is reused on the next attempt — identity is never regenerated.
@MainActor
final class SignalKeyManager {
    private let container: UnyriSignalStoreContainer
    private let keysRepository: KeysRepository

    init(container: UnyriSignalStoreContainer, keysRepository: KeysRepository) {
        self.container = container
        self.keysRepository = keysRepository
    }

    private var stateStore: SignalStateStore { container.stateStore }
    private var metadata: SignalMetadataStore { container.metadataStore }
    private var context: UnyriStoreContext { container.context }

    // MARK: - Identity / registration id (one-time)

    /// Idempotent: generates the identity key pair and registration id only if
    /// they do not exist yet for the current account/device. Safe to call on
    /// every login/app start.
    func ensureIdentity() throws {
        if try !metadata.hasLocalIdentity {
            let pair = IdentityKeyPair.generate()
            try stateStore.setData(pair.serialize(), forKey: SignalStorageKeys.identityKeyPair)
        }
        if try metadata.registrationId == nil {
            try metadata.setRegistrationId(KeyBootstrapPolicy.randomRegistrationId())
        }
    }

    // MARK: - Existence checks

    func hasSignedPreKey() throws -> Bool {
        try stateStore.contains(SignalStorageKeys.signedPreKey(id: 1))
    }

    func hasKyberPreKey() throws -> Bool {
        try stateStore.contains(SignalStorageKeys.kyberPreKey(id: 1))
    }

    // MARK: - Signed prekey (id 1)

    /// Generates (or rotates) the single active signed prekey, self-signed with
    /// the identity private key over the serialized public key bytes — exactly
    /// as Android signs it. Persists it through the signed-prekey store.
    @discardableResult
    func generateSignedPreKey() throws -> SignedPreKeyRecord {
        let identity = try container.identityKeyStore.identityKeyPair(context: context)
        let keyPair = PrivateKey.generate()
        let signature = identity.privateKey.generateSignature(message: keyPair.publicKey.serialize())
        let timestamp = Self.currentTimeMillis()
        let record = try SignedPreKeyRecord(id: 1, timestamp: timestamp, privateKey: keyPair, signature: signature)
        try container.signedPreKeyStore.storeSignedPreKey(record, id: 1, context: context)
        try metadata.setSignedPreKeyId(1)
        return record
    }

    // MARK: - Kyber prekey (id 1, last-resort)

    /// Generates the single reusable last-resort Kyber-1024 prekey, self-signed
    /// with the identity private key over the serialized Kyber public key.
    @discardableResult
    func generateKyberPreKey() throws -> KyberPreKeyRecord {
        let identity = try container.identityKeyStore.identityKeyPair(context: context)
        let keyPair = KEMKeyPair.generate()
        let signature = identity.privateKey.generateSignature(message: keyPair.publicKey.serialize())
        let timestamp = Self.currentTimeMillis()
        let record = try KyberPreKeyRecord(id: 1, timestamp: timestamp, keyPair: keyPair, signature: signature)
        try container.kyberPreKeyStore.storeKyberPreKey(record, id: 1, context: context)
        try metadata.setKyberPreKeyId(1)
        return record
    }

    // MARK: - One-time prekeys

    /// Generates and persists `count` one-time prekeys with monotonic,
    /// never-before-used ids, returning the new records for upload. The
    /// `nextOneTimePreKeyId` counter is advanced only after all records are
    /// durably written, so a mid-batch failure reuses the same ids next time.
    @discardableResult
    func generateOneTimePreKeys(count: Int) throws -> [PreKeyRecord] {
        guard count > 0 else { return [] }
        let nextId = try metadata.nextOneTimePreKeyId ?? 1
        let localCount = try metadata.localOneTimePreKeyCount
        // Validate persisted counters before generating/writing another key.
        // Exhaustion is a checked failure, never an overflow or counter reset.
        guard count <= Int(Int32.max), Int(nextId) <= Int(Int32.max) - count,
              localCount <= Int(Int32.max) - count else { throw SignalStorageError.exhaustedMetadata }
        let batch = OneTimePreKeyAllocation.nextBatch(nextId: nextId, count: count)

        var records: [PreKeyRecord] = []
        records.reserveCapacity(batch.ids.count)
        for id in batch.ids {
            let keyPair = PrivateKey.generate()
            let record = try PreKeyRecord(id: UInt32(id), privateKey: keyPair)
            try container.preKeyStore.storePreKey(record, id: UInt32(id), context: context)
            records.append(record)
        }

        try metadata.setNextOneTimePreKeyId(batch.nextId)
        try metadata.setLocalOneTimePreKeyCount(localCount + count)
        return records
    }

    // MARK: - Upload request

    /// Builds the exact `POST /keys` body from real records. Only public
    /// material is encoded (standard Base64); private keys never leave the
    /// local stores.
    func buildUploadRequest(
        signedPreKey: SignedPreKeyRecord,
        kyberPreKey: KyberPreKeyRecord,
        oneTimePreKeys: [PreKeyRecord]
    ) throws -> UploadKeysRequest {
        let identity = try container.identityKeyStore.identityKeyPair(context: context)
        guard let registrationId = try metadata.registrationId else {
            throw SignalError.invalidState("no local registration id stored for this account/device")
        }

        let oneTime: [OneTimePreKeyInput] = try oneTimePreKeys.map { record in
            OneTimePreKeyInput(
                keyId: Int32(record.id),
                publicKey: Base64.encode(try record.publicKey().serialize())
            )
        }

        return UploadKeysRequest(
            identityKey: Base64.encode(identity.publicKey.serialize()),
            registrationId: registrationId,
            signedPrekey: SignedPreKeyInput(
                keyId: Int32(signedPreKey.id),
                publicKey: Base64.encode(try signedPreKey.publicKey().serialize()),
                signature: Base64.encode(signedPreKey.signature)
            ),
            kyberPrekey: KyberPreKeyInput(
                keyId: Int32(kyberPreKey.id),
                publicKey: Base64.encode(try kyberPreKey.publicKey().serialize()),
                signature: Base64.encode(kyberPreKey.signature)
            ),
            oneTimePrekeys: oneTime
        )
    }

    // MARK: - Bootstrap

    /// High-level bootstrap: create only missing material, replenish one-time
    /// prekeys when low, upload, and mark the initial upload complete only
    /// after the server acknowledges. Idempotent — safe to call on every
    /// login. A failed upload leaves all generated records persisted, so the
    /// next attempt reuses the same identity/signed/kyber keys.
    func bootstrapKeysIfNeeded() async throws {
        try ensureIdentity()

        let serverCount: Int
        if let count = try? await keysRepository.fetchOneTimePreKeyCount().count {
            serverCount = Int(count)
        } else {
            serverCount = 0
        }

        let decision = KeyBootstrapPolicy.needsWork(
            hasSignedPreKey: try hasSignedPreKey(),
            hasKyberPreKey: try hasKyberPreKey(),
            localOneTimePreKeyCount: try metadata.localOneTimePreKeyCount,
            serverOneTimePreKeyCount: serverCount
        )
        // A nonempty remote pool is not proof of matching identity material.
        // Republish the existing records once per account-service bootstrap;
        // only missing/low prekeys are generated, never a new identity.

        let signedPreKey: SignedPreKeyRecord
        if decision.needsSignedPreKey {
            signedPreKey = try generateSignedPreKey()
        } else {
            signedPreKey = try container.signedPreKeyStore.loadSignedPreKey(id: 1, context: context)
        }

        let kyberPreKey: KyberPreKeyRecord
        if decision.needsKyberPreKey {
            kyberPreKey = try generateKyberPreKey()
        } else {
            kyberPreKey = try container.kyberPreKeyStore.loadKyberPreKey(id: 1, context: context)
        }

        let oneTimePreKeys: [PreKeyRecord]
        if decision.needsOneTimePreKeys {
            oneTimePreKeys = try generateOneTimePreKeys(count: decision.oneTimePreKeyBatchSize)
        } else {
            oneTimePreKeys = []
        }

        let request = try buildUploadRequest(
            signedPreKey: signedPreKey,
            kyberPreKey: kyberPreKey,
            oneTimePreKeys: oneTimePreKeys
        )
        _ = try await keysRepository.uploadKeys(request)
        try metadata.setInitialUploadCompleted(true)
    }

    private static func currentTimeMillis() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1000)
    }
}
