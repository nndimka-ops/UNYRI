import Foundation
import Combine

// BEGIN notification batch policy (Foundation-only contracts use this source).
struct UnyriNotificationBatchScope: Equatable {
    let server: String
    let owner: String
    let generation: UInt64
}
private struct UnyriNotificationBatchKey: Hashable {
    let chat: String
    let sender: String
    let album: String
}
struct UnyriNotificationBatchLedger {
    private let capacity: Int
    private var scope: UnyriNotificationBatchScope?
    private var batches = Set<UnyriNotificationBatchKey>()
    private var batchOrder: [UnyriNotificationBatchKey] = []
    private var messages = Set<String>()
    private var messageOrder: [String] = []
    init(capacity: Int = 512) { precondition(capacity > 0); self.capacity = capacity }
    mutating func claim(message: String, chat: String, sender: String, album: String?, in current: UnyriNotificationBatchScope) -> Bool {
        if scope != current {
            scope = current; batches.removeAll(); batchOrder.removeAll(); messages.removeAll(); messageOrder.removeAll()
        }
        guard messages.insert(message).inserted else { return false }
        messageOrder.append(message)
        if messageOrder.count > capacity { messages.remove(messageOrder.removeFirst()) }
        guard let album, !album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, album.utf8.count <= 256 else { return true }
        let key = UnyriNotificationBatchKey(chat: chat, sender: sender, album: album)
        guard batches.insert(key).inserted else { return false }
        batchOrder.append(key)
        if batchOrder.count > capacity { batches.remove(batchOrder.removeFirst()) }
        return true
    }
}
// END notification batch policy.

/// A message alert of the signed-in account that iOS offers while the app is
/// in the foreground (ids only, from the APNs payload).
struct UnyriForegroundPushHint: Equatable {
    let owner: String
    let generation: UInt64
    let chatId: String
    /// The server message id: alerts carry no client message id.
    let messageId: String
}

/// A bounded set of ids; the oldest leaves first.
private struct UnyriRecentIds {
    private let capacity: Int
    private var ids = Set<String>()
    private var order: [String] = []
    init(capacity: Int) { self.capacity = capacity }
    /// False when the id is already there.
    mutating func insert(_ id: String) -> Bool {
        guard ids.insert(id).inserted else { return false }
        order.append(id)
        if order.count > capacity { ids.remove(order.removeFirst()) }
        return true
    }
}

/// Resolves previews only from persisted plaintext, independent of WS/ingestion
/// subscriber order. One banner replaces the last; timers use generation IDs.
/// Socket and foreground APNs hints resolve through the same persisted plaintext.
/// Sync-only traffic without a foreground hint never creates an alert.
@MainActor
final class ForegroundMessageBannerController: ObservableObject {
    struct Banner: Equatable {
        let chatId: String
        var senderName: String?
        let preview: ChatPreview
        let generation: UInt64
        var shouldAlert = true
        var ownerID: String? = nil
        var messageID: String? = nil
        var avatarURL: URL? = nil
    }

    @Published private(set) var banner: Banner?
    @Published var navigationChatId: String? {
        didSet { if !isCallExpanded, let banner, banner.ownerID == nil, banner.chatId == navigationChatId { dismiss() } }
    }
    var isCallExpanded = false
    var isForeground = false {
        didSet { if !isForeground { pending.removeAll(); pendingPushes.removeAll(); dismiss() } }
    }

    private let realtimeEvents: PassthroughSubject<RealtimeEvent, Never>
    private let ingestorChanges: PassthroughSubject<Set<String>, Never>
    private let loadMessage: (String) throws -> DecryptedChatMessage?
    private let loadServerMessage: (String) throws -> DecryptedChatMessage?
    private let ownUserId: String
    private let notificationScope: UnyriNotificationBatchScope
    private var batchAlerts = UnyriNotificationBatchLedger()
    private let activeChatId: () -> String?
    private let activeCall: () -> Bool
    var isSilenced: (String) -> Bool = { _ in false }
    var notificationsEnabled: () -> Bool = { true }
    var messagePreviewEnabled: () -> Bool = { true }
    var resolveAvatar: (String) -> URL? = { _ in nil }
    var resolvePresentation: ((String) async -> (name: String?, avatar: URL?, muted: Bool)?)?
    var privacyBlocked: () -> Bool = { false }
    var onInactivePresented: (UnyriNotificationPreferences) -> Void = { _ in }
    var inAppPreferences: () -> UnyriNotificationPreferences = { UnyriNotificationPreferences() }
    var onPresented: (Banner, String) -> Void = { _, _ in }
    private let resolveSenderName: (String) -> String?

    private var cancellables = Set<AnyCancellable>()
    private var pending: [String: MessageDto] = [:]
    private var pendingPushes: [String: UnyriForegroundPushHint] = [:]
    private var presented = UnyriRecentIds(capacity: 1024)
    private var expiry: Task<Void, Never>?
    private var metadata: Task<Void, Never>?
    /// Client message ids already handled (REST/WS duplicates).
    private var seen = UnyriRecentIds(capacity: 1024)
    /// Server ids owned by the socket or a pending foreground push hint.
    private var claimed = UnyriRecentIds(capacity: 1024)
    private var generation: UInt64 = 0

    static let autoDismiss: TimeInterval = 3.5

    private struct LiveEntry { weak var controller: ForegroundMessageBannerController? }
    /// Push presentation asks these about foreground alerts; each answers only
    /// for its own account and session.
    private static var live: [LiveEntry] = []

    init(
        realtimeEvents: PassthroughSubject<RealtimeEvent, Never>,
        ingestorChanges: PassthroughSubject<Set<String>, Never>,
        loadMessage: @escaping (String) throws -> DecryptedChatMessage?,
        ownUserId: String,
        activeChatId: @escaping () -> String?,
        activeCall: @escaping () -> Bool,
        resolveSenderName: @escaping (String) -> String?,
        notificationServer: String = "",
        notificationGeneration: UInt64 = 0,
        loadServerMessage: @escaping (String) throws -> DecryptedChatMessage? = { _ in nil }
    ) {
        self.realtimeEvents = realtimeEvents
        self.ingestorChanges = ingestorChanges
        self.loadMessage = loadMessage
        self.loadServerMessage = loadServerMessage
        self.ownUserId = ownUserId
        self.notificationScope = UnyriNotificationBatchScope(server: notificationServer, owner: ownUserId, generation: notificationGeneration)
        self.activeChatId = activeChatId
        self.activeCall = activeCall
        self.resolveSenderName = resolveSenderName
        subscribe()
        Self.live.removeAll { $0.controller == nil }
        Self.live.append(LiveEntry(controller: self))
    }

    /// True means this foreground account owns the hint; the delegate never
    /// turns a message hint into a system foreground banner.
    static func takesAlert(_ hint: UnyriForegroundPushHint) -> Bool {
        for entry in live {
            if let taken = entry.controller?.claim(hint) { return taken }
        }
        return false
    }

    private func claim(_ hint: UnyriForegroundPushHint) -> Bool? {
        guard hint.owner == ownUserId, hint.generation == notificationScope.generation else { return nil }
        guard isForeground else { return false }
        guard claimed.insert(hint.messageId) else { return true }
        guard notificationsEnabled(), !isSilenced(hint.chatId), ForegroundBannerPolicy.shouldShow(
            isForeground: true, activeChatId: activeChatId(), eventChatId: hint.chatId, isOwnMessage: false,
            activeCall: activeCall() && isCallExpanded, navigationChatId: navigationChatId) else { return true }
        pendingPushes[hint.messageId] = hint
        if pendingPushes.count > 128 {
            // Bound unresolved hints even if decryption or transport never succeeds.
            if let first = pendingPushes.keys.sorted().first { pendingPushes.removeValue(forKey: first) }
        }
        resolvePushes(chatIds: [hint.chatId])
        return true
    }

    private func resolvePushes(chatIds: Set<String>) {
        let ready = pendingPushes.values.filter { chatIds.contains($0.chatId) }.compactMap { hint -> DecryptedChatMessage? in
            guard let message = try? loadServerMessage(hint.messageId),
                  message.serverId == hint.messageId, message.chatId == hint.chatId else { return nil }
            return message
        }.sorted { ($0.sequence ?? .max, $0.serverId ?? "") < ($1.sequence ?? .max, $1.serverId ?? "") }
        for message in ready {
            if let serverID = message.serverId { pendingPushes.removeValue(forKey: serverID) }
            presentResolved(message)
        }
    }

    private func subscribe() {
        realtimeEvents.sink { [weak self] event in
            guard let self, case .messageNew(let dto) = event else { return }
            guard self.seen.insert(dto.clientMessageId) else { return }
            guard self.isForeground, self.notificationsEnabled() else { return }
            // A push-first copy resolves through the server-ID loader after sync.
            guard self.claimed.insert(dto.id) else { return }
            self.pending[dto.clientMessageId] = dto
            if self.pending.count > 128,
               let oldest = self.pending.values.min(by: { $0.sequence < $1.sequence }) {
                self.pending.removeValue(forKey: oldest.clientMessageId)
            }
            // Ingestion can finish before this subscriber receives the event.
            self.resolvePending(chatIds: [dto.chatId])
        }.store(in: &cancellables)

        ingestorChanges.sink { [weak self] chatIds in
            guard let self else { return }
            self.resolvePending(chatIds: chatIds)
            self.resolvePushes(chatIds: chatIds)
        }.store(in: &cancellables)
    }

    private func resolvePending(chatIds: Set<String>) {
        // Consume every persisted candidate; an own/lifecycle event must not
        // mask an incoming content message from the same ingestion batch.
        let ready = pending.values.filter { chatIds.contains($0.chatId) }
            .sorted { $0.sequence < $1.sequence }
        for dto in ready {
            guard (try? loadMessage(dto.clientMessageId)) != nil else { continue }
            pending.removeValue(forKey: dto.clientMessageId)
            present(dto)
        }
    }

    private func present(_ dto: MessageDto) {
        guard dto.notificationBatchAlert != false else { return }
        guard notificationsEnabled(), !isSilenced(dto.chatId) else { return }
        guard let message = try? loadMessage(dto.clientMessageId) else { return }
        guard message.serverId == dto.id, message.clientMessageId == dto.clientMessageId,
              message.chatId == dto.chatId, message.senderId == dto.senderId, message.sequence == dto.sequence,
              message.kind == "content", !message.hiddenLocally, !message.isDeletedForEveryone,
              [.sent, .delivered, .read].contains(message.status) else { return }
        presentResolved(message)
    }

    private func presentResolved(_ message: DecryptedChatMessage) {
        guard isForeground, notificationsEnabled(), !isSilenced(message.chatId),
              message.kind == "content", !message.hiddenLocally, !message.isDeletedForEveryone,
              [.sent, .delivered, .read].contains(message.status), let serverID = message.serverId else { return }
        let sameChat = !ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: activeChatId(),
            eventChatId: message.chatId, isOwnMessage: false, activeCall: activeCall() && isCallExpanded,
            navigationChatId: navigationChatId)
        let decision = InAppNotificationPolicy.decide(foreground: isForeground, event: .message,
            sameChat: sameChat, muted: isSilenced(message.chatId), ownMessage: message.senderId == ownUserId,
            preferences: inAppPreferences())
        guard presented.insert(serverID), decision != .silent else { return }
        guard let originalPreview = Self.preview(message) else { return }
        let preview = messagePreviewEnabled() ? originalPreview : .text(UnyriL10n.string("New message"))

        generation += 1
        let shouldAlert = batchAlerts.claim(message: serverID, chat: message.chatId,
            sender: message.senderId, album: message.content.albumId, in: notificationScope)
        let item = Banner(chatId: message.chatId, senderName: resolveSenderName(message.chatId).map { DisplayNameSanitizer.peer($0, unknown: UnyriL10n.string("Unknown user")) }, preview: preview,
            generation: generation, shouldAlert: shouldAlert, avatarURL: resolveAvatar(message.chatId))
        show(item, visible: decision.banner)
        onPresented(item, message.clientMessageId)
    }

    /// Inactive accounts expose only their account label and generic preview.
    /// Their server push policy has already applied the inactive chat's mute.
    static func presentInactive(owner: String, chat: String, message: String, name: String,
                                preferences: UnyriNotificationPreferences) {
        guard let controller = live.reversed().compactMap(\.controller).first(where: { $0.isForeground }),
              !controller.privacyBlocked() else { return }
        let decision = InAppNotificationPolicy.decide(foreground: true, event: .message,
            sameChat: false, muted: false, preferences: preferences)
        guard decision != .silent, controller.presented.insert(owner + ":" + message) else { return }
        controller.generation += 1
        let item = Banner(chatId: chat, senderName: name, preview: .text(UnyriL10n.string("New message")),
            generation: controller.generation, ownerID: owner, messageID: message)
        controller.show(item, visible: decision.banner)
        controller.onInactivePresented(preferences)
    }

    private func show(_ item: Banner, visible: Bool) {
        dismiss()
        guard visible else { return }
        banner = item
        let gen = item.generation
        expiry = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(Self.autoDismiss * 1_000_000_000)) } catch { return }
            guard let self, self.banner?.generation == gen else { return }
            self.dismiss()
        }
        if item.ownerID == nil, let resolvePresentation {
            metadata = Task { @MainActor [weak self] in
                let resolved = await resolvePresentation(item.chatId)
                guard !Task.isCancelled, let self, self.isForeground,
                      var current = self.banner, current.generation == gen, let resolved else { return }
                guard self.notificationsEnabled(), !resolved.muted, !self.isSilenced(item.chatId) else { self.dismiss(); return }
                current.senderName = resolved.name.map { DisplayNameSanitizer.peer($0, unknown: UnyriL10n.string("Unknown user")) } ?? current.senderName
                current.avatarURL = resolved.avatar ?? current.avatarURL
                if self.banner != current { self.banner = current }
            }
        }
    }

    func dismiss() {
        expiry?.cancel(); expiry = nil
        metadata?.cancel(); metadata = nil
        banner = nil
    }

    func preferencesChanged() {
        pending.removeAll(); pendingPushes.removeAll()
        dismiss()
    }

    private static func preview(_ message: DecryptedChatMessage) -> ChatPreview? {
        let directText: String? = DirectFilePreviewText.display(text: message.content.text, offer: message.content.directFile)
        guard let raw = ForegroundBannerPolicy.preview(
            kind: message.kind,
            status: message.status.rawValue,
            isDeleted: message.isDeletedForEveryone,
            text: message.content.conferenceInvite != nil ? UnyriL10n.string(ConferenceInvitationMetadata.fallbackText) : directText ?? message.content.text,
            mediaType: message.content.attachment?.mediaType,
            filename: message.content.attachment?.filename,
            mimeType: message.content.attachment?.mimeType
        ) else { return nil }
        switch raw {
        case .text(let text): return .text(text)
        case .photo: return .photo
        case .voice: return .voice
        case .video: return .video
        case .file(let filename): return .file(filename)
        case .deleted: return .deleted
        }
    }
}
