package com.messenger.app.ui.chats

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.attachment.AttachmentCancelledException
import com.messenger.app.data.attachment.AttachmentManager
import com.messenger.app.data.attachment.AttachmentTooLargeException
import com.messenger.app.data.attachment.VoicePlaybackState
import com.messenger.app.data.attachment.VoicePlayerController
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.repository.SocketConnectionState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

private const val TAG = "ChatDetailViewModel"

sealed interface ChatDetailUiState {
    data object Loading : ChatDetailUiState
    data class Loaded(val chat: ChatSummary) : ChatDetailUiState
    data class Error(val error: ChatError) : ChatDetailUiState
}

class ChatDetailViewModel(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val authRepository: AuthRepository,
    private val attachmentManager: AttachmentManager,
    private val voicePlayerController: VoicePlayerController,
    val chatId: String,
    initialChat: ChatSummary? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatDetailUiState>(
        initialChat?.takeIf { it.chatId == chatId }?.let { ChatDetailUiState.Loaded(it) } ?: ChatDetailUiState.Loading
    )
    val uiState: StateFlow<ChatDetailUiState> = _uiState

    // Null means Room has not supplied its first snapshot, not an empty chat.
    val messages: StateFlow<List<Message>?> =
        messageRepository.observeMessages(chatId).map<List<Message>, List<Message>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val failedActions = messageRepository.observeFailedActions(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val attachments: StateFlow<List<Attachment>?> =
        messageRepository.observeAttachments(chatId).map<List<Attachment>, List<Attachment>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Read the SAME already-guarded repository messages; never coordinator records.
    internal fun directFileCard(expected: Message) = messages.visibleDirectFilePresentation(
        expected, chatId, authRepository.authState.map { authRepository.currentSessionEpoch() == sessionEpoch },
    ).flowOn(Dispatchers.Default)

    internal fun currentDirectFileCard(expected: Message) = visibleDirectFilePresentation(
        messages.value, expected, chatId, authRepository.currentSessionEpoch() == sessionEpoch,
    )

    private val _attachmentError = MutableStateFlow<ChatError?>(null)
    val attachmentError: StateFlow<ChatError?> = _attachmentError

    val socketState: StateFlow<SocketConnectionState> = messageRepository.socketState

    val historyBeforeSequence: Long get() = authRepository.historyBeforeSequence()
    val historyContext = authRepository.historyContext
    val currentUserId: String?
        get() = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id

    private val olderHistory = OlderHistoryLoader()
    val sessionEpoch = authRepository.currentSessionEpoch()

    // Delayed callbacks belong to the account that opened this screen, even
    // when the shared repositories already expose a newly logged-in account.
    private fun requireCurrentSession() {
        if (authRepository.currentSessionEpoch() != sessionEpoch) throw kotlinx.coroutines.CancellationException("Account changed")
    }
    private fun <T> Result<T>.withoutCancellation(): Result<T> = onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        requireCurrentSession()
    }


    init {
        viewModelScope.launch {
            requireCurrentSession()
            authRepository.reconcileHistoryContext()
        }
        viewModelScope.launch {
            requireCurrentSession()
            chatRepository.observeChat(chatId).collect { cached ->
                if (cached != null && authRepository.currentSessionEpoch() == sessionEpoch) {
                    _uiState.value = ChatDetailUiState.Loaded(cached)
                }
            }
        }
        viewModelScope.launch {
            requireCurrentSession()
            chatRepository.getChat(chatId)
                .onSuccess { chat ->
                    if (authRepository.currentSessionEpoch() != sessionEpoch) return@onSuccess
                    _uiState.value = ChatDetailUiState.Loaded(chat)
                    // Correctness-path fallback: recovers any receipt.updated
                    // WS event missed while this chat wasn't the active
                    // screen (e.g. this device was disconnected/backgrounded
                    // when the other member delivered/read our messages).
                    messageRepository.reconcileReceipts(chatId, chat.otherMemberDeliveredSequence, chat.otherMemberReadSequence)
                }
                .withoutCancellation().onFailure {
                    if (_uiState.value !is ChatDetailUiState.Loaded && authRepository.currentSessionEpoch() == sessionEpoch) {
                        _uiState.value = ChatDetailUiState.Error(errorFor(it))
                    }
                }
        }
        // Immediate freshness for this specific chat on open. Reconnect
        // catch-up for ALL chats (including this one) is now handled
        // globally by GlobalSyncManager, not per-screen.
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.syncLatest(chatId)
        }
    }

    val scheduledDates = messageRepository.scheduledDates(chatId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    suspend fun scheduleText(text: String, due: Long): Boolean {
        if (authRepository.currentSessionEpoch() != sessionEpoch) return false
        return messageRepository.scheduleText(chatId, text, due).withoutCancellation().onFailure { _actionError.value = errorFor(it) }.isSuccess
    }
    val peerActivity get() = messageRepository.peerActivity
    suspend fun peerOnline(): Boolean = chatRepository.peerOnline(chatId).getOrDefault(false).takeIf { authRepository.currentSessionEpoch() == sessionEpoch } ?: false

    fun sendActivity(kind: String) { if (authRepository.currentSessionEpoch() == sessionEpoch) messageRepository.sendActivity(chatId, kind) }
    suspend fun locateMessage(id: String): String? = messageRepository.locateMessage(chatId, id)
    suspend fun chatValue(name: String): String? = messageRepository.chatValue(chatId, name)
    private val workspaceWrites = kotlinx.coroutines.channels.Channel<Pair<String, String?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val workspaceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    init {
        workspaceScope.launch {
            for ((name, value) in workspaceWrites) {
                if (authRepository.currentSessionEpoch() != sessionEpoch) continue
                try { saveChatValueNow(name, value) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { if (authRepository.currentSessionEpoch() == sessionEpoch) _actionError.value = ChatError.GENERIC }
            }
            workspaceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
    suspend fun saveChatValueNow(name: String, value: String?) {
        authRepository.withWorkspaceWrite(sessionEpoch) { messageRepository.saveChatValue(chatId, name, value); true }
            ?: throw kotlinx.coroutines.CancellationException("Account changed")
    }
    suspend fun stageVoiceDraft(file: File, duration: Long, base: org.json.JSONObject? = null): org.json.JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        requireCurrentSession()
        val source = if (base != null) com.messenger.app.data.attachment.VoiceDraftJoiner.join(File(base.getString("path")), file) else file
        val staged = stageMediaDraft(Uri.fromFile(source))
        val value = org.json.JSONObject().put("id", UUID.randomUUID().toString()).put("path", staged.path)
            .put("duration", duration + (base?.getLong("duration") ?: 0))
        saveChatValueNow("voiceDraft", value.toString())
        // Replace the durable pointer before reclaiming the old app-owned audio.
        file.delete()
        if (source != file) source.delete()
        if (base != null) File(base.getString("path")).delete()
        value
    }
    fun preserveInterruptedVoice(file: File, duration: Long, base: org.json.JSONObject? = null) {
        val saver = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        saver.launch {
            try { stageVoiceDraft(file, duration, base) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (authRepository.currentSessionEpoch() == sessionEpoch) _attachmentError.value = ChatError.GENERIC }
            finally { saver.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }
    suspend fun sendVoiceDraft(file: File, duration: Long, id: String): Boolean {
        return try {
            requireCurrentSession()
            messageRepository.queueMedia(chatId, id, Uri.fromFile(file).toString(), "voice", duration = duration).getOrThrow()
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (error: Exception) { _attachmentError.value = attachmentErrorFor(error); false }
    }
    fun saveChatValue(name: String, value: String?) { workspaceWrites.trySend(name to value) }
    override fun onCleared() { workspaceWrites.close(); super.onCleared() }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.sendMessage(chatId, text)
                .withoutCancellation().onFailure { e ->
                    if (e is com.messenger.app.data.signal.IdentityChangedException) {
                        _actionError.value = ChatError.IDENTITY_CHANGED
                    }
                }
        }
    }

    fun retry(clientMessageId: String) {
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.retry(clientMessageId)
        }
    }

    fun loadOlderMessages() {
        if (authRepository.currentSessionEpoch() != sessionEpoch) return
        viewModelScope.launch {
            requireCurrentSession()
            olderHistory.load { messageRepository.loadOlderMessages(chatId) }
        }
    }

    fun markRead(sequence: Long) {
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.markRead(chatId, sequence)
        }
    }

    suspend fun updateChatPreference(action: String, peer: String?): Boolean {
        if (authRepository.currentSessionEpoch() != sessionEpoch) return false
        val result = when(action) {
            "mute" -> chatRepository.setMute(chatId)
            "unmute" -> chatRepository.clearMute(chatId)
            "block" -> chatRepository.blockUser(chatId, peer ?: return false)
            "unblock" -> chatRepository.unblockUser(chatId)
            else -> return false
        }
        if (result.isFailure || authRepository.currentSessionEpoch() != sessionEpoch) return false
        return chatRepository.getChat(chatId).onSuccess {
            if (authRepository.currentSessionEpoch() == sessionEpoch) _uiState.value = ChatDetailUiState.Loaded(it)
        }.isSuccess
    }
    suspend fun submitReport(reason: String, description: String): Boolean {
        if (authRepository.currentSessionEpoch() != sessionEpoch) return false
        val peer = (_uiState.value as? ChatDetailUiState.Loaded)?.chat?.otherUser?.id ?: return false
        return chatRepository.reportChat(chatId, reason, peer, description).isSuccess && authRepository.currentSessionEpoch() == sessionEpoch
    }

    fun setMute(muted: Boolean, mutedUntil: String? = null) {
        viewModelScope.launch {
            requireCurrentSession()
            val result = if (muted) chatRepository.setMute(chatId, mutedUntil) else chatRepository.clearMute(chatId)
            result.onSuccess {
                requireCurrentSession()
                chatRepository.getChat(chatId)
                    .onSuccess { chat -> if (authRepository.currentSessionEpoch() == sessionEpoch) _uiState.value = ChatDetailUiState.Loaded(chat) }
            }.withoutCancellation().onFailure {
                _actionError.value = ChatError.GENERIC
            }
        }
    }

    fun setBlock(blockedUserId: String, block: Boolean) {
        viewModelScope.launch {
            requireCurrentSession()
            val result = if (block) chatRepository.blockUser(chatId, blockedUserId) else chatRepository.unblockUser(chatId)
            result.onSuccess {
                requireCurrentSession()
                chatRepository.getChat(chatId)
                    .onSuccess { chat -> if (authRepository.currentSessionEpoch() == sessionEpoch) _uiState.value = ChatDetailUiState.Loaded(chat) }
            }.withoutCancellation().onFailure { _actionError.value = ChatError.GENERIC }
        }
    }

    fun reportChat(reason: String, reportedUserId: String?, description: String?) {
        viewModelScope.launch {
            requireCurrentSession()
            chatRepository.reportChat(chatId, reason, reportedUserId, description)
                .withoutCancellation().onFailure { _actionError.value = ChatError.GENERIC }
        }
    }

    suspend fun stageMediaDraft(uri: Uri): Uri {
        if (authRepository.currentSessionEpoch() != sessionEpoch) throw kotlinx.coroutines.CancellationException("Account changed")
        return authRepository.withWorkspaceWrite(sessionEpoch) { attachmentManager.stageDraft(uri) } ?: throw kotlinx.coroutines.CancellationException("Account changed")
    }
    suspend fun discardStagedMedia(uris: List<Uri>) {
        authRepository.withWorkspaceWrite(sessionEpoch) {
            messageRepository.discardMediaDrafts(uris.mapNotNull { it.path }.toSet())
        }
    }
    private val photoIds = mutableMapOf<Uri, String>()
    private val preparedPhotos = mutableMapOf<Uri, com.messenger.app.data.attachment.AttachmentMetadataDto>()
    private var sendingPhotoBatch = false

    suspend fun registerCameraLaunch(uri: Uri, source: File, nonce: String) {
        requireCurrentSession()
        val intent = CameraLaunchIntent(nonce, currentUserId ?: throw kotlinx.coroutines.CancellationException("Signed out"),
            com.messenger.app.data.api.ServerEndpoint.storageOrigin(),
            authRepository.currentDeviceId() ?: throw kotlinx.coroutines.CancellationException("No device"), chatId, uri.toString(), source.path)
        saveChatValueNow("cameraCaptureIntent", kotlinx.serialization.json.Json.encodeToString(CameraLaunchIntent.serializer(), intent))
    }

    suspend fun completeCameraLaunch(nonce: String, uri: Uri, source: File, confirmed: Boolean): Boolean {
        requireCurrentSession()
        val raw = messageRepository.chatValue(chatId, "cameraCaptureIntent")
            ?: throw kotlinx.coroutines.CancellationException("Capture was retired")
        val intent = kotlinx.serialization.json.Json.decodeFromString(CameraLaunchIntent.serializer(), raw)
        requireCurrentSession()
        if (intent.nonce != nonce || intent.uri != uri.toString() || !intent.matchesSource(source) ||
            !intent.belongsTo(currentUserId, com.messenger.app.data.api.ServerEndpoint.storageOrigin(), authRepository.currentDeviceId(), chatId)) {
            throw kotlinx.coroutines.CancellationException("Capture belongs to another session")
        }
        if (!confirmed) {
            saveChatValueNow("cameraCaptureIntent", null)
            source.delete()
            return false
        }
        saveChatValueNow("cameraCaptureIntent", kotlinx.serialization.json.Json.encodeToString(CameraLaunchIntent.serializer(), intent.copy(confirmed = true)))
        val sent = sendCameraCapture(uri, source)
        // A retry owns the source now; retain the launch record if saving that
        // retry failed (for example, a full disk) instead of losing ownership.
        if (sent || messageRepository.chatValue(chatId, "cameraSendDraft") != null) saveChatValueNow("cameraCaptureIntent", null)
        return sent
    }

    suspend fun registerDualCameraLaunch(uri: Uri, source: File, nonce: String) {
        requireCurrentSession()
        check(messageRepository.chatValue(chatId, "cameraSendDraft") == null) { "A camera retry already owns its source" }
        val pending = messageRepository.chatValue(chatId, "cameraCaptureIntent")?.let {
            runCatching { kotlinx.serialization.json.Json.decodeFromString(CameraLaunchIntent.serializer(), it) }.getOrNull()
        }
        check(pending?.confirmed != true) { "A confirmed camera result is still pending" }
        registerCameraLaunch(uri, source, nonce)
    }

    suspend fun cameraLaunchWasConsumed(nonce: String): Boolean {
        requireCurrentSession()
        val raw = messageRepository.chatValue(chatId, "cameraCaptureIntent")
        requireCurrentSession()
        if (raw == null) return true
        val pending = kotlinx.serialization.json.Json.decodeFromString(CameraLaunchIntent.serializer(), raw)
        return pending.nonce != nonce
    }

    /** The dual-mode screen registers its initial photo slot before launch.
     * Only its owner/chat-fenced private result may replace that slot with the
     * sibling MP4; commit the actual URI/type and explicit confirmation together.
     * Replays after a consumed launch are harmless and cannot send twice. */
    suspend fun completeDualCameraLaunch(result: com.messenger.app.capture.OwnedDualCameraResult) {
        requireCurrentSession()
        val raw = messageRepository.chatValue(chatId, "cameraCaptureIntent") ?: return
        val intent = kotlinx.serialization.json.Json.decodeFromString(CameraLaunchIntent.serializer(), raw)
        requireCurrentSession()
        if (intent.nonce != result.nonce ||
            !intent.belongsTo(currentUserId, com.messenger.app.data.api.ServerEndpoint.storageOrigin(), authRepository.currentDeviceId(), chatId)) return
        val original = result.originalFile.canonicalFile
        val actual = result.file.canonicalFile
        val initialMatches = intent.uri == result.originalUri.toString() && intent.matchesSource(result.originalFile)
        val alreadyConfirmed = intent.confirmed && intent.uri == result.uri.toString() && intent.matchesSource(result.file)
        if ((!initialMatches && !alreadyConfirmed) || original.name != "capture_${result.nonce}.jpg" ||
            actual.parentFile != original.parentFile || actual.name !in setOf(original.name, "capture_${result.nonce}.mp4") ||
            result.uri.scheme != "content" || result.uri.authority != result.originalUri.authority) {
            throw kotlinx.coroutines.CancellationException("Capture result does not match its private output")
        }
        if (!result.success) {
            completeCameraLaunch(result.nonce, result.originalUri, result.originalFile, false)
            return
        }
        val completed = intent.copy(uri = result.uri.toString(), source = result.file.path, confirmed = true)
        saveChatValueNow("cameraCaptureIntent", kotlinx.serialization.json.Json.encodeToString(CameraLaunchIntent.serializer(), completed))
        val sent = sendCameraCapture(result.uri, result.file)
        if (sent || messageRepository.chatValue(chatId, "cameraSendDraft") != null) saveChatValueNow("cameraCaptureIntent", null)
    }

    suspend fun cameraRecoveryDraft(): org.json.JSONObject? {
        requireCurrentSession()
        val savedDraft = messageRepository.chatValue(chatId, "cameraSendDraft")
        requireCurrentSession()
        savedDraft?.let { return org.json.JSONObject(it) }
        val raw = messageRepository.chatValue(chatId, "cameraCaptureIntent") ?: return null
        val intent = runCatching { kotlinx.serialization.json.Json.decodeFromString(CameraLaunchIntent.serializer(), raw) }.getOrNull() ?: return null
        requireCurrentSession()
        return if (intent.confirmed && intent.belongsTo(currentUserId, com.messenger.app.data.api.ServerEndpoint.storageOrigin(), authRepository.currentDeviceId(), chatId))
            org.json.JSONObject().put("uri", intent.uri).put("source", intent.source) else null
    }

    /** Native camera confirmation is the send action; keep its retry draft independent of the album. */
    suspend fun sendCameraCapture(uri: Uri, source: File?): Boolean {
        requireCurrentSession()
        return try {
            _attachmentError.value = null
            saveChatValueNow("cameraSendDraft", org.json.JSONObject().put("uri", uri.toString()).put("source", source?.path).toString())
            val durable = if (source == null && uri.scheme == "file") uri else stageMediaDraft(uri)
            requireCurrentSession()
            saveChatValueNow("cameraSendDraft", org.json.JSONObject().put("uri", durable.toString()).toString())
            saveChatValueNow("cameraCaptureIntent", null)
            source?.delete() // Only after a durable retry reference has been saved.
            val sent = sendPhotoNow(durable)
            requireCurrentSession()
            if (sent) saveChatValueNow("cameraSendDraft", null)
            sent
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (error: Exception) { _attachmentError.value = attachmentErrorFor(error); false }
    }

    fun sendPhoto(uri: Uri, sourceFileToDelete: File? = null, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            requireCurrentSession()
            val success = sendPhotoNow(uri)
            if (success) sourceFileToDelete?.delete()
            onComplete(success)
        }
    }

    private suspend fun sendPhotoNow(uri: Uri, caption: String? = null, albumId: String? = null, quality: String = "image"): Boolean {
        val id = photoIds.getOrPut(uri) { uri.path?.let { File(it).parentFile?.name }?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString() }
        return try {
            com.messenger.app.data.message.MessageTextPolicy.caption(caption)
            val durableUri = if (uri.scheme == "file") uri else stageMediaDraft(uri)
            requireCurrentSession()
            messageRepository.queueMedia(chatId, id, durableUri.toString(), galleryQueueKind(quality, attachmentManager.isVideo(uri)), caption, albumId = albumId).getOrThrow()
            photoIds.remove(uri); preparedPhotos.remove(uri)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (error: Exception) { _attachmentError.value = attachmentErrorFor(error); false }
    }

    /** Sends a user-selected photo batch in picker order. Each photo is a
     * separate encrypted attachment message, which matches the wire protocol
     * and keeps a failure from losing the successfully queued earlier photos. */
    fun sendPhotos(uris: List<Uri>, caption: String? = null, quality: String = "image", albumId: String? = null, onSent: (Uri) -> Unit, onComplete: (Boolean) -> Unit) {
        val selected = uris.distinct()
        if (selected.isEmpty() || sendingPhotoBatch) return
        if (!validCaption(caption)) { onComplete(false); return }
        sendingPhotoBatch = true
        viewModelScope.launch {
            requireCurrentSession()
            try {
                val album = albumId ?: chatValue("mediaAlbum") ?: UUID.randomUUID().toString().also { saveChatValueNow("mediaAlbum", it) }
                for (uri in selected) {
                    if (!sendPhotoNow(uri, if (uri == selected.first()) caption else null, album, quality)) { onComplete(false); return@launch }
                    if (uri == selected.first()) saveChatValueNow("mediaCaption", null)
                    onSent(uri)
                }
                saveChatValueNow("mediaAlbum",null)
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                _attachmentError.value = attachmentErrorFor(error); onComplete(false)
            } finally { sendingPhotoBatch = false }
        }
    }

    private val preparedFiles = mutableMapOf<String, com.messenger.app.data.attachment.AttachmentMetadataDto>()

    /** One press of Send owns one durable group, including documents. */
    fun sendComposerAttachments(photos:List<Uri>,files:List<Uri>,caption:String?,quality:String,
        onPhotoQueued:(Uri)->Unit,onFileQueued:suspend (Uri)->Unit,onComplete:(Boolean)->Unit) {
        if(sendingPhotoBatch)return
        if(photos.size+files.size !in 1..10){_attachmentError.value=ChatError.GENERIC;onComplete(false);return}
        if (!validCaption(caption)) { onComplete(false); return }
        sendingPhotoBatch=true
        viewModelScope.launch {
            try {
                requireCurrentSession()
                _attachmentError.value=null
                val album=chatValue("mediaAlbum")?:UUID.randomUUID().toString().also {saveChatValueNow("mediaAlbum",it)}
                val selectedPhotos=photos.distinct()
                val selectedFiles=files.distinct().filterNot {it in selectedPhotos}
                val drafts=mutableListOf<com.messenger.app.domain.repository.QueuedMediaDraft>()
                for(uri in selectedPhotos+selectedFiles) {
                    val id=photoIds.getOrPut(uri) {uri.path?.let {File(it).parentFile?.name}?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?:UUID.randomUUID().toString()}
                    val durable=if(uri.scheme=="file")uri else stageMediaDraft(uri)
                    requireCurrentSession()
                    drafts+=com.messenger.app.domain.repository.QueuedMediaDraft(id,durable.toString(),
                        if(uri in selectedPhotos)galleryQueueKind(quality,attachmentManager.isVideo(uri)) else "file",
                        caption.takeIf {drafts.isEmpty()},albumId=album)
                }
                // One transaction/one invalidation. Upload progress can never add new tiles.
                messageRepository.queueMediaBatch(chatId,drafts).getOrThrow()
                saveChatValueNow("mediaCaption",null)
                for(uri in selectedPhotos){photoIds.remove(uri);preparedPhotos.remove(uri);onPhotoQueued(uri)}
                for(uri in selectedFiles){photoIds.remove(uri);onFileQueued(uri)}
                saveChatValueNow("mediaAlbum",null)
                onComplete(true)
            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){_attachmentError.value=attachmentErrorFor(error);onComplete(false)}
            finally {sendingPhotoBatch=false}
        }
    }

    suspend fun discardPreparedFile(id: String, uri: Uri, clearLegacy: Boolean = true): Boolean = try {
        // Remove the durable reference before ownership-aware cleanup. A queued
        // upload sharing this source must keep it, even if cancellation is late.
        if(clearLegacy) saveChatValueNow("fileDraftUri", null)
        discardStagedMedia(listOf(uri))
        preparedFiles.remove(id)
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        _attachmentError.value = ChatError.GENERIC
        false
    }

    fun sendFile(uri: Uri, clientMessageId: String, caption: String? = null, albumId: String? = null, onComplete: (Boolean) -> Unit) {
        if (!validCaption(caption)) { onComplete(false); return }
        viewModelScope.launch {
            requireCurrentSession()
            try {
                val durable = if (uri.scheme == "file") uri else stageMediaDraft(uri)
                requireCurrentSession()
                messageRepository.queueMedia(chatId, clientMessageId, durable.toString(), "file", caption = caption, albumId=albumId).getOrThrow()
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                _attachmentError.value = attachmentErrorFor(error)
                onComplete(false)
            }
        }
    }

    fun sendVoice(recordedFile: File, durationMs: Long) = sendAttachment { clientMessageId ->
        attachmentManager.prepareAndUploadVoice(recordedFile, durationMs, chatId, clientMessageId)
    }

    private fun sendAttachment(
        sourceFileToDelete: File? = null,
        prepareAndUpload: suspend (clientMessageId: String) -> com.messenger.app.data.attachment.AttachmentMetadataDto,
    ) {
        viewModelScope.launch {
            requireCurrentSession()
            sendAttachmentNow(sourceFileToDelete, prepareAndUpload)
        }
    }

    private suspend fun sendAttachmentNow(
        sourceFileToDelete: File? = null,
        prepareAndUpload: suspend (clientMessageId: String) -> com.messenger.app.data.attachment.AttachmentMetadataDto,
    ) {
        val clientMessageId = UUID.randomUUID().toString()
        try {
            requireCurrentSession()
            val metadata = prepareAndUpload(clientMessageId)
            requireCurrentSession()
            messageRepository.sendAttachmentMessage(chatId, clientMessageId, metadata).getOrThrow()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (e: Exception) {
            requireCurrentSession()
            Log.w(TAG, "failed to send attachment: ${e::class.simpleName}")
            if (e !is AttachmentCancelledException) {
                _attachmentError.value = attachmentErrorFor(e)
            }
        } finally {
            // Camera capture files are app-owned and are deleted immediately
            // after ingest, whether the send succeeds or fails.
            sourceFileToDelete?.delete()
        }
    }

    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val pausedDownloads = mutableSetOf<String>()
    fun autoDownloadAttachment(attachment: Attachment) {
        if (attachment.attachmentId !in pausedDownloads && downloadJobs[attachment.attachmentId]?.isActive != true) downloadAttachment(attachment, automatic = true)
    }
    fun downloadAttachment(attachment: Attachment, automatic: Boolean = false) {
        val id = attachment.attachmentId
        downloadJobs[id]?.takeIf { it.isActive }?.let { pausedDownloads.add(id); it.cancel(); return }
        pausedDownloads.remove(id)
        downloadJobs[id] = viewModelScope.launch {
            requireCurrentSession()
            try { if (automatic) attachmentManager.autoDownload(id) else attachmentManager.download(id) }
            catch (_: kotlinx.coroutines.CancellationException) { }
            catch (_: Exception) { if (id !in pausedDownloads) _attachmentError.value = ChatError.ATTACHMENT_DOWNLOAD }
            finally { downloadJobs.remove(id) }
        }
    }

    suspend fun downloadMediaForViewer(id: String): File {
        if (authRepository.currentSessionEpoch() != sessionEpoch) throw kotlinx.coroutines.CancellationException("Account changed")
        val file = attachmentManager.download(id)
        if (authRepository.currentSessionEpoch() != sessionEpoch) throw kotlinx.coroutines.CancellationException("Account changed")
        return file
    }

    fun clearAttachmentError() {
        _attachmentError.value = null
    }

    fun reportCameraDenied() {
        _attachmentError.value = ChatError.CAMERA_DENIED
    }

    val voicePlaybackState: StateFlow<VoicePlaybackState> = voicePlayerController.state

    fun deleteSelected(ids: List<String>, everyone: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            requireCurrentSession()
            for (id in ids) {
                try {
                    requireCurrentSession()
                    val item = messages.value.orEmpty().firstOrNull { it.clientMessageId == id } ?: continue
                    if (everyone && item.senderId != currentUserId) continue
                    if (everyone && item.serverId != null) messageRepository.deleteForEveryone(id).getOrThrow()
                    else messageRepository.deleteForMe(id)
                    onDone(id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (_: Exception) { _actionError.value = ChatError.GENERIC; break }
            }
        }
    }
    fun stopVoice() { if (authRepository.currentSessionEpoch() == sessionEpoch) voicePlayerController.stop() }
    fun seekVoice(fraction: Float) { if (authRepository.currentSessionEpoch() == sessionEpoch) voicePlayerController.seek(fraction) }
    fun cycleVoiceSpeed() { if (authRepository.currentSessionEpoch() == sessionEpoch) voicePlayerController.cycleSpeed() }
    fun toggleVoicePlayback(attachmentId: String, file: File) {
        if (authRepository.currentSessionEpoch() == sessionEpoch) voicePlayerController.togglePlay(attachmentId, file)
    }

    private fun errorFor(t: Throwable): ChatError = when (t) {
        is com.messenger.app.data.message.MessageTextPolicyException -> ChatError.TEXT_POLICY
        is AuthApiException.Network -> ChatError.NETWORK
        else -> ChatError.GENERIC
    }

    private fun validCaption(caption: String?): Boolean = try {
        com.messenger.app.data.message.MessageTextPolicy.caption(caption)
        true
    } catch (_: com.messenger.app.data.message.MessageTextPolicyException) {
        _attachmentError.value = ChatError.CAPTION_POLICY
        false
    }

    private fun attachmentErrorFor(t: Throwable): ChatError = when (t) {
        is com.messenger.app.data.message.MessageTextPolicyException -> ChatError.CAPTION_POLICY
        is AttachmentTooLargeException -> ChatError.ATTACHMENT_TOO_LARGE
        is AuthApiException.Network -> ChatError.NETWORK
        else -> ChatError.ATTACHMENT_SEND
    }

    // --- Этап I: message lifecycle actions ---------------------------------

    /** Which message (if any) the composer is currently replying to - set
     *  by the UI's "Reply" action, cleared by send/cancel. */
    private val _replyTarget = MutableStateFlow<Message?>(null)
    val replyTarget: StateFlow<Message?> = _replyTarget

    /** Which OWN message (if any) the composer is currently editing - the
     *  composer swaps into "edit mode" (prefilled text, Save/Cancel)
     *  while this is set. */
    private val _editTarget = MutableStateFlow<Message?>(null)
    val editTarget: StateFlow<Message?> = _editTarget

    private val _actionError = MutableStateFlow<ChatError?>(null)
    val actionError: StateFlow<ChatError?> = _actionError

    /** Candidate destination chats for the "Forward" dialog - every OTHER
     *  chat this device knows about (excludes the current one). */
    val forwardableChats: StateFlow<List<ChatSummary>> =
        chatRepository.chats.map { list -> list.filter { it.chatId != chatId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ensure the real Saved Messages chat exists before the forward picker
     *  opens. It is created through the server API, not a synthetic client id.
     *  Offline/API failure is returned so the picker can show a retryable
     *  state instead of silently omitting Saved Messages. Cancellation is
     *  always propagated. */
    suspend fun ensureSavedChatForForwarding(): Boolean = try {
        chatRepository.getOrCreateSavedChat().getOrThrow()
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    fun startReply(message: Message) {
        _editTarget.value = null
        _replyTarget.value = message
    }

    fun cancelReply() {
        _replyTarget.value = null
    }

    fun startEdit(message: Message) {
        _replyTarget.value = null
        _editTarget.value = message
    }

    fun cancelEdit() {
        _editTarget.value = null
    }

    /** Clear the draft and reply/edit mode only after the operation is durable. */
    suspend fun sendComposerText(text: String): Boolean {
        if (text.isBlank() || authRepository.currentSessionEpoch() != sessionEpoch) return false
        val editing = _editTarget.value
        val replyingTo = _replyTarget.value
        val result = when {
            editing != null -> messageRepository.editMessage(editing.clientMessageId, text)
            replyingTo != null -> messageRepository.replyMessage(chatId, text, replyingTo.clientMessageId)
            else -> messageRepository.sendMessage(chatId, text)
        }
        result.withoutCancellation()
        requireCurrentSession()
        if (result.isSuccess) {
            if (_editTarget.value == editing) _editTarget.value = null
            if (_replyTarget.value == replyingTo) _replyTarget.value = null
        } else {
            _actionError.value = when {
                result.exceptionOrNull() is com.messenger.app.data.message.MessageTextPolicyException -> ChatError.TEXT_POLICY
                editing != null -> ChatError.EDIT
                replyingTo != null -> ChatError.REPLY
                else -> ChatError.GENERIC
            }
        }
        return result.isSuccess
    }

    fun deleteForEveryone(clientMessageId: String) {
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.deleteForEveryone(clientMessageId)
                .withoutCancellation().onFailure { _actionError.value = ChatError.DELETE }
        }
    }

    fun deleteForMe(clientMessageId: String) {
        viewModelScope.launch {
            requireCurrentSession()
            try { messageRepository.deleteForMe(clientMessageId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _actionError.value = ChatError.DELETE }
        }
    }

    fun cancelUnsentMessages(clientMessageIds: List<String>) {
        val captured = clientMessageIds.distinct().take(10).toList()
        if (captured.isEmpty()) return
        viewModelScope.launch {
            requireCurrentSession()
            messageRepository.cancelUnsentMessages(chatId, captured).withoutCancellation()
                .onFailure { _actionError.value = ChatError.DELETE }
        }
    }

    /** Quick-reaction tap semantics (Этап I п.18): tapping the SAME emoji
     *  the user already reacted with removes it, tapping a different one
     *  replaces it - the caller passes the user's own CURRENT emoji (if
     *  any) so this can decide which. */
    private val quickReactionRequests = mutableSetOf<String>()
    fun setQuickReaction(message: Message, emoji: String) {
        if (!com.messenger.app.ui.settings.QuickReactionPolicy.eligible(message, false) ||
            emoji !in com.messenger.app.ui.settings.QuickReactionPolicy.choices ||
            message.reactions.any { it.reactedByMe && it.emoji == emoji } ||
            !quickReactionRequests.add(message.clientMessageId)) return
        viewModelScope.launch {
            requireCurrentSession()
            try {
                messageRepository.setReaction(message.clientMessageId, emoji)
                    .withoutCancellation().onFailure { _actionError.value = ChatError.REACTION }
            } finally { quickReactionRequests.remove(message.clientMessageId) }
        }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val currentMine = message.reactions.firstOrNull { it.reactedByMe }
        viewModelScope.launch {
            requireCurrentSession()
            if (currentMine != null && currentMine.emoji == emoji) {
                messageRepository.removeReaction(message.clientMessageId)
            } else {
                messageRepository.setReaction(message.clientMessageId, emoji)
            }.withoutCancellation().onFailure { _actionError.value = ChatError.REACTION }
        }
    }

    /** Validate the captured selection before the first download/upload/queue.
     * Per-item repository admission still rechecks current durable source. */
    private fun validForwardSelection(ids: List<String>): Boolean {
        val rows = messages.value.orEmpty().associateBy { it.clientMessageId }
        val media = attachments.value.orEmpty().associateBy { it.clientMessageId }
        try {
            for (id in ids.distinct()) {
                val row = rows[id] ?: run { _actionError.value = ChatError.FORWARD; return false }
                if (row.conferenceInvitation != null) continue
                val attachment = media[id] ?: row.queuedAttachment
                if (attachment != null) {
                    com.messenger.app.data.message.MessageTextPolicy.caption(row.caption)
                    com.messenger.app.data.message.MessageTextPolicy.received(attachment.filename)
                } else com.messenger.app.data.message.MessageTextPolicy.outgoing(row.text)
            }
            return true
        } catch (_: com.messenger.app.data.message.MessageTextPolicyException) {
            _actionError.value = ChatError.FORWARD_TEXT_POLICY
            return false
        }
    }
    fun forwardMessage(clientMessageId: String, destinationChatId: String) = forwardMessages(listOf(clientMessageId), destinationChatId)
    fun forwardMessages(ids: List<String>, destinationChatId: String, onSent: (String) -> Unit = {}) {
        viewModelScope.launch {
            requireCurrentSession()
            if (!validForwardSelection(ids)) return@launch
            val grouped = chatForwardingAlbums(com.messenger.app.ui.calls.chatTimeline(messages.value.orEmpty(), emptyList()), ids.toSet())
            for (id in ids.distinct()) { if (!forwardMessageNow(id, destinationChatId, grouped[id])) break; onSent(id) }
        }
    }
    private suspend fun forwardMessageNow(clientMessageId: String, destinationChatId: String, albumId: String? = null): Boolean {
            requireCurrentSession()
            val attachment = attachments.value.orEmpty().firstOrNull { it.clientMessageId == clientMessageId }
            val result = if (attachment != null) {
                runCatching {
                    com.messenger.app.data.message.MessageTextPolicy.caption(messages.value.orEmpty().firstOrNull { it.clientMessageId == clientMessageId }?.caption)
                    com.messenger.app.data.message.MessageTextPolicy.received(attachment.filename)
                    if (attachment.localPath == null) attachmentManager.download(attachment.attachmentId)
                    requireCurrentSession()
                    val localPath = attachments.value.orEmpty().firstOrNull { it.attachmentId == attachment.attachmentId }?.localPath
                        ?: error("Attachment could not be downloaded for forwarding.")
                    val newClientMessageId = UUID.randomUUID().toString()
                    val metadata = attachmentManager.forwardAttachment(
                        localPath = localPath,
                        mediaType = attachment.mediaType.name.lowercase(),
                        mimeType = attachment.mimeType,
                        filename = attachment.filename,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        thumbnailBase64 = attachment.thumbnailBase64,
                        chatId = destinationChatId,
                        clientMessageId = newClientMessageId,
                    )
                    requireCurrentSession()
                    messageRepository.sendAttachmentMessage(destinationChatId, newClientMessageId, metadata, forwarded = true, caption = messages.value.orEmpty().firstOrNull { it.clientMessageId == clientMessageId }?.caption, albumId = albumId).getOrThrow()
                }
            } else {
                messageRepository.forwardMessage(clientMessageId, destinationChatId)
            }
            result.withoutCancellation().onFailure { _actionError.value = if (it is com.messenger.app.data.message.MessageTextPolicyException) ChatError.TEXT_POLICY else ChatError.FORWARD }
            return result.isSuccess
    }

    /** Save to Saved Messages (M.44-M.46): local decrypt -> NEW encryption ->
     *  NEW attachment upload -> new independent message in the owner-only
     *  saved chat. Independent delete semantics, never a pointer to the
     *  original. */
    fun saveMessage(clientMessageId: String) {
        viewModelScope.launch {
            requireCurrentSession()
            if (!validForwardSelection(listOf(clientMessageId))) return@launch
            val savedChat = chatRepository.getOrCreateSavedChat().withoutCancellation().getOrElse {
                _actionError.value = ChatError.SAVED_OPEN
                return@launch
            }
            requireCurrentSession()
            val attachment = attachments.value.orEmpty().firstOrNull { it.clientMessageId == clientMessageId }
            val result = if (attachment != null) {
                runCatching {
                    com.messenger.app.data.message.MessageTextPolicy.caption(messages.value.orEmpty().firstOrNull { it.clientMessageId == clientMessageId }?.caption)
                    com.messenger.app.data.message.MessageTextPolicy.received(attachment.filename)
                    if (attachment.localPath == null) attachmentManager.download(attachment.attachmentId)
                    requireCurrentSession()
                    val localPath = attachments.value.orEmpty().firstOrNull { it.attachmentId == attachment.attachmentId }?.localPath
                        ?: error("Attachment could not be downloaded for saving.")
                    val newClientMessageId = UUID.randomUUID().toString()
                    val metadata = attachmentManager.forwardAttachment(
                        localPath = localPath,
                        mediaType = attachment.mediaType.name.lowercase(),
                        mimeType = attachment.mimeType,
                        filename = attachment.filename,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        thumbnailBase64 = attachment.thumbnailBase64,
                        chatId = savedChat.chatId,
                        clientMessageId = newClientMessageId,
                    )
                    requireCurrentSession()
                    messageRepository.sendAttachmentMessage(savedChat.chatId, newClientMessageId, metadata, forwarded = false).getOrThrow()
                }
            } else {
                messageRepository.saveMessage(clientMessageId, savedChat.chatId)
            }
            result.withoutCancellation().onFailure { _actionError.value = if (it is com.messenger.app.data.message.MessageTextPolicyException) ChatError.TEXT_POLICY else ChatError.SAVE }
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }
}

class ChatDetailViewModelFactory(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val authRepository: AuthRepository,
    private val attachmentManager: AttachmentManager,
    private val voicePlayerController: VoicePlayerController,
    private val chatId: String,
    private val initialChat: ChatSummary? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatDetailViewModel(chatRepository, messageRepository, authRepository, attachmentManager, voicePlayerController, chatId, initialChat) as T
    }
}
