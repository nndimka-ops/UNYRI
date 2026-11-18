package com.messenger.app.data.websocket

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimePresenceTest {
    private val base = java.time.Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private var sequence = 0L
    private fun snapshot(online: Boolean = true, items: Boolean = true, observed: Long = base + sequence++) = Json.parseToJsonElement("""{"observed_at":"${java.time.Instant.ofEpochMilli(observed)}","valid_for_ms":5000,"items":${if(items) "[{\"user_id\":\"peer\",\"online\":$online,\"last_seen_at\":\"2026-10-10T11:59:00Z\"}]" else "[]"}}""").jsonObject
    @Test fun `leases expire to unknown and disconnect clears immediately`() = runTest {
        val store = RealtimePresence({ _, _ -> true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { base })
        store.subscribe("chat:a", listOf("peer")); store.setForeground(true); store.setConnected(true)
        store.accept(snapshot()); assertEquals(true, store.peers.value["peer"]?.online)
        advanceTimeBy(5001); runCurrent(); assertNull(store.peers.value["peer"])
        store.accept(snapshot(false)); assertEquals(false, store.peers.value["peer"]?.online)
        store.accept(snapshot(items=false)); assertNull(store.peers.value["peer"])
        store.accept(snapshot()); store.setConnected(false); assertTrue(store.peers.value.isEmpty())
    }
    @Test fun `heartbeat is foreground only and server aggregate is authoritative`() = runTest {
        val frames = mutableListOf<String>()
        val store = RealtimePresence({ frame, _ -> frames.add(frame); true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { base })
        store.subscribe("directory:1", listOf("peer")); store.setConnected(true); runCurrent()
        assertTrue(frames.isEmpty())
        store.setForeground(true); runCurrent(); advanceTimeBy(2001); runCurrent()
        assertEquals(2, frames.count { it.contains("heartbeat") && it.contains("true") })
        // One peer can have several phones: only server aggregate determines online.
        store.accept(snapshot(false)); store.accept(snapshot(true)); assertTrue(store.peers.value["peer"]!!.online)
        store.setForeground(false); runCurrent(); val count = frames.size
        advanceTimeBy(6000); runCurrent(); assertEquals(count, frames.size)
        assertTrue(frames.last().contains("false")); assertTrue(store.peers.value.isEmpty())
    }
    @Test fun `owner swap rejects queued frames and old snapshot subscriptions`() = runTest {
        var owner = "serverA|accountA"; val frames = mutableListOf<String>()
        val store = RealtimePresence({ frame, _ -> frames.add(frame); true }, { owner }, { testScheduler.currentTime }, backgroundScope, { base })
        store.subscribe("chat:a", listOf("peer")); store.setForeground(true); store.setConnected(true)
        owner = "serverB|accountB"; runCurrent(); assertTrue(frames.isEmpty())
        store.accept(snapshot()); assertTrue(store.peers.value.isEmpty())
        store.setConnected(false); store.setConnected(true); store.accept(snapshot())
        assertTrue(store.peers.value.isEmpty())
    }
    @Test fun `duplicates delayed observations and wall skew cannot renew online lease`() = runTest {
        var wall = base
        val store = RealtimePresence({ _, _ -> true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { wall })
        store.subscribe("chat:a", listOf("peer")); store.setForeground(true); store.setConnected(true)
        store.accept(snapshot(observed = base)); val deadline = store.peers.value["peer"]!!.expiresAt
        advanceTimeBy(2000); runCurrent(); wall += 2000
        store.accept(snapshot(observed = base)); assertEquals(deadline, store.peers.value["peer"]!!.expiresAt)
        store.accept(snapshot(observed = base - 1)); assertEquals(deadline, store.peers.value["peer"]!!.expiresAt)
        // Transit time reduces TTL: one second newer evidence arrives two seconds later.
        store.accept(snapshot(observed = base + 1000)); assertEquals(6000L, store.peers.value["peer"]!!.expiresAt)
        store.setForeground(false); store.setForeground(true)
        wall = base + 9000
        store.accept(snapshot(observed = base + 2000)); assertTrue(store.peers.value.isEmpty())
        wall = base - 10000
        store.accept(snapshot(observed = base + 3000)); assertTrue(store.peers.value.isEmpty())
        wall = base + 3000
        store.accept(snapshot(observed = base + 3000)); assertTrue(store.peers.value["peer"]!!.online)
    }

    @Test fun `expired online lease keeps only last confirmed server activity`() = runTest {
        val store = RealtimePresence({ _, _ -> true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { base })
        store.subscribe("chat:a", listOf("peer")); store.setForeground(true); store.setConnected(true)
        store.accept(snapshot())
        val confirmed = "2026-10-10T11:59:00Z"
        assertEquals(confirmed, store.lastSeenAt("peer"))
        advanceTimeBy(5001); runCurrent()
        assertNull(store.current("peer"))
        assertNull(store.peers.value["peer"])
        assertEquals(confirmed, store.lastSeenAt("peer"))
        store.setConnected(false)
        assertNull(store.current("peer"))
        assertEquals(confirmed, store.lastSeenAt("peer"))
    }

    @Test fun `authoritative omission and account swap revoke historical activity`() = runTest {
        var owner = "serverA|userA"
        val store = RealtimePresence({ _, _ -> true }, { owner }, { testScheduler.currentTime }, backgroundScope, { base })
        store.subscribe("chat:a", listOf("peer")); store.setForeground(true); store.setConnected(true)
        store.accept(snapshot(false)); assertNotNull(store.lastSeenAt("peer"))
        store.accept(snapshot(items = false))
        assertNull(store.lastSeenAt("peer"))
        store.accept(snapshot(false)); assertNotNull(store.lastSeenAt("peer"))
        // No lifecycle callback has arrived yet: accessors still reject old-owner evidence.
        owner = "serverB|userB"
        assertNull(store.current("peer")); assertNull(store.lastSeenAt("peer"))
        store.setConnected(false); store.setConnected(true)
        assertTrue(store.lastSeen.value.isEmpty())
    }

    @Test fun `historical timestamps stay bounded while active chat retains subscription priority`() = runTest {
        val frames = mutableListOf<String>()
        val store = RealtimePresence({ frame, _ -> frames.add(frame); true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { base })
        store.setForeground(true); store.setConnected(true)
        repeat(3) { page ->
            val ids = (page * 64 until (page + 1) * 64).map { "peer$it" }
            store.subscribe("chats:list", ids)
            val items = JsonArray(ids.map { id -> buildJsonObject {
                put("user_id", id); put("online", false); put("last_seen_at", "2026-10-10T11:59:00Z")
            } })
            store.accept(buildJsonObject {
                put("observed_at", java.time.Instant.ofEpochMilli(base + page).toString())
                put("valid_for_ms", 5000); put("items", items)
            })
        }
        assertEquals(128, store.lastSeen.value.size)
        assertNull(store.lastSeenAt("peer0")); assertNotNull(store.lastSeenAt("peer191"))
        store.subscribe("chat:active", listOf("current-peer")); runCurrent()
        val subscription = frames.map { Json.parseToJsonElement(it).jsonObject }
            .last { it["type"]?.jsonPrimitive?.content == "presence.subscribe" }
        val ids = subscription["user_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(64, ids.size); assertEquals("current-peer", ids.first())
    }

    @Test fun `viewport return retains an original lease without renewal and omission revokes it`() = runTest {
        val store = RealtimePresence({ _, _ -> true }, { "A" }, { testScheduler.currentTime }, backgroundScope, { base + testScheduler.currentTime })
        store.subscribe("contacts:list", listOf("peer")); store.setForeground(true); store.setConnected(true)
        store.accept(snapshot(observed = base))
        val deadline = store.peers.value.getValue("peer").expiresAt
        advanceTimeBy(1000); runCurrent()
        store.subscribe("contacts:list", emptyList())
        store.accept(snapshot(items = false, observed = base + 1000))
        assertEquals(deadline, store.peers.value.getValue("peer").expiresAt)
        store.subscribe("contacts:list", listOf("peer"))
        assertTrue(store.current("peer")!!.online)
        advanceTimeBy(4001); runCurrent()
        assertNull(store.current("peer")); assertTrue(store.peers.value.isEmpty())
        store.accept(snapshot(observed = base + 5001))
        store.accept(snapshot(items = false, observed = base + 5002))
        assertNull(store.current("peer")); assertNull(store.lastSeenAt("peer"))
    }

}
