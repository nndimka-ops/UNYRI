package com.messenger.app.data.websocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Last-seen is server evidence; a missing/expired lease means unknown, never offline. */
data class PeerPresence(val online: Boolean, val lastSeenAt: String?, val expiresAt: Long)

class RealtimePresence(private val send: (String, String) -> Boolean,
    private val identity: () -> String,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wallClock: () -> Long = { System.currentTimeMillis() }) {
    private val frames = kotlinx.coroutines.channels.Channel<() -> Unit>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    init { scope.launch { for (frame in frames) frame() } }
    private val _peers = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val peers: StateFlow<Map<String, PeerPresence>> = _peers
    // Historical server timestamps are not an online/offline lease. Keep them
    // separately so an expired lease removes green immediately without making
    // the last confirmed activity disappear while a new snapshot is in flight.
    private val _lastSeen = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastSeen: StateFlow<Map<String, String>> = _lastSeen
    private var historyIdentity = identity()
    private val owners = linkedMapOf<String, Pair<String, List<String>>>()
    private var connected = false
    private var foreground = false
    private var loop: Job? = null
    private var identityAtConnection = ""
    private var revision = 0L
    private var subscriptionRevision = 0L
    private var lastObserved: java.time.Instant? = null

    @Synchronized fun subscribe(owner: String, userIds: List<String>) {
        ensureHistoryOwner()
        owners[owner] = identity() to userIds.distinct().take(64)
        sendSubscription()
    }
    @Synchronized fun unsubscribe(owner: String) { owners.remove(owner); sendSubscription() }
    @Synchronized fun current(userId: String?): PeerPresence? =
        if (connected && foreground && identityAtConnection == identity() && userId != null)
            _peers.value[userId]?.takeIf { it.expiresAt > clock() } else null
    @Synchronized fun lastSeenAt(userId: String?): String? =
        if (historyIdentity == identity() && userId != null) _lastSeen.value[userId] else null

    private fun ensureHistoryOwner() {
        val current = identity()
        if (historyIdentity != current) {
            historyIdentity = current
            _lastSeen.value = emptyMap()
        }
    }
    private fun ids(): List<String> = owners.entries.filter { it.value.first == identity() }.sortedBy { if (it.key.startsWith("chat:")) 0 else 1 }.flatMap { it.value.second }.distinct().take(64)
    private fun emit(frame: String, subscription: Long? = null) {
        val owner = identity(); val ticket = revision
        frames.trySend {
            val valid = synchronized(this@RealtimePresence) { owner == identity() && ticket == revision && (subscription == null || subscription == subscriptionRevision) }
            if (valid) send(frame, owner)
        }
    }
    private fun sendSubscription() {
        val ticket = ++subscriptionRevision
        if (connected && foreground) emit(buildJsonObject { put("type", "presence.subscribe"); put("user_ids", JsonArray(ids().map(::JsonPrimitive))) }.toString(), ticket)
    }
    private fun heartbeat(active: Boolean) = emit(buildJsonObject { put("type", "presence.heartbeat"); put("active", active) }.toString())
    @Synchronized fun setForeground(active: Boolean) {
        ensureHistoryOwner()
        revision++
        foreground = active
        loop?.cancel(); loop = null
        if (!active) { if (connected) heartbeat(false); _peers.value = emptyMap(); return }
        restartLoop()
    }
    @Synchronized fun setConnected(active: Boolean) {
        ensureHistoryOwner()
        revision++
        connected = active
        loop?.cancel(); loop = null
        _peers.value = emptyMap()
        if (active) { if (identityAtConnection != identity()) lastObserved = null; identityAtConnection = identity(); owners.entries.removeAll { it.value.first != identityAtConnection }; restartLoop() }
    }
    private fun restartLoop() {
        if (!connected || !foreground) return
        heartbeat(true); sendSubscription()
        loop = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                synchronized(this@RealtimePresence) {
                    if (!connected || !foreground || identityAtConnection != identity()) { _peers.value = emptyMap(); return@launch }
                    _peers.value = _peers.value.filterValues { it.expiresAt > clock() }
                    if (++ticks % 4 == 0) heartbeat(true)
                }
            }
        }
    }
    @Synchronized fun accept(frame: JsonObject) {
        val snapshotOwner = identityAtConnection
        if (!connected || !foreground || snapshotOwner != identity()) return
        val ttl = frame["valid_for_ms"]?.jsonPrimitive?.longOrNull?.coerceIn(0, 5000) ?: return
        // Reject invalid timestamps instead of turning malformed events into offline labels.
        val observed = runCatching { java.time.Instant.parse(frame["observed_at"]?.jsonPrimitive?.content) }.getOrNull() ?: return
        if (lastObserved?.let { observed <= it } == true) return
        val age = runCatching { Math.subtractExact(wallClock(), observed.toEpochMilli()) }.getOrNull() ?: return
        // Fail closed on a clock far behind the server; never grant a future-dated lease.
        if (age < -5000) return
        val remaining = ttl - age.coerceAtLeast(0)
        if (remaining <= 0) return
        val watched = ids().toSet()
        val values = frame["items"]?.jsonArray ?: return
        val updates = values.take(64).mapNotNull { element ->
            val item = element.jsonObject
            val id = item["user_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (id !in watched) return@mapNotNull null
            val online = item["online"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            val last = item["last_seen_at"]?.jsonPrimitive?.contentOrNull?.takeIf { runCatching { java.time.Instant.parse(it) }.isSuccess }
            id to PeerPresence(online, last, clock() + remaining)
        }.toMap()
        // Auth may change outside this monitor while a frame is being parsed.
        // Never stamp old-socket evidence with the newly logged-in identity.
        if (snapshotOwner != identity() || !connected || !foreground) return
        if (historyIdentity != snapshotOwner) {
            historyIdentity = snapshotOwner
            _lastSeen.value = emptyMap()
        }
        lastObserved = observed
        val history = LinkedHashMap(_lastSeen.value)
        // This is a full snapshot of the subscribed set. Omission can mean a
        // block/privacy change; never retain a hidden peer's old last-seen.
        for (id in watched) {
            history.remove(id)
            updates[id]?.lastSeenAt?.let { history[id] = it }
        }
        _lastSeen.value = history.entries.toList().takeLast(128).associate { it.key to it.value }
        _peers.value = updates.filterValues { it.expiresAt > clock() }
    }
}
