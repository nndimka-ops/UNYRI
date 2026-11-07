import Foundation

/// Standard Base64 (RFC 4648 with padding) — compatible with the backend's
/// `base64::engine::general_purpose::STANDARD`. No custom encodings.
enum Base64 {
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
    }

    static func decode(_ string: String) -> Data? {
        Data(base64Encoded: string)
    }
}
