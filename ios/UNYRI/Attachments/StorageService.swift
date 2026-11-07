import Foundation
import Combine

// MARK: - Data & Storage (Phase 9.1)

private struct CacheEntry: Sendable {
    let attachmentId: String
    let chatId: String
    let downloadState: String
    let mediaType: String
    let localPath: String?
    let uploadState: String
}

/// Account-scoped media-cache scanner + clearer. File-system work runs off the
/// main actor; SQLite metadata reads/writes stay on the main actor (same
/// single-connection discipline as the rest of the store). Never touches the
/// message/chat databases, outbox, Signal keys or auth state.
@MainActor
final class StorageService: ObservableObject {
    private let store: AttachmentStore
    private let accountDirectory: URL
    private let draftReferenced: (URL) throws -> Bool
    private let current: () -> Bool
    private var clearing = false
    private let deletionFence = CacheDeletionFence()
    func retire() { deletionFence.retire(); policyObserver = nil }
    private var policyObserver: AnyCancellable?
    var onCleared: (() -> Void)?

    init(store: AttachmentStore, accountDirectory: URL, draftReferenced: @escaping (URL) throws -> Bool = { _ in true }, current: @escaping () -> Bool = { true }) {
        self.store = store
        self.accountDirectory = accountDirectory
        self.draftReferenced = draftReferenced
        self.current = current
        policyObserver = NotificationCenter.default.publisher(for: .unyriCachePolicyChanged).sink { [weak self] _ in
            Task { await self?.enforcePolicy() }
        }
    }

    private var cacheDirectory: URL {
        accountDirectory.appendingPathComponent("attachments", isDirectory: true)
    }

    func usage() async -> StorageUsage {
        let entries = ((try? await store.allAttachmentsAsync()) ?? []).map {
            CacheEntry(attachmentId: $0.attachmentId, chatId: $0.chatId, downloadState: $0.downloadState, mediaType: $0.mediaType,
                       localPath: $0.localPath, uploadState: $0.uploadState)
        }
        let cacheDir = cacheDirectory
        let accountDir = accountDirectory
        return await Task.detached(priority: .userInitiated) {
            var photos: Int64 = 0
            var voice: Int64 = 0
            var files: Int64 = 0
            for entry in entries {
                guard let path = entry.localPath else { continue }
                let size = Self.fileSize(path)
                switch entry.mediaType {
                case "image": photos += size
                case "voice": voice += size
                default: files += size
                }
            }
            let temp = Self.dirSize(cacheDir) { name in
                !name.hasSuffix(".enc") && !name.hasSuffix(".part") && !name.hasSuffix(".building")
            } - entries.reduce(Int64(0)) { acc, entry in
                guard let path = entry.localPath else { return acc }
                return acc + Self.fileSize(path)
            }
            return StorageUsage(
                photosBytes: photos,
                voiceBytes: voice,
                filesBytes: files,
                tempBytes: max(0, temp),
                draftBytes: Self.draftFiles(in: accountDir).reduce(Int64(0)) { total, url in
                    entries.contains(where: { $0.localPath == url.path }) ? total : total + Self.fileSize(url.path)
                },
                databaseBytes: Self.databaseSize(in: accountDir)
            )
        }.value
    }

    func usageByChat() async -> [ChatCacheUsage] {
        guard current() else { return [] }
        let entries = ((try? await store.allAttachmentsAsync()) ?? []).map {
            CacheEntry(attachmentId: $0.attachmentId, chatId: $0.chatId, downloadState: $0.downloadState,
                       mediaType: $0.mediaType, localPath: $0.localPath, uploadState: $0.uploadState)
        }
        return await Task.detached(priority: .utility) {
            var bytes: [String: Int64] = [:]
            var counted = Set<String>()
            for entry in entries {
                guard !Task.isCancelled, let path = entry.localPath, counted.insert(path).inserted else { continue }
                bytes[entry.chatId, default: 0] += Self.fileSize(path)
            }
            return bytes.filter { $0.value > 0 }.map { ChatCacheUsage(id: $0.key, bytes: $0.value) }.sorted { $0.bytes > $1.bytes }
        }.value
    }

    func enforcePolicy() async {
        let policy = UnyriDeviceSettings.shared.cache
        guard policy.maximumGiB > 0 || policy.retentionDays > 0 else { return }
        _ = await clear(policy: policy)
    }

    /// Each pass is bounded. Explicit clear also preserves all draft media,
    /// restored originals, uploads/retries, downloads and current file leases.
    func clear(chatID: String? = nil, policy: MediaCachePolicy? = nil) async -> CacheClearResult {
        let empty = CacheClearResult(deletedFiles: 0, deletedBytes: 0, protectedFiles: 0, failedFiles: 0)
        guard current(), !clearing else { return empty }
        clearing = true; defer { clearing = false }
        guard let records = try? await store.allAttachmentsAsync() else {
            return CacheClearResult(deletedFiles: 0, deletedBytes: 0, protectedFiles: 0, failedFiles: 1)
        }
        let entries = records.map {
            CacheEntry(attachmentId: $0.attachmentId, chatId: $0.chatId, downloadState: $0.downloadState,
                       mediaType: $0.mediaType, localPath: $0.localPath, uploadState: $0.uploadState)
        }
        let root = cacheDirectory.standardizedFileURL.resolvingSymlinksInPath().path + "/"
        let candidates = await Task.detached(priority: .utility) {
            entries.compactMap { entry -> (CacheEntry, Int64, Date)? in
                guard let path = entry.localPath, chatID == nil || entry.chatId == chatID else { return nil }
                let url = URL(fileURLWithPath: path).standardizedFileURL
                guard url.resolvingSymlinksInPath().path.hasPrefix(root), !url.lastPathComponent.hasPrefix("history-"),
                      let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey]),
                      values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
                return (entry, Self.fileSize(path), max(values.contentModificationDate ?? .distantPast, MediaCacheLease.lastAccess(path) ?? .distantPast))
            }.sorted { $0.2 < $1.2 }
        }.value
        var total = candidates.reduce(Int64(0)) { $0 + $1.1 }
        var deleted = 0, protected = 0, failures = 0
        var removedBytes: Int64 = 0
        let cutoff = policy.flatMap { $0.retentionDays > 0 ? Date().addingTimeInterval(-Double($0.retentionDays) * 86400) : nil }
        let limit = policy?.maximumBytes
        for (entry, size, modified) in candidates {
            guard current(), !Task.isCancelled else { break }
            if let policy, policy.maximumGiB == 0 && policy.retentionDays == 0 { break }
            if policy != nil && !(cutoff.map { modified < $0 } ?? false) && !(limit.map { total > $0 } ?? false) { continue }
            // Re-read immediately before deletion after every suspension. The
            // main actor owns state transitions; the lease owns the file operation.
            guard !UnyriMediaWorkState.shared.viewerOpen,
                  let fresh = try? store.attachment(byId: entry.attachmentId), fresh.localPath == entry.localPath,
                  fresh.downloadState == "downloaded", fresh.uploadState == "uploaded",
                  let path = entry.localPath,
                  (try? draftReferenced(URL(fileURLWithPath: path))) == false else { protected += 1; continue }
            do {
                let fence = deletionFence
                let removed = try await Task.detached(priority: .utility) {
                    try fence.perform { try MediaCacheLease.removeIfUnleased(URL(fileURLWithPath: path)) }
                }.value
                if removed {
                    if current(), let latest = try? store.attachment(byId: entry.attachmentId), latest.localPath == path {
                        try store.clearLocalCache(attachmentId: entry.attachmentId)
                    }
                    deleted += 1; removedBytes += size; total -= size
                } else { protected += 1 }
            } catch { failures += 1 }
            if policy != nil && deleted + failures >= 256 { break }
        }
        if deleted > 0 && current() { onCleared?() }
        return CacheClearResult(deletedFiles: deleted, deletedBytes: removedBytes, protectedFiles: protected, failedFiles: failures)
    }

    nonisolated private static func fileSize(_ path: String) -> Int64 {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: path),
              let size = (attrs[.size] as? NSNumber)?.int64Value else { return 0 }
        return size
    }

    nonisolated private static func draftFiles(in directory: URL) -> [URL] {
        let root = directory.appendingPathComponent("draft-media", isDirectory: true).standardizedFileURL.resolvingSymlinksInPath()
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]) else { return [] }
        return enumerator.compactMap { element in
            guard let url = element as? URL,
                  let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
                  values.isRegularFile == true, values.isSymbolicLink != true,
                  url.resolvingSymlinksInPath().path.hasPrefix(root.path + "/") else { return nil }
            return url
        }
    }

    nonisolated private static func dirSize(_ dir: URL, matching: (String) -> Bool) -> Int64 {
        guard let files = try? FileManager.default.contentsOfDirectory(
            atPath: dir.path) else { return 0 }
        return files.reduce(Int64(0)) { acc, name in
            matching(name) ? acc + fileSize(dir.appendingPathComponent(name).path) : acc
        }
    }

    nonisolated private static func databaseSize(in directory: URL) -> Int64 {
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return 0 }
        return files.reduce(Int64(0)) { acc, name in
            name.hasSuffix(".sqlite3") || name.hasSuffix(".sqlite3-wal") || name.hasSuffix(".sqlite3-shm")
                ? acc + fileSize(directory.appendingPathComponent(name).path) : acc
        }
    }
}

struct ChatCacheUsage: Identifiable, Equatable, Sendable { let id: String; let bytes: Int64 }
