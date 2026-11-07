import Foundation

@main
struct StorageContracts {
    @MainActor static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let cache = directory.appendingPathComponent("attachments")
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
        for state in ["uploading", "failed", "uploaded", "history-restored-original"] {
            let file = cache.appendingPathComponent(state + ".jpg")
            try Data("private source".utf8).write(to: file)
            try store.upsert(AttachmentRecord(
                attachmentId: state, clientMessageId: state, chatId: "chat", mediaType: "image", mimeType: "image/jpeg",
                filename: nil, size: 14, width: nil, height: nil, durationMs: nil, thumbnailBase64: nil,
                localPath: file.path, downloadState: "downloaded", uploadState: state.hasPrefix("history-") ? "uploaded" : state, progress: 0,
                objectKey: state, encryptedSize: 30, encryptionVersion: 1, keyBase64: nil,
                nonceBase64: nil, keysetBase64: nil, sha256Base64: "hash"))
        }
        let unregistered = cache.appendingPathComponent("preparing.jpg")
        try Data("not yet registered".utf8).write(to: unregistered)
        let result = await StorageService(store: store, accountDirectory: directory).clear()
        precondition(result.protectedFiles == 3)
        for name in ["uploading.jpg", "failed.jpg", "preparing.jpg", "history-restored-original.jpg"] {
            precondition(FileManager.default.fileExists(atPath: cache.appendingPathComponent(name).path), "Deleted protected upload: \(name)")
        }
        precondition(!FileManager.default.fileExists(atPath: cache.appendingPathComponent("uploaded.jpg").path))
        let row = try store.attachment(byId: "uploaded")
        precondition(row?.localPath == nil)
        let restoredPath = cache.appendingPathComponent("history-restored-original.jpg").path
        let restoredRow = try store.attachment(byId: "history-restored-original")
        precondition(restoredRow?.localPath == restoredPath && restoredRow?.downloadState == "downloaded",
                     "Cache clear removed the durable restored-original database reference")
        let drafts = directory.appendingPathComponent("draft-media")
        for name in ["live", "retry", "orphan", "new-import"] {
            let folder = drafts.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent("source.mov")
            try Data(repeating: 9, count: 128).write(to: file)
            if name != "new-import" { try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-172800)], ofItemAtPath: file.path) }
        }
        let service = StorageService(store: store, accountDirectory: directory, draftReferenced: { url in
            ["live", "retry"].contains(url.deletingLastPathComponent().lastPathComponent)
        })
        let usage = await service.usage()
        precondition(usage.draftBytes == 512, "Draft accounting omitted retained originals")
        let secondClear = await service.clear()
        precondition(secondClear.protectedFiles == 6)
        precondition(FileManager.default.fileExists(atPath: restoredPath))
        let stillRestored = try store.attachment(byId: "history-restored-original")
        precondition(stillRestored?.localPath == restoredPath && stillRestored?.downloadState == "downloaded")
        for name in ["live", "retry", "new-import"] {
            precondition(FileManager.default.fileExists(atPath: drafts.appendingPathComponent(name + "/source.mov").path))
        }
        precondition(!FileManager.default.fileExists(atPath: drafts.appendingPathComponent("orphan/source.mov").path))
        print("Storage contracts passed")
    }
}
