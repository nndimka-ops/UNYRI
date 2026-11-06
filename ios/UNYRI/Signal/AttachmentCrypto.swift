import CryptoKit
import Foundation

/// Bulk attachment encryption (mirrors Android's `AttachmentCrypto` — Этап H).
///
/// **Version 1 — AES-256-GCM** (NIST standard, Apple CryptoKit): a fresh
/// random 32-byte key + 12-byte nonce per attachment. The on-disk ciphertext
/// is the GCM `ciphertext || tag` (NOT CryptoKit's nonce-prefixed `.combined`
/// form — the nonce travels separately inside the Signal-encrypted metadata,
/// exactly as Android's v1 does), so the file format is byte-compatible with
/// Android's `javax.crypto` `AES/GCM/NoPadding` for the same key/nonce.
/// The SHA-256 is over the ciphertext (tag included).
///
/// **Version 2 — Tink Streaming AEAD** (`AES256_GCM_HKDF_1MB`, Android's
/// current format): decrypts UNYRI's single RAW-key template through CryptoKit.
/// Unsupported keysets/templates fail closed. New uploads use v2 as well.
struct AttachmentIntegrityError: Error, LocalizedError {
    var errorDescription: String? { "Attachment ciphertext failed integrity verification." }
}

struct AttachmentV2UnsupportedError: Error, LocalizedError {
    var errorDescription: String? { "This attachment uses an encryption version this app cannot read yet." }
}

enum AttachmentCrypto {
    static let versionV1AesGcm = EncryptionInfo.versionV1AesGcm
    static let versionV2StreamingAead = EncryptionInfo.versionV2StreamingAead

    /// Fresh random per-attachment v1 secret — NEVER reused, NEVER sent to
    /// the backend (only inside the Signal-encrypted message content).
    static func generateV1KeyMaterial() -> (keyBase64: String, nonceBase64: String, key: SymmetricKey, nonce: AES.GCM.Nonce) {
        let key = SymmetricKey(size: .bits256)
        let nonce = AES.GCM.Nonce()
        let keyBase64 = key.withUnsafeBytes { Data($0).base64EncodedString() }
        let nonceData = nonce.withUnsafeBytes { Data($0) }
        return (keyBase64, nonceData.base64EncodedString(), key, nonce)
    }

    /// Encrypts `plaintextFile` to `ciphertextFile` (GCM `ciphertext || tag`)
    /// and returns the SHA-256 of the ciphertext bytes. Mirrors Android v1's
    /// whole-file memory profile (JCA GCM also buffers the full payload).
    static func encryptV1(
        plaintextFile: URL,
        ciphertextFile: URL,
        key: SymmetricKey,
        nonce: AES.GCM.Nonce
    ) throws -> Data {
        let plaintext = try Data(contentsOf: plaintextFile)
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        let ciphertext = box.ciphertext + box.tag
        try ciphertext.write(to: ciphertextFile, options: .atomic)
        return Data(SHA256.hash(data: ciphertext))
    }

    /// Verifies the ciphertext SHA-256 FIRST (fail-closed on gross transport
    /// corruption/truncation), then AES-GCM-authenticates and decrypts.
    /// Throws `AttachmentIntegrityError` on hash mismatch; CryptoKit throws
    /// its own authentication error if the tag is bad.
    static func decryptV1(
        ciphertextFile: URL,
        plaintextFile: URL,
        key: SymmetricKey,
        nonce: AES.GCM.Nonce,
        expectedSha256: Data
    ) throws {
        let ciphertext = try Data(contentsOf: ciphertextFile)
        let actual = Data(SHA256.hash(data: ciphertext))
        guard actual == expectedSha256 else {
            throw AttachmentIntegrityError()
        }

        let tagSize = 16
        guard ciphertext.count >= tagSize else {
            throw AttachmentIntegrityError()
        }
        let body = ciphertext.dropLast(tagSize)
        let tag = ciphertext.suffix(tagSize)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(body), tag: Data(tag))
        let plaintext = try AES.GCM.open(box, using: key)
        try plaintext.write(to: plaintextFile, options: .atomic)
    }

    /// Streaming SHA-256 of a file (bounded memory) — used for the
    /// pre-verification pass on download.
    static func sha256File(_ file: URL) throws -> Data {
        var hasher = SHA256()
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        while let chunk = try handle.read(upToCount: 64 * 1024), !chunk.isEmpty {
            try Task.checkCancellation()
            hasher.update(data: chunk)
        }
        return Data(hasher.finalize())
    }
}

extension AttachmentCrypto {
    /// Tink RAW AES256_GCM_HKDF_1MB framing, using CryptoKit primitives.
    /// Random key per attachment; random salt/prefix per encryption. No key
    /// material leaves the encrypted message/local account storage.
    static func generateV2Keyset() -> Data {
        let bytes = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        return TinkAttachmentKey.encode(bytes)
    }

    static func encryptV2(plaintextFile: URL, ciphertextFile: URL, keyset: Data) throws -> Data {
        let keyBytes = try TinkAttachmentKey.parse(keyset)
        let salt = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let prefix = AES.GCM.Nonce().withUnsafeBytes { Data($0.prefix(7)) }
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: keyBytes),
            salt: salt, info: Data(), outputByteCount: 32)
        let input = try FileHandle(forReadingFrom: plaintextFile)
        defer { try? input.close() }
        let length = try input.seekToEnd()
        try input.seek(toOffset: 0)
        let temporary = ciphertextFile.appendingPathExtension(UUID().uuidString + ".partial")
        guard FileManager.default.createFile(atPath: temporary.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        defer { try? FileManager.default.removeItem(at: temporary) }
        let output = try FileHandle(forWritingTo: temporary)
        defer { try? output.close() }
        var hash = SHA256()
        func write(_ data: Data) throws { try output.write(contentsOf: data); hash.update(data: data) }
        try write(Data([40]) + salt + prefix)
        var remaining = length
        var segment: UInt64 = 0
        repeat {
            try Task.checkCancellation()
            guard segment <= UInt64(UInt32.max) else { throw AttachmentIntegrityError() }
            let capacity = 1_048_576 - 16 - (segment == 0 ? 40 : 0)
            let count = Int(min(UInt64(capacity), remaining))
            let plain = try readExactly(input, count: count)
            remaining -= UInt64(count)
            var counter = UInt32(segment).bigEndian
            let nonce = try AES.GCM.Nonce(data: prefix + withUnsafeBytes(of: &counter) { Data($0) } + Data([remaining == 0 ? 1 : 0]))
            let box = try AES.GCM.seal(plain, using: key, nonce: nonce)
            try write(box.ciphertext)
            try write(box.tag)
            segment += 1
        } while remaining > 0
        guard (try input.read(upToCount: 1) ?? Data()).isEmpty else { throw AttachmentIntegrityError() }
        try Task.checkCancellation()
        try output.synchronize()
        try output.close()
        if FileManager.default.fileExists(atPath: ciphertextFile.path) {
            _ = try FileManager.default.replaceItemAt(ciphertextFile, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: ciphertextFile)
        }
        return Data(hash.finalize())
    }

    /// UNYRI Android's RAW, single-key AES256_GCM_HKDF_1MB format.
    /// Specification: https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming
    /// CryptoKit supplies HKDF-SHA256 and AES-GCM; this adapter handles only
    /// Tink keyset/segment framing. No plaintext is published before all tags
    /// (including the final-segment bit) and the transport hash are verified.
    static func decryptV2(ciphertextFile: URL, plaintextFile: URL,
                          keyset: Data, expectedSha256: Data) throws {
        let keyBytes = try TinkAttachmentKey.parse(keyset)
        guard expectedSha256.count == 32,
              try sha256File(ciphertextFile) == expectedSha256 else { throw AttachmentIntegrityError() }
        let input = try FileHandle(forReadingFrom: ciphertextFile)
        defer { try? input.close() }
        let size = try input.seekToEnd()
        try input.seek(toOffset: 0)
        guard size >= 56 else { throw AttachmentIntegrityError() }
        let header = try readExactly(input, count: 40)
        guard header.first == 40 else { throw AttachmentIntegrityError() }
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: keyBytes),
            salt: Data(header[1..<33]), info: Data(), outputByteCount: 32)
        let prefix = Data(header[33..<40])
        let temporary = plaintextFile.appendingPathExtension(UUID().uuidString + ".partial")
        guard FileManager.default.createFile(atPath: temporary.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        defer { try? FileManager.default.removeItem(at: temporary) }
        let output = try FileHandle(forWritingTo: temporary)
        defer { try? output.close() }
        var remaining = size - 40
        var segment: UInt64 = 0
        while remaining > 0 {
            try Task.checkCancellation()
            guard segment <= UInt64(UInt32.max) else { throw AttachmentIntegrityError() }
            let capacity = 1_048_576 - (segment == 0 ? 40 : 0)
            let count = Int(min(remaining, UInt64(capacity)))
            guard count >= 16, segment == 0 || count > 16 else { throw AttachmentIntegrityError() }
            let encrypted = try readExactly(input, count: count)
            remaining -= UInt64(count)
            var counter = UInt32(segment).bigEndian
            let nonceBytes = prefix + withUnsafeBytes(of: &counter) { Data($0) } + Data([remaining == 0 ? 1 : 0])
            let nonce = try AES.GCM.Nonce(data: nonceBytes)
            let box = try AES.GCM.SealedBox(nonce: nonce,
                ciphertext: Data(encrypted.dropLast(16)), tag: Data(encrypted.suffix(16)))
            let plain = try AES.GCM.open(box, using: key)
            try output.write(contentsOf: plain)
            segment += 1
        }
        try Task.checkCancellation()
        try output.close()
        if FileManager.default.fileExists(atPath: plaintextFile.path) {
            _ = try FileManager.default.replaceItemAt(plaintextFile, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: plaintextFile)
        }
    }

    private static func readExactly(_ handle: FileHandle, count: Int) throws -> Data {
        var result = Data()
        while result.count < count {
            guard let part = try handle.read(upToCount: count - result.count), !part.isEmpty else {
                throw AttachmentIntegrityError()
            }
            result.append(part)
        }
        return result
    }
}

/// Strict bounded parser for the single RAW keysets generated per attachment
/// by UNYRI Android. Rotated/multiple-key sets and other templates are rejected.
private enum TinkAttachmentKey {
    static func encode(_ key: Data) -> Data {
        func varint(_ value: UInt64) -> Data {
            var remaining = value, result = Data()
            while remaining >= 128 { result.append(UInt8(remaining & 127) | 128); remaining >>= 7 }
            result.append(UInt8(remaining)); return result
        }
        func number(_ field: UInt64, _ value: UInt64) -> Data { varint(field << 3) + varint(value) }
        func bytes(_ field: UInt64, _ value: Data) -> Data { varint(field << 3 | 2) + varint(UInt64(value.count)) + value }
        let params = number(1, 1_048_576) + number(2, 32) + number(3, 3)
        let streaming = bytes(2, params) + bytes(3, key)
        let data = bytes(1, Data("type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey".utf8)) + bytes(2, streaming) + number(3, 1)
        // A fresh single-key keyset has no rotation: RAW key id 1 is local
        // bookkeeping only, never a nonce or cryptographic secret.
        return number(1, 1) + bytes(2, bytes(1, data) + number(2, 1) + number(3, 1) + number(4, 3))
    }

    static func parse(_ data: Data) throws -> Data {
        let set = try Fields(data)
        let entry = try Fields(set.bytes(2))
        guard set.number(1) > 0, set.number(1) <= UInt64(UInt32.max),
              entry.number(2) == 1, entry.number(3) == set.number(1), entry.number(4) == 3 else {
            throw AttachmentIntegrityError()
        }
        let keyData = try Fields(entry.bytes(1))
        guard String(data: try keyData.bytes(1), encoding: .utf8) == "type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey",
              keyData.number(3) == 1 else { throw AttachmentV2UnsupportedError() }
        let key = try Fields(keyData.bytes(2))
        let params = try Fields(key.bytes(2))
        let value = try key.bytes(3)
        guard key.number(1) == 0, params.number(1) == 1_048_576,
              params.number(2) == 32, params.number(3) == 3,
              value.count == 32 else { throw AttachmentV2UnsupportedError() }
        return value
    }

    private struct Fields {
        private var integers: [UInt64: UInt64] = [:]
        private var blobs: [UInt64: Data] = [:]
        init(_ data: Data) throws {
            guard data.count <= 16_384 else { throw AttachmentIntegrityError() }
            let bytes = [UInt8](data)
            var cursor = 0
            var seen: Set<UInt64> = []
            func varint() throws -> UInt64 {
                var value: UInt64 = 0
                for index in 0..<10 {
                    guard cursor < bytes.count else { throw AttachmentIntegrityError() }
                    let byte = bytes[cursor]; cursor += 1
                    guard index != 9 || byte <= 1 else { throw AttachmentIntegrityError() }
                    value |= UInt64(byte & 127) << (index * 7)
                    if byte < 128 { return value }
                }
                throw AttachmentIntegrityError()
            }
            while cursor < bytes.count {
                let tag = try varint(), field = tag >> 3
                guard field > 0, field < 1 << 29, seen.insert(field).inserted else { throw AttachmentIntegrityError() }
                switch tag & 7 {
                case 0: integers[field] = try varint()
                case 2:
                    let length = try varint()
                    guard length <= UInt64(bytes.count - cursor) else { throw AttachmentIntegrityError() }
                    blobs[field] = Data(bytes[cursor..<(cursor + Int(length))])
                    cursor += Int(length)
                default: throw AttachmentIntegrityError()
                }
            }
        }
        func number(_ field: UInt64) -> UInt64 { integers[field] ?? 0 }
        func bytes(_ field: UInt64) throws -> Data {
            guard let value = blobs[field] else { throw AttachmentIntegrityError() }
            return value
        }
    }
}
