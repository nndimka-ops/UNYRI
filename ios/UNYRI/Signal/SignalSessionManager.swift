import Foundation
import CryptoKit
import LibSignalClient

/// Coordinates recipient discovery, trust and per-device Signal operations.
/// Receive/crypto and journal work runs on the account's serial I/O queue;
/// network/session ownership and UI approval remain on the main actor.
@MainActor
final class SignalSessionManager {
    private let container: UnyriSignalStoreContainer
    private let keysRepository: KeysRepository
    /// Backend device UUID -> Signal device id cache (account-scoped: this
    /// manager is created per account). Stores no cryptographic material.
    private let deviceIdCache = SignalDeviceIdCache()
    /// In-flight resolutions keyed the same way as the cache, so simultaneous
    /// messages from the same unseen sender device share ONE bundle fetch
    /// instead of N identical `GET /keys/bundles` requests.
    func revokeOwnDevice(_ deviceID: String) async throws {
        let owner = try keysRepository.verificationOwner()
        guard let security = container.stateStore.localSecurity else { throw CancellationError() }
        // Persist the UUID first: an offline/hostile bundle lookup cannot cancel removal.
        try security.revoke(.init(userId: owner, serverDeviceId: deviceID.lowercased(), signalDeviceId: nil, identityFingerprint: nil))
        if let snapshot = try? await reviewExactDevice(userID: owner, deviceID: deviceID) {
            _ = try keysRepository.verificationOwner()
            try security.revoke(.init(userId: owner, serverDeviceId: deviceID.lowercased(), signalDeviceId: snapshot.signalID,
                                      identityFingerprint: LocalSecurityDecisions.fingerprint(snapshot.identity)))
        }
    }
    private var withheldDeviceIDs: [String: Set<String>] = [:]
    func withheldDevices(for userID: String) -> [String] { (withheldDeviceIDs[userID] ?? []).sorted() }
    private var inFlightResolutions: [String: Task<Int32, Error>] = [:]

    /// Fired after the user accepts a changed identity key. The message
    /// ingestor subscribes to retry durably-retained undecryptable envelopes
    /// from that sender (mirrors Android's `IdentityVerificationManager`).
    var onSecurityWarning: (() -> Void)?
    var onTrustAccepted: ((String, UInt32) -> Void)? = nil

    init(container: UnyriSignalStoreContainer, keysRepository: KeysRepository) {
        self.container = container
        self.keysRepository = keysRepository
    }
    func shutdown() {
        container.stateStore.invalidate()
        for task in inFlightResolutions.values { task.cancel() }
        inFlightResolutions.removeAll(); deviceIdCache.clear(); onTrustAccepted = nil; onSecurityWarning = nil
    }

    /// Fetches every available device bundle for `userId` and establishes a
    /// Signal session for each device that does not already have one. Returns
    /// the backend device-id -> signal-device-id mapping needed later for
    /// envelope routing. Existing sessions are reused and never re-established
    /// (re-establishing would reset the ratchet and burn a one-time prekey).
    /// `excludingDeviceId` (the sender's own current backend device UUID) is
    /// filtered out BEFORE any session is established or encrypted to.
    func ensureSessions(for userId: String, excludingDeviceId: String? = nil) async throws -> [SignalRecipientDevice] {
        var response = try await keysRepository.fetchDeviceBundles(userId: userId)
        _ = try keysRepository.verificationOwner()
        var decodedBundles = try trustedBundles(response, userId: userId, excludingDeviceId: excludingDeviceId)
        if try decodedBundles.contains(where: { decoded in
            let address = try ProtocolAddress(name: userId, deviceId: try HostileInteger.unsigned(decoded.signalDeviceId, device: true))
            return try !sessionExists(for: address)
        }) {
            let establishment = try await keysRepository.fetchBundles(userId: userId)
            _ = try keysRepository.verificationOwner()
            // Re-check the returned list and all identities after the await.
            // Do not combine a stale discovery list with newer key material.
            decodedBundles = try trustedBundles(establishment, userId: userId, excludingDeviceId: excludingDeviceId)
            response = establishment
        }
        withheldDeviceIDs[userId] = Set(response.devices.filter { $0.deviceId != excludingDeviceId }.map(\.deviceId))
            .subtracting(Set(decodedBundles.map(\.deviceId)))
        var recipients: [SignalRecipientDevice] = []
        recipients.reserveCapacity(decodedBundles.count)
        for decoded in decodedBundles {
            let address = try ProtocolAddress(name: userId, deviceId: try HostileInteger.unsigned(decoded.signalDeviceId, device: true))
            if try !sessionExists(for: address) {
                let preKeyBundle = try Self.makePreKeyBundle(from: decoded)
                // Approved identity checks and session persistence happen inside
                // processPreKeyBundle through the injected stores; a changed
                // remote identity throws SignalError.untrustedIdentity here.
                try container.stateStore.withExclusiveAccess {
                    try processPreKeyBundle(
                        preKeyBundle,
                        for: address,
                        sessionStore: container.sessionStore,
                        identityStore: container.identityKeyStore,
                        context: container.context
                    )
                }
            }

            recipients.append(
                SignalRecipientDevice(
                    deviceId: decoded.deviceId,
                    signalDeviceId: decoded.signalDeviceId,
                    addressName: userId
                )
            )
        }
        return recipients
    }

    func reviewExactDevice(userID: String, deviceID: String) async throws -> SignalDeviceTrustSnapshot {
        let response = try await keysRepository.fetchExactDeviceBundles(userId: userID, deviceId: deviceID)
        _ = try keysRepository.verificationOwner()
        guard response.devices.count == 1, let raw = response.devices.first, raw.deviceId == deviceID else { throw SignalDecryptionError.malformedCiphertext }
        let bundle = try SignalBundleDecoder.decode(raw)
        let id = try HostileInteger.unsigned(bundle.signalDeviceId, device: true)
        let address = try ProtocolAddress(name: userID, deviceId: id)
        _ = try container.identityKeyStore.isTrustedIdentity(IdentityKey(bytes: bundle.identityKey), for: address, direction: .sending, context: container.context)
        try container.stateStore.localSecurity?.observe(user: userID, device: deviceID, signal: id, identity: bundle.identityKey)
        return SignalDeviceTrustSnapshot(userID: userID, deviceID: deviceID, signalID: id, identity: bundle.identityKey)
    }
    func isDeviceTrusted(_ snapshot: SignalDeviceTrustSnapshot) throws -> Bool {
        _ = try keysRepository.verificationOwner()
        if try container.stateStore.localSecurity?.isRevoked(user: snapshot.userID, device: snapshot.deviceID, signal: snapshot.signalID, identity: snapshot.identity) == true { return false }
        let suffix = SignalStorageKeys.encodeAddress(name: snapshot.userID, deviceId: snapshot.signalID)
        return try DeviceTrustPolicy.mayUse(trusted: container.stateStore.data(forKey: "trusted_identity." + suffix),
            pending: container.stateStore.data(forKey: "pending_identity." + suffix), presented: snapshot.identity)
    }
    func retireConference(_ id: UUID) throws {
        _ = try keysRepository.verificationOwner()
        try container.stateStore.setData(Data([1]), forKey: "retired_conference." + id.uuidString.lowercased())
    }
    func conferenceIsRetired(_ id: UUID) throws -> Bool {
        _ = try keysRepository.verificationOwner()
        return try container.stateStore.contains("retired_conference." + id.uuidString.lowercased())
    }

    /// Exact-device mailbox. Discovery is non-consuming; establishment uses the
    /// targeted endpoint and never fans a room key out to the account's devices.
    func encryptExact(plaintext: Data, userId: String, deviceId: String, expectedIdentity: Data? = nil) async throws -> EnvelopeInput {
        try Task.checkCancellation()
        _ = try keysRepository.verificationOwner()
        let discovery = try await keysRepository.fetchExactDeviceBundles(userId: userId, deviceId: deviceId)
        try Task.checkCancellation()
        guard let target = discovery.devices.first(where: { $0.deviceId == deviceId }) else {
            throw SignalDecryptionError.unknownDevice(userId: userId, deviceId: deviceId)
        }
        var bundles = try trustedBundles(UserBundlesResponse(devices: [target]), userId: userId, excludingDeviceId: nil)
        guard let first = bundles.first else { throw SignalError.untrustedIdentity(userId) }
        guard expectedIdentity == nil || expectedIdentity == first.identityKey else { throw SignalError.untrustedIdentity(userId) }
        let address = try ProtocolAddress(name: userId, deviceId: try HostileInteger.unsigned(first.signalDeviceId, device: true))
        if try !sessionExists(for: address) {
            let response = try await keysRepository.fetchBundle(userId: userId, deviceId: deviceId)
            try Task.checkCancellation()
            _ = try keysRepository.verificationOwner()
            bundles = try trustedBundles(response, userId: userId, excludingDeviceId: nil)
            guard bundles.count == 1, let exact = bundles.first, exact.deviceId == deviceId,
                  exact.signalDeviceId == first.signalDeviceId else { throw SignalDecryptionError.malformedCiphertext }
            guard exact.identityKey == first.identityKey else { throw SignalError.untrustedIdentity(userId) }
            if try !sessionExists(for: address) {
                try container.stateStore.withExclusiveAccess {
                    try processPreKeyBundle(Self.makePreKeyBundle(from: exact), for: address,
                        sessionStore: container.sessionStore, identityStore: container.identityKeyStore, context: container.context)
                }
            }
        }
        _ = try keysRepository.verificationOwner()
        let values = try await encryptForDelivery(plaintext: plaintext, recipients: [SignalRecipientDevice(deviceId: deviceId, signalDeviceId: first.signalDeviceId, addressName: userId)])
        guard values.count == 1, let value = values.first, value.recipientDeviceId == deviceId else { throw SignalDecryptionError.malformedCiphertext }
        return value
    }

    func decryptExact(_ envelope: EnvelopeInput, senderUserId: String, senderDeviceId: String, expectedIdentity: Data? = nil) async throws -> Data {
        try Task.checkCancellation()
        let response = try await keysRepository.fetchExactDeviceBundles(userId: senderUserId, deviceId: senderDeviceId)
        try Task.checkCancellation()
        let devices = try trustedBundles(response, userId: senderUserId, excludingDeviceId: nil)
        guard devices.count == 1, let sender = devices.first, sender.deviceId == senderDeviceId else {
            throw SignalDecryptionError.unknownDevice(userId: senderUserId, deviceId: senderDeviceId)
        }
        guard expectedIdentity == nil || expectedIdentity == sender.identityKey else { throw SignalError.untrustedIdentity(senderUserId) }
        _ = try keysRepository.verificationOwner()
        return try await decryptReceived(ciphertextBase64: envelope.ciphertext, messageType: envelope.messageType,
            senderUserId: senderUserId, senderSignalDeviceId: sender.signalDeviceId)
    }

    private func trustedBundles(_ response: UserBundlesResponse, userId: String, excludingDeviceId: String?) throws -> [DecodedSignalBundle] {
        let decoded = try response.devices.filter { $0.deviceId != excludingDeviceId }.map { try SignalBundleDecoder.decode($0) }
        var allowed: [DecodedSignalBundle] = []
        for bundle in decoded {
            try container.stateStore.localSecurity?.observe(user: userId, device: bundle.deviceId,
                signal: HostileInteger.unsigned(bundle.signalDeviceId, device: true), identity: bundle.identityKey)
            if let security = container.stateStore.localSecurity,
               try security.isRevoked(user: userId, device: bundle.deviceId, signal: try HostileInteger.unsigned(bundle.signalDeviceId, device: true), identity: bundle.identityKey) {
                if userId.lowercased() == security.ownUserId {
                    try security.revoke(.init(userId: userId.lowercased(), serverDeviceId: bundle.deviceId.lowercased(),
                        signalDeviceId: try HostileInteger.unsigned(bundle.signalDeviceId, device: true),
                        identityFingerprint: LocalSecurityDecisions.fingerprint(bundle.identityKey)))
                }
                if try security.claimRelistingWarning([bundle.deviceId]) { onSecurityWarning?() }
                continue
            }
            let address = try ProtocolAddress(name: userId, deviceId: try HostileInteger.unsigned(bundle.signalDeviceId, device: true))
            let identity = try IdentityKey(bytes: bundle.identityKey)
            let trusted = try container.identityKeyStore.isTrustedIdentity(identity, for: address, direction: .sending, context: container.context)
            if trusted { allowed.append(bundle) }
        }
        if try container.stateStore.hasVerifiedIdentityChange(user: userId) { throw SignalError.untrustedIdentity(userId) }
        return allowed
    }

    /// True if a session record is already persisted for `address`. Mirrors
    /// Android's `containsSession` (storage existence, no deserialization):
    /// an established session is reused as-is.
    private func sessionExists(for address: ProtocolAddress) throws -> Bool {
        try container.stateStore.contains(
            SignalStorageKeys.session(name: address.name, deviceId: address.deviceId)
        )
    }

    /// Builds a real `PreKeyBundle` from decoded bundle bytes, always including
    /// the Kyber (PQXDH) prekey. Uses the with-one-time-prekey constructor when
    /// the server supplied one, otherwise the without-one-time-prekey
    /// constructor (a valid PQXDH fallback, not a downgrade).
    static func makePreKeyBundle(from bundle: DecodedSignalBundle) throws -> PreKeyBundle {
        let identity = try IdentityKey(bytes: bundle.identityKey)
        let signedPrekey = try PublicKey(bundle.signedPrekeyPublic)
        let kyberPrekey = try KEMPublicKey(bundle.kyberPrekeyPublic)

        if let oneTimeId = bundle.oneTimePrekeyId, let oneTimePublic = bundle.oneTimePrekeyPublic {
            return try PreKeyBundle(
                registrationId: try HostileInteger.unsigned(bundle.registrationId),
                deviceId: try HostileInteger.unsigned(bundle.signalDeviceId, device: true),
                prekeyId: try HostileInteger.unsigned(oneTimeId),
                prekey: PublicKey(oneTimePublic),
                signedPrekeyId: try HostileInteger.unsigned(bundle.signedPrekeyId),
                signedPrekey: signedPrekey,
                signedPrekeySignature: bundle.signedPrekeySignature,
                identity: identity,
                kyberPrekeyId: try HostileInteger.unsigned(bundle.kyberPrekeyId),
                kyberPrekey: kyberPrekey,
                kyberPrekeySignature: bundle.kyberPrekeySignature
            )
        }
        return try PreKeyBundle(
            registrationId: try HostileInteger.unsigned(bundle.registrationId),
            deviceId: try HostileInteger.unsigned(bundle.signalDeviceId, device: true),
            signedPrekeyId: try HostileInteger.unsigned(bundle.signedPrekeyId),
            signedPrekey: signedPrekey,
            signedPrekeySignature: bundle.signedPrekeySignature,
            identity: identity,
            kyberPrekeyId: try HostileInteger.unsigned(bundle.kyberPrekeyId),
            kyberPrekey: kyberPrekey,
            kyberPrekeySignature: bundle.kyberPrekeySignature
        )
    }

    /// Encrypts `plaintext` independently for every recipient device with an
    /// established session, producing exactly one `EnvelopeInput` per device.
    ///
    /// The backend wire type (1 = PreKeySignalMessage, 2 = SignalMessage) is
    /// derived from the actual `CiphertextMessage` type returned by
    /// `signalEncrypt` — never inferred from session existence or call order.
    /// `signalEncrypt` persists the advanced session through `SessionStore`,
    /// so no explicit ratchet write happens here.
    ///
    /// All-or-error: any single device failure throws and returns no partial
    /// envelope list (matching Android, which maps + throws).
    func encrypt(plaintext: Data, recipients: [SignalRecipientDevice]) throws -> [EnvelopeInput] {
        _ = try keysRepository.verificationOwner()
        return try container.stateStore.withExclusiveAccess {
            try Self.encryptStored(container: container, plaintext: plaintext, recipients: recipients)
        }
    }
    func encryptForDelivery(plaintext: Data, recipients: [SignalRecipientDevice]) async throws -> [EnvelopeInput] {
        _ = try keysRepository.verificationOwner()
        let stores = container
        let envelopes = try await stores.stateStore.perform {
            try Self.encryptStored(container: stores, plaintext: plaintext, recipients: recipients)
        }
        _ = try keysRepository.verificationOwner()
        return envelopes
    }
    func requireTrustedRecipientsForDelivery(_ recipients: [SignalRecipientDevice]) async throws {
        _ = try keysRepository.verificationOwner()
        let stores = container
        try await stores.stateStore.perform { try Self.requireTrustedRecipientsStored(container: stores, recipients) }
        _ = try keysRepository.verificationOwner()
    }
    nonisolated private static func encryptStored(container: UnyriSignalStoreContainer, plaintext: Data, recipients: [SignalRecipientDevice]) throws -> [EnvelopeInput] {
        try requireTrustedRecipientsStored(container: container, recipients)
        var envelopes: [EnvelopeInput] = []
        envelopes.reserveCapacity(recipients.count)

        for recipient in recipients {
            let address = try ProtocolAddress(name: recipient.addressName, deviceId: try HostileInteger.unsigned(recipient.signalDeviceId, device: true))
            let ciphertextMessage = try signalEncrypt(
                message: plaintext,
                for: address,
                sessionStore: container.sessionStore,
                identityStore: container.identityKeyStore,
                context: container.context
            )
            envelopes.append(
                EnvelopeInput(
                    recipientDeviceId: recipient.deviceId,
                    messageType: try Self.messageType(for: ciphertextMessage.messageType),
                    ciphertext: Base64.encode(try ciphertextMessage.serialize())
                )
            )
        }
        return envelopes
    }

    nonisolated private static func requireTrustedRecipientsStored(container: UnyriSignalStoreContainer, _ recipients: [SignalRecipientDevice]) throws {
        for recipient in recipients {
            if try container.stateStore.hasVerifiedIdentityChange(user: recipient.addressName) { throw SignalError.untrustedIdentity(recipient.addressName) }
            if try container.stateStore.localSecurity?.isRevoked(user: recipient.addressName, device: recipient.deviceId,
                signal: HostileInteger.unsigned(recipient.signalDeviceId, device: true)) == true { throw LocalSecurityDecisions.Failure.revoked }
            guard try container.stateStore.contains(SignalStorageKeys.trustedIdentity(name: recipient.addressName, deviceId: try HostileInteger.unsigned(recipient.signalDeviceId, device: true))),
                  !(try container.stateStore.contains("pending_identity." + SignalStorageKeys.encodeAddress(name: recipient.addressName, deviceId: try HostileInteger.unsigned(recipient.signalDeviceId, device: true)))) else {
                throw SignalError.untrustedIdentity(recipient.addressName)
            }
        }
    }

    func receiveID(chatID: String, sequence: Int64, context: [String]) throws -> String {
        SignalReceiveJournal.chatPrefix(chatID) + String(sequence) + "." + SHA256.hash(data: try JSONEncoder().encode(context)).map { String(format: "%02x", $0) }.joined()
    }
    func discardReceiveJournals(chatID: String, through: Int64? = nil) async throws {
        let stores = container
        try await stores.stateStore.perform { try stores.stateStore.discardReceiveJournals(chatID: chatID, through: through) }
    }
    func pendingReceiveMetadata() async throws -> [Data] {
        let stores = container
        return try await stores.stateStore.perform { try stores.stateStore.pendingReceiveMetadata() }
    }
    func recoveredReceive(id: String) async throws -> Data? {
        let stores = container
        return try await stores.stateStore.perform { try stores.stateStore.recoveredReceive(id: id) }
    }
    func finishReceive(id: String) async throws {
        let stores = container
        try await stores.stateStore.perform { try stores.stateStore.finishReceive(id: id) }
    }

    /// Maps a libsignal `CiphertextMessage.MessageType` to UNYRI's backend wire
    /// tag. Only `.preKey` and `.whisper` are produced by 1:1 `signalEncrypt`;
    /// the group/plaintext types are impossible here and are treated as errors
    /// rather than being assigned an invented wire tag.
    nonisolated private static func messageType(for type: CiphertextMessage.MessageType) throws -> Int {
        if type == .preKey { return SignalMessageType.preKey }
        if type == .whisper { return SignalMessageType.whisper }
        throw SignalError.internalError("unexpected ciphertext message type from signalEncrypt")
    }

    /// Resolves a backend device UUID to its Signal device id by fetching the
    /// sender's bundle list and matching `device_id` (Android's
    /// `resolveSignalDeviceId`: local cache hit, else `GET /keys/bundles/:userId`
    /// and cache the UUID<->signalDeviceId mapping). Does NOT establish
    /// sessions — decryption of a PreKeySignalMessage establishes its own
    /// session from the incoming message.
    func resolveSignalDeviceId(senderId: String, senderDeviceId: String) async throws -> Int32 {
        if try container.stateStore.localSecurity?.isRevoked(user: senderId, device: senderDeviceId) == true { throw LocalSecurityDecisions.Failure.revoked }
        // Cache hit: resolve purely from the local mapping, no network call.
        if let cached = deviceIdCache.cached(userId: senderId, deviceId: senderDeviceId) {
            return cached
        }
        // Share one in-flight fetch for the same (user, device) pair.
        let cacheKey = SignalDeviceIdCache.key(userId: senderId, deviceId: senderDeviceId)
        if let task = inFlightResolutions[cacheKey] {
            return try await task.value
        }
        let task = Task<Int32, Error> { [weak self] in
            guard let self else { throw CancellationError() }
            let response = try await self.keysRepository.fetchDeviceBundles(userId: senderId)
            // Cache EVERY device in the response (Android parity: one fetch
            // warms the whole user's UUID -> signalDeviceId map).
            let devices: [(deviceId: String, signalDeviceId: Int32)] = try response.devices.map {
                _ = try HostileInteger.unsigned($0.signalDeviceId, device: true)
                return (deviceId: $0.deviceId, signalDeviceId: $0.signalDeviceId)
            }
            self.deviceIdCache.store(userId: senderId, devices: devices)
            guard let signalDeviceId = self.deviceIdCache.cached(userId: senderId, deviceId: senderDeviceId) else {
                throw SignalDecryptionError.unknownDevice(userId: senderId, deviceId: senderDeviceId)
            }
            return signalDeviceId
        }
        inFlightResolutions[cacheKey] = task
        defer { inFlightResolutions[cacheKey] = nil }
        return try await task.value
    }

    /// Decrypts an incoming Signal ciphertext for a known sender address.
    /// `messageType` 1 = PreKeySignalMessage (session-establishing), 2 =
    /// SignalMessage (established session). Returns the exact plaintext bytes
    /// produced by libsignal; no content decoding happens here.
    ///
    /// The official libsignal flow persists session/prekey/kyber/identity
    /// mutations through the stores (consume one-time prekey, mark Kyber used,
    /// save approved identity, store session) — nothing is written manually.
    /// An identity change throws `SignalError.untrustedIdentity` (not caught).
    func decrypt(ciphertextBase64: String, messageType: Int, senderUserId: String, senderSignalDeviceId: Int32,
                 receiveID: String? = nil, receiveMetadata: Data = Data()) throws -> Data {
        try container.stateStore.withExclusiveAccess {
            try Self.decryptStored(container: container, ciphertextBase64: ciphertextBase64, messageType: messageType,
                senderUserId: senderUserId, senderSignalDeviceId: senderSignalDeviceId, receiveID: receiveID, receiveMetadata: receiveMetadata)
        }
    }
    func decryptReceived(ciphertextBase64: String, messageType: Int, senderUserId: String, senderSignalDeviceId: Int32,
                         receiveID: String? = nil, receiveMetadata: Data = Data()) async throws -> Data {
        _ = try keysRepository.verificationOwner()
        let stores = container
        let plaintext = try await stores.stateStore.perform {
            try Self.decryptStored(container: stores, ciphertextBase64: ciphertextBase64, messageType: messageType,
                senderUserId: senderUserId, senderSignalDeviceId: senderSignalDeviceId, receiveID: receiveID, receiveMetadata: receiveMetadata)
        }
        _ = try keysRepository.verificationOwner()
        return plaintext
    }
    nonisolated private static func decryptStored(container: UnyriSignalStoreContainer,
        ciphertextBase64: String,
        messageType: Int,
        senderUserId: String,
        senderSignalDeviceId: Int32,
        receiveID: String? = nil,
        receiveMetadata: Data = Data()
    ) throws -> Data {
        if let security = container.stateStore.localSecurity {
            if try security.blockDecision(senderUserId) == true || security.isRevoked(user: senderUserId,
                signal: HostileInteger.unsigned(senderSignalDeviceId, device: true)) { throw LocalSecurityDecisions.Failure.revoked }
        }
        if let receiveID {
            return try container.stateStore.receive(id: receiveID, metadata: receiveMetadata) {
                try decryptStored(container: container, ciphertextBase64: ciphertextBase64, messageType: messageType,
                    senderUserId: senderUserId, senderSignalDeviceId: senderSignalDeviceId)
            }
        }
        guard let ciphertext = Base64.decode(ciphertextBase64) else {
            throw SignalDecryptionError.malformedCiphertext
        }
        let address = try ProtocolAddress(name: senderUserId, deviceId: try HostileInteger.unsigned(senderSignalDeviceId, device: true))

        switch try SignalIncomingMessageType.parse(messageType) {
        case .preKey:
            let message = try PreKeySignalMessage(bytes: ciphertext)
            return try signalDecryptPreKey(
                message: message,
                from: address,
                sessionStore: container.sessionStore,
                identityStore: container.identityKeyStore,
                preKeyStore: container.preKeyStore,
                signedPreKeyStore: container.signedPreKeyStore,
                kyberPreKeyStore: container.kyberPreKeyStore,
                context: container.context
            )
        case .whisper:
            let message = try SignalMessage(bytes: ciphertext)
            return try signalDecrypt(
                message: message,
                from: address,
                sessionStore: container.sessionStore,
                identityStore: container.identityKeyStore,
                context: container.context
            )
        }
    }
}

// Manual verification is local and bound to the exact pair of public keys.
extension SignalSessionManager {
    /// Cheap presentation query: no fingerprint computation or trust changes.
    func hasPendingIdentity(peer: String) throws -> Bool {
        try SignalStorageKeys.hasPendingIdentity(in: container.stateStore.keys(withPrefix: "pending_identity."), peer: peer)
    }

    func pendingIdentityPeers(among peers: Set<String>) throws -> Set<String> {
        guard !peers.isEmpty else { return [] }
        return try SignalStorageKeys.pendingIdentityPeers(in: container.stateStore.keys(withPrefix: "pending_identity."), among: peers)
    }

    func hasPendingIdentity(peers: Set<String>) throws -> Bool {
        try !pendingIdentityPeers(among: peers).isEmpty
    }

    func verificationDevices(peer: String) throws -> [UnyriVerifiedDevice] {
        let owner = try keysRepository.verificationOwner()
        let state = container.stateStore
        let local = try container.identityKeyStore.identityKeyPair(context: container.context).publicKey
        let names = try state.keys(withPrefix: "trusted_identity.") + state.keys(withPrefix: "pending_identity.")
        let ids = Set(names.compactMap { key -> UInt32? in
            guard let dot = key.firstIndex(of: "."),
                  let address = SignalStorageKeys.decodeAddress(String(key[key.index(after: dot)...])),
                  address.name == peer, (1...127).contains(address.deviceId) else { return nil }
            return address.deviceId
        })
        return try ids.sorted().map { id in
            let suffix = SignalStorageKeys.encodeAddress(name: peer, deviceId: id)
            let trusted = try state.data(forKey: SignalStorageKeys.trustedIdentity(name: peer, deviceId: id))
            let pending = try state.data(forKey: "pending_identity." + suffix)
            guard let bytes = pending ?? trusted else { throw SignalError.invalidState("missing peer identity") }
            let remote = try IdentityKey(bytes: bytes)
            let fingerprint = try NumericFingerprintGenerator(iterations: 5200).create(version: 2,
                localIdentifier: Data(owner.utf8), localKey: local,
                remoteIdentifier: Data(peer.utf8), remoteKey: remote.publicKey)
            let number = fingerprint.displayable.formatted
            let durableVerified = try state.localSecurity?.pins().contains { $0.userId == peer.lowercased() && $0.signalDeviceId == id && $0.identityKey == trusted && $0.verified } == true
            let verified = try pending == nil && (durableVerified || state.data(forKey: "verified_identity." + suffix) == Data(number.utf8))
            return UnyriVerifiedDevice(id: id, number: number, trusted: trusted, pending: pending, verified: verified)
        }
    }

    func updateVerification(peer: String, expected: UnyriVerifiedDevice, acceptChange: Bool) throws {
        try container.stateStore.withExclusiveAccess {
            // Re-read before writing: a key changed while the confirmation was open must never be accepted.
            let latest = try verificationDevices(peer: peer).first(where: { $0.id == expected.id })
            guard UnyriVerificationDecision.canApply(expected: expected, current: latest, acceptChange: acceptChange),
                  let current = latest else { throw SignalError.invalidState("verification snapshot changed") }
            let state = container.stateStore
            let suffix = SignalStorageKeys.encodeAddress(name: peer, deviceId: expected.id)
            let marker = "verified_identity." + suffix
            if try state.localSecurity?.isRevoked(user: peer, signal: current.id, identity: current.pending ?? current.trusted) == true { throw LocalSecurityDecisions.Failure.revoked }
            if acceptChange {
                guard let pending = current.pending else { throw SignalError.invalidState("no changed key") }
                let session = SignalStorageKeys.session(name: peer, deviceId: current.id)
                try state.remove(forKey: session)
                guard try !state.contains(session) else { throw SignalError.invalidState("could not reset session") }
                try state.remove(forKey: marker)
                guard try !state.contains(marker) else { throw SignalError.invalidState("could not reset verification") }
                let identity = SignalStorageKeys.trustedIdentity(name: peer, deviceId: current.id)
                try state.setData(pending, forKey: identity)
                guard try state.data(forKey: identity) == pending else { throw SignalError.invalidState("could not save identity") }
                try state.remove(forKey: "pending_identity." + suffix)
                guard try !state.contains("pending_identity." + suffix), try !state.contains(marker) else { throw SignalError.invalidState("could not reset verification") }
                onTrustAccepted?(peer, current.id)
            } else {
                guard current.pending == nil else { throw SignalError.invalidState("accept changed key first") }
                let value = Data(current.number.utf8)
                try state.setData(value, forKey: marker)
                guard try state.data(forKey: marker) == value else { throw SignalError.invalidState("could not save verification") }
            }
        }
    }
}
