package com.messenger.app.data.directtransfer

import com.messenger.app.data.local.chat.ChatWorkspaceDao
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class DirectFilePreviewStoreTest {
    @Test fun `reads outgoing history and old received records without a live coordinator or JSON SQL functions`() = runTest {
        val outgoing = DirectPreviewFixture.offer()
        val history = outgoing.copy(offerClientMessageId = "10000000-0000-4000-8000-000000000007")
        val received = outgoing.copy(offerClientMessageId = "10000000-0000-4000-8000-000000000008")
        val values = mutableListOf(
            ChatWorkspaceValue("chat", "direct_offer:${outgoing.offerClientMessageId}", outgoing.stored()),
            ChatWorkspaceValue("chat", "direct_history:${history.offerClientMessageId}", history.stored()),
            ChatWorkspaceValue("chat", "direct_transfer:${received.transferId}", "{\"offer\":${received.stored()},\"completed\":true,\"unknown_state\":{}}"),
            ChatWorkspaceValue("other", "direct_offer:wrong-chat", outgoing.stored()),
            ChatWorkspaceValue("chat", "direct_offer:wrong-id", outgoing.stored()),
        )
        repeat(70) { values += ChatWorkspaceValue("chat", "direct_transfer:%03d".format(it), "{broken") }
        var pages = 0
        val dao = Proxy.newProxyInstance(ChatWorkspaceDao::class.java.classLoader, arrayOf(ChatWorkspaceDao::class.java)) { _, method, args ->
            when (method.name) {
                "get" -> values.firstOrNull { it.chatId == args[0] && it.name == args[1] }?.value
                "directPreviewPage" -> {
                    pages++
                    values.filter { it.chatId == args[0] && it.name.startsWith("direct_transfer:") && it.name > args[1] as String }.sortedBy { it.name }.take(64)
                }
                else -> error("Unexpected presentation write/read: ${method.name}")
            }
        } as ChatWorkspaceDao
        val expected = setOf(outgoing.offerClientMessageId, history.offerClientMessageId, received.offerClientMessageId)
        val result = dao.previewOffers("chat", expected + "missing" + "wrong-id")
        assertEquals(expected, result.keys)
        assertEquals(2, pages)
        assertTrue(result.values.all { DirectFileOffer.decodeStored(it)?.filename == "b023-report.pdf" })
        assertTrue(dao.previewOffers("other", setOf(received.offerClientMessageId)).isEmpty())
    }
}
