package com.messenger.app.push

import com.messenger.app.data.api.MessageDto
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.sync.InMemoryAttachmentDao
import com.messenger.app.data.sync.InMemoryMessageDao
import com.messenger.app.data.websocket.IncomingSocketEvent
import com.messenger.app.domain.model.LastMessagePreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InlineBannerContractsTest {
    private class Fixture(val scope: TestScope) {
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val clients = mutableMapOf<String, MutableStateFlow<MessageEntity?>>()
        val servers = mutableMapOf<String, MutableStateFlow<MessageEntity?>>()
        val ownership = MessageAlertOwnership()
        var foreground = true
        var open: String? = null
        var muted = false
        var durableAllowed = true
        var owner = "owner"
        var epoch = 1
        var settings = NotificationSettings()
        var heldNameChat: String? = null
        val metadataReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val feedback = mutableListOf<Pair<Boolean, Boolean>>()
        val controller = ForegroundMessageBannerController(scope.backgroundScope, events,
            object : MessageDao by InMemoryMessageDao() {
                override fun observeByClientMessageId(clientMessageId: String) = clients.getOrPut(clientMessageId) { MutableStateFlow(null) }
                override fun observeByServerId(serverId: String) = servers.getOrPut(serverId) { MutableStateFlow(null) }
            }, InMemoryAttachmentDao(), { foreground }, { open }, { false }, { owner }, { chat -> if (chat == heldNameChat) metadataReady.await(); "Peer" },
            isSilenced = { muted }, sessionEpoch = { epoch }, ownership = ownership, currentServer = { "server" },
            settings = { settings }, allowPresentation = { durableAllowed }, onFeedback = { sound, vibration -> feedback += sound to vibration })
        fun dto(id: String, chat: String = "other") = MessageDto(id, chat, "client-$id", "peer", "device", 3,
            "2026-11-17T12:00:00Z", 1, "ciphertext")
        fun push(dto: MessageDto) = PushEvent.NewMessage(dto.chatId, dto.id, "3", owner)
        fun persist(dto: MessageDto, album: String? = null) {
            val row = MessageEntity(dto.clientMessageId, dto.id, dto.chatId, dto.senderId, "hello", dto.sequence, dto.createdAt, "delivered", albumId = album)
            clients.getOrPut(dto.clientMessageId) { MutableStateFlow(null) }.value = row
            servers.getOrPut(dto.id) { MutableStateFlow(null) }.value = row
        }
        suspend fun start() { controller.start(); scope.runCurrent() }
        suspend fun socket(dto: MessageDto) { events.emit(IncomingSocketEvent.NewMessage(dto)); scope.runCurrent() }
    }
    @Test fun `FCM first waits for exact persisted plaintext and deduplicates socket and later background copies`() = runTest {
        val f = Fixture(this); f.start(); val message = f.dto("push-first")
        assertTrue(f.controller.acceptPush(f.push(message))); runCurrent()
        assertNull(f.controller.banner.value)
        f.socket(message); f.persist(message); runCurrent()
        assertEquals(LastMessagePreview.Text("hello"), f.controller.banner.value?.preview)
        assertEquals(listOf(true to true), f.feedback)
        f.foreground = false
        assertFalse(f.ownership.claim(message.id, NotificationBatchSession("server", "owner", 1)))
        assertFalse(MessagePushHandlerImpl.shouldNotify(true, null, "other"))
    }
    @Test fun `persistence first socket first and duplicate FCM present once`() = runTest {
        val f = Fixture(this); f.start(); val message = f.dto("socket-first"); f.persist(message)
        f.socket(message); assertTrue(f.controller.acceptPush(f.push(message))); f.socket(message)
        assertEquals(1, f.feedback.size)
        advanceTimeBy(3499); runCurrent(); assertNotNull(f.controller.banner.value)
        advanceTimeBy(1); runCurrent(); assertNull(f.controller.banner.value)
    }
    @Test fun `replacement cancels the old expiry and dismissal cancels the final job`() = runTest {
        val f = Fixture(this); f.start()
        val a = f.dto("a"); f.persist(a); f.socket(a)
        advanceTimeBy(2000)
        val b = f.dto("b"); f.persist(b); f.socket(b)
        advanceTimeBy(1500); runCurrent(); assertEquals("b", f.controller.banner.value?.messageId)
        f.controller.dismiss(); advanceTimeBy(5000); runCurrent(); assertNull(f.controller.banner.value)
        assertEquals(2, f.feedback.size)
    }
    @Test fun slowOlderMetadataCannotReplaceANewerBannerAndOpeningItsChatDismissesIt() = runTest {
        val f = Fixture(this); f.start()
        f.heldNameChat = "slow"
        val old = f.dto("old", "slow"); f.persist(old); f.socket(old)
        val fresh = f.dto("fresh"); f.persist(fresh); f.socket(fresh)
        assertEquals("fresh", f.controller.banner.value?.messageId)
        f.metadataReady.complete(Unit); runCurrent()
        assertEquals("fresh", f.controller.banner.value?.messageId)
        f.controller.navigationChatId = "other"
        assertNull(f.controller.banner.value)
        advanceTimeBy(5000); runCurrent(); assertNull(f.controller.banner.value)
    }

    @Test fun `open muted locked own disabled and background messages produce neither banner nor feedback`() = runTest {
        val f = Fixture(this); f.start()
        suspend fun send(id: String, own: Boolean = false) { val dto = f.dto(id).let { if (own) it.copy(senderId = "owner") else it }; f.persist(dto); f.socket(dto) }
        f.open = "other"; send("open"); f.open = null
        f.controller.navigationChatId = "other"; send("picker-covered-open"); f.controller.navigationChatId = null
        f.muted = true; send("muted"); f.muted = false
        f.durableAllowed = false; send("durable-muted"); f.durableAllowed = true
        f.controller.privacyBlocked = true; send("locked"); f.controller.privacyBlocked = false
        send("own", true)
        f.settings = f.settings.copy(messages = false); send("disabled"); f.settings = NotificationSettings()
        f.foreground = false; send("background")
        assertNull(f.controller.banner.value); assertTrue(f.feedback.isEmpty())
    }
    @Test fun `banner feedback toggles are independent of background sound and previews`() = runTest {
        val f = Fixture(this); f.start()
        f.settings = NotificationSettings(messageSound = false, inAppBanners = false, inAppSound = true, inAppVibration = false)
        val a = f.dto("feedback"); f.persist(a); f.socket(a)
        assertNull(f.controller.banner.value); assertEquals(listOf(true to false), f.feedback)
        f.settings = f.settings.copy(inAppBanners = true, inAppSound = false, inAppVibration = true, previews = false)
        val b = f.dto("private"); f.persist(b); f.socket(b)
        assertEquals(LastMessagePreview.Text("New message"), f.controller.banner.value?.preview)
        assertEquals(false to true, f.feedback.last())
    }
    @Test fun `album alerts once while newer content replaces and lock drops pending work`() = runTest {
        val f = Fixture(this); f.start()
        for (id in listOf("album-a", "album-b")) { val dto = f.dto(id); f.persist(dto, "album"); f.socket(dto) }
        assertEquals(listOf(true to true, false to false), f.feedback)
        assertEquals("album-b", f.controller.banner.value?.messageId)
        val pending = f.dto("pending"); f.socket(pending)
        f.controller.privacyBlocked = true; f.persist(pending); runCurrent()
        assertNull(f.controller.banner.value); assertEquals(2, f.feedback.size)
    }
    @Test fun inactiveAccountBannerIsGenericAndKeepsItsValidatedTapTarget() = runTest {
        val f = Fixture(this); f.start()
        val target = NotificationTapTarget("a".repeat(64), "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
            "00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004")
        assertTrue(f.controller.presentInactive(target, "Work account", NotificationSettings()))
        assertEquals(target, f.controller.banner.value?.target)
        assertEquals("Work account", f.controller.banner.value?.senderName)
        assertEquals(LastMessagePreview.Text("New message"), f.controller.banner.value?.preview)
        f.controller.privacyBlocked = true
        assertNull(f.controller.banner.value)
        assertTrue(f.controller.presentInactive(target, "Work account", NotificationSettings()))
        assertNull(f.controller.banner.value)
        f.foreground = false
        assertFalse(f.controller.presentInactive(target, "Work account", NotificationSettings()))
    }

    @Test fun `account and session changes invalidate pending foreground hints and sync alone cannot show a banner`() = runTest {
        val f = Fixture(this); f.start(); val dto = f.dto("pending")
        f.controller.acceptPush(f.push(dto)); runCurrent(); f.epoch++; f.owner = "new-owner"
        f.persist(dto); runCurrent(); assertNull(f.controller.banner.value)
        f.persist(f.dto("sync-only")); runCurrent(); assertNull(f.controller.banner.value)
        assertTrue(f.feedback.isEmpty())
    }
}
