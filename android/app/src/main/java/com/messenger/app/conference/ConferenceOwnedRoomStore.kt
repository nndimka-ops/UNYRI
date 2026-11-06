package com.messenger.app.conference

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A login nonce survives token refresh/process restart, but changes on a new login. */
@Serializable
data class ConferenceRoomOwner(val origin: String, val userId: String, val deviceId: String, val loginNonce: String) {
    override fun toString() = "ConferenceRoomOwner([redacted])"
}

/**
 * Small account/device/login-bound vault. Its adapter is the existing Keystore-
 * encrypted token store. No password, participant token, SDK state or media is
 * persisted. A normal Leave keeps this capability until the room expires;
 * logout and End remove it. The adapter rechecks the owner atomically on write.
 */
class ConferenceOwnedRoomStore(
    private val current: () -> ConferenceRoomOwner?,
    private val read: (ConferenceRoomOwner) -> String?,
    private val write: (ConferenceRoomOwner, String?) -> Boolean,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    @Serializable private class Entry(val room: String, val expires: Long, val invitation: String)
    @Serializable private class Record(val owner: ConferenceRoomOwner, val version: Int = 1, val entries: List<Entry>)
    private val json = Json { ignoreUnknownKeys = false }
    private var owner: ConferenceRoomOwner? = null
    private var entries = emptyList<Entry>()
    private val retired = linkedMapOf<String, Long>()

    fun currentOwner(): ConferenceRoomOwner? = current()

    @Synchronized
    private fun load(expected: ConferenceRoomOwner): Boolean {
        if (current() != expected) { owner = null; entries = emptyList(); retired.clear(); return false }
        if (owner == expected) return true
        retired.clear()
        val raw = read(expected)
        entries = if (raw == null || raw.length > 12_288) emptyList() else runCatching {
            val value = json.decodeFromString(Record.serializer(), raw)
            require(value.version == 1 && value.owner == expected && value.entries.size <= 3)
            require(value.entries.map { it.room }.distinct().size == value.entries.size)
            value.entries.filter { entry ->
                entry.expires > now() && entry.expires <= now() + 86_430 && runCatching {
                    val invite = ConferenceInvite.parseForServer(entry.invitation, expected.origin)
                    invite.roomId == entry.room
                }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())
        if (current() != expected) { owner = null; entries = emptyList(); return false }
        owner = expected
        return true
    }

    @Synchronized
    fun remember(expected: ConferenceRoomOwner, invite: ConferenceInvite, expires: String, ownerUserId: String): Boolean {
        if (expected.userId != ownerUserId || expected.origin != invite.server || !load(expected)) return false
        retired.entries.removeAll { it.value <= now() }
        if (retired.containsKey(invite.roomId)) return false
        val deadline = runCatching { Instant.parse(expires).epochSecond }.getOrNull() ?: return false
        if (deadline <= now() || deadline > now() + 86_430) return false
        val link = runCatching { invite.link().also { ConferenceInvite.parseForServer(it, expected.origin) } }.getOrNull() ?: return false
        val updated = (entries.filter { it.room != invite.roomId && it.expires > now() } + Entry(invite.roomId, deadline, link)).takeLast(3)
        return save(expected, updated)
    }

    @Synchronized
    fun invitation(room: ConferenceDto): ConferenceInvite? {
        val expected = current() ?: run { owner = null; entries = emptyList(); return null }
        if (room.ownerUserId != expected.userId || !load(expected)) return null
        val deadline = runCatching { Instant.parse(room.expiresAt).epochSecond }.getOrNull() ?: return null
        if (room.state != "active" || deadline <= now()) { remove(room.id); return null }
        val entry = entries.firstOrNull { it.room == room.id && it.expires > now() && deadline <= it.expires } ?: return null
        return runCatching { ConferenceInvite.parseForServer(entry.invitation, expected.origin) }.getOrNull()
    }

    @Synchronized
    fun remove(room: String) {
        val expected = current() ?: run { owner = null; entries = emptyList(); return }
        if (!load(expected)) return
        retired.entries.removeAll { it.value <= now() }
        retired[room] = now() + 86_430
        while (retired.size > 128) retired.remove(retired.keys.first())
        val updated = entries.filter { it.room != room && it.expires > now() }
        if (updated.size != entries.size) save(expected, updated)
    }

    @Synchronized
    fun forgetMemory() { owner = null; entries = emptyList(); retired.clear() }

    private fun save(expected: ConferenceRoomOwner, updated: List<Entry>): Boolean {
        if (current() != expected) return false
        val encoded = updated.takeIf { it.isNotEmpty() }?.let { json.encodeToString(Record.serializer(), Record(owner = expected, entries = it)) }
        val saved = runCatching { write(expected, encoded) }.getOrDefault(false)
        if (!saved || current() != expected) { owner = null; entries = emptyList(); return false }
        owner = expected; entries = updated
        return true
    }
}
