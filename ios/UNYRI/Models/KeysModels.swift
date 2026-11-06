import Foundation

// MARK: - Key upload (POST /keys)
// Mirrors server/src/keys/models.rs. All key bytes are base64 strings; the
// server stores them opaquely and never interprets them.

struct SignedPreKeyInput: Codable, Equatable {
    let keyId: Int32
    let publicKey: String
    let signature: String
}

struct KyberPreKeyInput: Codable, Equatable {
    let keyId: Int32
    let publicKey: String
    let signature: String
}

struct OneTimePreKeyInput: Codable, Equatable {
    let keyId: Int32
    let publicKey: String
}

struct UploadKeysRequest: Encodable, Equatable {
    let identityKey: String
    let registrationId: Int32
    let signedPrekey: SignedPreKeyInput
    let kyberPrekey: KyberPreKeyInput
    let oneTimePrekeys: [OneTimePreKeyInput]
}

struct UploadKeysResponse: Decodable, Equatable {
    let oneTimePrekeyCount: Int64
}

// MARK: - Bundle fetch (GET /keys/bundles/:user_id)

struct DeviceBundle: Decodable, Equatable {
    let deviceId: String
    let signalDeviceId: Int32
    let registrationId: Int32
    let identityKey: String
    let signedPrekeyId: Int32
    let signedPrekeyPublic: String
    let signedPrekeySignature: String
    let kyberPrekeyId: Int32
    let kyberPrekeyPublic: String
    let kyberPrekeySignature: String
    let oneTimePrekeyId: Int32?
    let oneTimePrekeyPublic: String?
}

struct UserBundlesResponse: Decodable, Equatable {
    let devices: [DeviceBundle]
}

struct OneTimePreKeyCountResponse: Decodable, Equatable {
    let count: Int64
}
