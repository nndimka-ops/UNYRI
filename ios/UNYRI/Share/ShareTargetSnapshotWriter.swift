import Foundation
import Combine
import UIKit
import ImageIO
import UniformTypeIdentifiers
import CryptoKit

@MainActor
final class ShareTargetSnapshotWriter {
    private weak var auth: AuthRepository?
    private let chats: ChatsRepository
    private let users: UsersRepository
    private let generation: UInt64
    private var subscriptions = Set<AnyCancellable>()
    private var task: Task<Void, Never>?
    private var gate = ShareSnapshotGate()
    private var settings: String
    private var stopped = false
    private static func preferences() -> String {
        [UnyriL10n.language, UserDefaults.standard.string(forKey: UnyriAppColorStyle.preferenceKey) ?? "monochrome",
         String(UserDefaults.standard.bool(forKey: "appLock.enabled")), MediaGallerySendChoice.saved().rawValue].joined(separator: "|")
    }
    init(auth: AuthRepository, chats: ChatsRepository, users: UsersRepository) {
        self.auth = auth; self.chats = chats; self.users = users
        generation = auth.sessionGeneration; settings = Self.preferences()
        chats.$cacheRevision.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        chats.$archivedChatIDs.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        users.$blockedAccountsRevision.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        users.$shareAvatarRevision.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        users.$ownProfile.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        UnyriAccountManager.shared.$records.sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        UnyriAccountManager.shared.$needsSignIn.removeDuplicates().sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: ShareContainer.snapshotAuthorityChanged).sink { [weak self] _ in self?.changed() }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification).receive(on: DispatchQueue.main).sink { [weak self] _ in
            guard let self else { return }
            let next = Self.preferences()
            guard next != self.settings else { return }
            self.settings = next; self.changed()
        }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification).sink { [weak self] _ in
            self?.changed()
        }.store(in: &subscriptions)
        changed()
    }
    func stop() { stopped = true; task?.cancel(); task = nil; subscriptions.removeAll() }
    func changed() {
        guard !stopped else { return }
        let ticket = gate.changed(at: ProcessInfo.processInfo.systemUptime)
        task?.cancel()
        task = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 2_000_000_000) } catch { return }
            guard let self, self.gate.ready(ticket, at: ProcessInfo.processInfo.systemUptime) else { return }
            await self.publish(ticket)
        }
    }
    private var current: Bool { !stopped && !Task.isCancelled && auth?.sessionGeneration == generation && auth?.currentUser != nil }
    private func publish(_ ticket: UInt64) async {
        guard current, UIApplication.shared.isProtectedDataAvailable, let auth, let key = auth.shareAccountKey else { return }
        do {
            let root = try ShareContainer.root()
            let authority = try await ShareContainer.disk.perform { try ShareContainer.snapshotAuthority(root: root) }
            guard current, !authority.revokedAccounts.contains(key) else { return }
            let cached = try await chats.loadShareChatsAsync()
            guard current, ticket == gate.revision, UIApplication.shared.isProtectedDataAvailable else { return }
            let pins = chats.cachedPins(), archives = chats.cachedArchives()
            var rows = cached.compactMap { chat -> ShareTargetSnapshot.Target? in
                guard chat.isBlocked != true, !chats.isDeleted(chat.chatId),
                      ["saved", "direct", "group"].contains(chat.chatType),
                      chat.chatType != "direct" || chat.otherUser != nil else { return nil }
                return .init(chatId: chat.chatId, type: chat.chatType,
                    title: chat.chatType == "saved" ? UnyriL10n.string("Saved Messages") : (chat.otherUser?.visibleName ?? UnyriL10n.string("Chat")),
                    peerUserId: chat.chatType == "direct" ? chat.otherUser?.id : nil,
                    pinned: pins.contains(chat.chatId), archived: archives.contains(chat.chatId),
                    lastActivity: (chat.lastMessageAt ?? chat.createdAt).flatMapDate)
            }
            rows = ShareTargetSnapshot.ordered(rows, query: "") + rows.filter(\.archived).sorted { $0.chatId < $1.chatId }
            let avatarIDs = Set(rows.filter { !$0.archived }.prefix(60).map(\.chatId))
            let avatars: [String: URL] = Dictionary(uniqueKeysWithValues: cached.compactMap { chat in
                guard avatarIDs.contains(chat.chatId), let peer = chat.otherUser,
                      let url = users.shareAvatarFileURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion) else { return nil }
                return (chat.chatId, url)
            })
            let binding = try await UnyriShareStore.inboxIO.prepareAccount(key)
            guard current, ticket == gate.revision else { return }
            let avatarSignatures = try await ShareContainer.disk.perform {
                Dictionary(uniqueKeysWithValues: avatars.compactMap { entry -> (String, String)? in
                    let (id, url) = entry
                    guard let info = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey, .isSymbolicLinkKey]),
                          info.isRegularFile == true, info.isSymbolicLink != true else { return nil }
                    return (id, url.lastPathComponent + "|" + String(info.fileSize ?? 0) + "|" + String(info.contentModificationDate?.timeIntervalSince1970 ?? 0))
                })
            }
            guard current, ticket == gate.revision else { return }
            let profile = users.cachedMe()
            let account = ShareTargetSnapshot.Account(key: key, binding: binding,
                displayName: profile?.displayName ?? UnyriL10n.string("UNYRI"),
                appLock: UnyriAppLock.shared.enabled,
                colorStyle: UnyriAppColorStyle.parse(UserDefaults.standard.string(forKey: UnyriAppColorStyle.preferenceKey)).rawValue,
                language: UnyriL10n.language, mediaChoice: MediaGallerySendChoice.saved().rawValue)
            var accounts = [account]
            for record in UnyriAccountManager.shared.records {
                let otherKey = UnyriShareStore.accountKey(storageOrigin: AppConfig.storageOrigin(for: UnyriAccountSlot(id: record.slot)), userID: record.userId)
                guard !authority.revokedAccounts.contains(otherKey), !accounts.contains(where: { $0.key == otherKey }) else { continue }
                let otherBinding = try await UnyriShareStore.inboxIO.prepareAccount(otherKey)
                guard current, ticket == gate.revision else { return }
                accounts.append(.init(key: otherKey, binding: otherBinding, displayName: record.displayName,
                    state: UnyriAccountManager.shared.needsSignIn.contains(record.slot) ? "signedOut" : "inactive",
                    appLock: account.appLock, colorStyle: account.colorStyle, language: account.language, mediaChoice: account.mediaChoice))
            }
            accounts.sort { $0.key < $1.key }
            let snapshot = ShareTargetSnapshot.Snapshot(accountKey: key, writtenAt: 0, targets: rows)
            // Hash minimal inputs before image work; avatar versions are encoded
            // in local cache filenames. A no-op revision does no image decoding.
            struct Content: Encodable { let accounts: [ShareTargetSnapshot.Account]; let snapshot: ShareTargetSnapshot.Snapshot; let avatars: [String: String] }
            let content = try ShareContainer.encode(Content(accounts: accounts, snapshot: snapshot, avatars: avatarSignatures))
            let hash = Data(SHA256.hash(data: content))
            guard gate.differs(hash) else { return }
            try await ShareContainer.disk.perform {
              try ShareContainer.publishSnapshot(authority: authority, root: root, accountKeys: accounts.map(\.key)) {
                try Task.checkCancellation()
                let folder = ShareContainer.targets(root).appendingPathComponent(key)
                let hashURL = folder.appendingPathComponent("content.sha256")
                // Persist the gate across process launches, while still changing
                // the active account when returning to an existing snapshot.
                if (try? ShareContainer.read(hashURL, maximum: 32)) == hash,
                   let existing = try? ShareTargetSnapshot.readIndex(root: root), existing.activeAccountKey == key,
                   existing.accounts == accounts,
                   (try? ShareTargetSnapshot.read(account: account, root: root)) != nil,
                   ShareContainer.isDirectory(ShareContainer.accountJobs(binding, root: root)) { return }
                try ShareContainer.prepare(binding, root: root)
                try ShareContainer.prepareTargets(root: root)
                try ShareContainer.directory(folder)
                let avatarFolder = folder.appendingPathComponent("avatars")
                try ShareContainer.directory(avatarFolder)
                var output = snapshot
                output.writtenAt = Date().timeIntervalSince1970
                for index in output.targets.indices {
                    try Task.checkCancellation()
                    guard let source = avatars[output.targets[index].chatId],
                          let data = Self.thumbnail(source) else { continue }
                    let name = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() + ".jpg"
                    let destination = avatarFolder.appendingPathComponent(name)
                    if !FileManager.default.fileExists(atPath: destination.path) { try ShareContainer.write(data, to: destination) }
                    output.targets[index].avatar = name
                }
                try ShareContainer.write(ShareContainer.encode(output), to: folder.appendingPathComponent("targets.json"))
                let index = ShareTargetSnapshot.Index(activeAccountKey: key, accounts: accounts)
                try ShareContainer.write(ShareContainer.encode(index), to: ShareContainer.targets(root).appendingPathComponent("index.json"))
                let retained = Set(output.targets.compactMap(\.avatar))
                for file in try ShareContainer.children(avatarFolder) where !retained.contains(file.lastPathComponent) { try UnyriShareStore.removeIfPresent(file) }
                try ShareContainer.write(hash, to: hashURL)
              }
            }
            guard current, ticket == gate.revision else { return }
            gate.didWrite(hash)
        } catch { /* Event-driven retry on the next real change/unlock/launch. */ }
    }
    nonisolated private static func thumbnail(_ url: URL) -> Data? {
        guard url.isFileURL, let source = PeerImagePolicy.source(url: url),
              let pixels = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 120] as CFDictionary) else { return nil }
        let bytes = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(bytes, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, pixels, [kCGImageDestinationLossyCompressionQuality: 0.75] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? bytes as Data : nil
    }
}

private extension String {
    var flatMapDate: TimeInterval { ISO8601Cocoa.parse(self)?.timeIntervalSince1970 ?? 0 }
}
