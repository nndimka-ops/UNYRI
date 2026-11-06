package com.messenger.app

import com.messenger.app.ui.theme.applyLocale

import android.app.Application
import com.messenger.app.call.CallForegroundServiceController
import com.messenger.app.data.api.AttachmentsApi
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.api.DevicesApi
import com.messenger.app.data.api.KeysApi
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.data.api.PushApi
import com.messenger.app.data.api.SyncApi
import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.attachment.AttachmentManager
import com.messenger.app.data.attachment.MediaGalleryRepository
import com.messenger.app.data.auth.AuthRepositoryImpl
import com.messenger.app.data.call.CallApiImpl
import com.messenger.app.data.call.CallSignalingClientImpl
import com.messenger.app.data.call.WebRtcEngineImpl
import com.messenger.app.data.chat.ChatRepositoryImpl
import com.messenger.app.data.devices.DevicesRepositoryImpl
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.message.MessageRepositoryImpl
import com.messenger.app.data.profile.ProfileRepositoryImpl
import com.messenger.app.data.push.PushRegistrationRepository
import com.messenger.app.data.push.PushTokenStore
import com.messenger.app.data.search.MessageSearchRepositoryImpl
import com.messenger.app.data.secure.SecureTokenStore
import com.messenger.app.data.signal.AndroidKeystoreSignalStore
import com.messenger.app.data.signal.IdentityVerificationManager
import com.messenger.app.data.signal.SignalKeyStore
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.data.sync.GlobalSyncManager
import com.messenger.app.data.sync.MessageIngestionPipeline
import com.messenger.app.data.websocket.IncomingSocketEvent
import com.messenger.app.data.websocket.MessagingSocketClient
import com.messenger.app.data.websocket.WebSocketConnectionRepository
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.ConnectionRepository
import com.messenger.app.domain.repository.DevicesRepository
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.repository.MessageSearchRepository
import com.messenger.app.domain.repository.ProfileRepository
import com.messenger.app.push.CallPushHandlerImpl
import com.messenger.app.push.FirebaseAvailability
import com.messenger.app.push.ForegroundMessageBannerController
import com.messenger.app.push.MessagePushHandlerImpl
import com.messenger.app.push.NotificationChannels
import com.messenger.app.push.PushEvent
import com.messenger.app.push.PushRouter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Stage 1: manual dependency wiring (no DI framework yet, keeps the app
 * small until a real DI need arises).
 */
class MessengerApp : Application() {
    val notificationSettings by lazy { com.messenger.app.push.NotificationSettingsStore(this) }
    lateinit var liveDrafts: com.messenger.app.data.websocket.LiveDrafts
        private set
    lateinit var callHistory: com.messenger.app.data.call.CallHistoryRepository
        private set

    lateinit var connectionRepository: ConnectionRepository
        private set

    lateinit var authRepository: AuthRepository
        private set
    lateinit var performanceDiagnostics: com.messenger.app.data.api.PerformanceDiagnostics
        private set

    lateinit var historyBackupTransport: () -> com.messenger.app.data.history.HistoryBackupTransport
        private set
    lateinit var historySettingsSession: () -> com.messenger.app.data.history.HistoryKeySettingsSession
        private set
    private lateinit var historyRestoreBarrier: suspend () -> Unit
    val historyReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var avatarRepository: com.messenger.app.data.avatar.AvatarRepository
        private set

    lateinit var devicesRepository: DevicesRepository
        private set

    @Volatile private var notificationPreferences: Pair<String?, Map<String, com.messenger.app.domain.model.ChatSummary>> = null to emptyMap()
    private fun isChatSilenced(id: String): Boolean {
        val snapshot = notificationPreferences
        val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        return owner != null && snapshot.first == owner && snapshot.second[id]?.suppressesNotifications() == true
    }
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var blockedUsersRepository: com.messenger.app.domain.repository.BlockedUsersRepository
        private set

    lateinit var messageRepository: MessageRepository
    lateinit var directTransfers: com.messenger.app.data.directtransfer.DirectTransferCoordinator
        private set
    internal lateinit var directBackground: com.messenger.app.data.directtransfer.DirectTransferBackgroundController
        private set
    lateinit var chatClearQueue: com.messenger.app.data.chat.BoundedChatClearQueue
        private set

    lateinit var messageSearchRepository: MessageSearchRepository
        private set

    lateinit var mediaGalleryRepository: MediaGalleryRepository
        private set

    lateinit var identityVerification: IdentityVerificationManager
        private set

    lateinit var attachmentManager: AttachmentManager
        private set

    val messageReadVisibility = com.messenger.app.security.MessageReadVisibility()
    lateinit var privacyRepository: com.messenger.app.data.profile.PrivacyRepository
        private set
    lateinit var accountAutoDeleteRepository: com.messenger.app.data.profile.AccountAutoDeleteRepository
        private set
    private lateinit var accountActivityReporter: com.messenger.app.data.profile.ForegroundAccountActivityReporter
    lateinit var mediaCacheManager: com.messenger.app.data.local.MediaCacheManager
        private set

    lateinit var callController: CallController
        private set
    lateinit var conferenceApi: com.messenger.app.conference.ConferenceApi
        private set
    lateinit var conferenceKeyMailbox: com.messenger.app.conference.ConferenceKeyMailbox
        private set
    lateinit var conferenceController: com.messenger.app.conference.ConferenceController
        private set
    lateinit var nonCallCaptures: com.messenger.app.capture.NonCallCaptureCoordinator
        private set
    lateinit var externalCaptureStore: com.messenger.app.capture.ExternalCaptureStore
        private set

    fun captureSession(): com.messenger.app.capture.CaptureSession? {
        if (!::authRepository.isInitialized) return null
        val user = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user ?: return null
        val device = authRepository.currentDeviceId() ?: return null
        return com.messenger.app.capture.CaptureSession(com.messenger.app.capture.CaptureOwner(user.id,
            com.messenger.app.data.api.ServerEndpoint.current(), device), authRepository.currentSessionEpoch())
    }

    fun clearConferenceForSessionChange() {
        if (::conferenceController.isInitialized) conferenceController.terminateForSessionChange()
        if (::nonCallCaptures.isInitialized) nonCallCaptures.interruptAll()
    }

    lateinit var incomingCallNotificationManager: com.messenger.app.call.IncomingCallNotificationManager
        private set

    var routeMessagePush: suspend (PushEvent.NewMessage) -> Unit = {}
        private set
    lateinit var pushRegistrationRepository: PushRegistrationRepository
        private set

    lateinit var pushRouter: PushRouter
        private set
    lateinit var localMessaging: com.messenger.app.local.LocalMessagingController
        private set

    lateinit var presence: com.messenger.app.data.websocket.RealtimePresence
        private set
    lateinit var foregroundMessageBannerController: ForegroundMessageBannerController
        private set

    val voicePlayerController = com.messenger.app.data.attachment.VoicePlayerController()

    lateinit var mediaPreparation: com.messenger.app.data.attachment.MediaPreparationCoordinator
        private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        com.messenger.app.data.api.ServerEndpoint.initialize(this)
        val createStartMs = android.os.SystemClock.elapsedRealtime()
        // Registered once, eagerly - Tink Streaming AEAD (Этап H.2) needs
        // this before any encrypt/decrypt call (AttachmentCryptoV2 also
        // calls it lazily/idempotently, this just avoids the first-call cost).
        com.messenger.app.data.attachment.AttachmentCryptoV2.ensureRegistered()
        connectionRepository = WebSocketConnectionRepository(serverUrl = BuildConfig.WS_URL)

        val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        // encodeDefaults=true: fields at their default (e.g. device_platform)
        // must still be sent - the server requires them explicitly.
        val json = com.messenger.app.data.api.ApiJson
        val tokenStore = SecureTokenStore(this)
        val diagnostics = com.messenger.app.data.api.ClientDiagnostics(this)
        val httpClient = OkHttpClient.Builder().dns(com.messenger.app.data.api.AndroidSystemDns.create(this)).callTimeout(45, TimeUnit.SECONDS).addInterceptor { chain ->
            val epoch = if (::authRepository.isInitialized) authRepository.currentSessionEpoch() else 0
            val request = chain.request()
            val requestStarted = android.os.SystemClock.elapsedRealtimeNanos()
            val sentToken = request.header("Authorization")?.removePrefix("Bearer ")
            if (com.messenger.app.data.api.ServerEndpoint.changes > 0 && sentToken != null && request.url.encodedPath != "/auth/logout" && (sentToken != tokenStore.accessToken() || !request.url.toString().startsWith(com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') + "/"))) {
                throw java.io.IOException("Session changed")
            }
            val response = try { chain.proceed(request) } catch (e: java.io.IOException) {
                if (::performanceDiagnostics.isInitialized) performanceDiagnostics.network((android.os.SystemClock.elapsedRealtimeNanos() - requestStarted) / 1_000_000.0, 0)
                diagnostics.report(request, 0)
                throw e
            }
            if (!response.isSuccessful) diagnostics.report(request, response.code)
            if (::performanceDiagnostics.isInitialized) performanceDiagnostics.network((android.os.SystemClock.elapsedRealtimeNanos() - requestStarted) / 1_000_000.0, response.code)
            val token = request.header("Authorization")?.removePrefix("Bearer ")
            if (token != null && response.code in listOf(401, 403)) {
                val failure = com.messenger.app.data.api.parseApiException(json, response.code, response.peekBody(16_384).string())
                if (failure is com.messenger.app.data.api.AuthApiException.Api && failure.code == "session_revoked") {
                    applicationScope.launch {
                        (authRepository as? AuthRepositoryImpl)?.handleRevokedAccessToken(token, epoch)
                    }
                }
            }
            response
        }.build()
        val authApi = AuthApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val devicesApi = DevicesApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val installationCredentials = com.messenger.app.data.auth.InstallationCredentialCoordinator(
            com.messenger.app.data.secure.AndroidInstallationCredentialStore(this), authApi::createInstallationCredential, devicesApi::bindInstallationCredential,
        )
        // Shared instance: also the durable global-sync cursor store (see
        // SecureTokenStore.lastSyncedSequence), so auth and sync agree on
        // exactly one persisted copy, cleared together on logout.
        val database = MessengerDatabase.create(this)
        com.messenger.app.data.auth.PendingLogout.scheduleSaved(this)
        authRepository = AuthRepositoryImpl(api = authApi, tokenStore = tokenStore,
            installationCredentials = installationCredentials,
            deferLogout = { com.messenger.app.data.auth.PendingLogout.save(this, it) },
            clearAccountData = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { voicePlayerController.stop() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.messenger.app.push.ChatReminderReceiver.clear(this@MessengerApp)
                    database.clearAllTables()
                    AndroidKeystoreSignalStore(this@MessengerApp).eraseAllAccountSecrets()
                    com.messenger.app.data.history.AndroidHistorySecretStorage(this@MessengerApp).eraseAllAccountSecrets()
                    com.messenger.app.data.push.PushTokenStore(this@MessengerApp).clearUploadedFlag()
                    com.messenger.app.data.history.HistoryAutoBackup.logout(this@MessengerApp)
                    com.messenger.app.capture.ExternalCaptureStore(this@MessengerApp).retireAll()
                    for (name in listOf("chat_pins", "history-auto", "notification_accept", "incoming_call_notification_owner", "admin_public_pins")) {
                        check(getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()) { "Could not clear account settings" }
                    }
                    if (::avatarRepository.isInitialized) avatarRepository.eraseCache()
                    com.messenger.app.data.local.PrivateCachePurger.erase(cacheDir, java.io.File(noBackupFilesDir, "call-history"), java.io.File(noBackupFilesDir, "media-drafts"), java.io.File(noBackupFilesDir, "voice-drafts"), java.io.File(filesDir, "history-restoration"), java.io.File(filesDir, "history-upload-plans"))
                }
            })

        val usersApi = UsersApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        privacyRepository = com.messenger.app.data.profile.PrivacyRepository(usersApi, authRepository)
        accountAutoDeleteRepository = com.messenger.app.data.profile.AccountAutoDeleteRepository(usersApi, authRepository)
        accountActivityReporter = com.messenger.app.data.profile.ForegroundAccountActivityReporter(accountAutoDeleteRepository, applicationScope)
        applicationScope.launch {
            authRepository.authState.map { accountAutoDeleteRepository.ownerKey() }.distinctUntilChanged().collect {
                accountAutoDeleteRepository.invalidate()
                accountActivityReporter.sessionChanged()
            }
        }
        applicationScope.launch {
            authRepository.authState.collectLatest {
                privacyRepository.invalidate()
                if (it is com.messenger.app.domain.model.AuthSessionState.LoggedIn) {
                    privacyRepository.refresh()
                }
            }
        }
        historyBackupTransport = { com.messenger.app.data.api.HistoryBackupApi(httpClient, authRepository, BuildConfig.API_BASE_URL) }
        historySettingsSession = {
            val transport = com.messenger.app.data.api.HistoryBackupApi(httpClient, authRepository, BuildConfig.API_BASE_URL)
            val owner = com.messenger.app.data.history.HistoryNativeOwner(authRepository, database, transport, transport,
                java.io.File(filesDir, "history-restoration"), java.io.File(filesDir, "history-upload-plans"), prepareRestore = { historyRestoreBarrier() }, calls = callHistory) {
                historyRestoreBarrier()
                chatRepository.refreshChats().getOrThrow()
                val chats = chatRepository.chats.first()
                check(chats.size <= 10_000)
                for (chat in chats) {
                    messageRepository.prepareHistoryBackup(chat.chatId).getOrThrow()
                }
                callHistory.prepareBackupHistory()
            }
            com.messenger.app.data.history.HistoryKeySettingsSession(authRepository, transport, owner) {
                com.messenger.app.data.history.AndroidHistorySecretStorage(this)
            }
        }
        profileRepository = ProfileRepositoryImpl(api = usersApi, authRepository = authRepository, tokenStore = tokenStore,
            invalidateAvatar = { userId -> if (::avatarRepository.isInitialized) avatarRepository.invalidate(userId) })

        // E.1: one app-scoped avatar cache - screens never download avatars
        // independently. Cache identity = (server, userId, avatarVersion).
        avatarRepository = com.messenger.app.data.avatar.AvatarRepository(
            cacheDir = java.io.File(cacheDir, "avatars"),
            serverTag = BuildConfig.API_BASE_URL.hashCode().toString(16),
            downloader = { userId ->
                val token = authRepository.currentAccessToken()
                if (token == null) {
                    null
                } else {
                    runCatching {
                        val dto = usersApi.getAvatarDownloadUrl(token, userId)
                        usersApi.downloadAvatarBytes(dto.downloadUrl)
                    }.getOrNull()
                }
            },
        )

        devicesRepository = DevicesRepositoryImpl(api = devicesApi, authRepository = authRepository, tokenStore = tokenStore)

        val chatsApi = ChatsApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val chatRepositoryImpl = ChatRepositoryImpl(api = chatsApi, dao = database.chatDao(), authRepository = authRepository, deletedChatDao = database.deletedChatDao(), workspaceDao = database.workspaceDao(), pinPreferences = getSharedPreferences("chat_pins", MODE_PRIVATE), scope = applicationScope)
        chatRepository = chatRepositoryImpl
        blockedUsersRepository = com.messenger.app.data.profile.BlockedUsersRepositoryImpl(usersApi, authRepository, chatRepositoryImpl::updateKnownPeerBlock)
        applicationScope.launch {
            while (true) {
                chatRepositoryImpl.replayPreferences()
                kotlinx.coroutines.delay(15_000)
            }
        }

        val messagesApi = MessagesApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val syncApi = SyncApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val keysApi = KeysApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        // Official Signal Protocol only - no homemade crypto. Backed by a
        // dedicated Android-Keystore-encrypted file, separate from
        // SecureTokenStore (see AndroidKeystoreSignalStore for why).
        // All key material is namespaced by (userId:deviceId) so switching
        // accounts on one installation never shares identity/sessions/trust.
        val signalStorage = AndroidKeystoreSignalStore(this)
        val restoredUser = tokenStore.userId()
        val restoredDevice = tokenStore.deviceId()
        if (com.messenger.app.data.api.ServerEndpoint.signalPrefix().isEmpty() && restoredUser != null && restoredDevice != null) signalStorage.migrateLegacy("$restoredUser:$restoredDevice")
        val signalNamespaces = com.messenger.app.data.signal.SignalNamespaceResolver(signalStorage)
        val signalKeyStore = SignalKeyStore(
            signalStorage,
            accountEpoch = { authRepository.currentSessionEpoch() },
            accountNamespace = {
                val uid = tokenStore.userId()
                val did = tokenStore.deviceId()
                if (uid != null && did != null) signalNamespaces.resolve(com.messenger.app.data.api.ServerEndpoint.storageOrigin(), "$uid:$did", com.messenger.app.data.api.ServerEndpoint.signalPrefix()) else ""
            },
        )
        val identityVerification = IdentityVerificationManager(
            keyStore = signalKeyStore,
            dao = database.peerIdentityDao(),
            myUserIdProvider = { tokenStore.userId() },
            currentEpoch = { authRepository.currentSessionEpoch() },
            withSessionWrite = { epoch, block -> authRepository.withSessionWrite(epoch, block); Unit },
            scope = applicationScope,
        )
        this.identityVerification = identityVerification
        val signalSessionManager = SignalSessionManager(
            keyStore = signalKeyStore,
            keysApi = keysApi,
            identityVerification = identityVerification,
        )
        // Dedicated client: a WebSocket must not be killed by the default
        // read timeout during idle periods between messages; pingInterval
        // keeps it alive and lets OkHttp detect a dead connection.
        val socketHttpClient = httpClient.newBuilder().callTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val socketClient = MessagingSocketClient(
            client = socketHttpClient,
            wsUrl = BuildConfig.WS_URL,
            authRepository = authRepository,
            json = json,
        )
        var privacyRefresh: kotlinx.coroutines.Job? = null
        socketClient.onPrivacyChanged = {
            synchronized(privacyRepository) {
                if (privacyRefresh?.isActive != true) {
                    val owner = privacyRepository.ownerKey()
                    privacyRefresh = applicationScope.launch {
                        kotlinx.coroutines.delay(100)
                        if (owner != null && owner == privacyRepository.ownerKey()) privacyRepository.refresh()
                    }
                }
            }
        }
        presence = socketClient.presence
        liveDrafts = com.messenger.app.data.websocket.LiveDrafts(this, authRepository, socketClient, signalSessionManager)
        localMessaging = com.messenger.app.local.LocalMessagingController(this, authRepository, socketClient.connectionState)
        // Missing metadata follows durable message rows, so catch-up, first arrivals
        // and reconnects hydrate only those conversations, never the account list.
        applicationScope.launch {
            combine(authRepository.authState, authRepository.historyContext, socketClient.connectionState) { state, history, connection ->
                Triple(if(state is AuthSessionState.LoggedIn) authRepository.currentSessionEpoch() else -1,
                    history?.beforeSequence ?: authRepository.historyBeforeSequence(), connection)
            }.flatMapLatest { (epoch, cutoff, connection) ->
                if(epoch < 0 || connection != com.messenger.app.domain.repository.SocketConnectionState.CONNECTED)
                    kotlinx.coroutines.flow.flowOf(epoch to emptyList<String>())
                else database.chatDao().observeMissingChatIds(cutoff).map { epoch to it.sorted() }
            }.distinctUntilChanged().collectLatest { (epoch, ids) ->
                if(ids.isNotEmpty()) for(attempt in 0..2) {
                    if(epoch != authRepository.currentSessionEpoch()) break
                    if(chatRepositoryImpl.refreshKnownChats(ids).isSuccess) break
                    kotlinx.coroutines.delay(2000L * (attempt + 1))
                }
            }
        }
        // The single ingestion pipeline every incoming message (WS push,
        // `/sync` catch-up, history pagination) is decrypted/persisted
        // through - see MessageIngestionPipeline's doc comment.
        val ingestionPipeline = MessageIngestionPipeline(
            workspaceDao = database.workspaceDao(),
            deletedChatDao = database.deletedChatDao(),
            dao = database.messageDao(),
            chatDao = database.chatDao(),
            attachmentDao = database.attachmentDao(),
            reactionDao = database.reactionDao(),
            decryptor = signalSessionManager,
            authRepository = authRepository,
            messagesApi = messagesApi,
            tokenStore = tokenStore,
            scheduleDelivery = { report -> applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) { report() }; Unit },
        )
        // P1-4/B.1: accepting a changed peer identity triggers immediate
        // pending-decrypt recovery for that peer (no new message needed).
        identityVerification.onTrustAccepted = { userId, _ ->
            applicationScope.launch { runCatching { ingestionPipeline.retryPendingDecrypts(userId) } }
        }
        val callApi = CallApiImpl(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL, authRepository = authRepository)
        callHistory = com.messenger.app.data.call.CallHistoryRepository(
            java.io.File(noBackupFilesDir, "call-history"), BuildConfig.API_BASE_URL, callApi, authRepository, applicationScope,
            localPersonalClears = { com.messenger.app.data.chat.RoomChatClearStore.callClears(database) },
            restoredCallDeletions = { ids -> com.messenger.app.data.api.HistoryBackupApi(httpClient, authRepository, BuildConfig.API_BASE_URL).restoreCallCheck(ids) })
        val globalSyncManager = GlobalSyncManager(
            clearHistory = { cutoff, sequence, epoch ->
                val applied = authRepository.withSessionWrite(epoch) {
                    database.messageDao().applyHistoryClear(sequence)
                    true
                } == true
                applied && callHistory.applyHistoryClear(cutoff, epoch)
            },
            syncApi = syncApi,
            ingestionPipeline = ingestionPipeline,
            authRepository = authRepository,
            tokenStore = tokenStore,
        )
        globalSyncManager.beforeSync = { token, epoch ->
            applicationScope.launch { chatRepositoryImpl.replayPreferences() }
            try {
                if (::chatClearQueue.isInitialized) chatClearQueue.flush()
                val clears = chatsApi.personalClears(token)
                callHistory.applyPersonalClears(clears, epoch)
                authRepository.withSessionWrite(epoch) {
                    clears.forEach { database.deletedChatDao().clearPersonal(it.chat_id, it.through_sequence) }; true
                } == true
            } catch (e: com.messenger.app.data.api.AuthApiException) {
                // Older servers have no clear endpoint; preserve their sync compatibility.
                if (e is com.messenger.app.data.api.AuthApiException.Api && (e.code == "not_found" || (e.code == "http_error" && e.message == "HTTP 404"))) true else false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { false } // Disk/clear persistence failure: do not ingest past an unapplied clear.
        }
        historyRestoreBarrier = { globalSyncManager.awaitHistoryRestoreSync() }
        val attachmentsApi = AttachmentsApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        attachmentManager = AttachmentManager(
            isChatDeleted = { database.deletedChatDao().contains(it) },
            context = this,
            httpClient = httpClient.newBuilder().callTimeout(10, TimeUnit.MINUTES).build(),
            attachmentsApi = attachmentsApi,
            authRepository = authRepository,
            dao = database.attachmentDao(),
        )

        // A cold display-only bridge avoids accessing the later coordinator during construction.
        val directStopsReady = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.flow.StateFlow<Map<String, com.messenger.app.data.directtransfer.DirectLocalTransferStop>>>()
        messageRepository = MessageRepositoryImpl(
            directLocalStops = kotlinx.coroutines.flow.flow {
                emit(emptyMap())
                directStopsReady.await().collect { emit(it) }
            },
            canRecordViewedCursor = messageReadVisibility::allowsViewedCursor,
            pendingMediaLabel = { file ->
                val localized = applyLocale(com.messenger.app.ui.theme.LanguagePreference(this).current())
                file.name + "\n" + android.text.format.Formatter.formatShortFileSize(localized, file.length()) + " · " + localized.getString(R.string.attachment_waiting_to_send)
            },
            deletedChatDao = database.deletedChatDao(),
            shareActivity = { getSharedPreferences("chat-privacy", MODE_PRIVATE).getBoolean("activity", false) },
            mediaUploader = attachmentManager,
            workspaceDao = database.workspaceDao(),
            api = messagesApi,
            deliveryEngine = com.messenger.app.domain.delivery.DeliveryEngine(
                com.messenger.app.data.delivery.ServerTransport(
                    api = messagesApi,
                    currentAccessToken = authRepository::currentAccessToken,
                    currentSessionEpoch = authRepository::currentSessionEpoch,
                ),
            ),
            dao = database.messageDao(),
            attachmentDao = database.attachmentDao(),
            reactionDao = database.reactionDao(),
            authRepository = authRepository,
            chatRepository = chatRepository,
            socketClient = socketClient,
            signalSessionManager = signalSessionManager,
            ingestionPipeline = ingestionPipeline,
            globalSyncManager = globalSyncManager,
        )

        val foregroundTracker = AppForegroundTracker(this).apply { start() }
        directBackground = com.messenger.app.data.directtransfer.DirectTransferBackgroundController(this, authRepository, foregroundTracker.isForeground, applicationScope)
        directTransfers = com.messenger.app.data.directtransfer.DirectTransferCoordinator(
            context = this, auth = authRepository, workspace = database.workspaceDao(), messages = database.messageDao(),
            signal = signalSessionManager,
            api = com.messenger.app.data.directtransfer.DirectTransferApi(httpClient, authRepository, BuildConfig.API_BASE_URL),
            enqueueOffer = { chat, offer, epoch -> (messageRepository as MessageRepositoryImpl).sendDirectFileOffer(chat, offer, epoch) },
            resolveRecipient = { chat ->
                val epoch = authRepository.currentSessionEpoch()
                val user = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
                val summary = chatRepository.getChat(chat).getOrThrow()
                val currentUser = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
                if (epoch != authRepository.currentSessionEpoch() || currentUser != user) throw kotlinx.coroutines.CancellationException("Account changed")
                com.messenger.app.data.directtransfer.directFileRecipient(
                    com.messenger.app.data.directtransfer.directFileDestination(summary, user, epoch), chat, currentUser, epoch,
                ) ?: throw com.messenger.app.data.directtransfer.DirectStorageError("destination_unavailable")
            },
            stunUrls = { callApi.iceServers().flatMap { it.urls }.filter { it.startsWith("stun:") || it.startsWith("stuns:") } },
            canStream = {
                com.messenger.app.data.directtransfer.directTransferCanStream(
                    if (::callController.isInitialized) callController.state.value else null,
                    ::conferenceController.isInitialized && conferenceController.reservesCallSlot,
                )
            },
            background = directBackground,
        )

        directStopsReady.complete(directTransfers.localStops)

        mediaPreparation = com.messenger.app.data.attachment.MediaPreparationCoordinator(
            scope = applicationScope,
            epoch = authRepository::currentSessionEpoch,
            stageWithLimit = { epoch, source, maximum ->
                authRepository.withWorkspaceWrite(epoch) { attachmentManager.stageDraft(android.net.Uri.parse(source), maximum).toString() }
                    ?: throw kotlinx.coroutines.CancellationException("Account changed")
            },
            stage = { epoch, source ->
                authRepository.withWorkspaceWrite(epoch) { attachmentManager.stageDraft(android.net.Uri.parse(source)).toString() }
                    ?: throw kotlinx.coroutines.CancellationException("Account changed")
            },
            commit = { epoch, chat, uri, asFile ->
                authRepository.withWorkspaceWrite(epoch) {
                    check(!database.deletedChatDao().contains(chat)) { "Chat removed" }
                    if (asFile) {
                        val entries=org.json.JSONArray(messageRepository.chatValue(chat,"fileDrafts")?:"[]")
                        messageRepository.chatValue(chat,"fileDraftUri")?.let { legacy->
                            if((0 until entries.length()).none { entries.getString(it)==legacy }) entries.put(legacy)
                        }
                        if((0 until entries.length()).none { entries.getString(it)==uri }) entries.put(uri)
                        messageRepository.saveChatValue(chat,"fileDrafts",entries.toString())
                        messageRepository.saveChatValue(chat,"fileDraftUri",null)
                    }
                    else {
                        val entries = org.json.JSONArray(messageRepository.chatValue(chat, "mediaDrafts") ?: "[]")
                        entries.put(uri)
                        messageRepository.saveChatValue(chat, "mediaDrafts", entries.toString())
                    }
                    true
                } ?: throw kotlinx.coroutines.CancellationException("Account changed")
            },
            marker = { epoch, chat, active ->
                authRepository.withWorkspaceWrite(epoch) {
                    check(!database.deletedChatDao().contains(chat)) { "Chat removed" }
                    messageRepository.saveChatValue(chat, "mediaImportPending", if (active) "1" else null)
                    true
                } ?: throw kotlinx.coroutines.CancellationException("Account changed")
            },
            discard = { epoch, uri ->
                authRepository.withWorkspaceWrite(epoch) {
                    android.net.Uri.parse(uri).path?.let { messageRepository.discardMediaDrafts(setOf(it)) }
                }
                Unit
            },
        )
        applicationScope.launch {
            var importEpoch = authRepository.currentSessionEpoch()
            authRepository.authState.collect {
                val current = authRepository.currentSessionEpoch()
                if (current != importEpoch) { importEpoch = current; mediaPreparation.invalidateSession() }
            }
        }

        messageSearchRepository = MessageSearchRepositoryImpl(dao = database.searchDao())

        mediaGalleryRepository = MediaGalleryRepository(dao = database.mediaDao())


        // Phase 9.1: local media-cache accounting + cleanup. Scans/clears on
        // Dispatchers.IO; never touches the DB/outbox/keys/auth.
        mediaCacheManager = com.messenger.app.data.local.MediaCacheManager(
            context = this,
            dao = database.attachmentDao(),
            avatarCacheDir = java.io.File(cacheDir, "avatars"),
            workspaceDao = database.workspaceDao(),
            authRepository = authRepository,
        ).also { manager -> manager.onAvatarsCleared = { avatarRepository.clearMemory() } }

        val cacheSweepRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        attachmentManager.onCacheWritten = { cacheSweepRequests.trySend(Unit); Unit }
        applicationScope.launch {
            for (ignored in cacheSweepRequests) {
                kotlinx.coroutines.delay(500)
                if (authRepository.authState.value is com.messenger.app.domain.model.AuthSessionState.LoggedIn) {
                    do {
                        val removed = runCatching { mediaCacheManager.enforcePolicy().deletedFiles }.getOrDefault(0)
                        if (removed >= 100) kotlinx.coroutines.delay(500)
                    } while (removed >= 100 && authRepository.authState.value is com.messenger.app.domain.model.AuthSessionState.LoggedIn)
                }
            }
        }
        applicationScope.launch {
            authRepository.authState.collectLatest { state ->
                if (state is com.messenger.app.domain.model.AuthSessionState.LoggedIn) {
                    while (true) {
                        // Disabled by default; bounded age/LRU batches run off the UI thread.
                        runCatching { mediaCacheManager.enforcePolicy() }
                        kotlinx.coroutines.delay(60 * 60 * 1000L)
                    }
                }
            }
        }

        // Этап J: 1:1 calls. Signaling reuses the SAME authenticated socket as
        // messaging (socketClient above) - only the frame types are new, no
        // second WebSocket (Этап J п.1).
        val callSignalingClient = CallSignalingClientImpl(socketClient = socketClient, json = json)
        fun clearOwner(): com.messenger.app.data.chat.ChatClearOwner? {
            val user = (authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id ?: return null
            val device = authRepository.currentDeviceId() ?: return null
            return com.messenger.app.data.chat.ChatClearOwner(user, com.messenger.app.data.api.ServerEndpoint.resolve(BuildConfig.API_BASE_URL), device)
        }
        chatClearQueue = com.messenger.app.data.chat.BoundedChatClearQueue(
            applicationScope,
            current = { clearOwner()?.let { it to authRepository.currentSessionEpoch() } },
            store = com.messenger.app.data.chat.RoomChatClearStore(database, authRepository, ::clearOwner,
                syncCursor = { tokenStore.lastSyncedSequence() },
                callBoundary = { chatId ->
                    callHistory.calls.value.filter { it.chatId == chatId && it.chatVisible != false && it.state == "ended" }.map { it.createdAt }
                        .maxByOrNull { java.time.Instant.parse(it) }
                },
                withDeliveryPaused = { work -> (messageRepository as MessageRepositoryImpl).withBoundedChatClear(work) }),
            applyCallClears = {
                val epoch = authRepository.currentSessionEpoch()
                callHistory.applyLocalChatClears(com.messenger.app.data.chat.RoomChatClearStore.callClears(database), epoch)
            },
            send = { command, epoch ->
                val token = authRepository.currentAccessToken() ?: throw kotlinx.coroutines.CancellationException("Signed out")
                if (epoch != authRepository.currentSessionEpoch() || clearOwner() != command.owner) throw kotlinx.coroutines.CancellationException("Session changed")
                chatsApi.clearHistory(token, command.chatId, command.request)
            },
        )
        nonCallCaptures = com.messenger.app.capture.NonCallCaptureCoordinator(::captureSession) {
            (::callController.isInitialized && !callController.canBeginNonCallCapture()) ||
                (::conferenceController.isInitialized && conferenceController.reservesCallSlot)
        }
        externalCaptureStore = com.messenger.app.capture.ExternalCaptureStore(this)
        // Durable metadata alone is not hardware ownership. Only an attached
        // camera wrapper restores its lease; an orphan after task removal must
        // not permanently block calls on a fresh application launch.
        callController = CallController(
            callApi = callApi,
            signalingClient = callSignalingClient,
            sessionOwner = { (authRepository.authState.value as? AuthSessionState.LoggedIn)?.let { "${com.messenger.app.data.api.ServerEndpoint.current()}|${it.user.id}|${authRepository.currentSessionEpoch()}" } },
            currentUserId = { (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id },
            externalCallBusy = { ::conferenceController.isInitialized && conferenceController.reservesCallSlot },
            nonCallCaptureBusy = { nonCallCaptures.busy },
            drainNonCallCapture = { nonCallCaptures.stopAndAwaitIdle() },
            onCaptureStillBusy = { android.widget.Toast.makeText(this, R.string.capture_finish_before_answer, android.widget.Toast.LENGTH_LONG).show() },
            onStartBlocked = { reason ->
                val message = when (reason) {
                    com.messenger.app.domain.call.CallStartBlockedReason.ROOM -> if (::conferenceController.isInitialized && conferenceController.state.value.active) R.string.media_busy_room else R.string.media_busy_cleanup
                    com.messenger.app.domain.call.CallStartBlockedReason.CALL -> R.string.media_busy_call
                    com.messenger.app.domain.call.CallStartBlockedReason.CAPTURE -> R.string.media_busy_capture
                }
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            },
            webRtcEngine = WebRtcEngineImpl(this),
            scope = applicationScope,
            displayNameResolver = { callerUserId ->
                val cached = chatRepository.chats.first().firstOrNull { it.otherUser?.id == callerUserId }?.otherUser?.displayName
                cached ?: run {
                    val epoch = authRepository.currentSessionEpoch()
                    val token = authRepository.currentAccessToken()
                    val peer = if(token != null && epoch == authRepository.currentSessionEpoch()) runCatching { chatsApi.getPeerChat(token, callerUserId)?.otherUser }.getOrNull() else null
                    if(epoch == authRepository.currentSessionEpoch()) peer?.displayName ?: "Incoming call" else "Incoming call"
                }
            },
        )
        conferenceApi = com.messenger.app.conference.ConferenceApi(httpClient, json, authRepository)
        conferenceKeyMailbox = com.messenger.app.conference.ConferenceKeyMailbox(conferenceApi, authRepository, signalSessionManager)
        conferenceController = com.messenger.app.conference.ConferenceController(
            this, conferenceApi, authRepository, applicationScope, mailbox = conferenceKeyMailbox, canStart = callController::canBeginConference,
            ownedRoomStore = com.messenger.app.conference.ConferenceOwnedRoomStore(tokenStore::conferenceRoomOwner, tokenStore::readConferenceRoomAccess, tokenStore::saveConferenceRoomAccess),
        )
        applicationScope.launch {
            kotlinx.coroutines.flow.combine(callController.state, conferenceController.captureReservation, authRepository.authState) { _, _, _ -> Unit }
                .collect {
                    if (!callController.canBeginNonCallCapture()) nonCallCaptures.interruptAll()
                    nonCallCaptures.interruptStaleSessions()
                }
        }
        CallForegroundServiceController(this, callController, applicationScope).start()

        // N.2R addendum: caller-side ringback while the outgoing call rings.
        // Single authoritative owner — stops on Accept/Reject/Busy/Cancel/
        // Timeout/Failure, never continues into Connected.
        com.messenger.app.call.CallRingbackPlayer(callController, applicationScope).start()

        // Этап J.1-4: background process-alive incoming call presentation.
        // A dedicated notification manager + foreground tracker, so the
        // CallController stays framework-free. FCM/process-dead wake is
        // handled by the push components below (Этап K).
        val profileRefresh = com.messenger.app.data.profile.SelfProfileRefreshCoordinator(
            profileRepository, authRepository, socketClient.connectionState, foregroundTracker.isForeground, applicationScope,
        )
        socketClient.onProfileChanged = profileRefresh::changed
        applicationScope.launch {
            combine(authRepository.authState, foregroundTracker.isForeground) { state, foreground -> state to foreground }
                .collectLatest { (state, foreground) ->
                    if (state !is AuthSessionState.LoggedIn || !foreground) return@collectLatest
                    // Restored sessions bind off the auth lock. A foreground
                    // return retries after offline use; invalid proofs never loop.
                    for (wait in listOf(0L, 15_000L, 60_000L)) {
                        if (wait > 0) kotlinx.coroutines.delay(wait)
                        val result = (authRepository as AuthRepositoryImpl).reconcileInstallationCredential()
                        val failure = result.exceptionOrNull() ?: break
                        val transportFailure = failure is com.messenger.app.data.api.AuthApiException.Network &&
                            (failure.httpStatus == null || failure.httpStatus >= 500)
                        if (!transportFailure && failure !is java.io.IOException) break
                    }
                }
        }
        performanceDiagnostics = com.messenger.app.data.api.PerformanceDiagnostics(this, authRepository, applicationScope)
        val callRefreshes = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)
        fun refreshCallActivity() { chatClearQueue.trigger(); callRefreshes.trySend(authRepository.currentSessionEpoch()) }
        applicationScope.launch {
            for (epoch in callRefreshes) {
                kotlinx.coroutines.delay(150)
                if (epoch != authRepository.currentSessionEpoch()) continue
                try {
                    callHistory.refresh()
                    if (epoch == authRepository.currentSessionEpoch()) chatRepositoryImpl.projectCallActivity(callHistory.calls.value, callHistory.snapshotEpoch)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
                  catch (_: Exception) { /* Retry on next foreground/connection/event. */ }
            }
        }
        applicationScope.launch {
            callHistory.calls.collectLatest { rows ->
                try { chatRepositoryImpl.projectCallActivity(rows, callHistory.snapshotEpoch) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
        applicationScope.launch { foregroundTracker.isForeground.collect { if (it) { refreshCallActivity(); directTransfers.recoveryEvent() } } }
        applicationScope.launch { socketClient.connectionState.collect { if (it == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED) { refreshCallActivity(); directTransfers.recoveryEvent() } } }
        applicationScope.launch {
            socketClient.events.collect { event ->
                if (event is IncomingSocketEvent.CallRinging || event is IncomingSocketEvent.CallEnded ||
                    event is IncomingSocketEvent.CallAccepted || event is IncomingSocketEvent.CallAnsweredElsewhere ||
                    event is IncomingSocketEvent.CallHistoryChanged) refreshCallActivity()
            }
        }

        // Presence heartbeats also prove inactivity-policy activity on the server.
        // A visible but PAUSED activity must not keep that deadline alive.
        applicationScope.launch { accountActivityReporter.isResumed.collect { presence.setForeground(it) } }

        // Этап K: FCM push. Firebase is OPTIONAL - the app builds and runs
        // without google-services.json; FCM only activates when a real
        // Firebase project is configured (see FirebaseAvailability).
        NotificationChannels.ensure(this)
        val pushApi = PushApi(client = httpClient, json = json, baseUrl = BuildConfig.API_BASE_URL)
        val pushTokenStore = PushTokenStore(this)
        pushRegistrationRepository = PushRegistrationRepository(
            api = pushApi,
            tokenStore = pushTokenStore,
            authRepository = authRepository,
            scope = applicationScope,
            acquireToken = { FirebaseAvailability.currentToken(this@MessengerApp) },
        )
        applicationScope.launch {
            messageRepository.socketState.collect { state ->
                if (state == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED) pushRegistrationRepository.ensureRegistered()
            }
        }
        applicationScope.launch {
            foregroundTracker.isForeground.collect { active -> if (active) pushRegistrationRepository.ensureRegistered() }
        }
        applicationScope.launch {
            chatRepository.chats.collect { chats ->
                notificationPreferences = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id to chats.associateBy { it.chatId }
            }
        }
        val callPushHandler = CallPushHandlerImpl(callController)
        val messagePushHandler = MessagePushHandlerImpl(
            this,
            currentServer = { com.messenger.app.data.api.ServerEndpoint.current() },
            settings = { notificationSettings.current },
            sessionEpoch = { authRepository.currentSessionEpoch() },
            scope = applicationScope,
            resolveAvatar = { chatId ->
                val epoch = authRepository.currentSessionEpoch()
                val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
                val snapshot = notificationPreferences
                val other = snapshot.takeIf { it.first == owner }?.second?.get(chatId)?.otherUser
                    ?: chatRepository.getChat(chatId).getOrNull()?.otherUser
                other?.let {
                    val bytes = avatarRepository.resolve(it.id, it.avatarVersion, it.hasAvatar)
                    if (epoch != authRepository.currentSessionEpoch() || owner != (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id) null
                    else com.messenger.app.push.NotificationAvatar(it.id, it.displayName, bytes)
                }
            },
            isSilenced = { isChatSilenced(it) },
            isForeground = { foregroundTracker.isForeground.value },
            activeChatId = { ActiveChatTracker.activeChatId.value },
            currentUserId = {
                (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
            },
            activeCall = {
                when (callController.state.value) {
                    is CallUiState.OutgoingRinging,
                    is CallUiState.IncomingRinging,
                    is CallUiState.Connecting,
                    is CallUiState.Connected,
                    is CallUiState.Reconnecting -> true
                    else -> false
                }
            },
        )
        val notificationGate = com.messenger.app.push.NotificationDeliveryGate(
            enabled = { notificationSettings.current.messageAlertsEnabled },
            owner = { (authRepository.authState.value as? AuthSessionState.LoggedIn)?.let { "${com.messenger.app.data.api.ServerEndpoint.current()}|${it.user.id}|${authRepository.currentSessionEpoch()}" } },
            silenced = { chatId ->
                val epoch = authRepository.currentSessionEpoch()
                authRepository.withSessionWrite(epoch) {
                    val chat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { database.chatDao().cachedChat(chatId) }
                    chat?.let { it.isBlocked || (it.isMuted && (it.mutedUntil == null || runCatching { java.time.Instant.parse(it.mutedUntil).isAfter(java.time.Instant.now()) }.getOrDefault(true))) } ?: false
                } ?: throw kotlinx.coroutines.CancellationException("Notification owner changed")
            },
        )
        fun notificationSession(): com.messenger.app.push.NotificationBatchSession? {
            val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return null
            return com.messenger.app.push.NotificationBatchSession(
                com.messenger.app.data.api.ServerEndpoint.current(), owner, authRepository.currentSessionEpoch())
        }
        var notificationCatchUp: kotlinx.coroutines.Job? = null
        var notificationCatchUpSession: com.messenger.app.push.NotificationBatchSession? = null
        val notificationMetadata = com.messenger.app.push.NotificationMetadataResolver(
            current = ::notificationSession,
            load = { id -> database.messageDao().findByServerId(id)?.let { row ->
                com.messenger.app.push.NotificationMessageMetadata(row.serverId, row.chatId, row.senderId,
                    row.sequence, row.albumId, row.kind, row.status, row.hiddenLocally, row.isDeletedForEveryone)
            } },
            requestCatchUp = {
                val current = notificationSession()
                if (current != null && (notificationCatchUp?.isActive != true || notificationCatchUpSession != current)) {
                    notificationCatchUpSession = current
                    // Use the correctness path and let it finish independently
                    // of the bounded notification lookup, including cold FCM.
                    notificationCatchUp = applicationScope.launch { globalSyncManager.runCatchUpSync() }
                }
            },
        )
        routeMessagePush = { event ->
            val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
            val expectedEpoch = authRepository.currentSessionEpoch()
            if (owner != null && event.userId == owner) {
                val expected = notificationSession()
                val gate = notificationGate.decide(event.chatId)
                if (expected != null && expected == notificationSession()) {
                    if (gate == com.messenger.app.push.NotificationDeliveryGate.Decision.SUPPRESS) {
                        messagePushHandler.consumeSuppressed(event, expectedEpoch, owner)
                    } else {
                        val resolved = notificationMetadata.resolve(event, expected)
                        if (expected == notificationSession()) {
                            when (resolved) {
                                is com.messenger.app.push.NotificationMetadataResolution.Readable ->
                                    messagePushHandler.onNewMessage(event,
                                        gate == com.messenger.app.push.NotificationDeliveryGate.Decision.SILENT, resolved.metadata)
                                com.messenger.app.push.NotificationMetadataResolution.Unresolved ->
                                    // No trusted batch metadata: preserve the
                                    // generic alert instead of guessing that
                                    // independent messages belong together.
                                    messagePushHandler.onNewMessage(event,
                                        gate == com.messenger.app.push.NotificationDeliveryGate.Decision.SILENT)
                                com.messenger.app.push.NotificationMetadataResolution.Suppress ->
                                    messagePushHandler.consumeSuppressed(event, expectedEpoch, owner)
                            }
                        }
                    }
                }
            }
        }

        pushRouter = PushRouter(callPushHandler, messagePushHandler)

        // Runtime Correction D: foreground message banner (presentation-only).
        // Driven by the WS new-message events, resolved from the just-persisted
        // LOCAL row (never the wire ciphertext). Replace-latest, auto-dismissed.
        foregroundMessageBannerController = ForegroundMessageBannerController(
            scope = applicationScope,
            sessionEpoch = { authRepository.currentSessionEpoch() },
            socketEvents = socketClient.events,
            messageDao = database.messageDao(),
            attachmentDao = database.attachmentDao(),
            isForeground = { foregroundTracker.isForeground.value },
            activeChatId = { ActiveChatTracker.activeChatId.value },
            activeCall = {
                when (callController.state.value) {
                    is CallUiState.OutgoingRinging,
                    is CallUiState.IncomingRinging,
                    is CallUiState.Connecting,
                    is CallUiState.Connected,
                    is CallUiState.Reconnecting -> true
                    else -> false
                }
            },
            currentUserId = {
                (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
            },
            isSilenced = { !notificationSettings.current.foregroundBannersEnabled || isChatSilenced(it) },
            resolveAvatar = { chatId ->
                val other = (chatRepository.chats.first().firstOrNull { it.chatId == chatId }
                    ?: chatRepository.getChat(chatId).getOrNull())?.otherUser
                other?.let { com.messenger.app.push.BannerAvatar(it.id, it.avatarVersion, it.hasAvatar) }
            },
            resolveSenderName = { chatId ->
                chatRepository.chats.first().firstOrNull { it.chatId == chatId }?.otherUser?.displayName
            },
        ).also { it.start() }

        applicationScope.launch {
            notificationSettings.settings.collect { value ->
                if (!value.messageAlertsEnabled) messagePushHandler.clearVisible()
                if (!value.foregroundBannersEnabled) foregroundMessageBannerController.dismiss()
                if (!value.enabled) {
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.activeNotifications.filter { it.tag?.startsWith("admin:") == true || it.tag?.startsWith("reminder:") == true }
                        .forEach { manager.cancel(it.tag, it.id) }
                }
            }
        }

        // N.2R §104: LAN WebSocket messages also produce a local notification
        // (no FCM needed). Route them through the SAME push router so the
        // foreground/open-chat/sequence-dedup/active-call policy applies
        // identically to both transport paths (FCM and WS).
        applicationScope.launch {
            socketClient.events.collect { event ->
                if (event is IncomingSocketEvent.NewMessage) {
                    val message = event.message
                    val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
                    if (owner != null && message.senderId != owner) {
                        routeMessagePush(PushEvent.NewMessage(message.chatId, message.id, message.sequence.toString(), owner))
                    }
                }
            }
        }

        historyReady.complete(Unit)
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:android.app.Activity) {
                com.messenger.app.data.history.HistoryAutoBackup.reconcile(this@MessengerApp,true)
                accountActivityReporter.resumed(activity)
            }
            override fun onActivityCreated(activity:android.app.Activity,state:android.os.Bundle?) = Unit
            override fun onActivityStarted(activity:android.app.Activity) = Unit
            override fun onActivityPaused(activity:android.app.Activity) { accountActivityReporter.paused(activity) }
            override fun onActivityStopped(activity:android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity:android.app.Activity,state:android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity:android.app.Activity) { accountActivityReporter.paused(activity) }
        })
        // FirebaseApp is initialized manually (the auto-init provider is
        // removed in the manifest) only when the config actually exists.
        FirebaseAvailability.initialize(this)

        // Token lifecycle: re-upload on every login, clear the uploaded flag
        // on logout so the next account's login re-registers its own mapping.
        applicationScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthSessionState.LoggedIn -> {
                        com.messenger.app.data.history.HistoryAutoBackup.reconcile(this@MessengerApp,true)
                        pushRegistrationRepository.ensureRegistered()
                        // P1-9.1: copy PRE-EXISTING Room verification rows
                        // (from before durable storage existed) into the
                        // account-scoped Signal namespace, before a later
                        // logout can clear Room. Fill-only and idempotent.
                        identityVerification.backfillExistingVerificationState()
                        // P1-9: restore the durable peer verification state
                        // that survived the logout (Room was wiped, the
                        // account-scoped Signal namespace was not).
                        identityVerification.hydrateFromDurableStorage()
                    }
                    is AuthSessionState.LoggedOut -> {
                        com.messenger.app.data.history.HistoryAutoBackup.logout(this@MessengerApp)
                        messagePushHandler.clearForLogout()
                        foregroundMessageBannerController.dismiss()
                        // P1-2: kill any call + its notification from the
                        // previous account before another account can log in,
                        // so Alice's ringing/active call can never appear
                        // under Bob. Local teardown only - the old token is
                        // already gone (no fake decline, no Bob-account
                        // network call); the backend liveness sweep ends the
                        // server-side call later.
                        callController.terminateForLogout()
                        clearConferenceForSessionChange()
                        if (this@MessengerApp::incomingCallNotificationManager.isInitialized) {
                            incomingCallNotificationManager.cancelIncoming(null)
                        }
                        pushRegistrationRepository.onLogout()
                    }
                    AuthSessionState.Unknown -> Unit
                }
            }
        }

        val incomingNotificationOwnerStore = com.messenger.app.call.IncomingNotificationOwnerStore(this)
        incomingCallNotificationManager = com.messenger.app.call.IncomingCallNotificationManager(
            this,
            callController,
            foregroundTracker.isForeground,
            applicationScope,
            incomingNotificationOwnerStore,
            notificationSettings = notificationSettings.settings,
            resolveAvatar = { userId ->
                val epoch = authRepository.currentSessionEpoch()
                val owner = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
                val other = notificationPreferences.takeIf { it.first == owner }?.second?.values
                    ?.firstOrNull { it.otherUser?.id == userId }?.otherUser
                other?.let {
                    val bytes = avatarRepository.resolve(it.id, it.avatarVersion, it.hasAvatar)
                    if (epoch != authRepository.currentSessionEpoch() || owner != (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id) null
                    else com.messenger.app.push.NotificationAvatar(it.id, it.displayName, bytes)
                }
            },
        )
        // P0.1: a terminal call push must clear the incoming-call slot even
        // after a process restart (no local ringing state). The framework-free
        // controller signals the terminal call_id; the manager applies the
        // owner-scoped check (a delayed old-call terminal never cancels a
        // newer call's notification).
        callController.onDismissIncomingPush = incomingCallNotificationManager::cancelIncoming
        applicationScope.launch {
            callController.state.collect { state ->
                voicePlayerController.setCallActive(
                    state !is CallUiState.Idle && state !is CallUiState.Ended && state !is CallUiState.Failed,
                )
                if (state is CallUiState.Ended) launch { runCatching { callHistory.refresh() } }
            }
        }
        callController.onIncomingPushReconciled = incomingCallNotificationManager::refresh
        incomingCallNotificationManager.start()
        // P0.1: reconcile the persisted incoming-notification owner after any
        // process restart. Still RINGING -> restore/keep the notification;
        // TERMINAL -> owner-scoped cleanup; network fail -> leave untouched.
        incomingNotificationOwnerStore.get()?.let { owner ->
            callController.reconcileIncomingNotificationOwner(owner)
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("FcmTimeline", "onCreate complete elapsed=${android.os.SystemClock.elapsedRealtime()} durationMs=${android.os.SystemClock.elapsedRealtime() - createStartMs}")
        }
    }
}

