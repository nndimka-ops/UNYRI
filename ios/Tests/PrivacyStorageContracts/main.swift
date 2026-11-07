import Foundation
import Combine

// Metadata adapters are deliberately tiny; the production StorageService,
// MediaCacheLease and settings policies perform all decisions and filesystem IO.
struct AttachmentRecord: Sendable {
    let attachmentId: String
    let chatId: String
    let mediaType: String
    var localPath: String?
    var downloadState: String
    let uploadState: String
    static let downloadStateDownloading = "downloading"
    static let uploadStateUploading = "uploading"
    static let uploadStateFailed = "failed"
}
@MainActor final class AttachmentStore {
    var records: [AttachmentRecord] = []
    var onSnapshot: (() -> Void)?
    func allAttachmentsAsync() async throws -> [AttachmentRecord] { await Task.yield(); onSnapshot?(); return records }
    func attachment(byId id: String) throws -> AttachmentRecord? { records.first { $0.attachmentId == id } }
    func clearLocalCache(attachmentId id: String) throws {
        if let index = records.firstIndex(where: { $0.attachmentId == id }) {
            records[index].localPath = nil; records[index].downloadState = "not_downloaded"
        }
    }
}
struct StorageUsage {
    let photosBytes: Int64, voiceBytes: Int64, filesBytes: Int64, tempBytes: Int64, draftBytes: Int64, databaseBytes: Int64
}
struct CacheClearResult { let deletedFiles: Int, deletedBytes: Int64, protectedFiles: Int, failedFiles: Int }
@MainActor final class UnyriDeviceSettings { static let shared = UnyriDeviceSettings(); var cache = MediaCachePolicy() }
@MainActor final class UnyriMediaWorkState { static let shared = UnyriMediaWorkState(); var viewerOpen = false }
extension Notification.Name { static let unyriCachePolicyChanged = Notification.Name("COCOCachePolicyChanged") }

@main struct PrivacyStorageContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ condition: @autoclosure () -> Bool, _ message: String) {
            precondition(condition(), message); checks += 1
        }
        var lock = AppLockPolicy(enabled: true, timeout: 60)
        check(lock.locked, "Cold launch starts locked")
        lock.unlock(); lock.background(at: 100); lock.activate(at: 159)
        check(!lock.locked, "A short interruption keeps the authenticated session")
        lock.background(at: 200); lock.activate(at: 260)
        check(lock.locked, "Exact timeout relocks using monotonic uptime")
        lock.unlock(); lock.timeout = 0; lock.background(at: 300)
        check(lock.locked, "Immediate policy locks on background")
        lock.unlock(); lock.invalidate(); check(lock.locked, "Account replacement invalidates authentication")
        var five = AppLockPolicy(enabled: true, timeout: 300); five.unlock(); five.background(at: 50); five.activate(at: 349)
        check(!five.locked, "Five-minute policy preserves a short background interval")
        five.background(at: 400); five.activate(at: 700); check(five.locked, "Five-minute boundary locks")

        let owner = UUID().uuidString.lowercased(), peer = UUID().uuidString.lowercased(), deny = UUID().uuidString.lowercased()
        let normalized = UserPrivacy(onlineVisibility: .nobody, lastSeenVisibility: .everyone,
            alwaysAllow: [peer.uppercased(), peer, owner, "invalid", deny], neverAllow: [deny, owner], sendReadReceipts: false, revision: 7).normalized(owner: owner)
        check(normalized.alwaysAllow == [peer, deny].sorted(), "IDs normalize, deduplicate and exclude self")
        check(normalized.neverAllow == [deny], "Deny list excludes self")
        check(normalized.permits(peer, online: true), "Allow exception overrides nobody")
        check(!normalized.permits(deny, online: false), "Deny takes priority over allow and everyone")
        check(!normalized.permits(peer, online: false, blocked: true), "Block takes priority over visibility")
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let wire = try JSONSerialization.jsonObject(with: encoder.encode(UpdateUserPrivacy(normalized))) as! [String: Any]
        check(wire["revision"] == nil && (wire["expected_revision"] as? Int) == 7, "PUT sends expected_revision, never revision")
        check(wire["send_read_receipts"] as? Bool == false, "Receipt setting matches server contract")

        var presence = PresenceHistory()
        let observed = Date()
        let visible = PresenceObservation(item: PresenceItem(userId: peer, online: true, lastSeenAt: "2026-10-01T00:00:00Z"), observedAt: observed, expiresAtUptime: 5)
        presence.apply([peer: visible], requestedIDs: [peer])
        check(presence.date(for: peer) != nil, "Visible presence can keep historical evidence")
        let hiddenWhileOnline = PresenceObservation(item: PresenceItem(userId: peer, online: true, lastSeenAt: nil), observedAt: observed, expiresAtUptime: 5)
        presence.apply([peer: hiddenWhileOnline], requestedIDs: [peer])
        check(presence.date(for: peer) == nil, "Authoritative null clears fallback even when online is separately visible")
        presence.apply([peer: visible], requestedIDs: [peer])
        let hiddenOffline = PresenceObservation(item: PresenceItem(userId: peer, online: false, lastSeenAt: nil), observedAt: observed, expiresAtUptime: 5)
        presence.apply([peer: hiddenOffline], requestedIDs: [peer])
        check(presence.date(for: peer) == nil, "Authoritative offline null clears fallback")
        presence.apply([peer: visible], requestedIDs: [peer]); presence.apply([:], requestedIDs: [peer])
        check(presence.date(for: peer) == nil, "An authoritative omission clears blocked or hidden peer history")

        var downloads = AutomaticMediaSettings.defaults(wifiEnabled: true, cellularEnabled: false)
        check(downloads.allows(kind: .photo, wifi: true, connected: true, bytes: 50 * 1024 * 1024), "Existing Wi-Fi limit preserved")
        check(!downloads.allows(kind: .photo, wifi: true, connected: true, bytes: 50 * 1024 * 1024 + 1), "Oversized media denied")
        check(!downloads.allows(kind: .voice, wifi: false, connected: true, bytes: 1), "Cellular choice is independent")
        for bytes in [Int64(-1), 0] { check(!downloads.allows(kind: .file, wifi: true, connected: true, bytes: bytes), "Unknown size fails closed") }
        check(!downloads.allows(kind: .photo, wifi: true, connected: false, bytes: 1), "Offline automatic downloads denied")
        downloads.wifi["photo"]?.enabled = false
        check(!downloads.allows(kind: .photo, wifi: true, connected: true, bytes: 1), "Photo switch reaches admission policy")
        check(downloads.allows(kind: .voice, wifi: true, connected: true, bytes: 1), "Photo switch preserves voice policy")
        check(AutomaticMediaKind.classify(mediaType: "file", mime: "image/jpeg") == .file, "Original-file images use file rules")
        check(AutomaticMediaKind.classify(mediaType: "file", mime: "video/mp4") == .video, "Video uses video rules")
        check(MediaCachePolicy().maximumBytes == nil && MediaCachePolicy().retentionDays == 0, "Default cache is unlimited and forever")

        let root = FileManager.default.temporaryDirectory.appendingPathComponent("coco-storage-contract-" + UUID().uuidString)
        let cache = root.appendingPathComponent("attachments")
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        func file(_ name: String, bytes: Int64 = 128, directory: URL? = nil) throws -> URL {
            let target = (directory ?? cache).appendingPathComponent(name)
            FileManager.default.createFile(atPath: target.path, contents: Data())
            let handle = try FileHandle(forWritingTo: target); try handle.truncate(atOffset: UInt64(bytes)); try handle.close()
            try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-864000)], ofItemAtPath: target.path)
            return target
        }
        let normal = try file("normal"), otherChat = try file("other-chat"), uploading = try file("upload"), downloading = try file("download"), history = try file("history-original"), leased = try file("leased"), draft = try file("draft", directory: root), database = try file("messages.sqlite3", directory: root)
        let store = AttachmentStore()
        store.records = [(normal,"a","uploaded","downloaded"),(otherChat,"b","uploaded","downloaded"),(uploading,"a","uploading","downloaded"),(downloading,"a","uploaded","downloading"),(history,"a","uploaded","downloaded"),(leased,"a","uploaded","downloaded"),(draft,"a","uploaded","downloaded")].map {
            AttachmentRecord(attachmentId: $0.0.lastPathComponent, chatId: $0.1, mediaType: "image", localPath: $0.0.path, downloadState: $0.3, uploadState: $0.2)
        }
        var current = true
        let storage = StorageService(store: store, accountDirectory: root, draftReferenced: { _ in false }, current: { current })
        var lease: MediaCacheLease? = MediaCacheLease(path: leased.path)
        let usage = await storage.usageByChat()
        check(usage.first(where: { $0.id == "a" })?.bytes == 6 * 128, "Per-chat usage measures real local files")
        let outcome = await storage.clear(chatID: "a")
        check(outcome.deletedFiles == 1 && outcome.deletedBytes == 128, "Per-chat clear deletes only eligible files")
        for path in [otherChat, uploading, downloading, history, leased, draft, database] { check(FileManager.default.fileExists(atPath: path.path), "Active, restored, draft, database and other-chat files survive") }
        let clearedRecord = try store.attachment(byId: "normal")
        check(clearedRecord?.localPath == nil, "Successful deletion clears its local reference")
        UnyriMediaWorkState.shared.viewerOpen = true
        let viewer = await storage.clear(chatID: "b")
        check(viewer.deletedFiles == 0 && FileManager.default.fileExists(atPath: otherChat.path), "An active viewer protects cached files")
        UnyriMediaWorkState.shared.viewerOpen = false
        current = false
        let stale = await storage.clear(chatID: "b")
        check(stale.deletedFiles == 0 && FileManager.default.fileExists(atPath: otherChat.path), "A replaced account cannot clear files")
        current = true
        withExtendedLifetime(lease) {}; lease = nil
        let withinGrace = try MediaCacheLease.removeIfUnleased(leased)
        check(!withinGrace, "Recently closed files retain a conservative grace period")
        let outsideGrace = try MediaCacheLease.removeIfUnleased(leased, grace: 0)
        check(outsideGrace, "Released file becomes eligible after grace")
        let quota1 = try file("quota1", bytes: 600 * 1024 * 1024), quota2 = try file("quota2", bytes: 600 * 1024 * 1024)
        store.records = [quota1,quota2].map { AttachmentRecord(attachmentId: $0.lastPathComponent, chatId: "q", mediaType: "file", localPath: $0.path, downloadState: "downloaded", uploadState: "uploaded") }
        let quota = await storage.clear(policy: MediaCachePolicy(maximumGiB: 1, retentionDays: 0))
        check(quota.deletedFiles == 1, "Quota removes oldest eligible cache only until under limit")
        let retention = await storage.clear(policy: MediaCachePolicy(maximumGiB: 0, retentionDays: 7))
        check(retention.deletedFiles == 1, "Retention removes expired downloaded media")
        let draftReference = try file("workspace-draft")
        store.records = [AttachmentRecord(attachmentId: "workspace-draft", chatId: "d", mediaType: "file", localPath: draftReference.path, downloadState: "downloaded", uploadState: "uploaded")]
        let draftProtected = StorageService(store: store, accountDirectory: root, draftReferenced: { _ in true })
        let protectedResult = await draftProtected.clear()
        check(protectedResult.deletedFiles == 0 && FileManager.default.fileExists(atPath: draftReference.path), "Workspace-referenced cache is never removed")
        let failedDraftLookup = StorageService(store: store, accountDirectory: root, draftReferenced: { _ in throw CocoaError(.fileReadUnknown) })
        let failedLookupResult = await failedDraftLookup.clear()
        check(failedLookupResult.deletedFiles == 0, "Draft-reference lookup failure protects files")
        store.records[0].downloadState = "future_state"
        let unknownDownload = await storage.clear()
        check(unknownDownload.deletedFiles == 0, "Unknown download state fails closed")
        store.records = [AttachmentRecord(attachmentId: "workspace-draft", chatId: "d", mediaType: "file", localPath: draftReference.path, downloadState: "downloaded", uploadState: "future_state")]
        let unknownUpload = await storage.clear()
        check(unknownUpload.deletedFiles == 0, "Unknown upload state fails closed")
        store.records = [AttachmentRecord(attachmentId: "workspace-draft", chatId: "d", mediaType: "file", localPath: draftReference.path, downloadState: "downloaded", uploadState: "uploaded")]
        store.onSnapshot = { current = false }
        let retiredSnapshot = await storage.clear()
        check(retiredSnapshot.deletedFiles == 0 && FileManager.default.fileExists(atPath: draftReference.path), "Owner replacement while snapshot awaits prevents deletion")
        store.onSnapshot = nil; current = true
        let fence = CacheDeletionFence(); fence.retire()
        var executed = false
        let retiredJob = fence.perform { executed = true; return true }
        check(!retiredJob && !executed, "Retired account fence blocks queued deletion operations")
        storage.retire()
        let retiredService = await storage.clear()
        check(retiredService.deletedFiles == 0 && FileManager.default.fileExists(atPath: draftReference.path), "Retired service cannot remove a file even if auth callback lags")
        print("PASS: \(checks) production privacy, app-lock, automatic-download and cache contracts")
    }
}
