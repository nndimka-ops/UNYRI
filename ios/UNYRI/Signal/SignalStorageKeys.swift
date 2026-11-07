import Foundation

/// Deterministic, unambiguous storage-key construction for UNYRI Signal state.
///
/// All keys are consumed by `SignalStateStore`, which already prefixes them
/// with the current account/device namespace, so these helpers only need to be
/// unambiguous *within* a single account/device. The namespace prefixes here
/// intentionally match `SignalStateStore.Namespace` (do not change one without
/// the other).
enum SignalStorageKeys {
    /// Presentation only: a pending key belongs to one exact peer/device.
    static func hasPendingIdentity(in keys: [String], peer: String) -> Bool {
        let prefix = "pending_identity."
        return keys.contains { key in
            guard key.hasPrefix(prefix),
                  let address = decodeAddress(String(key.dropFirst(prefix.count))),
                  (1...127).contains(address.deviceId) else { return false }
            return address.name == peer
        }
    }

    // MARK: - Fixed keys

    /// Serialized `IdentityKeyPair` (libsignal record bytes). Written by the
    /// future key bootstrap; read by `UnyriIdentityKeyStore`.
    static let identityKeyPair = "identity.key_pair"

    /// Local registration id, stored as UTF-8 decimal (plain metadata, not a
    /// libsignal record). Shared with `SignalMetadataStore`.
    static let registrationId = "metadata.registration_id"

    // MARK: - Id-only record keys

    static func preKey(id: UInt32) -> String {
        "\(Namespace.preKey).\(id)"
    }

    static func signedPreKey(id: UInt32) -> String {
        "\(Namespace.signedPreKey).\(id)"
    }

    static func kyberPreKey(id: UInt32) -> String {
        "\(Namespace.kyberPreKey).\(id)"
    }

    // MARK: - Kyber "used" bookkeeping

    /// Marks a kyber prekey id as used (one-shot semantics).
    static func kyberPreKeyUsed(id: UInt32) -> String {
        "\(Namespace.kyberPreKey).used.\(id)"
    }

    /// Records that a given base key was already presented for a
    /// (kyber id, signed prekey id) pair, to detect reuse. The base key is a
    /// *public* value (no secret material is embedded in storage keys).
    static func kyberBaseKeySeen(id: UInt32, signedPreKeyId: UInt32, baseKey: Data) -> String {
        let encoded = baseKey.base64EncodedString()
        return "\(Namespace.kyberPreKey).base_seen.\(id).\(signedPreKeyId).\(encoded)"
    }

    // MARK: - Address-keyed records (trusted identities + sessions)

    static func trustedIdentity(name: String, deviceId: UInt32) -> String {
        "\(Namespace.trustedIdentity).\(encodeAddress(name: name, deviceId: deviceId))"
    }

    static func session(name: String, deviceId: UInt32) -> String {
        "\(Namespace.session).\(encodeAddress(name: name, deviceId: deviceId))"
    }

    // MARK: - Address encoding

    /// Encodes `(name, deviceId)` into one unambiguous string component:
    /// a fixed-width 3-digit zero-padded device id, a `.`, then the raw name.
    ///
    /// Device ids are always in 1...127, so the numeric prefix is *always*
    /// exactly three characters and can never be confused with the start of the
    /// name. No escaping of `name` is required because it is the unbounded tail.
    static func encodeAddress(name: String, deviceId: UInt32) -> String {
        "\(paddedDeviceId(deviceId)).\(name)"
    }

    /// Reverses `encodeAddress`. Returns `nil` if `encoded` is malformed.
    static func decodeAddress(_ encoded: String) -> (name: String, deviceId: UInt32)? {
        let chars = Array(encoded)
        guard chars.count >= 5, // at least "000." + one name character
              chars[0].isNumber, chars[1].isNumber, chars[2].isNumber,
              chars[3] == "." else {
            return nil
        }
        guard let deviceId = UInt32(String(chars[0...2])) else { return nil }
        let name = String(chars[4...])
        return (name, deviceId)
    }

    static func paddedDeviceId(_ deviceId: UInt32) -> String {
        let raw = String(deviceId)
        if raw.count >= 3 { return raw }
        return String(repeating: "0", count: 3 - raw.count) + raw
    }

    // MARK: - Namespace prefixes (must match SignalStateStore.Namespace)

    enum Namespace {
        static let identity = "identity"
        static let preKey = "prekey"
        static let signedPreKey = "signed_prekey"
        static let kyberPreKey = "kyber_prekey"
        static let session = "session"
        static let trustedIdentity = "trusted_identity"
        static let metadata = "metadata"
    }
}

/// Stable Signal-state namespace for the current account + installation.
enum SignalNamespace {
    /// The namespace is derived from the authenticated user id (so switching
    /// accounts can never share Signal material) and the per-installation UUID
    /// (so a reinstall starts with fresh, well-defined state while a logout +
    /// relogin on the same install reuses the same identity/records).
    ///
    /// The separator `:` cannot appear in either component (both are UUIDs in
    /// this system), so the concatenation is unambiguous.
    static func make(userId: String, installationId: String) -> String {
        "\(userId):\(installationId)"
    }
}

/// A confirmation snapshot includes both keys so stale dialogs cannot approve a replacement.
struct UnyriVerifiedDevice: Identifiable, Equatable {
    let id: UInt32
    let number: String
    let trusted: Data?
    let pending: Data?
    let verified: Bool
}

enum UnyriVerificationDecision {
    static func canApply(expected: UnyriVerifiedDevice, current: UnyriVerifiedDevice?, acceptChange: Bool) -> Bool {
        guard let current, current == expected else { return false }
        return acceptChange ? current.pending != nil : current.pending == nil
    }
}


/// Pins a whole Signal store, preserving identity, prekeys and ratchets together.
enum SignalNamespaceBinding {
    enum Failure: Error { case invalidBinding, persistence }
    static func resolve(origin: String, owner: String, legacyPrefix: String,
                        read: (String) throws -> Data?, write: (String, Data) throws -> Void) throws -> String {
        let canonical = "server." + Data(origin.utf8).base64EncodedString() + "." + owner
        let pin = "namespace_binding:" + canonical
        if let data = try read(pin) {
            guard let saved = String(data: data, encoding: .utf8),
                  saved == canonical || saved == owner else { throw Failure.invalidBinding }
            return saved
        }
        let legacy = legacyPrefix + owner
        let knownLegacyServer = ["http://192.168.0.106:8080", "https://192.168.0.106:8443"].contains(origin)
        let selected: String
        if (legacy == canonical || (legacy == owner && knownLegacyServer)), try read(legacy + ":identity.key_pair") != nil {
            selected = legacy
        } else if try read(canonical + ":identity.key_pair") != nil {
            selected = canonical
        } else if knownLegacyServer, try read(owner + ":identity.key_pair") != nil {
            selected = owner
        } else { selected = canonical }
        try write(pin, Data(selected.utf8))
        guard try read(pin) == Data(selected.utf8) else { throw Failure.persistence }
        return selected
    }
}
