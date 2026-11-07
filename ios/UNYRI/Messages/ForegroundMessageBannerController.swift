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

/// Resolves previews only from persisted plaintext, independent of WS/ingestion
/// subscriber order. One banner replaces the last; timers use generation IDs.
@MainActor
final class ForegroundMessageBannerController: ObservableObject {
    struct Banner: Equatable {
        let chatId: String
        let senderName: String?
        let preview: ChatPreview
        let generation: UInt64
        var shouldAlert = true
    }

    @Published private(set) var banner: Banner?
    @Published var navigationChatId: String? {
        didSet { if !isCallExpanded, let banner, banner.chatId == navigationChatId { dismiss() } }
    }
    var isCallExpanded = false
    var isForeground = false {
        didSet { if !isForeground { pending.removeAll(); dismiss() } }
    }

    private let realtimeEvents: PassthroughSubject<RealtimeEvent, Never>
    private let ingestorChanges: PassthroughSubject<Set<String>, Never>
    private let loadMessage: (String) throws -> DecryptedChatMessage?
    private let ownUserId: String
    private let notificationScope: UnyriNotificationBatchScope
    private var batchAlerts = UnyriNotificationBatchLedger()
    private let activeChatId: () -> String?
    private let activeCall: () -> Bool
    var isSilenced: (String) -> Bool = { _ in false }
    var notificationsEnabled: () -> Bool = { true }
    var messagePreviewEnabled: () -> Bool = { true }
    var onPresented: (Banner, String) -> Void = { _, _ in }
    private let resolveSenderName: (String) -> String?

    private var cancellables = Set<AnyCancellable>()
    private var pending: [String: MessageDto] = [:]
    private var seen = Set<String>()
    private var seenOrder: [String] = []
    private var generation: UInt64 = 0

    static let autoDismiss: TimeInterval = 4

    init(
        realtimeEvents: PassthroughSubject<RealtimeEvent, Never>,
        ingestorChanges: PassthroughSubject<Set<String>, Never>,
        loadMessage: @escaping (String) throws -> DecryptedChatMessage?,
        ownUserId: String,
        activeChatId: @escaping () -> String?,
        activeCall: @escaping () -> Bool,
        resolveSenderName: @escaping (String) -> String?,
        notificationServer: String = "",
        notificationGeneration: UInt64 = 0
    ) {
        self.realtimeEvents = realtimeEvents
        self.ingestorChanges = ingestorChanges
        self.loadMessage = loadMessage
        self.ownUserId = ownUserId
        self.notificationScope = UnyriNotificationBatchScope(server: notificationServer, owner: ownUserId, generation: notificationGeneration)
        self.activeChatId = activeChatId
        self.activeCall = activeCall
        self.resolveSenderName = resolveSenderName
        subscribe()
    }

    private func subscribe() {
        realtimeEvents.sink { [weak self] event in
            guard let self, case .messageNew(let dto) = event else { return }
            guard !self.seen.contains(dto.clientMessageId) else { return }
            self.seen.insert(dto.clientMessageId)
            self.seenOrder.append(dto.clientMessageId)
            if self.seenOrder.count > 1024 {
                self.seen.remove(self.seenOrder.removeFirst())
            }
            guard self.isForeground, self.notificationsEnabled() else { return }
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
        guard ForegroundBannerPolicy.shouldShow(
            isForeground: isForeground,
            activeChatId: activeChatId(),
            eventChatId: dto.chatId,
            isOwnMessage: message.senderId == ownUserId,
            activeCall: activeCall() && isCallExpanded,
            navigationChatId: navigationChatId
        ) else { return }
        guard let originalPreview = Self.preview(message) else { return }
        let preview = messagePreviewEnabled() ? originalPreview : .text(UnyriL10n.string("New message"))

        generation += 1
        let shouldAlert = batchAlerts.claim(message: dto.id, chat: message.chatId,
            sender: message.senderId, album: message.content.albumId, in: notificationScope)
        let item = Banner(chatId: dto.chatId, senderName: resolveSenderName(dto.chatId), preview: preview,
            generation: generation, shouldAlert: shouldAlert)
        banner = item
        onPresented(item, message.clientMessageId)
        let gen = generation
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.autoDismiss * 1_000_000_000))
            guard let self, self.banner?.generation == gen else { return }
            self.banner = nil
        }
    }

    func dismiss() {
        banner = nil
        generation += 1
    }

    func preferencesChanged() {
        pending.removeAll()
        // Do not leave a formerly visible private preview after hiding previews
        // or switching banners off. History and ingestion remain untouched.
        dismiss()
    }

    private static func preview(_ message: DecryptedChatMessage) -> ChatPreview? {
        guard let raw = ForegroundBannerPolicy.preview(
            kind: message.kind,
            status: message.status.rawValue,
            isDeleted: message.isDeletedForEveryone,
            text: message.content.conferenceInvite != nil ? UnyriL10n.string(ConferenceInvitationMetadata.fallbackText) : message.content.text,
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
