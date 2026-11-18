package com.messenger.app.push

import com.messenger.app.data.api.MessageDto
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.websocket.IncomingSocketEvent
import com.messenger.app.domain.model.LastMessagePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

/** One visible foreground banner. [generation] is a monotonic identity so a
 *  stale auto-dismiss timer can never remove a NEWER banner (replace-latest). */
data class ForegroundMessageBanner(
    val chatId: String,
    val senderName: String?,
    val preview: LastMessagePreview,
    val generation: Long,
    val avatar: BannerAvatar? = null,
    val messageId: String? = null,
    val target: NotificationTapTarget? = null,
)

data class BannerAvatar(val userId: String, val version: Long, val hasAvatar: Boolean)

/** Presentation-only foreground banner state (Runtime Correction D). Observes
 *  the realtime WebSocket `new_message` events, waits for the shared ingestion
 *  pipeline to persist the exact message, then resolves a human-safe preview
 *  from the LOCAL row (never the wire payload — no plaintext, no re-decrypt).
 *
 *  Replace-latest: one banner at a time; a newer message replaces the current
 *  one and restarts the dismiss timer. Duplicate/replayed events are dropped
 *  by clientMessageId. */
class ForegroundMessageBannerController(
    private val scope: CoroutineScope,
    private val socketEvents: Flow<IncomingSocketEvent>,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val isForeground: () -> Boolean,
    private val activeChatId: () -> String?,
    private val activeCall: () -> Boolean,
    private val currentUserId: () -> String?,
    private val resolveSenderName: suspend (chatId: String) -> String?,
    private val resolveAvatar: suspend (String) -> BannerAvatar? = { null },
    private val isSilenced: (String) -> Boolean = { false },
    private val sessionEpoch: () -> Int = { 0 },
    private val autoDismissMs: Long = ForegroundMessageBannerPolicy.AUTO_DISMISS_MS,
    private val ownership: MessageAlertOwnership = MessageAlertOwnership(),
    private val currentServer: () -> String = { "" },
    private val settings: () -> NotificationSettings = { NotificationSettings() },
    private val onPresented: (Boolean) -> Unit = {},
    private val genericPreview: () -> String = { "New message" },
    private val onFeedback: (Boolean, Boolean) -> Unit = { _, _ -> },
    private val resolveDirectOffer: suspend (String, String) -> String? = { _, _ -> null },
    private val allowPresentation: suspend (String) -> Boolean = { true },
) {
    private val _banner = MutableStateFlow<ForegroundMessageBanner?>(null)
    val banner: StateFlow<ForegroundMessageBanner?> = _banner

    var navigationChatId: String? = null
        set(value) {
            field = value
            if (!conversationCoveredByCall && value != null && _banner.value?.target == null && value == _banner.value?.chatId) dismiss()
        }
    var callExpanded = true
    val conversationCoveredByCall: Boolean get() = activeCall() && callExpanded
    private var generation = 0L
    private var arrival = 0L
    private var displayedArrival = 0L
    private var expiry: kotlinx.coroutines.Job? = null
    var privacyBlocked: Boolean = false
        set(value) { if (field != value) { field = value; if (value) invalidate() } }
    private val seenClientMessageIds = ArrayDeque<String>()
    private val pending = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val batches = NotificationBatchAlertLedger()
    private var preferenceRevision = 0L

    fun start() {
        scope.launch {
            socketEvents.collect { event ->
                if (event is IncomingSocketEvent.NewMessage) {
                    val message = event.message
                    val owner = currentUserId() ?: return@collect
                    if (message.kind != "content") {
                        // Remember authenticated lifecycle operations without waiting
                        // for a content row or letting a later push copy alert them.
                        ownership.claim(message.id, NotificationBatchSession(currentServer(), owner, sessionEpoch()))
                        return@collect
                    }
                    // Claim at receipt, never after waiting for persistence.
                    if (!isForeground()) return@collect
                    if (!ownership.claim(message.id, NotificationBatchSession(currentServer(), owner, sessionEpoch()))) return@collect
                    if (pending.size >= 128) pending.entries.firstOrNull()?.let { pending.remove(it.key)?.cancel() }
                    val order = ++arrival
                    pending[message.id] = scope.launch {
                        try { handle(message, order) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { android.util.Log.w("ForegroundAlert", "Presentation unavailable: ${error.javaClass.simpleName}") }
                        finally { pending.remove(message.id) }
                    }
                }
            }
        }
    }

    /** Foreground FCM owns its message before catch-up. Only Room plaintext can present it. */
    fun acceptPush(event: PushEvent.NewMessage): Boolean {
        if (!isForeground()) return false
        val owner = currentUserId() ?: return true
        val session = NotificationBatchSession(currentServer(), owner, sessionEpoch())
        if (event.userId != owner || !event.mayPresent || !ownership.claim(event.messageId, session)) return true
        if (privacyBlocked || !settings().messageAlertsEnabled || isSilenced(event.chatId)) return true
        val preference = preferenceRevision
        val order = ++arrival
        if (pending.size >= 128) pending.entries.firstOrNull()?.let { pending.remove(it.key)?.cancel() }
        pending[event.messageId] = scope.launch {
            try {
                val entity = withTimeoutOrNull(60_000) { messageDao.observeByServerId(event.messageId).filterNotNull().first() } ?: return@launch
                if (session != NotificationBatchSession(currentServer(), currentUserId().orEmpty(), sessionEpoch()) || preference != preferenceRevision ||
                    entity.chatId != event.chatId || entity.sequence != event.sequence.toLongOrNull()) return@launch
                handle(MessageDto(event.messageId, entity.chatId, entity.clientMessageId, entity.senderId, "persisted", entity.sequence ?: return@launch,
                    entity.createdAt, 1, "", kind = entity.kind), order)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unresolved plaintext never becomes a system foreground alert. */ }
            finally { pending.remove(event.messageId) }
        }
        return true
    }

    private suspend fun handle(message: MessageDto, order: Long) {
        val owner = currentUserId() ?: return
        val epoch = sessionEpoch()
        val server = currentServer()
        val preference = preferenceRevision
        fun sessionValid() = server == currentServer() && owner == currentUserId() && epoch == sessionEpoch() && preference == preferenceRevision && settings().messageAlertsEnabled && !privacyBlocked
        if (!sessionValid() || !seenClientMessageIds.addUnique(message.clientMessageId)) return

        // Wait for the shared pipeline to persist THIS message (banner is
        // created only after successful ingest/decrypt/persist). If it never
        // lands (e.g. a session switch aborted the batch), skip after a short
        // window instead of leaking a suspended observer.
        val entity = withTimeoutOrNull(60_000) {
            messageDao.observeByClientMessageId(message.clientMessageId).filterNotNull().first()
        } ?: return

        if (!sessionValid() || isSilenced(message.chatId) || message.notificationBatchAlert == false ||
            entity.serverId != message.id || entity.chatId != message.chatId || entity.senderId != message.senderId ||
            entity.sequence != message.sequence || entity.hiddenLocally ||
            entity.status !in setOf("sent", "delivered", "read")) return
        if (!ForegroundMessageBannerPolicy.shouldShow(
                isForeground = isForeground(),
                activeChatId = navigationChatId ?: activeChatId(),
                eventChatId = message.chatId,
                isOwnMessage = entity.senderId == currentUserId(),
                activeCall = activeCall() && callExpanded,
            )
        ) return

        val attachment = attachmentDao.findByClientMessageId(entity.clientMessageId)
        val originalPreview = ForegroundMessageBannerPolicy.previewFor(
            kind = entity.kind,
            status = entity.status,
            isDeleted = entity.isDeletedForEveryone,
            text = entity.text,
            mediaType = attachment?.mediaType,
            filename = attachment?.filename,
            mimeType = attachment?.mimeType,
            directFileOfferJson = if (com.messenger.app.domain.model.FilePreviewText.isDirectFallback(entity.text))
                resolveDirectOffer(entity.chatId, entity.clientMessageId) else null,
        ) ?: return
        val preview = if (settings().previews) originalPreview else LastMessagePreview.Text(genericPreview())

        val name = optionalDecoration { resolveSenderName(message.chatId) }?.let { com.messenger.app.domain.model.DisplayNamePolicy.display(it, "") }
        val avatar = optionalDecoration { resolveAvatar(message.chatId) }
        // Re-read durable mute preferences after metadata and fence the suspended decision.
        if (optionalDecoration { allowPresentation(message.chatId) } != true) return
        // Name lookup can suspend while the user opens this conversation.
        if (!sessionValid() || isSilenced(message.chatId)) return
        if (!ForegroundMessageBannerPolicy.shouldShow(isForeground(), navigationChatId ?: activeChatId(), message.chatId, entity.senderId == currentUserId(), activeCall() && callExpanded)) return
        if (order < displayedArrival) return
        displayedArrival = order
        val gen = ++generation
        expiry?.cancel()
        _banner.value = if (!settings().inAppBanners) null else ForegroundMessageBanner(
            chatId = message.chatId,
            senderName = name,
            preview = preview,
            generation = gen,
            avatar = avatar,
            messageId = message.id,
        )
        val batchSession = NotificationBatchSession(currentServer(), owner, epoch)
        val batch = entity.albumId?.takeIf { it.isNotBlank() }?.let { NotificationBatchKey(entity.chatId, entity.senderId, it) }
        val alert = batches.shouldAlert(message.id, batch, batchSession)
        batches.recordPosted(message.id, batch, batchSession)
        onPresented(alert && settings().inAppSound && !activeCall())
        onFeedback(alert && settings().inAppSound && !activeCall(), alert && settings().inAppVibration && !activeCall())
        if (_banner.value == null) return
        expiry = scope.launch {
            delay(autoDismissMs)
            dismissIf(gen)
        }
    }

    fun hasResumedActivity(): Boolean = isForeground()

    fun presentInactive(target: NotificationTapTarget, accountName: String, preferences: NotificationSettings): Boolean {
        if (!isForeground()) return false
        if (privacyBlocked || !preferences.messageAlertsEnabled) return true
        displayedArrival = ++arrival
        dismiss()
        val gen = ++generation
        if (preferences.inAppBanners) {
            _banner.value = ForegroundMessageBanner(target.chat, accountName, LastMessagePreview.Text(genericPreview()), gen, target = target)
            expiry = scope.launch { delay(autoDismissMs); dismissIf(gen) }
        }
        onFeedback(preferences.inAppSound && !activeCall(), preferences.inAppVibration && !activeCall())
        return true
    }

    fun dismiss() {
        expiry?.cancel(); expiry = null
        _banner.value = null
        generation++
    }

    fun invalidate() {
        preferenceRevision++
        pending.values.toList().forEach { it.cancel() }; pending.clear()
        seenClientMessageIds.clear()
        dismiss()
    }

    private fun dismissIf(gen: Long) {
        if (_banner.value?.generation == gen) { _banner.value = null; expiry = null }
    }

    private suspend fun <T> optionalDecoration(load: suspend () -> T?): T? = try {
        withTimeoutOrNull(800) { load() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    private fun ArrayDeque<String>.addUnique(value: String): Boolean {
        if (value in this) return false
        addLast(value)
        while (size > 128) removeFirst()
        return true
    }
}
