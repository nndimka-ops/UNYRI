package com.messenger.app.data.attachment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageAuthenticationTest {
    @Test fun `bound lifecycle rejects substituted context`() {
        val content = MessageContentCodec.decode(MessageContentCodec.authenticate(
            MessageContentCodec.encodeDelete(), "chat", "event", "delete", "target", "original-client"))
        assertTrue(MessageContentCodec.authenticates(content, "chat", "event", "delete", "target"))
        for ((chat, client, operation, target) in listOf(
            listOf("other", "event", "delete", "target"),
            listOf("chat", "other", "delete", "target"),
            listOf("chat", "event", "edit", "target"),
            listOf("chat", "event", "delete", "other"))) {
            assertFalse(MessageContentCodec.authenticates(content, chat, client, operation, target))
        }
        assertFalse(MessageContentCodec.authenticates(content.copy(authentication = content.authentication!!.copy(version = 2)),
            "chat", "event", "delete", "target"))
    }

    @Test fun `Swift wire payload is accepted and malformed authentication never becomes legacy text`() {
        val swift = """{"kind":"delete","authentication":{"version":1,"chat_id":"chat","client_message_id":"event","operation":"delete","target_message_id":"target","target_client_message_id":"original-client"}}"""
        assertTrue(MessageContentCodec.authenticates(MessageContentCodec.decode(swift.toByteArray()), "chat", "event", "delete", "target"))
        for (raw in listOf("""{"kind":"text","authentication":null}""", """{"kind":"text","authentication":{"version":"bad"}}""")) {
            assertFalse(MessageContentCodec.authenticates(MessageContentCodec.decode(raw.toByteArray()), "chat", "event", "content", null))
        }
        val legacy = MessageContentCodec.decode("old message".toByteArray())
        assertTrue(MessageContentCodec.authenticates(legacy, "chat", "event", "content", null))
        assertFalse(MessageContentCodec.authenticates(legacy, "chat", "event", "edit", "target"))
    }
}
