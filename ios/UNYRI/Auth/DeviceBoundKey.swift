import Foundation
import Security
import CryptoKit

/// Only public SEC1 bytes leave Security. The key itself is never serialized.
enum DeviceBoundKey {
    private static let queue = DispatchQueue(label: "unyri.device-bound-key", qos: .userInitiated)
    private static func tag(_ id: String) -> Data {
        Data(((Bundle.main.bundleIdentifier ?? "com.messenger.coco") + ".device-bound." + id).utf8)
    }
    private static func query(_ id: String) -> [String: Any] {
        [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag(id),
         kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
         kSecAttrKeyClass as String: kSecAttrKeyClassPrivate]
    }

    private static func key(_ id: String) throws -> SecKey {
        var lookup = query(id)
        lookup[kSecReturnRef as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &value)
        if status == errSecItemNotFound { throw DeviceBoundProof.Failure.missingKey }
        guard status == errSecSuccess, let value else { throw DeviceBoundProof.Failure.unavailable }
        return value as! SecKey
    }

    private static func registration(_ privateKey: SecKey) throws -> DeviceSessionKey {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw DeviceBoundProof.Failure.unavailable }
        var error: Unmanaged<CFError>?
        guard let bytes = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?,
              bytes.count == 65, bytes.first == 4 else { throw DeviceBoundProof.Failure.unavailable }
        let attributes = SecKeyCopyAttributes(privateKey) as? [String: Any]
        let enclave = (attributes?[kSecAttrTokenID as String] as? String) == (kSecAttrTokenIDSecureEnclave as String)
        return DeviceSessionKey(publicKey: DeviceBoundProof.base64URL(bytes), protection: enclave ? "secure_enclave" : "keychain")
    }

    private static func perform<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do { continuation.resume(returning: try operation()) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func create(id: String) throws -> DeviceSessionKey {
            // Only absent hardware permits a software Keychain key. A locked or
            // failing Secure Enclave never causes a silent downgrade.
            let enclave = SecureEnclave.isAvailable
            var privateAttributes: [String: Any] = [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag(id),
            ]
            var attributes: [String: Any] = [
                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits as String: 256,
            ]
            if enclave {
                var accessError: Unmanaged<CFError>?
                guard let access = SecAccessControlCreateWithFlags(nil,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, &accessError) else {
                    throw DeviceBoundProof.Failure.unavailable
                }
                privateAttributes[kSecAttrAccessControl as String] = access
                attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
            } else {
                privateAttributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                privateAttributes[kSecAttrSynchronizable as String] = false
                privateAttributes[kSecAttrIsExtractable as String] = false
            }
            attributes[kSecPrivateKeyAttrs as String] = privateAttributes
            var error: Unmanaged<CFError>?
            guard let generated = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
                throw DeviceBoundProof.Failure.unavailable
            }
            return try registration(generated)
    }

    /// Both ID lookup and persistence run on the signing queue. Independent
    /// SessionStore instances for an active/inactive slot cannot race enrollment.
    static func prepare(readID: @escaping () throws -> String?, saveID: @escaping (String) throws -> Void,
                        mayReplaceMissing: @escaping () throws -> Bool = { false })
        async throws -> (id: String, key: DeviceSessionKey) {
        try await perform {
            if let id = try readID() {
                do { return (id, try registration(key(id))) }
                catch DeviceBoundProof.Failure.missingKey {
                    guard try mayReplaceMissing() else { throw DeviceBoundProof.Failure.missingKey }
                }
            }
            let id = UUID().uuidString.lowercased()
            let registration = try create(id: id)
            do { try saveID(id) }
            catch { try? erase(id: id); throw error }
            return (id, registration)
        }
    }

    static func registration(id: String) async throws -> DeviceSessionKey {
        try await perform { try registration(key(id)) }
    }

    static func proof(id: String, method: String, target: String, session: String,
                      token: String, timestamp: Int64) async throws -> String {
        try await perform {
            let privateKey = try key(id)
            return try DeviceBoundProof.build(method: method, target: target, session: session,
                token: token, timestamp: timestamp) { message in
                var error: Unmanaged<CFError>?
                guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256,
                    message as CFData, &error) as Data? else { throw DeviceBoundProof.Failure.unavailable }
                return signature
            }
        }
    }

    private static func erase(id: String) throws {
        let status = SecItemDelete(query(id) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw DeviceBoundProof.Failure.persistence }
    }

    /// Serialize teardown with queued enrollment/signing. Looking up the ID on
    /// this queue prevents an in-flight first enrollment from escaping logout.
    static func eraseStored(readID: () throws -> String?, removeID: () -> Void) throws {
        try queue.sync {
            if let id = try readID() { try erase(id: id) }
            removeID()
        }
    }
}
