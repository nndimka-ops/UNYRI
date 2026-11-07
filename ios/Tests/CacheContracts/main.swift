import Foundation

// Pure cache-management contract checks (Phase 9.1) — no network, no SQLite,
// no libsignal. Verifies the human-size formatter, the upload-source
// protection policy, and cache-total arithmetic that back the Data & Storage
// screen on iOS.

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

// MARK: - Human file-size formatting (locale-independent)

expect(FileSizeFormatter.format(0), "0 B", "0 bytes")
expect(FileSizeFormatter.format(1023), "1023 B", "sub-KB stays in bytes")
expect(FileSizeFormatter.format(1024), "1 KB", "1 KB boundary")
expect(FileSizeFormatter.format(843_776), "824 KB", "whole KB")
expect(FileSizeFormatter.format(13_002_342), "12.4 MB", "one-decimal MB")
expect(FileSizeFormatter.format(12 * 1024 * 1024), "12 MB", "trailing .0 trimmed")
expect(FileSizeFormatter.format(1_288_490_189), "1.2 GB", "one-decimal GB")
expect(FileSizeFormatter.format(-5), "0 B", "negative clamps to 0")

// MARK: - Upload-source protection policy

func record(uploadState: String) -> AttachmentRecord {
    AttachmentRecord(
        attachmentId: "a", clientMessageId: "c", chatId: "chat",
        mediaType: "image", mimeType: "image/jpeg", filename: nil, size: 1,
        width: nil, height: nil, durationMs: nil, thumbnailBase64: nil,
        localPath: "/tmp/x.jpg", downloadState: AttachmentRecord.downloadStateDownloaded,
        uploadState: uploadState, progress: 0, objectKey: "k", encryptedSize: 1,
        encryptionVersion: 1, keyBase64: nil, nonceBase64: nil, keysetBase64: nil,
        sha256Base64: "s"
    )
}

check(record(uploadState: AttachmentRecord.uploadStateUploading).isPendingUploadSource,
      "uploading attachment source is protected")
check(record(uploadState: AttachmentRecord.uploadStateFailed).isPendingUploadSource,
      "failed attachment source is protected")
check(!record(uploadState: AttachmentRecord.uploadStateUploaded).isPendingUploadSource,
      "uploaded attachment source is clearable")

// MARK: - Cache totals arithmetic

let usage = StorageUsage(
    photosBytes: 100, voiceBytes: 50, filesBytes: 30,
    tempBytes: 20, draftBytes: 40, databaseBytes: 999
)
expect(usage.mediaBytes, 180, "media bytes = photos + voice + files")
expect(usage.totalCacheBytes, 240, "total cache = media + temp + draft sources")
check(usage.databaseBytes == 999, "database size kept separate (informational)")

if failures > 0 {
    print("\(failures) cache contract check(s) failed")
    exit(1)
}
print("cache contracts passed")
