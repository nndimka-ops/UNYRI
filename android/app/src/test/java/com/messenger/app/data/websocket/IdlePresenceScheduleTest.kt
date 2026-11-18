package com.messenger.app.data.websocket

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IdlePresenceScheduleTest {
    @Test fun renewalsKeepExistingExpiryButEarlierDeadlinesAndClearingPreemptIt() {
        assertTrue(PresenceSchedule.replaceExpiry(null, 5_000))
        assertFalse(PresenceSchedule.replaceExpiry(5_000, 7_000))
        assertFalse(PresenceSchedule.replaceExpiry(5_000, 5_000))
        assertTrue(PresenceSchedule.replaceExpiry(5_000, 3_000))
        assertTrue(PresenceSchedule.replaceExpiry(5_000, null))
    }
    @Test fun `heartbeat slows to 25 s only after the server grants the long lease`() = runTest {
        val sent = mutableListOf<JsonObject>()
        val wall = 1_800_000_000_000L
        val store = RealtimePresence({ frame, _ -> sent += Json.parseToJsonElement(frame).jsonObject; true },
            { "owner" }, { testScheduler.currentTime }, backgroundScope, { wall })
        store.subscribe("chat:test", listOf("peer")); store.setForeground(true); store.setConnected(true); runCurrent()
        assertEquals(60_000L, sent.last { it["type"]?.jsonPrimitive?.content == "presence.subscribe" }["lease_ms"]!!.jsonPrimitive.long)
        fun beats() = sent.count { it["type"]?.jsonPrimitive?.content == "presence.heartbeat" }
        val before = beats(); advanceTimeBy(10_000); runCurrent()
        assertTrue(beats() - before >= 4, "an older server (no long lease yet) keeps the 2 s renewal")
        store.accept(buildJsonObject {
            put("type", "presence.snapshot"); put("valid_for_ms", 60_000)
            put("observed_at", java.time.Instant.ofEpochMilli(wall).toString())
            put("items", buildJsonArray { add(buildJsonObject { put("user_id", "peer"); put("online", true) }) })
        })
        advanceTimeBy(2_500); runCurrent() // the in-flight 2 s wait ends once
        val slow = beats(); advanceTimeBy(24_000); runCurrent()
        assertEquals(slow, beats(), "no heartbeat inside 25 s once the long lease is granted")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(slow + 1, beats())
        assertEquals(true, store.current("peer")?.online, "a 60 s lease is kept, not cut to 5 s")
    }
    @Test fun `pause clears server watches even when owners unsubscribe before queued frames drain`() = runTest {
        val sent = mutableListOf<JsonObject>()
        val store = RealtimePresence({ frame, _ -> sent += Json.parseToJsonElement(frame).jsonObject; true },
            { "owner" }, { testScheduler.currentTime }, backgroundScope)
        store.subscribe("chat:test", listOf("peer")); store.setForeground(true); store.setConnected(true); runCurrent()
        store.setForeground(false); store.unsubscribe("chat:test"); runCurrent()
        assertTrue(sent.last { it["type"]?.jsonPrimitive?.content == "presence.subscribe" }["user_ids"]!!.jsonArray.isEmpty())
        val count = sent.size
        advanceTimeBy(120_000); runCurrent(); assertEquals(count, sent.size)
        store.subscribe("chat:test", listOf("peer")); store.setForeground(true); runCurrent()
        assertEquals("peer", sent.last { it["type"]?.jsonPrimitive?.content == "presence.subscribe" }["user_ids"]!!.jsonArray.single().jsonPrimitive.content)
    }
    @Test fun `only the earliest real lease owns an expiry wakeup`() {
        assertNull(PresenceSchedule.expiryDelay(emptyList(), 100))
        assertEquals(25L, PresenceSchedule.expiryDelay(listOf(PeerPresence(true, null, 200), PeerPresence(false, null, 125)), 100))
        assertEquals(0L, PresenceSchedule.expiryDelay(listOf(PeerPresence(true, null, 99)), 100))
        assertEquals(2_000L, PresenceSchedule.heartbeatDelay(true, true, true), "older server: 2 s renewal")
        assertEquals(25_000L, PresenceSchedule.heartbeatDelay(true, true, true, longLease = true), "granted long lease: 25 s")
        assertNull(PresenceSchedule.heartbeatDelay(false, true, true))
        assertNull(PresenceSchedule.heartbeatDelay(true, false, true))
        assertNull(PresenceSchedule.heartbeatDelay(true, true, false))
    }

    @Test fun `renewals do not emit UI changes and replacement deadline wins`() = runTest {
        val base = java.time.Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
        val store = RealtimePresence({ _, _ -> true }, { "owner" }, { testScheduler.currentTime }, backgroundScope, { base + testScheduler.currentTime })
        val changes = mutableListOf<Map<String, PeerPresence>>()
        val onlineChanges = mutableListOf<Map<String, Boolean>>()
        backgroundScope.launch { store.presentation.collect { changes += it } }
        backgroundScope.launch { store.online.collect { onlineChanges += it } }
        store.subscribe("chat:test", listOf("peer")); store.setForeground(true); store.setConnected(true)
        fun frame() = buildJsonObject {
            put("observed_at", java.time.Instant.ofEpochMilli(base + testScheduler.currentTime).toString())
            put("valid_for_ms", 5_000)
            put("items", buildJsonArray { add(buildJsonObject {
                put("user_id", "peer"); put("online", true)
                put("last_seen_at", java.time.Instant.ofEpochMilli(base + testScheduler.currentTime).toString())
            }) })
        }
        runCurrent(); store.accept(frame()); runCurrent()
        advanceTimeBy(2_000); store.accept(frame()); runCurrent()
        assertEquals(2, changes.size) // empty, then online; expiry/last-seen renewal is invisible.
        assertEquals(listOf(emptyMap(), mapOf("peer" to true)), onlineChanges)
        advanceTimeBy(3_000); runCurrent(); assertNotNull(store.current("peer"))
        advanceTimeBy(2_000); runCurrent(); assertNull(store.current("peer")); assertEquals(3, changes.size)
        assertEquals(emptyMap(), onlineChanges.last())
        store.setForeground(false); runCurrent()
        val stopped = changes.size
        advanceTimeBy(120_000); runCurrent(); assertEquals(stopped, changes.size)
    }

    @Test fun identicalViewportSubscriptionsDoNotSendRepeatedFrames() = runTest {
        val sent = mutableListOf<String>()
        val store = RealtimePresence({ frame, _ -> sent += frame; true }, { "owner" },
            { testScheduler.currentTime }, backgroundScope)
        store.subscribe("list", listOf("peer")); store.setForeground(true); store.setConnected(true); runCurrent()
        val initial = sent.size
        repeat(20) { store.subscribe("list", listOf("peer", "peer")) }
        store.unsubscribe("already-absent"); runCurrent()
        assertEquals(initial, sent.size)
        store.setForeground(false); runCurrent()
        val stopped = sent.size
        advanceTimeBy(120_000); runCurrent()
        assertEquals(stopped, sent.size)
    }
}
