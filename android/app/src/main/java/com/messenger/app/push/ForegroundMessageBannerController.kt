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
) {
    private val _banner = MutableStateFlow<ForegroundMessageBanner?>(null)
    val banner: StateFlow<ForegroundMessageBanner?> = _banner

    var callExpanded = true
    private var generation = 0L
    private val seenClientMessageIds = ArrayDeque<String>()

    fun start() {
        scope.launch {
            socketEvents.collect { event ->
                if (event is IncomingSocketEvent.NewMessage) handle(event.message)
            }
        }
    }

    private suspend fun handle(message: MessageDto) {
        val owner = currentUserId() ?: return
        val epoch = sessionEpoch()
        fun sessionValid() = owner == currentUserId() && epoch == sessionEpoch()
        if (!seenClientMessageIds.addUnique(message.clientMessageId)) return

        // Wait for the shared pipeline to persist THIS message (banner is
        // created only after successful ingest/decrypt/persist). If it never
        // lands (e.g. a session switch aborted the batch), skip after a short
        // window instead of leaking a suspended observer.
        val entity = withTimeoutOrNull(3000) {
            messageDao.observeByClientMessageId(message.clientMessageId).filterNotNull().first()
        } ?: return

        if (!sessionValid() || isSilenced(message.chatId)) return
        if (!ForegroundMessageBannerPolicy.shouldShow(
                isForeground = isForeground(),
                activeChatId = activeChatId(),
                eventChatId = message.chatId,
                isOwnMessage = entity.senderId == currentUserId(),
                activeCall = activeCall() && callExpanded,
            )
        ) return

        val attachment = attachmentDao.findByClientMessageId(entity.clientMessageId)
        val preview = ForegroundMessageBannerPolicy.previewFor(
            kind = entity.kind,
            status = entity.status,
            isDeleted = entity.isDeletedForEveryone,
            text = entity.text,
            mediaType = attachment?.mediaType,
            filename = attachment?.filename,
            mimeType = attachment?.mimeType,
        ) ?: return

        val name = resolveSenderName(message.chatId)
        val avatar = withTimeoutOrNull(800) { resolveAvatar(message.chatId) }
        // Name lookup can suspend while the user opens this conversation.
        if (!sessionValid() || isSilenced(message.chatId)) return
        if (!ForegroundMessageBannerPolicy.shouldShow(isForeground(), activeChatId(), message.chatId, entity.senderId == currentUserId(), activeCall() && callExpanded)) return
        val gen = ++generation
        _banner.value = ForegroundMessageBanner(
            chatId = message.chatId,
            senderName = name,
            preview = preview,
            generation = gen,
            avatar = avatar,
        )
        scope.launch {
            delay(autoDismissMs)
            dismissIf(gen)
        }
    }

    fun dismiss() {
        _banner.value = null
        generation++
    }

    private fun dismissIf(gen: Long) {
        if (_banner.value?.generation == gen) _banner.value = null
    }

    private fun ArrayDeque<String>.addUnique(value: String): Boolean {
        if (value in this) return false
        addLast(value)
        while (size > 128) removeFirst()
        return true
    }
}
