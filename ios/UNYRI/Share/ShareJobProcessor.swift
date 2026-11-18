import Foundation
#if canImport(UIKit)
import UIKit
import Combine
#endif

/// The Foundation state machine is exercised with durable fake send records in
/// contracts. Native bindings below use exactly the existing outbox admission.
@MainActor
final class ShareJobProcessor {
    enum Record: Equatable { case missing, pending, sent, failed, cancelled, conflict }
    struct Dependencies {
        let current: () -> Bool
        let accountKey: String
        let registeredKeys: () -> Set<String>
        let targets: () async throws -> [ShareTargetSnapshot.Target]
        let inspect: (ShareJob, ShareJob.Item) throws -> Record
        let adopt: (ShareJob.Item, URL) async throws -> URL
        let enqueue: (ShareJob, ShareJob.Item, URL) async -> Bool
        let notice: (String) -> Void
        let flush: () -> Void
        var abandon: (ShareJob.Item) -> Void = { _ in }
    }
    private let dependencies: Dependencies
    private let root: () throws -> URL
    private var task: Task<Void, Never>?
    private var rescan = false
    private var stopped = false
    init(dependencies: Dependencies, root: @escaping () throws -> URL = ShareContainer.root) {
        self.dependencies = dependencies; self.root = root
    }
    func stop() { stopped = true; rescan = false; task?.cancel() }
    private var current: Bool { !stopped && !Task.isCancelled && dependencies.current() }
    func request() {
        guard !stopped else { return }
        if task != nil { rescan = true; return }
        task = Task { [weak self] in
            guard let self else { return }
            defer { self.task = nil }
            // One finite debounce, armed only by a real event. No polling.
            do { try await Task.sleep(nanoseconds: 300_000_000) } catch { return }
            repeat {
                self.rescan = false
                do { try await self.process() } catch { /* Retry on the next wake/change. */ }
            } while self.rescan && self.current
        }
    }
    /// A pass never waits for uploads/network. Outbox owns those retries.
    func process(now: Date = Date()) async throws {
        guard current else { return }
        #if canImport(UIKit)
        let background = UIApplication.shared.beginBackgroundTask(withName: "Share job import") { [weak self] in
            Task { @MainActor in self?.task?.cancel() }
        }
        defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) } }
        #endif
        let root = try self.root()
        let directories = try await ShareContainer.disk.perform {
            try ShareJobStore.sweepStaging(root: root, olderThan: 3600, now: now)
            return try ShareJobStore.committed(root: root).map { directory -> (URL, ShareJob?, Bool) in
                do { return (directory, try ShareJobStore.read(directory), false) }
                catch let error as CocoaError where error.code == .fileReadCorruptFile { return (directory, nil, true) }
                catch { return (directory, nil, false) }
            }
        }
        guard current else { return }
        // Load authoritative account data once, after discovering actual work.
        var targets: [ShareTargetSnapshot.Target]?
        for (directory, manifest, corrupt) in directories.sorted(by: { ($0.1?.createdAt ?? 0) < ($1.1?.createdAt ?? 0) }) {
            guard current else { return }
            guard let job = manifest else {
                if corrupt {
                    try await ShareContainer.disk.perform { try UnyriShareStore.removeIfPresent(directory) }
                    dependencies.notice("Could not read this file. Try again or share it from another app.")
                }
                // Do not erase an unreadable/protected job as if it were corrupt.
                continue
            }
            // Expire shared plaintext even for a registered inactive account.
            // No other account's DB, private source or outbox is opened.
            let whollyExpired = try await ShareContainer.disk.perform { try ShareJobStore.expireSources(job, in: directory, now: now) }
            if job.accountKey != dependencies.accountKey {
                if whollyExpired {
                    try await ShareContainer.disk.perform { try UnyriShareStore.removeIfPresent(directory) }
                    continue
                }
                if !dependencies.registeredKeys().contains(job.accountKey) {
                    try await ShareContainer.disk.perform { try UnyriShareStore.removeIfPresent(directory) }
                }
                continue
            }
            if targets == nil { targets = try await dependencies.targets() }
            guard current else { return }
            let target = targets?.first { $0.chatId == job.target.chatId && $0.type == job.target.type && $0.canPost && $0.peerUserId == job.target.peerUserId }
            var terminal = true
            for item in job.items {
                guard current else { return }
                let record = try dependencies.inspect(job, item)
                switch record {
                case .sent:
                    try await fact(.init(name: .imported), item: item, directory: directory)
                    try await fact(.init(name: .sent), item: item, directory: directory)
                case .pending, .failed, .cancelled:
                    // The DB row, not an event, proves ownership after a crash.
                    try await fact(.init(name: .imported), item: item, directory: directory)
                    if case .pending = record { terminal = false }
                    if case .failed = record { try await fact(.init(name: .failed, code: "outbox", permanent: true), item: item, directory: directory) }
                case .conflict:
                    try await fact(.init(name: .failed, code: "identity", permanent: true), item: item, directory: directory)
                    dependencies.abandon(item)
                    dependencies.notice("Shared files could not be sent: the chat is no longer available.")
                case .missing:
                    let wasImported = try await ShareContainer.disk.perform { ShareJobStore.event(.imported, itemID: item.id, in: directory) != nil }
                    if wasImported { dependencies.abandon(item); continue } // never resurrect a deleted/cancelled durable row
                    let expired = now.timeIntervalSince1970 - job.createdAt > 7 * 86400
                    let failure = try await ShareContainer.disk.perform { ShareJobStore.event(.failed, itemID: item.id, in: directory) }
                    if failure?.permanent == true {
                        if failure?.code == "expired" { dependencies.notice("Shared files expired after 7 days. Share them again.") }
                        continue
                    }
                    guard target != nil, !expired,
                          ShareRoutePolicy.issue(kind: item.kind, bytes: item.bytes, sendAs: item.sendAs) == nil else {
                        try await fact(.init(name: .failed, code: expired ? "expired" : "target", permanent: true), item: item, directory: directory)
                        dependencies.notice(expired ? "Shared files expired after 7 days. Share them again." : "Shared files could not be sent: the chat is no longer available.")
                        dependencies.abandon(item)
                        continue
                    }
                    do {
                        let source = ShareJobStore.source(item, in: directory)
                        let privateURL = try await dependencies.adopt(item, source)
                        guard current else { return }
                        guard await dependencies.enqueue(job, item, privateURL), current else { terminal = false; continue }
                        let admitted = try dependencies.inspect(job, item)
                        guard admitted != .missing && admitted != .conflict else { terminal = false; continue }
                        try await fact(.init(name: .imported), item: item, directory: directory)
                        terminal = false
                    } catch is CancellationError { return }
                      catch { terminal = false; dependencies.notice("Could not prepare shared files. Open UNYRI again to retry.") }
                }
            }
            guard current else { return }
            try await ShareContainer.disk.perform {
                try ShareJobStore.removeImportedSources(job, in: directory)
                if terminal { try UnyriShareStore.removeIfPresent(directory) }
            }
        }
        if current { dependencies.flush() }
    }
    private func fact(_ event: ShareJobEvent, item: ShareJob.Item, directory: URL) async throws {
        guard current else { throw CancellationError() }
        try await ShareContainer.disk.perform { try ShareJobStore.event(event, itemID: item.id, in: directory) }
    }
}

#if canImport(UIKit)
@MainActor enum UnyriShareLifecycle {
    static var retire: (() -> Void)?
}

extension ShareJobProcessor {
    static func native(auth: AuthRepository, chats: ChatsRepository, outbox: MessageOutbox, messages: MessagesRepository,
                       store: MessageStore, directory: URL, notice: @escaping (String) -> Void) -> ShareJobProcessor? {
        guard let key = auth.shareAccountKey, let owner = auth.currentUser?.id else { return nil }
        let generation = auth.sessionGeneration
        let current = { [weak auth] in
            auth?.sessionGeneration == generation && auth?.shareAccountKey == key && UIApplication.shared.isProtectedDataAvailable
        }
        return ShareJobProcessor(dependencies: .init(current: current, accountKey: key, registeredKeys: {
            Set(UnyriAccountManager.shared.records.map { record in
                UnyriShareStore.accountKey(storageOrigin: AppConfig.storageOrigin(for: UnyriAccountSlot(id: record.slot)), userID: record.userId)
            }).union([key])
        }, targets: {
            try await chats.loadShareChatsAsync().compactMap { chat in
                guard !chats.isDeleted(chat.chatId), chat.isBlocked != true, chat.chatType != "direct" || chat.otherUser != nil else { return nil }
                return ShareTargetSnapshot.Target(chatId: chat.chatId, type: chat.chatType, title: "", peerUserId: chat.chatType == "direct" ? chat.otherUser?.id : nil)
            }
        }, inspect: { job, item in
            guard let row = try store.message(byClientMessageId: item.id) else { return .missing }
            guard row.chatId == job.target.chatId, row.senderId == owner, row.kind == "content" else { return .conflict }
            if row.hiddenLocally || row.isDeletedForEveryone { return .cancelled }
            switch row.status {
            case .sent, .delivered, .read: return .sent
            case .failed: return .failed
            default: return .pending
            }
        }, adopt: { item, source in
            try await messages.waitForDraftSourceRecovery()
            let destination = directory.appendingPathComponent("draft-media/share-" + item.id).appendingPathComponent(item.name)
            return try await ShareContainer.disk.perform {
                try Task.checkCancellation()
                if FileManager.default.fileExists(atPath: destination.path) {
                    guard try ShareContainer.regularSize(destination) == item.bytes else { throw CocoaError(.fileReadCorruptFile) }
                    return destination
                }
                guard try ShareContainer.regularSize(source) == item.bytes else { throw CocoaError(.fileReadCorruptFile) }
                guard ShareContainer.isDirectory(source.deletingLastPathComponent()),
                      ShareContainer.isDirectory(source.deletingLastPathComponent().deletingLastPathComponent()) else { throw CocoaError(.fileReadCorruptFile) }
                try ShareContainer.directory(destination.deletingLastPathComponent())
                let temporary = destination.appendingPathExtension("copying")
                try UnyriShareStore.removeIfPresent(temporary)
                do {
                    try FileManager.default.copyItem(at: source, to: temporary)
                    try Task.checkCancellation()
                    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: temporary.path)
                    try FileManager.default.moveItem(at: temporary, to: destination)
                    return destination
                } catch { try? UnyriShareStore.removeIfPresent(temporary); throw error }
            }
        }, enqueue: { job, item, url in
            guard current() else { return false }
            var kind = ShareRoutePolicy.sendKind(item)
            if item.kind == .image && item.sendAs == .media {
                let inspected = try? await ShareContainer.disk.perform { try ImageProcessor.shareSendKind(sourceURL: url, allowLargeStillSource: true, allowUnreadableAsFile: false) }
                guard current() else { return false }
                if inspected == "file" { kind = "file" }
            }
            // Revalidate after the copy/probe, including a block/delete racing it.
            guard let live = try? await chats.loadShareChatsAsync(), current(),
                  live.contains(where: { $0.chatId == job.target.chatId && $0.isBlocked != true && $0.chatType == job.target.type &&
                    (job.target.type != "direct" || $0.otherUser?.id == job.target.peerUserId) }) else { return false }
            if kind == "file" {
                return outbox.enqueueMedia(chatId: job.target.chatId, otherUserId: job.target.peerUserId,
                    id: item.id, url: url, kind: kind, caption: job.caption(at: item.albumItemIndex), duration: 0,
                    albumId: job.albumId, albumItemIndex: item.albumItemIndex)
            }
            return await outbox.enqueueVisualMedia(chatId: job.target.chatId, otherUserId: job.target.peerUserId,
                id: item.id, url: url, kind: kind, caption: job.caption(at: item.albumItemIndex),
                albumId: job.albumId, albumItemIndex: item.albumItemIndex, admission: {
                    current() && !chats.isDeleted(job.target.chatId) && chats.preferenceCache[job.target.chatId]?.isBlocked != true &&
                        (try? store.personallyHiddenChatIDs().contains(job.target.chatId)) == false
                })
        }, notice: notice, flush: { }, abandon: { item in
            let destination = directory.appendingPathComponent("draft-media/share-" + item.id).appendingPathComponent(item.name)
            messages.discardUnownedDraftSource(destination)
        }))
    }
}
#endif
