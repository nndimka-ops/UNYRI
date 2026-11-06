import Foundation

/// A recipient device the sender can address.
///
/// - `deviceId` is the backend `devices.id` UUID (used later for
///   `EnvelopeInput.recipient_device_id` envelope routing).
/// - `signalDeviceId` is the small int the Signal Protocol itself addresses
///   (`ProtocolAddress.deviceId`, 1...127).
///
/// These are distinct identifiers and must never be conflated.
struct SignalRecipientDevice: Equatable {
    let deviceId: String
    let signalDeviceId: Int32
    let addressName: String
}

/// Decoded bundle fields as raw bytes, after base64 validation but before
/// libsignal key deserialization (kept libsignal-free so this file can be
/// exercised on Windows).
struct DecodedSignalBundle: Equatable {
    let deviceId: String
    let signalDeviceId: Int32
    let registrationId: Int32
    let identityKey: Data
    let signedPrekeyId: Int32
    let signedPrekeyPublic: Data
    let signedPrekeySignature: Data
    let kyberPrekeyId: Int32
    let kyberPrekeyPublic: Data
    let kyberPrekeySignature: Data
    let oneTimePrekeyId: Int32?
    let oneTimePrekeyPublic: Data?
}

/// Decodes backend `DeviceBundle` DTOs into raw bytes, rejecting malformed
/// base64 and inconsistent one-time-prekey pairs clearly. Mirrors Android,
/// which throws on corrupt bundle data rather than silently skipping it.
enum SignalBundleDecoder {
    enum BundleError: Error, LocalizedError {
        case malformedBase64(field: String)
        case inconsistentOneTimePrekey

        var errorDescription: String? {
            switch self {
            case .malformedBase64(let field):
                return "Malformed base64 in bundle field: \(field)"
            case .inconsistentOneTimePrekey:
                return "One-time prekey id and public key must both be present or both absent"
            }
        }
    }

    static func decode(_ bundle: DeviceBundle) throws -> DecodedSignalBundle {
        if (bundle.oneTimePrekeyId == nil) != (bundle.oneTimePrekeyPublic == nil) {
            throw BundleError.inconsistentOneTimePrekey
        }
        return DecodedSignalBundle(
            deviceId: bundle.deviceId,
            signalDeviceId: bundle.signalDeviceId,
            registrationId: bundle.registrationId,
            identityKey: try decodeBase64(bundle.identityKey, field: "identity_key"),
            signedPrekeyId: bundle.signedPrekeyId,
            signedPrekeyPublic: try decodeBase64(bundle.signedPrekeyPublic, field: "signed_prekey_public"),
            signedPrekeySignature: try decodeBase64(bundle.signedPrekeySignature, field: "signed_prekey_signature"),
            kyberPrekeyId: bundle.kyberPrekeyId,
            kyberPrekeyPublic: try decodeBase64(bundle.kyberPrekeyPublic, field: "kyber_prekey_public"),
            kyberPrekeySignature: try decodeBase64(bundle.kyberPrekeySignature, field: "kyber_prekey_signature"),
            oneTimePrekeyId: bundle.oneTimePrekeyId,
            oneTimePrekeyPublic: try decodeOptionalBase64(bundle.oneTimePrekeyPublic, field: "one_time_prekey_public")
        )
    }

    /// Maps fetched bundles to recipient devices for a given user. The backend
    /// device UUID is retained separately from the Signal device id.
    static func recipients(for userId: String, bundles: [DeviceBundle]) -> [SignalRecipientDevice] {
        bundles.map {
            SignalRecipientDevice(
                deviceId: $0.deviceId,
                signalDeviceId: $0.signalDeviceId,
                addressName: userId
            )
        }
    }

    private static func decodeBase64(_ value: String, field: String) throws -> Data {
        guard let data = Base64.decode(value) else {
            throw BundleError.malformedBase64(field: field)
        }
        return data
    }

    private static func decodeOptionalBase64(_ value: String?, field: String) throws -> Data? {
        guard let value else { return nil }
        return try decodeBase64(value, field: field)
    }
}
