package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import org.junit.Assert.*
import org.junit.Test

class CallActivityPolicyTest {
    private fun call() = CallDto("id", "peer", "owner", "audio", "ended", createdAt = "2026-10-10T12:00:00Z", chatId = "canonical", chatVisible = true, unread = true)
    @Test fun `only explicit eligible current owner call promotes canonical chat`() {
        assertTrue(CallActivityPolicy.eligible(call(), "owner"))
        assertFalse(CallActivityPolicy.eligible(call(), "different"))
        assertFalse(CallActivityPolicy.eligible(call().copy(chatVisible = false), "owner"))
        assertFalse(CallActivityPolicy.eligible(call().copy(chatVisible = null), "owner"))
        assertFalse(CallActivityPolicy.eligible(call().copy(chatId = null), "owner"))
    }
    @Test fun `recreated chat and cleared calls cannot inherit old inline events`() {
        assertTrue(CallActivityPolicy.belongsToChat(call(), "canonical", "peer"))
        assertFalse(CallActivityPolicy.belongsToChat(call(), "newchat", "peer"))
        assertFalse(CallActivityPolicy.belongsToChat(call().copy(chatVisible = false), "canonical", "peer"))
        assertTrue(CallActivityPolicy.belongsToChat(call().copy(chatId = null, chatVisible = null), "legacy", "peer"))
        assertFalse(CallActivityPolicy.belongsToChat(call().copy(chatId = null, chatVisible = true), "legacy", "peer"))
    }
    @Test fun `durable pending acknowledgement wins over replayed server unread and duplicates`() {
        val pending = setOf("id")
        val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.SetSerializer(kotlinx.serialization.serializer<String>()), pending)
        val restored = kotlinx.serialization.json.Json.decodeFromString<Set<String>>(encoded)
        val result = CallActivityPolicy.suppressPending(listOf(call(), call().copy(id = "another")), restored)
        assertFalse(result[0].unread); assertTrue(result[1].unread)
        assertEquals(result, CallActivityPolicy.suppressPending(result, restored))
    }
    @Test fun `personal clear invalidates all cached pages before latest page merge`() {
        val cutoff = java.time.Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        val cached = (0 until 80).map { call().copy(id = "$it") }
        val firstPage = listOf(call().copy(id = "new", createdAt = "2026-10-10T12:00:01Z"))
        val projected = CallActivityPolicy.applyClears(cached + firstPage, mapOf("canonical" to cutoff))
        assertEquals(80, projected.count { it.chatVisible == false && !it.unread })
        assertEquals(listOf("new"), projected.filter { CallActivityPolicy.eligible(it, "owner") }.map { it.id })
        assertEquals(cached, CallActivityPolicy.applyClears(cached, emptyMap())) // legacy missing field
        assertTrue(CallActivityPolicy.applyClears(listOf(call().copy(chatId = "different")), mapOf("canonical" to cutoff)).single().unread)
    }

}
