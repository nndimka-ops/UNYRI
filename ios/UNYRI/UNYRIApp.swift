import SwiftUI
import Combine
import UserNotifications

@main
@MainActor
struct UNYRIApp: App {
    @UIApplicationDelegateAdaptor(ChatReminderDelegate.self) private var reminderDelegate
    @AppStorage(UnyriL10n.key) private var language = "system"
    @Environment(\.scenePhase) private var appPhase
    @StateObject private var auth: AuthRepository
    @StateObject private var serverOriginGate: UnyriServerOriginController

    init() {
        let store = SessionStore()
        let originGate = UnyriServerOriginController(store: store)
        // The placeholder is never used while proof is blocked. Every
        // Auth.bootstrap entry point repeats the guard before any side effect.
        let selectedOrigin = (try? originGate.prepare()) ?? URL(string: "https://localhost")!
        let client = APIClient(baseURL: selectedOrigin)
        let account = AuthRepository(api: AuthAPI(client: client), store: store, device: DeviceInfo(store: store),
            clearLocalData: { try await LocalAccountCachePurger.erase() },
            hasLocalAccountWorkspace: { userId in
                try UnyriServerWorkspaceEvidence.hasWorkspace(support: Self.supportDirectory,
                    origin: AppConfig.storageOrigin, user: userId)
            }, hasLocalInstallation: {
                let marker = Self.installationDirectory.appendingPathComponent("continuity-v1")
                do {
                    guard try Data(contentsOf: marker) == Data("COCO installation v1".utf8) else {
                        throw CocoaError(.fileReadCorruptFile)
                    }
                    return true
                } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
                    return false
                }
            }, recordLocalInstallation: {
                var directory = Self.installationDirectory
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var attributes = URLResourceValues()
                attributes.isExcludedFromBackup = true
                try directory.setResourceValues(attributes)
                try Data("COCO installation v1".utf8).write(to: directory.appendingPathComponent("continuity-v1"), options: .atomic)
            }, prepareServerForBootstrap: { try originGate.prepare() },
            installationProofs: InstallationProofCoordinator(store: KeychainInstallationProofStore()) { origin in
                try await AuthAPI(client: APIClient(baseURL: origin, preventRedirects: true)).createInstallationCredential()
            })
        _auth = StateObject(wrappedValue: account)
        _serverOriginGate = StateObject(wrappedValue: originGate)
        let push = UnyriPushNotificationCoordinator.shared
        push.bind(auth: account)
        push.onIncomingCall = { [weak account] hint, ticket in
            guard let account else { return false }
            await account.bootstrap()
            guard push.matchesReceived(hint, ticket: ticket), let session = push.currentSession() else { return false }
            let services = AccountServices.shared(auth: account)
            guard services.sessionGeneration == session.generation, services.initializationError == nil,
                  let controller = services.callController, let call = hint.callID else { return false }
            let provider = UnyriCallKitProvider.shared
            let handled = await controller.receiveIncomingPush(call,
                admitted: { push.matchesReceived(hint, ticket: ticket) && provider.admitted(ticket, session: session) },
                takeReject: { push.matchesReceived(hint, ticket: ticket) && provider.takeReject(ticket, session: session) },
                terminal: { provider.terminal(ticket, reason: $0) })
            guard handled, push.matchesReceived(hint, ticket: ticket) else { return false }
            // A genuine ringing CallKit call owns background signaling, using
            // the same connection and recovery policy as an ordinary call.
            services.realtime?.start()
            return true
        }
        push.onMessageTap = { [weak account] hint, expected in
            guard let account else { return }
            await account.bootstrap()
            guard push.currentSession() == expected, !Task.isCancelled,
                  UIApplication.shared.applicationState == .active else { return }
            let services = AccountServices.shared(auth: account)
            await services.syncManager?.runCatchUpSync()
            guard push.currentSession() == expected, !Task.isCancelled,
                  let chat = hint.chatID, let message = hint.messageID else { return }
            UserDefaults.standard.set(["owner": expected.scope.user.uuidString.lowercased(),
                "chat": chat.uuidString.lowercased(), "message": message.uuidString.lowercased()], forKey: "chatReminderRoute")
            NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil)
        }
        HistoryAutomaticBackup.shared.register {
            await account.bootstrap()
            guard account.currentUser != nil else { return false }
            let services = AccountServices.shared(auth: account)
            guard let access = services.historyBackupAccess else { return false }
            return await HistoryAutomaticBackup.shared.run(auth: account, access: access)
        }
    }

    // Kept outside the account-cache root so ordinary logout cannot erase it.
    private static var installationDirectory: URL {
        supportDirectory.appendingPathComponent("COCOInstallation", isDirectory: true)
    }

    private static var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if let issue = serverOriginGate.issue {
                    UnyriServerOriginRecoveryView(issue: issue) { choice in
                        do { _ = try serverOriginGate.prepare(choice: choice) } catch { return }
                        Task { await auth.bootstrap() }
                    }
                } else { ContentView() }
            }
                .unyriChatAppearanceRoot()
                .unyriAppColorThemeRoot()
                .unyriSidebarAppearanceRoot()
                .performanceDiagnosticsSession()
                .modifier(UnyriAppLockProtection())
                .environmentObject(auth)
                .modifier(UnyriAccountActivityReporter(auth: auth))
                .environment(\.locale, Locale(identifier: language == "system" ? UnyriL10n.language : language))
                .preferredColorScheme(.dark)
                .task { UnyriL10n.syncExtensionLanguage(); await auth.bootstrap() }
                .task(id: appPhase) {
                    guard appPhase == .active else { return }
                    await UnyriPushNotificationCoordinator.shared.foreground()
                    while !Task.isCancelled {
                        await auth.retryPendingLogouts()
                        await auth.ensureInstallationBinding()
                        UnyriPushNotificationCoordinator.shared.retry()
                        do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
                    }
                }
                .task(id: "\(auth.sessionGeneration)|\(auth.currentUser?.id ?? "")") {
                    UnyriPushNotificationCoordinator.shared.sessionChanged()
                    UnyriAppLock.shared.bind(owner: auth.currentUser.map { auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration) }, auth: auth)
                    UnyriAppLock.shared.phase(appPhase)
                }
                .onChange(of: appPhase) { UnyriAppLock.shared.phase($0) }
                .onChange(of: language) { _ in UnyriL10n.syncExtensionLanguage() }
                .onOpenURL { url in
                    if ["unyri", "coco"].contains(url.scheme?.lowercased() ?? ""), ["conference", "c"].contains(url.host ?? "") {
                        NotificationCenter.default.post(name: ConferenceController.invitationNotification, object: url)
                    } else { UnyriIncomingFile.shared.receive(url) }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                    Task { await auth.bootstrap() }
                }
        }
    }
}

/// One installation-owned preflight. Failure never clears a session or probes
/// any server; a readable, uniquely proven previous origin is selected locally.
@MainActor
private final class UnyriServerOriginController: ObservableObject {
    @Published private(set) var issue: UnyriServerOriginContinuity.Failure?
    private let store: SessionStore
    init(store: SessionStore) { self.store = store }
    func prepare(choice: String? = nil) throws -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        do {
            let url = try AppConfig.prepareServerOrigin(choice: choice,
                identity: { try self.store.checkedServerContinuityUserID() },
                installation: { try UnyriServerWorkspaceEvidence.installationExists(support: support) },
                hasWorkspaces: { try UnyriServerWorkspaceEvidence.hasAnyWorkspace(support: support) },
                workspace: { origin, user in try UnyriServerWorkspaceEvidence.hasWorkspace(support: support, origin: origin, user: user) })
            issue = nil
            return url
        } catch let failure as UnyriServerOriginContinuity.Failure { issue = failure; throw failure }
    }
}

private struct UnyriServerOriginRecoveryView: View {
    let issue: UnyriServerOriginContinuity.Failure
    let resume: (String?) -> Void
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "lock.shield").font(.system(size: 32)).foregroundStyle(UnyriColors.accent)
                Text("Confirm your previous server").font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                Text(issue.reason == .sessionUnreadable
                    ? "Unlock this iPhone, then retry. UNYRI has not changed your server or removed your history."
                    : "UNYRI could not safely identify the server for your saved account. Your session and history have not been removed. Choose a proven previous server below, or retry when storage is available.")
                    .multilineTextAlignment(.center).foregroundStyle(UnyriColors.secondaryText)
                ForEach(issue.choices, id: \.absoluteString) { origin in
                    Button(origin.absoluteString) { resume(origin.absoluteString) }.unyriProminentAction()
                }
                Button("Retry") { resume(nil) }.unyriProminentAction()
            }.padding(24).frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
        }.unyriScreenBackground()
    }
}

/// Created only after login; discarded together with all cached UI state on logout.
@MainActor
private final class AccountServices: ObservableObject {
    private static var instance: AccountServices?
    private static var authIdentity: ObjectIdentifier?
    static func shared(auth: AuthRepository) -> AccountServices {
        if let existing = instance, existing.initializationError == nil, authIdentity == ObjectIdentifier(auth), existing.sessionGeneration == auth.sessionGeneration { return existing }
        instance?.shutdown()
        let value = AccountServices(auth: auth)
        instance = value; authIdentity = ObjectIdentifier(auth)
        return value
    }
    let sessionGeneration: UInt64
    private(set) var signalManager: SignalSessionManager?
    let privacy: UserPrivacyRepository
    let accountAutoDelete: AccountAutoDeleteRepository
    let users: UsersRepository
    let chats: ChatsRepository
    let messages: MessagesRepository?
    let realtime: RealtimeClient?
    let syncManager: SyncManager?
    let outbox: MessageOutbox?
    private(set) var directFiles: DirectFileTransfers?
    let attachmentManager: AttachmentManager?
    let storage: StorageService?
    let voicePlayer: VoicePlayerController
    let voiceRecorder: VoiceRecorder
    let callController: CallController?
    let conferenceController: ConferenceController?
    private(set) var callsHistory: CallsRepository?
    let callKit: CallKitCoordinator?
    let foregroundBanner: ForegroundMessageBannerController?
    let initializationError: String?
    private(set) var historyBackupAccess: HistoryBackupAccess?

    private var cancellables = [AnyCancellable]()
    private var recoveryTask: Task<Void, Never>?
    private var foregroundTask: Task<Void, Never>?
    private var foregroundTicket = UUID()
    private var historyAutomaticBinding: UUID?
    private weak var auth: AuthRepository?

    init(auth: AuthRepository) {
        self.auth = auth
        sessionGeneration = auth.sessionGeneration
        let session = SessionStore()
        let generation = auth.sessionGeneration
        let client = APIClient(baseURL: AppConfig.apiBaseURL, onAuthenticatedRequestError: { [weak auth] error, bearer in
            // Let the rejected request unwind before cache erasure waits for writers.
            Task { await auth?.handleAuthenticatedRequestError(error, generation: generation, requestToken: bearer) }
        })
        privacy = UserPrivacyRepository(auth: auth)
        accountAutoDelete = AccountAutoDeleteRepository(auth: auth)
        users = UsersRepository(api: UsersAPI(client: client), auth: auth)
        chats = ChatsRepository(api: ChatsAPI(client: client), auth: auth)
        voicePlayer = VoicePlayerController()
        let recordingController = VoiceRecorder()
        voiceRecorder = recordingController
        voicePlayer.playbackAllowed = { [weak recordingController] in
            guard let recordingController else { return false }
            return !recordingController.callInProgress && !recordingController.hasRecordingSession
        }
        do {
            guard let userId = auth.currentUser?.id else { throw APIError.missingSession }
            guard let pushOwner = UUID(uuidString: userId), let pushDevice = auth.currentDeviceId else { throw APIError.missingSession }
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            let server = Data(AppConfig.storageOrigin.utf8).base64EncodedString()
                .replacingOccurrences(of: "/", with: "_")
            let directory = base.appendingPathComponent("COCO", isDirectory: true)
                .appendingPathComponent(server, isDirectory: true)
                .appendingPathComponent(userId, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var privateDirectory = directory
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try privateDirectory.setResourceValues(resourceValues)
            let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let attachmentStore = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
            let chatStore = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3"))
            try HistoryAttachmentRestorer.resume(messages: store, attachments: attachmentStore,
                applyDeletedChat: { try chatStore.deleteChat($0) }, removeCachedFile: { path in
                    let cache = directory.appendingPathComponent("attachments",isDirectory:true).standardizedFileURL.resolvingSymlinksInPath().path + "/"
                    let file = URL(fileURLWithPath:path).standardizedFileURL.resolvingSymlinksInPath()
                    guard file.path.hasPrefix(cache), FileManager.default.fileExists(atPath:file.path),
                          try file.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile == true else { return }
                    try FileManager.default.removeItem(at:file)
                }) {
                guard auth.sessionGeneration == generation, auth.currentUser?.id == userId else { throw APIError.missingSession }
            }
            chats.store = chatStore
            chats.messageStore = store
            let attachmentsCache = directory.appendingPathComponent("attachments", isDirectory: true)
            try FileManager.default.createDirectory(at: attachmentsCache, withIntermediateDirectories: true)
            let container = try UnyriSignalStoreContainer.forCurrentAccount(sessionStore: session)
            let keys = KeysRepository(api: KeysAPI(client: client), auth: auth)
            let messagesAPI = MessagesAPI(client: client)
            let attachmentsAPI = AttachmentsAPI(client: client)
            let keyManager = SignalKeyManager(container: container, keysRepository: keys)
            let sessionManager = SignalSessionManager(container: container, keysRepository: keys)
            self.signalManager = sessionManager
            let attachmentManager = AttachmentManager(
                store: attachmentStore,
                api: attachmentsAPI,
                auth: auth,
                cacheDirectory: attachmentsCache
            )
            self.attachmentManager = attachmentManager
            storage = StorageService(store: attachmentStore, accountDirectory: directory, draftReferenced: { try store.workspaceReferences($0) }, current: { generation == auth.sessionGeneration && auth.currentUser?.id == userId })
            let ingestor = MessageIngestor(store: store, attachmentStore: attachmentStore,
                keyManager: keyManager,
                sessionManager: sessionManager, auth: auth, messagesAPI: messagesAPI,
                sessionStore: session)
            chats.onPeerReceipt = { [weak ingestor] chat, delivered, read in
                ingestor?.advanceOwnMessageStatuses(chatId: chat, deliveredSequence: delivered, readSequence: read)
            }
            sessionManager.onTrustAccepted = { [weak ingestor] userId, _ in
                Task { await ingestor?.retryPendingDecrypts(senderId: userId) }
            }
            let outbox = MessageOutbox(store: store, ingestor: ingestor,
                sessionManager: sessionManager, auth: auth, messagesAPI: messagesAPI,
                sessionStore: session, chatsRepository: chats, attachmentManager: attachmentManager)
            self.outbox = outbox
            outbox.onAuthTerminal = {
                Task { await auth.handleAuthenticatedError(.server(code: "session_revoked", message: "Session ended"), generation: generation) }
            }
            let liveChats = chats
            let liveUsers = users
            liveUsers.onAccountBlockChange = { [weak liveChats] userID, blocked in
                liveChats?.applyAccountBlock(userID: userID, blocked: blocked)
            }
            liveChats.onAccountBlockChange = { [weak liveUsers] userID, blocked in
                liveUsers?.accountBlockAcknowledged(userID: userID, blocked: blocked)
            }
            ingestor.changes.merge(with: outbox.changes)
                .sink { chatIds in
                    Task { await liveChats.messagesChanged(in: chatIds) }
                }.store(in: &cancellables)

            let messagesRepository = MessagesRepository(api: messagesAPI, auth: auth,
                store: store, ingestor: ingestor, outbox: outbox,
                attachmentManager: attachmentManager)
            messages = messagesRepository
            let directFiles = DirectFileTransfers(auth: auth, signal: sessionManager, outbox: outbox,
                store: store, client: client, accountDirectory: directory)
            self.directFiles = directFiles
            messagesRepository.directFiles = directFiles
            ingestor.onDirectFileOffer = { [weak directFiles] chat, offer in
                directFiles?.receivedOffer(chatId: chat, offer: offer)
            }
            messagesRepository.onReadStateChange = { [weak liveChats] in
                Task { await liveChats?.refreshUnreadCounts() }
            }

            let realtime = RealtimeClient(wsURL: AppConfig.wsURL, auth: auth)
            let privacyRepository = privacy
            let profileRepository = users
            realtime.profileEvents.sink { [weak profileRepository] hint in
                profileRepository?.refreshOwnProfile(hint)
            }.store(in: &cancellables)
            realtime.privacyEvents.sink { [weak privacyRepository] _ in Task { await privacyRepository?.refresh() } }.store(in: &cancellables)
            let accountAutoDeleteRepository = accountAutoDelete
            realtime.accountAutoDeleteEvents.sink { [weak accountAutoDeleteRepository] _ in Task { await accountAutoDeleteRepository?.refresh() } }.store(in: &cancellables)
            realtime.liveDrafts.configure(signal: sessionManager, send: { [weak realtime] value in
                guard realtime?.state == .connected else { return }
                realtime?.sendRaw(value)
            })
            outbox.onMediaActivity = { [weak realtime] chat, kind in
                if UserDefaults.standard.object(forKey: "chatActivityEnabled") as? Bool ?? true {
                    realtime?.sendActivity(chatId: chat, kind: kind)
                }
            }
            self.realtime = realtime
            let syncManager = SyncManager(api: SyncAPI(client: client), ingestor: ingestor,
                auth: auth, sessionStore: session)
            self.syncManager = syncManager
            syncManager.onPhaseChange = { [weak realtime] phase in realtime?.syncPhase = phase }
            realtime.onRetrySync = { [weak syncManager] in await syncManager?.runCatchUpSync() }
            let callsRepository = CallsRepository(api: CallsAPI(client: client), auth: auth,
                historyFile: directory.appendingPathComponent("calls.json"), ownerId: UUID(uuidString: userId),
                deletionServerOrigin: AppConfig.storageOrigin)
            let historyLease = HistoryAccountLease()
            historyBackupAccess = HistoryBackupAccess(lease: historyLease) { server, remote in
                HistoryNativeOwner(server: server, owner: userId, directory: directory, remote: remote, lease: historyLease,
                    sourceReady: {
                        guard generation == auth.sessionGeneration else { throw CancellationError() }
                        try await callsRepository.prepareBackupHistory()
                        do { try await syncManager.prepareHistoryBackup() }
                        catch is SyncManager.HistoryPreparationFailure { throw HistoryNativeOwner.Failure.incompleteSource }
                        var offset: Int64 = 0
                        for _ in 0..<100 {
                            let page = try await liveChats.listChats(limit: 100, offset: offset)
                            liveChats.persistChats(page.items)
                            guard let next = page.nextOffset else { return }
                            guard next > offset else { throw HistoryNativeOwner.Failure.incompleteSource }
                            offset = next
                        }
                        throw HistoryNativeOwner.Failure.incompleteSource
                    }, current: {
                        guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    }, updated: { [weak callsRepository] touched in
                        _ = await liveChats.loadCachedChatsAsync()
                        await callsRepository?.loadCachedHistory()
                        await liveChats.messagesChanged(in: touched)
                    }, exportCalls: { [weak callsRepository] archive in
                        guard let callsRepository else { throw CancellationError() }
                        return try await callsRepository.exportBackupCalls(archive)
                    }, restoreCalls: { [weak callsRepository] archive in
                        guard let callsRepository else { throw CancellationError() }
                        try await callsRepository.restoreBackupCalls(archive)
                    })
            }
            UnyriAppLock.shared.presentationChanges.sink { [weak messagesRepository] _ in
                Task { await messagesRepository?.presentationVisibilityChanged() }
            }.store(in: &cancellables)
            storage?.onCleared = { [weak messagesRepository] in messagesRepository?.invalidateLocalMediaCache() }
            syncManager.onDeletedChat = { [weak liveChats, weak messagesRepository, weak realtime] chatId in
                guard let liveChats, let messagesRepository, let realtime else { throw CancellationError() }
                guard generation == auth.sessionGeneration else { throw CancellationError() }
                try store.markChatDeleted(chatId)
                try liveChats.removeDeletedChat(chatId)
                realtime.liveDrafts.close(chat: chatId)
                try await messagesRepository.purgeDeletedChat(chatId)
                ConversationImages.clearDecodedCache()
            }

            liveChats.onPersonalClear = { [weak messagesRepository] chat, through in
                guard let messagesRepository else { throw CancellationError() }
                return try await messagesRepository.clearPersonalChat(chat, through: through)
            }
            liveChats.clearServerOrigin = AppConfig.storageOrigin
            liveChats.currentClearServer = { AppConfig.storageOrigin }
            liveChats.clearSyncSequence = {
                guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { return 0 }
                return session.lastSyncedSequence() ?? 0
            }
            liveChats.onLocalBoundedClear = { [weak messagesRepository] chat, cancelled in
                messagesRepository?.boundedChatWasCleared(chat,cancelled:cancelled)
            }
            syncManager.beforeSync = {
                do { try await liveChats.refreshPersonalClears() }
                catch APIError.http(404) { /* Older server. */ }
            }
            liveChats.onDeleteConversation = syncManager.onDeletedChat

            // Call subsystem: REST lifecycle + signaling over the SAME socket +
            // WebRTC media, all behind one authoritative controller.
            callsRepository.currentDeletionServer = { AppConfig.storageOrigin }
            callsRepository.currentDeletionOwner = { auth.currentUser.flatMap { UUID(uuidString: $0.id) } }
            callsRepository.messageStore = store
            callsRepository.beforeHistoryRefresh = {
                do { try await liveChats.refreshPersonalClears() }
                catch APIError.http(404) { /* Legacy server. */ }
                catch APIError.server(let code, _) where code == "not_found" { }
            }
            liveChats.onCallClear = { [weak callsRepository] chat, before in callsRepository?.applyCallClear(chat: chat, before: before) }
            let deleteChat = syncManager.onDeletedChat
            syncManager.onDeletedChat = { [weak callsRepository] chat in
                try await deleteChat?(chat)
                callsRepository?.chatDeleted(chat)
            }
            liveChats.onDeleteConversation = syncManager.onDeletedChat
            callsRepository.projectHistory = { [weak callsRepository] rows, since in
                guard let callsRepository else { throw CancellationError() }
                let historyRevision = callsRepository.historyRevision
                var resolved: [CallDTO] = []
                var changed = false
                let chatRevision = liveChats.cacheRevision
                for var call in rows {
                    guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    guard CallActivityPolicy.eligible(call, owner: UUID(uuidString: userId), since: since) else { continue }
                    let peer = (call.callerUserId == UUID(uuidString: userId) ? call.calleeUserId : call.callerUserId).uuidString.lowercased()
                    var visible = false
                    if call.chatVisible != false {
                        do {
                            if let chat = try await liveChats.resolveCallChat(call, peer: peer) {
                                call.chatId = UUID(uuidString: chat.chatId); visible = true
                            }
                        } catch APIError.network {
                            guard generation == auth.sessionGeneration else { throw CancellationError() }
                            // A transport failure does not revoke an already validated canonical cache entry.
                            if let id = call.chatId?.uuidString.lowercased(),
                               let cached = liveChats.preferenceCache[id], !liveChats.isDeleted(id),
                               UUID(uuidString: cached.otherUser?.id ?? "") == UUID(uuidString: peer),
                               let created = ISO8601Cocoa.parse(cached.createdAt), created <= call.createdAt { visible = true }
                        } catch {
                            guard generation == auth.sessionGeneration else { throw CancellationError() }
                        }
                    }
                    guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    guard callsRepository.historyRevision == historyRevision else { throw CancellationError() }
                    let rowChanged = try store.projectCallActivity(CallActivityProjection(callId: call.id.uuidString.lowercased(),
                        chatId: call.chatId?.uuidString.lowercased(), peerId: peer, createdAt: call.createdAt,
                        mediaKind: call.mediaKind.rawValue, outcome: call.isEnded ? "ended:\(call.endReason ?? "")" : call.state,
                        visible: visible, unread: call.unread), owner: userId)
                    changed = changed || rowChanged
                    resolved.append(call)
                }
                return (resolved, changed || liveChats.cacheRevision != chatRevision)
            }
            callsRepository.activityChanged = { [weak liveChats] in
                guard generation == auth.sessionGeneration else { return }
                await liveChats?.messagesChanged(in: [])
            }
            realtime.callHistoryEvents.sink { [weak callsRepository] in callsRepository?.reconcile() }.store(in: &cancellables)
            realtime.callEvents.sink { [weak callsRepository] event in
                switch event {
                case .ringing(let id, _, _), .ended(let id, _), .accepted(let id), .answeredElsewhere(let id):
                    callsRepository?.reconcile(callId: UUID(uuidString: id))
                default: break
                }
            }.store(in: &cancellables)
            realtime.$state.removeDuplicates().sink { [weak callsRepository] state in
                if state == .connected {
                    callsRepository?.reconcile()
                    Task { await liveChats.replayPreferences(); _ = try? await liveChats.archives() }
                    Task { await liveChats.replayPendingChatClears() }
                }
            }.store(in: &cancellables)
            syncManager.onHistoryClear = { cutoff, sequence in
                guard generation == auth.sessionGeneration else { throw CancellationError() }
                let touched = try store.clearServerHistory(throughSequence: sequence)
                ingestor.changes.send(touched)
                try await callsRepository.clearServerHistory(beforeMs: cutoff)
            }
            callsHistory = callsRepository
            let callSignaling = SocketCallSignaling(realtime: realtime)
            let callEngine = WebRTCEngineImpl()
            let callController = CallController(
                api: callsRepository,
                signaling: callSignaling,
                engine: callEngine,
                generation: auth.sessionGeneration
            )
            self.callController = callController
            let conferenceController = ConferenceController(auth: auth, signalManager: sessionManager)
            self.conferenceController = conferenceController
            callController.externalMediaBusy = { ConferenceMediaOwnership.isBusy }
            historyBackupAccess?.canUseCamera = { [weak callController] in
                callController?.state == .idle && !ConferenceMediaOwnership.isBusy
            }
            let callVoiceRecorder = voiceRecorder
            let callVoicePlayer = voicePlayer
            callController.$state.sink { state in
                switch state {
                case .outgoingRinging, .incomingRinging, .connecting, .connected, .reconnecting:
                    callVoiceRecorder.callInProgress = true
                    callVoicePlayer.stop()
                default:
                    callVoiceRecorder.callInProgress = ConferenceMediaOwnership.isBusy
                    if UIApplication.shared.applicationState == .background { realtime.stop() }
                }
            }.store(in: &cancellables)
            let callerChats = chats
            callController.resolveCallerName = { userId in
                return try? await callerChats.directPeerName(userId)
            }
            // Native system-call UI mirror of the same controller (no second
            // state machine). Shares the early process provider with PushKit.
            let pushSession = UnyriPushSession(scope: UnyriPushScope(origin: auth.authenticatedServerURL.absoluteString,
                user: pushOwner, device: pushDevice), generation: auth.sessionGeneration)
            callController.incomingCallAllowed = { UnyriCallKitProvider.shared.allowsIncoming($0, session: pushSession) }
            let callKit = CallKitCoordinator(controller: callController, generation: auth.sessionGeneration, pushSession: pushSession)
            self.callKit = callKit
            directFiles.canStream = { [weak callController, weak callKit] in
                callController?.state == .idle && callKit?.hasPendingMediaStart == false && !ConferenceMediaOwnership.isBusy
            }
            callController.$state.sink { [weak directFiles] _ in directFiles?.mediaOwnershipChanged() }.store(in: &cancellables)
            conferenceController.$phase.sink { [weak directFiles] _ in directFiles?.mediaOwnershipChanged() }.store(in: &cancellables)
            NonCallCaptureOwnership.currentGeneration = { [weak auth] in
                guard let auth, auth.currentUser != nil else { return nil }
                return auth.sessionGeneration
            }
            NonCallCaptureOwnership.callsBusy = { [weak callController, weak callKit] in
                ConferenceMediaOwnership.isBusy || callController?.isPrivateMediaBusy != false || callKit?.hasPendingMediaStart != false
            }
            callController.externalCaptureBusy = { NonCallCaptureOwnership.isBusy }
            callController.interruptExternalCapture = { NonCallCaptureOwnership.interrupt() }
            callController.awaitExternalCaptureRelease = { deadline in
                try await NonCallCaptureOwnership.waitForRelease(until: deadline)
            }
            conferenceController.canUseMedia = { [weak callController, weak callKit, weak callVoiceRecorder] in
                callController?.canAcquireConferenceMedia == true && callKit?.hasPendingMediaStart == false
                    && callVoiceRecorder?.hasRecordingSession == false
            }
            conferenceController.onMediaOwnershipChanged = { [weak callVoiceRecorder, weak callVoicePlayer, weak callController] active in
                callVoiceRecorder?.callInProgress = active || callController?.isPrivateMediaBusy == true
                if active { callVoicePlayer?.stop() }
            }

            // Runtime Correction D: foreground message banner (presentation-only).
            // Driven by WS new-message events, resolved from the just-persisted
            // LOCAL row (never the wire ciphertext). Replace-latest, auto-dismissed.
            let bannerController = ForegroundMessageBannerController(
                realtimeEvents: realtime.events,
                ingestorChanges: ingestor.changes,
                loadMessage: { try store.message(byClientMessageId: $0) },
                ownUserId: userId,
                activeChatId: { messagesRepository.activeChatId },
                activeCall: {
                    switch callController.state {
                    case .outgoingRinging, .incomingRinging, .connecting, .connected, .reconnecting:
                        return true
                    default:
                        return false
                    }
                },
                resolveSenderName: { chatId in
                    callerChats.loadCachedChats(includePreviews: false).first(where: { $0.chatId == chatId })?.otherUser?.displayName
                },
                notificationServer: AppConfig.apiBaseURL.absoluteString,
                notificationGeneration: generation
            )
            bannerController.isSilenced = { chatId in
                callerChats.preferenceCache[chatId]?.suppressNotifications == true
            }
            bannerController.notificationsEnabled = { UnyriNotificationPreferencesStore.shared.value.allowsMessages }
            bannerController.messagePreviewEnabled = { UnyriNotificationPreferencesStore.shared.value.previews }
            UnyriForegroundMessageSound.bind(owner: userId, generation: generation, canPlay: { [weak auth, weak callController, weak callKit] in
                auth?.sessionGeneration == generation && auth?.currentUser?.id == userId &&
                    callController?.isPrivateMediaBusy == false && callKit?.hasPendingMediaStart == false &&
                    !ConferenceMediaOwnership.isBusy
            })
            bannerController.onPresented = { [weak auth, weak bannerController] banner, messageId in
                guard let auth, auth.sessionGeneration == generation, auth.currentUser?.id == userId,
                      bannerController?.isForeground == true, banner.shouldAlert else { return }
                UnyriForegroundMessageSound.present(owner: userId, generation: generation, chatId: banner.chatId, messageId: messageId,
                    stillCurrent: { [weak auth, weak bannerController] in
                        auth?.sessionGeneration == generation && auth?.currentUser?.id == userId &&
                            bannerController?.isForeground == true
                    })
            }
            NotificationCenter.default.publisher(for: UnyriNotificationPreferencesStorage.changed)
                .receive(on: RunLoop.main)
                .sink { [weak bannerController] _ in
                    bannerController?.preferencesChanged()
                    UnyriLocalNotificationPreferences.reconcile()
                    Task { await UnyriPushNotificationCoordinator.shared.refreshAuthorization() }
                }.store(in: &cancellables)
            UnyriLocalNotificationPreferences.attach(owner: userId, isCurrent: { [weak auth] in
                auth?.sessionGeneration == generation && auth?.currentUser?.id == userId
            })
            foregroundBanner = bannerController

            // WS reconnect -> global catch-up sync + pending-message flush
            // (Android parity: the socket fast path only delivers while
            // connected; /sync and forceFlush cover the gaps).
            realtime.$state
                .removeDuplicates()
                .sink { [weak profileRepository, weak directFiles] state in
                    if state == .connected {
                        directFiles?.networkDidRecover()
                        profileRepository?.refreshOwnProfile()
                        Task {
                            await syncManager.runCatchUpSync()
                            await outbox.forceFlush()
                            await messagesRepository.flushReadReceipts()
                        }
                    }
                }
                .store(in: &cancellables)

            // Realtime event dispatch: incoming messages funnel through the
            // shared ingestor (single decrypt/dedup path); receipts advance our
            // own bubbles; a server-side session revocation logs out.
            realtime.events.sink { event in
                switch event {
                case .messageNew(let dto), .messageSent(let dto):
                    Task { _ = await ingestor.ingest([dto], advanceCursor: false) }
                case .receiptUpdated(let chatId, let userId, let delivered, let read):
                    if userId == auth.currentUser?.id {
                        messagesRepository.applyAccountReadReceipt(chatId: chatId, sequence: read)
                        return
                    }
                    ingestor.advanceOwnMessageStatuses(
                        chatId: chatId,
                        deliveredSequence: delivered,
                        readSequence: read
                    )
                case .sessionRevoked:
                    Task { await auth.handleAuthenticatedError(.server(code: "session_revoked", message: "Session ended"), generation: generation) }
                case .chatManagementChanged:
                    liveChats.accountBlocksChangedRemotely()
                    liveUsers.accountBlocksChanged()
                    liveChats.managementRevision &+= 1
                    Task {
                        await liveChats.replayPreferences()
                        _ = try? await liveChats.archives()
                        await liveChats.replayPendingChatClears()
                        try? await liveChats.refreshPersonalClears()
                    }
                case .chatDeleted(let chatId):
                    Task { try? await syncManager.onDeletedChat?(chatId) }
                }
            }.store(in: &cancellables)

            initializationError = nil
            historyAutomaticBinding = HistoryAutomaticBackup.shared.attach { [weak self, weak auth] in
                guard let self, let auth, let access = self.historyBackupAccess,
                      auth.sessionGeneration == self.sessionGeneration else { return false }
                return await HistoryAutomaticBackup.shared.run(auth: auth, access: access, canRun: self.callController?.state == .idle)
            }
        } catch {
            messages = nil
            realtime = nil
            syncManager = nil
            outbox = nil
            attachmentManager = nil
            storage = nil
            callController = nil
            conferenceController = nil
            callKit = nil
            foregroundBanner = nil
            initializationError = error.localizedDescription
        }
        let accountUsers = users
        let attachments = attachmentManager
        let accountMessages = messages
        let accountCalls = callsHistory
        let recorder = voiceRecorder
        let player = voicePlayer
        auth.prepareForCacheErasure = { [weak self] in
            self?.shutdown()
            await self?.directFiles?.drain()
            await self?.historyBackupAccess?.drain()
            ConversationImages.clearDecodedCache()
            UserAvatar.clearDecodedCache()
            recorder.cancel()
            player.stop()
            await accountMessages?.finishSessionDraftWrites()
            await accountCalls?.finishSessionCacheWrites()
            await accountUsers.finishSessionCacheWrites()
            await attachments?.finishSessionCacheWrites()
        }
    }

    /// Only one account-owned foreground preflight can publish work. A scene
    /// transition revokes its ticket before an old privacy/disk await returns.
    func startRealtime() {
        guard auth?.sessionGeneration == sessionGeneration, auth?.currentUser != nil,
              UIApplication.shared.applicationState == .active,
              foregroundTask == nil, recoveryTask == nil else { return }
        let ticket = UUID()
        foregroundTicket = ticket
        foregroundTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.foregroundTicket == ticket { self.foregroundTask = nil } }
            guard self.foregroundCurrent(ticket) else { return }
            await self.privacy.refresh()
            guard self.foregroundCurrent(ticket) else { return }
            await self.storage?.enforcePolicy()
            guard self.foregroundCurrent(ticket) else { return }
            await self.startForegroundRealtime(ticket: ticket)
        }
    }

    /// Revokes foreground jobs only. Call/room/checkpoint background leases
    /// retain their existing owners and are not controlled by this ticket.
    func cancelForegroundWork() {
        foregroundTicket = UUID()
        foregroundTask?.cancel()
        foregroundTask = nil
        recoveryTask?.cancel()
        recoveryTask = nil
    }

    private func foregroundCurrent(_ ticket: UUID) -> Bool {
        !Task.isCancelled && foregroundTicket == ticket &&
            auth?.sessionGeneration == sessionGeneration && auth?.currentUser != nil &&
            UIApplication.shared.applicationState == .active
    }

    private func startForegroundRealtime(ticket: UUID) async {
        guard foregroundCurrent(ticket) else { return }
        directFiles?.start()
        users.refreshOwnProfile()
        realtime?.setPresenceActive(true)
        if recoveryTask == nil {
            recoveryTask = Task { [weak self] in
                while !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                    guard let self, self.foregroundCurrent(ticket) else { return }
                    self.realtime?.start()
                    self.callController?.refreshActiveCall()
                    self.callsHistory?.reconcile()
                    async let preferenceReplay: Void = { @MainActor in
                        guard self.foregroundCurrent(ticket) else { return }
                        await self.chats.replayPreferences()
                    }()
                    await self.chats.replayPendingChatClears()
                    guard self.foregroundCurrent(ticket) else { return }
                    await self.syncManager?.runCatchUpSync()
                    guard self.foregroundCurrent(ticket) else { return }
                    await self.chats.recoverVisiblePeerReceipts { self.messages?.activeChatId }
                    await preferenceReplay
                }
            }
        }
        realtime?.start()
        callController?.refreshActiveCall()
        callsHistory?.reconcile()
        async let preferenceReplay: Void = { @MainActor in
            guard self.foregroundCurrent(ticket) else { return }
            await self.chats.replayPreferences()
        }()
        await chats.replayPendingChatClears()
        guard foregroundCurrent(ticket) else { return }
        await syncManager?.runCatchUpSync()
        guard foregroundCurrent(ticket) else { return }
        await chats.recoverVisiblePeerReceipts { self.messages?.activeChatId }
        guard foregroundCurrent(ticket) else { return }
        HistoryAutomaticBackup.shared.foreground()
        // Flush any PENDING rows surviving a restart/login.
        await outbox?.forceFlush()
        guard foregroundCurrent(ticket) else { return }
        await messages?.flushReadReceipts()
        await preferenceReplay
    }

    func stopRealtime() {
        cancelForegroundWork()
        directFiles?.suspendForBackground()
        realtime?.setPresenceActive(false)
        // An active CallKit audio session can keep the app running in the
        // background. Keep signaling alive so remote hangup reaches CallKit.
        if !CallKitPolicy.keepsSignalingInBackground(callController?.state ?? .idle) { realtime?.stop() }
        // Stop media so playback/recording never continues while backgrounded
        // or survives into another account's session.
        voicePlayer.stop()
        voiceRecorder.interrupt()
    }

    func shutdown() {
        cancelForegroundWork()
        directFiles?.shutdown()
        users.stopOwnProfileSync()
        storage?.retire()
        NonCallCaptureOwnership.interrupt(generation: sessionGeneration)
        if Self.instance === self { Self.instance = nil; Self.authIdentity = nil }
        HistoryAutomaticBackup.shared.detach(historyAutomaticBinding)
        historyBackupAccess?.stop()
        signalManager?.shutdown(); signalManager = nil
        outbox?.stopScheduling()
        foregroundBanner?.isForeground = false
        callKit?.shutdown()
        conferenceController?.shutdown()
        callController?.shutdown()
        callsHistory?.stopReconciliation()
        stopRealtime()
    }
}

@MainActor
struct AccountContentView: View {
    @ObservedObject var auth: AuthRepository
    @ObservedObject private var incomingFile = UnyriIncomingFile.shared
    @StateObject private var services: AccountServices
    @State private var showHistoryRestore = false
    @Environment(\.scenePhase) private var scenePhase

    init(auth: AuthRepository) {
        self.auth = auth
        _services = StateObject(wrappedValue: AccountServices.shared(auth: auth))
    }

    var body: some View {
        Group {
            if let messages = services.messages, let attachments = services.attachmentManager, let storage = services.storage, let callController = services.callController, let conferenceController = services.conferenceController, let callKit = services.callKit, let realtime = services.realtime, let bannerController = services.foregroundBanner, let callsHistory = services.callsHistory {
                LoggedInView()
                    .environment(\.historyBackupAccess, services.historyBackupAccess)
                    .environment(\.signalVerificationManager, services.signalManager)
                    .environmentObject(services.privacy)
                    .environmentObject(services.accountAutoDelete)
                    .environmentObject(services.users)
                    .environmentObject(services.chats)
                    .environmentObject(callsHistory)
                    .environmentObject(messages)
                    .environmentObject(attachments)
                    .environmentObject(storage)
                    .environmentObject(services.voicePlayer)
                    .environmentObject(services.voiceRecorder)
                    .environmentObject(callController)
                    .environmentObject(conferenceController)
                    .environmentObject(callKit)
                    .environmentObject(realtime)
                    .environmentObject(bannerController)
            } else {
                VStack(spacing: 16) {
                    Text("Could not open local messages")
                    Text(services.initializationError ?? "Please restart UNYRI.").font(.footnote)
                    Button("Sign out") { Task { await auth.logout() } }
                }.padding()
            }
        }
        .task {
            if let calls = services.callController, let rooms = services.conferenceController, let user = auth.currentUser {
                UnyriAppLock.shared.attach(calls: calls, rooms: rooms,
                    owner: auth.authenticatedServerURL.absoluteString + "|" + user.id + "|" + String(auth.sessionGeneration), auth: auth)
                UnyriAppLock.shared.phase(scenePhase)
            }
            services.foregroundBanner?.isForeground = scenePhase == .active
            if UserDefaults.standard.string(forKey: "openHistoryBackupForAccount") == auth.currentUser?.id {
                UserDefaults.standard.removeObject(forKey: "openHistoryBackupForAccount")
                showHistoryRestore = true
            }
            incomingFile.checkSharedInbox()
            services.startRealtime()
        }
        .task {
            while !Task.isCancelled {
                await services.storage?.enforcePolicy()
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
            }
        }
        .sheet(isPresented: Binding(get: { incomingFile.url != nil && scenePhase == .active && UIApplication.shared.isProtectedDataAvailable }, set: { if !$0 && scenePhase == .active && UIApplication.shared.isProtectedDataAvailable { incomingFile.close() } }), onDismiss: { incomingFile.checkSharedInbox() }) {
            if let messages = services.messages { UnyriImportFileSheet(inbox: incomingFile, messages: messages, chats: services.chats).id(incomingFile.url?.path) }
        }
        .sheet(isPresented: $showHistoryRestore) {
            NavigationStack {
                HistoryKeySettingsView()
                    .environmentObject(auth)
                    .environment(\.historyBackupAccess, services.historyBackupAccess)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showHistoryRestore = false } } }
            }
        }
        .onReceive(auth.$state) { _ in
            if auth.sessionGeneration != services.sessionGeneration { incomingFile.close(); services.shutdown() }
        }
        .onDisappear {
            // A full-screen call can hide this view without ending its account.
            if auth.sessionGeneration != services.sessionGeneration {
                services.shutdown()
            }
        }
        .onChange(of: scenePhase) { phase in
            services.foregroundBanner?.isForeground = phase == .active
            if phase == .active {
                UnyriLocalNotificationPreferences.reconcile()
                incomingFile.checkSharedInbox()
                services.startRealtime()
            } else if phase == .background {
                services.stopRealtime()
            } else {
                services.cancelForegroundWork()
            }
        }
    }
}

/// Local reminder routing survives a cold launch; payload contains IDs only.
final class ChatReminderDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UnyriMediaOrientation.mask(for: window?.windowScene)
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UnyriPushNotificationCoordinator.shared.start(application)
        return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        UnyriPushNotificationCoordinator.shared.receivedAPNs(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        UnyriPushNotificationCoordinator.shared.failedAPNs()
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let values = response.notification.request.content.userInfo
        if response.notification.request.trigger is UNPushNotificationTrigger {
            if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                Task { @MainActor in UnyriPushNotificationCoordinator.shared.tapped(values) }
            }
            completionHandler(); return
        }
        if ["chat_reminder", "foreground_message_sound"].contains(values["kind"] as? String ?? "") {
            guard let owner = values["owner"] as? String, UUID(uuidString: owner) != nil,
                  NotificationRecipientPolicy.matches(owner: owner, current: SessionStore().currentUser()?.id),
                  let chat = values["chat"] as? String, UUID(uuidString: chat) != nil,
                  let message = values["message"] as? String, UUID(uuidString: message) != nil else {
                completionHandler(); return
            }
            let route = ["owner": values["owner"] as? String ?? "", "chat": values["chat"] as? String ?? "", "message": values["message"] as? String ?? ""]
            UserDefaults.standard.set(route, forKey: "chatReminderRoute")
            DispatchQueue.main.async { NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil) }
        }
        completionHandler()
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let values = notification.request.content.userInfo
        if notification.request.trigger is UNPushNotificationTrigger {
            Task { @MainActor in completionHandler(UnyriPushNotificationCoordinator.shared.presentation(values)) }
            return
        }
        let preferences = UnyriNotificationPreferencesStorage.load()
        if values["kind"] as? String == "foreground_message_sound" {
            Task { @MainActor in
                let current = UnyriForegroundMessageSound.permitsPresentation(owner: values["owner"] as? String,
                    generation: (values["session_generation"] as? NSNumber)?.uint64Value)
                completionHandler(current && UnyriNotificationPreferencesStore.shared.value.allowsMessageSound ? [.sound] : [])
            }
            return
        }
        if values["kind"] as? String == "chat_reminder" &&
            !NotificationRecipientPolicy.matches(owner: values["owner"] as? String, current: SessionStore().currentUser()?.id) {
            completionHandler([]); return
        }
        if values["kind"] as? String == "chat_reminder" {
            guard preferences.allowsReminders else { completionHandler([]); return }
            completionHandler(preferences.allowsReminderSound ? [.banner, .sound] : [.banner]); return
        }
        // Foreground message presentation belongs to the in-app banner policy.
        // Reminders and security/call notifications keep their existing behavior.
        if values["event"] as? String == "new_message" || values["type"] as? String == "message.new" {
            completionHandler([])
        } else { completionHandler([.banner, .sound]) }
    }
}
