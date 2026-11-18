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
class ForegroundBannerParityTest {
    @Test fun `persisted offer metadata supplies banner filenames and captions for both fallback spellings`() = runTest {
        val offer = com.messenger.app.data.directtransfer.DirectPreviewFixture.offer()
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val dao = InMemoryMessageDao()
        val controller = ForegroundMessageBannerController(backgroundScope, events, dao, InMemoryAttachmentDao(),
            { true }, { null }, { false }, { "owner" }, { "Peer" },
            resolveDirectOffer = { _, client -> if (client.endsWith("broken")) "{invalid" else offer.stored() })
        controller.start(); runCurrent()
        for ((index, raw) in listOf("Direct file transfer", "Direct file transfer\n\nCaption", "Передача файла напрямую", "Передача файла напрямую\n\nПідпис").withIndex()) {
            val message = dto("offer-$index")
            dao.upsert(row(message).copy(text = raw))
            events.emit(IncomingSocketEvent.NewMessage(message)); runCurrent()
            assertEquals(LastMessagePreview.File("b023-report.pdf", com.messenger.app.data.directtransfer.DirectFileOffer.caption(raw)), controller.banner.value?.preview)
        }
        val broken = dto("broken")
        dao.upsert(row(broken).copy(text = "Direct file transfer\n\nCaption"))
        events.emit(IncomingSocketEvent.NewMessage(broken)); runCurrent()
        assertEquals(LastMessagePreview.File(null), controller.banner.value?.preview)
    }

    @Test fun `lifecycle events claim no alert and never wait for a content row`() = runTest {
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val ownership = MessageAlertOwnership()
        val session = NotificationBatchSession("server", "owner", 1)
        var lookups = 0
        var foreground = false
        val dao = object : MessageDao by InMemoryMessageDao() {
            override fun observeByClientMessageId(clientMessageId: String): kotlinx.coroutines.flow.Flow<MessageEntity?> {
                lookups++
                return MutableStateFlow(null)
            }
        }
        val controller = ForegroundMessageBannerController(backgroundScope, events, dao, InMemoryAttachmentDao(),
            { foreground }, { null }, { false }, { "owner" }, { "Peer" },
            sessionEpoch = { 1 }, ownership = ownership, currentServer = { "server" })
        controller.start(); runCurrent()
        for (visible in listOf(false, true)) {
            foreground = visible
            for (kind in listOf("edit", "delete", "reaction")) {
                val message = dto("$visible-$kind").copy(kind = kind)
                events.emit(IncomingSocketEvent.NewMessage(message)); runCurrent()
                assertNull(controller.banner.value)
                assertFalse(ownership.claim(message.id, session))
                assertEquals(0, lookups)
            }
        }
    }
    private fun dto(id: String, chat: String = "chat") = MessageDto(id, chat, "client-$id", "peer", "peer-device", 3,
        "2026-11-12T12:00:00Z", 1, "encrypted")
    private fun row(message: MessageDto) = MessageEntity(message.clientMessageId, message.id, message.chatId,
        message.senderId, "private text", message.sequence, message.createdAt, "delivered")
    @Test fun `decoration failures never discard a persisted message alert`() = runTest {
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val message = dto("undecorated")
        val dao = InMemoryMessageDao(); dao.upsert(row(message))
        val controller = ForegroundMessageBannerController(backgroundScope, events, dao, InMemoryAttachmentDao(),
            { true }, { null }, { false }, { "owner" }, { error("Name unavailable") },
            resolveAvatar = { error("Avatar unavailable") })
        controller.start(); runCurrent()
        events.emit(IncomingSocketEvent.NewMessage(message)); runCurrent()
        assertEquals(message.chatId, controller.banner.value?.chatId)
        assertNull(controller.banner.value?.senderName)
        assertNull(controller.banner.value?.avatar)
    }
    @Test fun `missing first row never blocks another chat and previews respect settings`() = runTest {
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val missing = dto("missing")
        val ready = dto("ready", "other-chat")
        val rows = mapOf(missing.clientMessageId to MutableStateFlow<MessageEntity?>(null),
            ready.clientMessageId to MutableStateFlow<MessageEntity?>(row(ready)))
        val dao = object : MessageDao by InMemoryMessageDao() {
            override fun observeByClientMessageId(clientMessageId: String) = rows.getValue(clientMessageId)
        }
        val sounds = mutableListOf<Boolean>()
        val controller = ForegroundMessageBannerController(backgroundScope, events, dao, InMemoryAttachmentDao(),
            { true }, { "open-chat" }, { false }, { "owner" }, { "Peer" },
            settings = { NotificationSettings(previews = false) }, genericPreview = { "Generic localized message" },
            onPresented = { sounds += it })
        controller.start(); runCurrent()
        events.emit(IncomingSocketEvent.NewMessage(missing)); runCurrent()
        events.emit(IncomingSocketEvent.NewMessage(ready)); runCurrent()
        assertEquals("other-chat", controller.banner.value?.chatId)
        assertEquals(LastMessagePreview.Text("Generic localized message"), controller.banner.value?.preview)
        assertEquals(listOf(true), sounds)
        controller.invalidate()
        rows.getValue(missing.clientMessageId).value = row(missing)
        runCurrent(); assertNull(controller.banner.value)
    }
    @Test fun `push owned message has no socket banner and background socket claims nothing`() = runTest {
        val events = MutableSharedFlow<IncomingSocketEvent>()
        val message = dto("message")
        val dao = InMemoryMessageDao(); dao.upsert(row(message))
        val ownership = MessageAlertOwnership()
        var foreground = false
        val session = NotificationBatchSession("server", "owner", 1)
        val controller = ForegroundMessageBannerController(backgroundScope, events, dao, InMemoryAttachmentDao(),
            { foreground }, { null }, { false }, { "owner" }, { "Peer" },
            sessionEpoch = { 1 }, ownership = ownership, currentServer = { "server" })
        controller.start(); runCurrent()
        events.emit(IncomingSocketEvent.NewMessage(message)); runCurrent()
        assertTrue(ownership.claim(message.id, session))
        foreground = true
        events.emit(IncomingSocketEvent.NewMessage(message)); runCurrent()
        assertNull(controller.banner.value)
    }
}
