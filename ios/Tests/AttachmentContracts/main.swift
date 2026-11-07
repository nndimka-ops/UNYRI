import Foundation
import SQLite3
import CryptoKit

if let path = ProcessInfo.processInfo.environment["UNYRI_VERIFY_TINK"] {
    let root = URL(fileURLWithPath: path)
    for folder in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
        let output = folder.appendingPathComponent("swift-decrypted")
        try AttachmentCrypto.decryptV2(ciphertextFile: folder.appendingPathComponent("tink.enc"), plaintextFile: output,
            keyset: Data(contentsOf: folder.appendingPathComponent("keyset")), expectedSha256: Data(contentsOf: folder.appendingPathComponent("tink.sha256")))
        guard try Data(contentsOf: output) == Data(contentsOf: folder.appendingPathComponent("plain")) else { fatalError("Tink -> Swift mismatch") }
    }
    print("PASS: Tink ciphertext read by production Swift decoder")
    exit(0)
}

// Pure attachment-pipeline contract checks — no network, no libsignal.
// Mirrors the backend attachment DTOs and Android's v1 AES-256-GCM + SHA-256
// semantics, caught on macOS CI without an iPhone.

var failures = 0

func fail(_ description: String) {
    failures += 1
    let line = "FAIL: \(description)\n"
    FileHandle.standardError.write(Data(line.utf8))
}

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    if condition() {
        print("PASS: \(description)")
    } else {
        fail(description)
    }
}

func expect<T: Equatable>(_ actual: T, _ expected: T, _ description: String) {
    if actual == expected {
        print("PASS: \(description)")
    } else {
        fail("\(description) (expected \(expected), got \(actual))")
    }
}

// MARK: - Backend DTO encoding/decoding

let encoder = JSONEncoder()
encoder.keyEncodingStrategy = .convertToSnakeCase
let createBody = try! encoder.encode(CreateAttachmentRequest(clientAttachmentId: "cid-1", encryptedSize: 42))
let createJSON = String(decoding: createBody, as: UTF8.self)
check(createJSON.contains("client_attachment_id"), "create request uses client_attachment_id")
check(createJSON.contains("encrypted_size"), "create request uses encrypted_size")
check(!createJSON.contains("clientAttachmentId"), "create request never sends camelCase")

let decoder = JSONDecoder()
decoder.keyDecodingStrategy = .convertFromSnakeCase
decoder.dateDecodingStrategy = .iso8601
let createResponse = """
{"attachment_id":"a1","object_key":"k1","upload_url":"https://storage/x","expires_at":"2026-09-08T20:00:00Z"}
"""
if let parsed = try? decoder.decode(CreateAttachmentResponse.self, from: Data(createResponse.utf8)) {
    expect(parsed.attachmentId, "a1", "create response attachment_id")
    expect(parsed.objectKey, "k1", "create response object_key")
    expect(parsed.uploadUrl, "https://storage/x", "create response upload_url")
} else {
    fail("CreateAttachmentResponse decodes")
}

let downloadResponse = """
{"download_url":"https://storage/d","expires_at":"2026-09-08T20:00:00Z"}
"""
if let parsed = try? decoder.decode(DownloadAttachmentResponse.self, from: Data(downloadResponse.utf8)) {
    expect(parsed.downloadUrl, "https://storage/d", "download response download_url")
} else {
    fail("DownloadAttachmentResponse decodes")
}

// MARK: - AttachmentMetadata (Signal-encrypted content) Codable round-trip

let meta = AttachmentMetadata(
    attachmentId: "att-1",
    mediaType: AttachmentMetadata.mediaTypeImage,
    objectKey: "obj-1",
    size: 1234,
    encryptedSize: 1300,
    mimeType: "image/jpeg",
    encryption: EncryptionInfo(version: 1, key: "a2V5", nonce: "bm9uY2U=", keyset: nil, sha256: "c2hh"),
    filename: nil,
    image: ImageInfo(width: 640, height: 480),
    voice: nil,
    thumbnail: "dGh1bWI="
)
let metaJSON = try! JSONEncoder().encode(meta)
let metaJSONString = String(decoding: metaJSON, as: UTF8.self)
check(metaJSONString.contains("attachment_id"), "metadata uses attachment_id")
check(metaJSONString.contains("media_type"), "metadata uses media_type")
check(metaJSONString.contains("encrypted_size"), "metadata uses encrypted_size")
check(metaJSONString.contains("mime_type"), "metadata uses mime_type")
let metaRound = try! JSONDecoder().decode(AttachmentMetadata.self, from: metaJSON)
expect(metaRound, meta, "AttachmentMetadata Codable round-trips exactly")

// EncryptionInfo version 1 omits the default version key and absent fields.
let encJSON = String(decoding: try! JSONEncoder().encode(meta.encryption), as: UTF8.self)
check(!encJSON.contains("version"), "encryption v1 omits default version")
check(!encJSON.contains("keyset"), "encryption v1 omits absent keyset")

// MARK: - AttachmentRecord -> metadata mapping

let record = AttachmentRecord(
    attachmentId: "att-1", clientMessageId: "m1", chatId: "c1",
    mediaType: "image", mimeType: "image/jpeg", filename: "pic.jpg",
    size: 100, width: 640, height: 480, durationMs: nil,
    thumbnailBase64: "dGh1bWI=", localPath: nil,
    downloadState: "not_downloaded", uploadState: "uploaded", progress: 0,
    objectKey: "obj-1", encryptedSize: 130, encryptionVersion: 1,
    keyBase64: "a2V5", nonceBase64: "bm9uY2U=", keysetBase64: nil, sha256Base64: "c2hh"
)
let mapped = attachmentMetadata(from: record)
expect(mapped.attachmentId, "att-1", "record->metadata attachmentId")
expect(mapped.image?.width, 640, "record->metadata image width")
expect(mapped.encryption.key, "a2V5", "record->metadata encryption key")
expect(mapped.encryption.version, 1, "record->metadata encryption version")

// MARK: - v1 AES-256-GCM round-trip (CryptoKit, byte-compatible with javax.crypto)

func tempDir() -> URL {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("coco-att-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
}

do {
    let dir = tempDir()
    defer { try? FileManager.default.removeItem(at: dir) }
    let plaintext = dir.appendingPathComponent("p.bin")
    let ciphertext = dir.appendingPathComponent("c.enc")
    let restored = dir.appendingPathComponent("r.bin")
    let payload = Data((0..<500_000).map { UInt8($0 % 251) })
    try payload.write(to: plaintext)

    let material = AttachmentCrypto.generateV1KeyMaterial()
    let sha = try AttachmentCrypto.encryptV1(
        plaintextFile: plaintext, ciphertextFile: ciphertext,
        key: material.key, nonce: material.nonce
    )

    // SHA-256 is over the ciphertext (tag included).
    let ciphertextOnDisk = try Data(contentsOf: ciphertext)
    expect(Data(sha), try AttachmentCrypto.sha256File(ciphertext), "sha256 over ciphertext")
    check(ciphertextOnDisk.count == payload.count + 16, "ciphertext = plaintext || 16-byte tag")

    try AttachmentCrypto.decryptV1(
        ciphertextFile: ciphertext, plaintextFile: restored,
        key: material.key, nonce: material.nonce, expectedSha256: Data(sha)
    )
    expect(try Data(contentsOf: restored), payload, "v1 decrypt round-trips exactly")

    // Tampered ciphertext fails closed on the SHA-256 pre-check.
    var tampered = ciphertextOnDisk
    tampered[0] ^= 0xFF
    try tampered.write(to: ciphertext)
    var integrityFailed = false
    do {
        try AttachmentCrypto.decryptV1(
            ciphertextFile: ciphertext, plaintextFile: restored,
            key: material.key, nonce: material.nonce, expectedSha256: Data(sha)
        )
    } catch is AttachmentIntegrityError {
        integrityFailed = true
    }
    check(integrityFailed, "tampered ciphertext fails SHA-256 integrity check")

    // Restore the untampered ciphertext, then prove a WRONG key fails AEAD
    // authentication (hash passes, tag does not).
    try ciphertextOnDisk.write(to: ciphertext)
    let wrongKey = SymmetricKey(size: .bits256)
    var authFailed = false
    do {
        try AttachmentCrypto.decryptV1(
            ciphertextFile: ciphertext, plaintextFile: restored,
            key: wrongKey, nonce: material.nonce,
            expectedSha256: try AttachmentCrypto.sha256File(ciphertext)
        )
    } catch {
        authFailed = true
    }
    check(authFailed, "wrong key fails AEAD authentication")
} catch {
    fail("v1 crypto round-trip: \(error)")
}

// MARK: - AttachmentStore state machine (SQLite)

do {
    let dir = tempDir()
    defer { try? FileManager.default.removeItem(at: dir) }
    let store = try AttachmentStore(databaseURL: dir.appendingPathComponent("attachments.sqlite3"))
    let r = AttachmentRecord(
        attachmentId: "a1", clientMessageId: "m1", chatId: "c1",
        mediaType: "image", mimeType: "image/jpeg", filename: "pic.jpg",
        size: 100, width: nil, height: nil, durationMs: nil,
        thumbnailBase64: nil, localPath: nil,
        downloadState: "not_downloaded", uploadState: "uploading", progress: 0,
        objectKey: "obj", encryptedSize: 130, encryptionVersion: 1,
        keyBase64: "a2V5", nonceBase64: "bm9uY2U=", keysetBase64: nil, sha256Base64: "c2hh"
    )
    try store.upsert(r)
    expect(try store.attachment(byId: "a1")?.uploadState, "uploading", "store upsert persists uploadState")

    try store.updateUploadState(attachmentId: "a1", state: "uploaded", progress: 100)
    expect(try store.attachment(byId: "a1")?.uploadState, "uploaded", "store updateUploadState")

    try store.updateDownloadState(attachmentId: "a1", state: "downloading", progress: 50)
    expect(try store.attachment(byId: "a1")?.downloadState, "downloading", "store updateDownloadState")

    try store.markDownloaded(attachmentId: "a1", localPath: "/tmp/x.jpg")
    let downloaded = try store.attachment(byId: "a1")
    expect(downloaded?.downloadState, "downloaded", "store markDownloaded")
    expect(downloaded?.localPath, "/tmp/x.jpg", "store markDownloaded localPath")
    expect(downloaded?.progress, 100, "store markDownloaded progress")

    try store.clearLocalCache(attachmentId: "a1")
    let cleared = try store.attachment(byId: "a1")
    expect(cleared?.downloadState, "not_downloaded", "store clearLocalCache")
    check(cleared?.localPath == nil, "store clearLocalCache drops path")

    // ClientMessageId lookup is independent of the attachmentId key.
    expect(try store.attachment(byClientMessageId: "m1")?.attachmentId, "a1", "store byClientMessageId")
} catch {
    fail("AttachmentStore: \(error)")
}

for version in [1, 2] {
    for durationKey in ["durationMs", "duration_ms"] {
        let raw = """
        {"kind":"attachment","attachment":{"attachment_id":"voice-test","media_type":"voice","object_key":"attachments/test","size":12,"encrypted_size":28,"mime_type":"audio/mp4","encryption":{"version":\(version),"sha256":"test"},"voice":{"\(durationKey)":3000}}}
        """
        let content = MessageContentCodec.decode(Data(raw.utf8))
        expect(content.attachment?.voice?.durationMs, 3000, "voice interop v\(version) \(durationKey)")
        expect(content.attachment?.encryption.version, version, "crypto version survives envelope decoding")
    }
}
let malformedVoice = MessageContentCodec.decode(Data(#"{"kind":"attachment","attachment":{"encryption":{"key":"SECRET"}}}"#.utf8))
check(!(malformedVoice.text ?? "").contains("SECRET"), "malformed attachment never leaks envelope into UI")

// MARK: - Attachment size formatting + limits (Phase 7)

expect(AttachmentFormat.fileSize(0), "0 B", "size 0")
expect(AttachmentFormat.fileSize(512), "512 B", "size bytes")
expect(AttachmentFormat.fileSize(1024), "1 KB", "size 1 KB")
expect(AttachmentFormat.fileSize(12 * 1024), "12 KB", "size 12 KB")
expect(AttachmentFormat.fileSize(3_670_016), "3.5 MB", "size 3.5 MB")
expect(AttachmentFormat.fileSize(-1), "0 B", "negative size clamps")
expect(AttachmentFormat.maxItems, 10, "selection cap is 10")
check(AttachmentFormat.maxImageBytes == 25 * 1024 * 1024, "image limit 25 MB")
check(AttachmentFormat.maxFileBytes == 100 * 1024 * 1024, "file limit 100 MB")

// An empty AES-GCM plaintext is represented by its valid 16-byte tag.
do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("empty")
    let encrypted = directory.appendingPathComponent("encrypted")
    let output = directory.appendingPathComponent("output")
    try Data().write(to: source)
    let material = AttachmentCrypto.generateV1KeyMaterial()
    let hash = try AttachmentCrypto.encryptV1(plaintextFile: source, ciphertextFile: encrypted, key: material.key, nonce: material.nonce)
    try AttachmentCrypto.decryptV1(ciphertextFile: encrypted, plaintextFile: output, key: material.key, nonce: material.nonce, expectedSha256: hash)
    expect(try Data(contentsOf: output).count, 0, "empty attachment decrypts with valid tag")
} catch { fail("empty attachment round trip: \(error)") }

do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("source")
    let target = directory.appendingPathComponent("target")
    let bytes = Data(repeating: 7, count: 131072)
    try bytes.write(to: source)
    try AttachmentFileCopy.copy(from: source, to: target, maxBytes: Int64(bytes.count))
    expect(try Data(contentsOf: target), bytes, "bounded copy preserves exact-limit file")
    do {
        try AttachmentFileCopy.copy(from: source, to: target, maxBytes: 70000)
        fail("oversized file copy must fail")
    } catch AttachmentFileCopy.CopyError.tooLarge { }
    expect(try Data(contentsOf: target), bytes, "size preflight preserves an existing destination")
    try Data().write(to: source)
    try AttachmentFileCopy.copy(from: source, to: target, maxBytes: 0)
    expect(try Data(contentsOf: target).count, 0, "empty file copy succeeds")
} catch { fail("bounded file copy: \(error)") }

do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    for size in [0, 1, 1_048_520, 1_048_521, 2_097_080, 3_200_001] {
        let plain = directory.appendingPathComponent("plain")
        let encrypted = directory.appendingPathComponent("cipher")
        let result = directory.appendingPathComponent("result")
        let bytes = Data((0..<size).map { UInt8($0 % 251) })
        try bytes.write(to: plain)
        let keyset = AttachmentCrypto.generateV2Keyset()
        let hash = try AttachmentCrypto.encryptV2(plaintextFile: plain, ciphertextFile: encrypted, keyset: keyset)
        if let path = ProcessInfo.processInfo.environment["UNYRI_STREAMING_FIXTURES"] {
            let folder = URL(fileURLWithPath: path).appendingPathComponent(String(size))
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try bytes.write(to: folder.appendingPathComponent("plain"))
            try keyset.write(to: folder.appendingPathComponent("keyset"))
            try Data(contentsOf: encrypted).write(to: folder.appendingPathComponent("swift.enc"))
        }
        try AttachmentCrypto.decryptV2(ciphertextFile: encrypted, plaintextFile: result, keyset: keyset, expectedSha256: hash)
        expect(try Data(contentsOf: result), bytes, "streaming v2 round trip at boundary \(size)")
        var corrupt = try Data(contentsOf: encrypted)
        corrupt[corrupt.count - 1] ^= 1
        try corrupt.write(to: encrypted)
        do {
            try AttachmentCrypto.decryptV2(ciphertextFile: encrypted, plaintextFile: result, keyset: keyset, expectedSha256: AttachmentCrypto.sha256File(encrypted))
            fail("tampered streaming final tag must fail")
        } catch { }
        expect(try Data(contentsOf: result), bytes, "tampering never replaces verified plaintext")
    }
} catch { fail("streaming upload contracts: \(error)") }

if failures > 0 {
    print("\(failures) attachment contract check(s) failed")
    exit(1)
}
print("Attachment contract checks passed")
