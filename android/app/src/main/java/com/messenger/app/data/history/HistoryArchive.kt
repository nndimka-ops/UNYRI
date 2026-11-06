package com.messenger.app.data.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import java.util.Base64
import java.util.UUID

/** Portable final-state history, never a database dump or an outbox/Signal export.
 * Plaintext must stay inside an authenticated encrypted container. No filesystem
 * paths are accepted; media IDs resolve through a separately verified manifest.
 */
@Serializable
data class HistoryArchive(
    val format: Int = 1,
    val server: String,
    val owner: String,
    val snapshot: String,
    val messages: List<HistoryArchiveMessage>,
    val clearedThrough: Long = 0,
    val chatClears: Map<String, Long> = emptyMap(),
    val deletedChats: Set<String> = emptySet(),
    val reactions: List<HistoryArchiveReaction> = emptyList(),
    val media: List<HistoryMediaEntry>? = null,
    val chats: List<HistoryArchiveChat>? = null,
    val calls: List<HistoryArchiveCall> = emptyList(),
    val callDeletedIds: Set<String> = emptySet(),
    val callClearedBeforeMs: Long = 0,
    val callChatClears: Map<String, Long> = emptyMap(),
) {
    fun validate(expectedServer: String, expectedOwner: String) {
        require(format in 1..2 && server == expectedServer && owner == expectedOwner)
        require(if (format == 1) media == null && chats == null else media != null && chats != null)
        require(format == 2 || (calls.isEmpty() && callDeletedIds.isEmpty() && callClearedBeforeMs == 0L && callChatClears.isEmpty()))
        require(calls.size <= 50_000 && callDeletedIds.size <= 50_000 && callChatClears.size <= 10_000 && callClearedBeforeMs >= 0)
        callDeletedIds.forEach(::requireHistoryId)
        callChatClears.forEach { (id, cutoff) -> requireHistoryId(id); require(cutoff >= 0) }
        val callIds = HashSet<String>()
        calls.forEach { call ->
            call.validate(owner)
            require(callIds.add(call.id) && call.id !in callDeletedIds && (call.chat !in deletedChats || !call.chatVisible))
            require(call.createdAtMs >= callClearedBeforeMs && (call.createdAtMs > (callChatClears[call.chat] ?: -1) || !call.chatVisible))
        }
        chats?.let { rows ->
            require(rows.size <= 10_000)
            val ids = HashSet<String>()
            rows.forEach { it.validate(); require(ids.add(it.id) && it.id !in deletedChats) }
        }
        listOf(server, owner, snapshot).forEach(::requireHistoryId)
        require(messages.size <= 50_000 && chatClears.size <= 10_000 && clearedThrough >= 0)
        chatClears.forEach { (chat, cutoff) -> requireHistoryId(chat); require(cutoff >= 0) }
        require(deletedChats.size <= 10_000 && reactions.size <= 50_000)
        deletedChats.forEach(::requireHistoryId)
        val clients = HashSet<String>(); val servers = HashSet<String>(); val sequences = HashSet<Pair<String, Long>>()
        var budget = 1024L + chatClears.size * 80L + deletedChats.size * 40L + reactions.size * 512L
        budget += (media?.size ?: 0) * 384L
        budget += callDeletedIds.size * 40L + callChatClears.size * 80L + calls.sumOf { 1024L + 6L * ((it.peer?.username?.toByteArray(Charsets.UTF_8)?.size ?: 0) + (it.peer?.displayName?.toByteArray(Charsets.UTF_8)?.size ?: 0)) }
        budget += chats?.sumOf { 256L + 6L * ((it.peer?.username?.toByteArray(Charsets.UTF_8)?.size ?: 0) + (it.peer?.displayName?.toByteArray(Charsets.UTF_8)?.size ?: 0)) } ?: 0L
        require(budget <= MAX_BYTES)
        messages.forEach { message ->
            message.validate()
            // Upper bound JSON escaping before allocating the complete encoded archive.
            budget += 512L + 6L * (message.content?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            require(budget <= MAX_BYTES)
            require(clients.add(message.client) && servers.add(message.id) && sequences.add(message.chat to message.sequence))
            require(message.sequence > maxOf(clearedThrough, chatClears[message.chat] ?: 0))
            require(message.chat !in deletedChats)
        }
        val targets = messages.associateBy { it.id }
        val reactionIds = HashSet<Pair<String,String>>()
        reactions.forEach { reaction ->
            reaction.validate()
            val target = requireNotNull(targets[reaction.target])
            require(!target.hidden && target.deletedThrough == 0L && reaction.sequence > target.sequence)
            require(reactionIds.add(reaction.target to reaction.user))
        }
        media?.let { entries ->
            require(entries.size <= 50_000)
            val expected = mediaIdentities()
            val attachments = HashSet<String>(); val blobs = HashSet<String>()
            entries.forEach { entry ->
                entry.validate()
                require(attachments.add(entry.attachment) && blobs.add(entry.blob))
                require(expected[entry.attachment] == HistoryMediaIdentity(entry.bytes, entry.sha256))
            }
        }
    }

    fun withoutMedia() = copy(format = 1, media = null, chats = null, calls = emptyList(), callDeletedIds = emptySet(), callClearedBeforeMs = 0, callChatClears = emptyMap())
    /** Server tombstones are scoped by authenticated chat membership. This
     * projection prevents an old copy resurrecting deletes sent before this
     * device's Signal keys existed. It never advances read/sync cursors. */
    fun applyingServerDeletions(deleted: Map<String, Long>): HistoryArchive {
        validate(server, owner)
        val targets = messages.associateBy { it.id }
        deleted.forEach { (id, sequence) -> require(sequence > requireNotNull(targets[id]).sequence) }
        val result = copy(messages = messages.map { row ->
            deleted[row.id]?.let { row.copy(content=null, deletedThrough=maxOf(row.deletedThrough,it)) } ?: row
        }, reactions = reactions.filter { it.target !in deleted })
        val wanted = result.mediaIdentities()
        return result.copy(media = result.media?.filter { it.attachment in wanted }).also {
            it.validate(server, owner); if (it.format == 2) it.validateCompleteMedia()
        }
    }
    fun validateCompleteMedia() {
        require(format == 2)
        validateHistoryMedia(requireNotNull(media), mediaIdentities().keys, Long.MAX_VALUE)
        require(requireNotNull(chats).map { it.id }.toSet().containsAll(messages.map { it.chat }))
    }
    /** New copies must include chat shells even when a chat contains only calls.
     * Legacy v2 copies remain readable and are repaired after authentication. */
    fun validatePreparedChatDescriptors() {
        validateCompleteMedia()
        require(requireNotNull(chats).map { it.id }.toSet().containsAll(calls.filter { it.chatVisible }.mapNotNull { it.chat }))
    }
    fun restoringTextChatDescriptors(knownChats: List<HistoryArchiveChat> = emptyList()): HistoryArchive {
        validate(server,owner)
        if (format != 1) return this
        // A v1 attachment has no independently durable backup media manifest.
        // Do not drop its row or claim a complete media restoration.
        if (mediaIdentities().isNotEmpty()) throw HistoryMediaUnavailable()
        val known = knownChats.associateBy { it.id }
        val descriptors = messages.groupBy { it.chat }.map { (id, rows) ->
            known[id] ?: HistoryArchiveChat(id,"group",rows.minOf { it.createdAtMs })
        }.sortedBy { it.id }
        return copy(format=2,media=emptyList(),chats=descriptors).also {
            it.validate(server,owner); it.validateCompleteMedia()
        }
    }
    fun restoringCallChatDescriptors(): HistoryArchive {
        validate(server, owner)
        if (format != 2) return this
        val descriptors = requireNotNull(chats).associateBy { it.id }.toMutableMap()
        for ((chat, rows) in calls.filter { it.chatVisible && it.chat != null }.groupBy { requireNotNull(it.chat) }) {
            val existing = descriptors[chat]
            if (existing != null && existing.type != "direct") continue
            val peers = rows.map { if (it.caller == owner) it.callee else it.caller }.toSet()
            require(peers.size == 1) { "Conflicting call chat participants" }
            if (existing != null) {
                if (existing.type == "direct") require(existing.peer?.id == peers.single())
                continue
            }
            val peer = rows.firstNotNullOfOrNull { it.peer } ?: HistoryArchivePeer(peers.single(), "restored-contact", "Restored contact")
            descriptors[chat] = HistoryArchiveChat(chat,"direct",rows.minOf { it.createdAtMs },peer)
        }
        return copy(chats=descriptors.values.sortedBy { it.id }).also {
            it.validate(server,owner); it.validatePreparedChatDescriptors()
        }
    }
    fun mediaIdentities(): Map<String, HistoryMediaIdentity> {
        val result = LinkedHashMap<String, HistoryMediaIdentity>()
        for (row in messages) {
            if (row.hidden || row.deletedThrough > 0) continue
            val content = row.content ?: continue
            val objectValue = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: continue
            val attachment = objectValue["attachment"]?.takeUnless { it is JsonNull }?.jsonObject ?: continue
            val id = attachment.getValue("attachment_id").jsonPrimitive.content
            requireHistoryId(id)
            val hash = Base64.getDecoder().decode(attachment.getValue("encryption").jsonObject.getValue("sha256").jsonPrimitive.content)
            require(hash.size == 32)
            val value = HistoryMediaIdentity(attachment.getValue("encrypted_size").jsonPrimitive.long,
                hash.joinToString("") { "%02x".format(it.toInt() and 255) })
            require(value.bytes in 1..HistoryMediaEntry.MAX_FILE_BYTES)
            require(result.put(id, value)?.let { it == value } != false)
        }
        return result
    }

    companion object {
        // Bound before JSON parsing, then bound records/individual payloads.
        const val MAX_BYTES = 8 * 1024 * 1024 - 28
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        fun decode(bytes: ByteArray, server: String, owner: String): HistoryArchive {
            require(bytes.size in 1..MAX_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            val root = json.parseToJsonElement(text).jsonObject
            val fieldsForFormat = setOf("format", "server", "owner", "snapshot", "messages", "clearedThrough", "chatClears", "deletedChats", "reactions") +
                if (root.getValue("format").jsonPrimitive.content == "2") setOf("media", "chats") else emptySet()
            val optional = if (root.getValue("format").jsonPrimitive.content == "2") setOf("calls", "callDeletedIds", "callClearedBeforeMs", "callChatClears") else emptySet()
            require(root.keys - optional == fieldsForFormat)
            val fields = setOf("client", "id", "chat", "sender", "sequence", "createdAtMs", "editedThrough", "deletedThrough", "hidden")
            root.getValue("messages").jsonArray.forEach { row ->
                require(row.jsonObject.keys - setOf("content", "replyTo") == fields)
            }
            return json.decodeFromString<HistoryArchive>(text).also { it.validate(server, owner); if (it.format == 2) it.validateCompleteMedia() }
        }
        fun encode(archive: HistoryArchive): ByteArray {
            archive.validate(archive.server, archive.owner)
            if (archive.format == 2) archive.validateCompleteMedia()
            val root = json.parseToJsonElement(json.encodeToString(archive)).jsonObject
            val omitted = mutableSetOf<String>()
            if (archive.calls.isEmpty()) omitted.add("calls")
            if (archive.callDeletedIds.isEmpty()) omitted.add("callDeletedIds")
            if (archive.callClearedBeforeMs == 0L) omitted.add("callClearedBeforeMs")
            if (archive.callChatClears.isEmpty()) omitted.add("callChatClears")
            if (archive.format == 1) omitted.addAll(setOf("media", "chats"))
            val encoded = JsonObject(root - omitted).toString()
            return encoded.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
        }
    }
}

data class HistoryMediaIdentity(val bytes: Long, val sha256: String)

/** Terminal call metadata lives only inside the authenticated encrypted archive.
 * Restoring it never grants access to the server's old signalling log. */
@Serializable
data class HistoryArchiveCall(
    val id: String, val caller: String, val callee: String, val mediaKind: String,
    val createdAtMs: Long, val acceptedAtMs: Long? = null, val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null, val endReason: String? = null, val chat: String? = null,
    val chatVisible: Boolean = true, val peer: HistoryArchivePeer? = null,
) {
    fun validate(owner: String) {
        listOf(id, caller, callee).forEach(::requireHistoryId); chat?.let(::requireHistoryId)
        require(caller != callee && owner in setOf(caller, callee) && mediaKind in setOf("audio", "video"))
        require(createdAtMs in 0..253402300799999L)
        listOfNotNull(acceptedAtMs, connectedAtMs, endedAtMs).forEach { require(it in createdAtMs..253402300799999L) }
        if (acceptedAtMs != null && connectedAtMs != null) require(acceptedAtMs <= connectedAtMs)
        if (acceptedAtMs != null && endedAtMs != null) require(acceptedAtMs <= endedAtMs)
        if (connectedAtMs != null && endedAtMs != null) require(connectedAtMs <= endedAtMs)
        require(endReason == null || endReason in setOf("hangup", "missed", "rejected", "busy", "failed", "timeout", "cancelled", "canceled", "ended", "disconnect", "answered_elsewhere", "remote_ended"))
        peer?.let { it.validate(); require(it.id == if (caller == owner) callee else caller) }
    }
}

@Serializable
data class HistoryArchivePeer(val id: String, val username: String, val displayName: String) {
    fun validate() { requireHistoryId(id); require(username.toByteArray(Charsets.UTF_8).size in 1..512 && displayName.toByteArray(Charsets.UTF_8).size <= 1024) }
}
@Serializable
data class HistoryArchiveChat(val id: String, val type: String, val createdAtMs: Long, val peer: HistoryArchivePeer? = null) {
    fun validate() {
        requireHistoryId(id); require(type in setOf("direct", "group", "saved") && createdAtMs in 0..253402300799999L)
        peer?.validate()
        require(type != "direct" || peer != null)
    }
}

@Serializable
data class HistoryArchiveMessage(
    val client: String,
    val id: String,
    val chat: String,
    val sender: String,
    val sequence: Long,
    val createdAtMs: Long,
    /** Portable MessageContent JSON, not Android text/iOS enum serialization. */
    val content: String? = null,
    val editedThrough: Long = 0,
    val deletedThrough: Long = 0,
    val hidden: Boolean = false,
    val replyTo: String? = null,
) {
    fun validate() {
        listOf(client, id, chat, sender).forEach(::requireHistoryId)
        replyTo?.let(::requireHistoryId)
        require(sequence > 0 && createdAtMs in 0..253402300799999L)
        require(editedThrough == 0L || editedThrough > sequence)
        require(deletedThrough == 0L || deletedThrough > sequence)
        require(if (hidden || deletedThrough > 0) content == null else !content.isNullOrBlank())
        require(content == null || content.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
    }
}

@Serializable
data class HistoryArchiveReaction(val target: String, val user: String, val sequence: Long, val emoji: String? = null) {
    fun validate() {
        requireHistoryId(target); requireHistoryId(user)
        require(sequence > 0 && (emoji == null || (emoji.isNotBlank() && emoji.toByteArray(Charsets.UTF_8).size <= 64)))
    }
}

internal fun requireHistoryId(id: String) {
    require(id.length == 36 && UUID.fromString(id).toString() == id)
}

/** Pure merge planner; a native adapter must apply its result in one account-
 * fenced transaction, after validating ALL media. Never advances sync cursors,
 * sends receipts, creates pending sends or authorizes membership.
 */
object HistoryArchiveMerge {
    fun merge(local: HistoryArchive, restored: HistoryArchive): HistoryArchive {
        local.validate(local.server, local.owner)
        restored.validate(local.server, local.owner)
        val cutoff = maxOf(local.clearedThrough, restored.clearedThrough)
        val deletedChats = local.deletedChats + restored.deletedChats
        val clears = (local.chatClears.keys + restored.chatClears.keys).associateWith {
            maxOf(local.chatClears[it] ?: 0, restored.chatClears[it] ?: 0)
        }
        val callDeleted = local.callDeletedIds + restored.callDeletedIds
        val callCutoff = maxOf(local.callClearedBeforeMs, restored.callClearedBeforeMs)
        val callClears = (local.callChatClears.keys + restored.callChatClears.keys).associateWith { maxOf(local.callChatClears[it] ?: 0, restored.callChatClears[it] ?: 0) }
        val callRows = local.calls.associateBy { it.id }.toMutableMap()
        restored.calls.forEach { incoming ->
            val current = callRows[incoming.id]
            if (current != null) require(current.caller == incoming.caller && current.callee == incoming.callee && current.mediaKind == incoming.mediaKind && current.createdAtMs == incoming.createdAtMs && current.chat == incoming.chat &&
                current.acceptedAtMs == incoming.acceptedAtMs && current.connectedAtMs == incoming.connectedAtMs && current.endedAtMs == incoming.endedAtMs && current.endReason == incoming.endReason)
            callRows[incoming.id] = current?.copy(chatVisible = current.chatVisible && incoming.chatVisible, peer = current.peer ?: incoming.peer) ?: incoming
        }
        val calls = callRows.values.filter { it.id !in callDeleted && it.createdAtMs >= callCutoff }
            .map { if (it.chat in deletedChats || it.createdAtMs <= (callClears[it.chat] ?: -1)) it.copy(chatVisible = false) else it }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        val rows = local.messages.associateBy { it.client }.toMutableMap()
        restored.messages.forEach { incoming ->
            val current = rows[incoming.client]
            rows[incoming.client] = if (current == null) incoming else {
                // Identifier conflicts are corruption, not permission to move a row.
                require(current.id == incoming.id && current.chat == incoming.chat &&
                    current.sender == incoming.sender && current.sequence == incoming.sequence &&
                    kotlin.math.abs(current.createdAtMs - incoming.createdAtMs) <= 1 && current.replyTo == incoming.replyTo)
                val deleted = maxOf(current.deletedThrough, incoming.deletedThrough)
                val hidden = current.hidden || incoming.hidden
                val winner = if (incoming.editedThrough > current.editedThrough) incoming else current
                if (!hidden && deleted == 0L && current.editedThrough == incoming.editedThrough) {
                    require(sameContent(current.content, incoming.content))
                }
                winner.copy(deletedThrough = deleted, hidden = hidden,
                    content = if (hidden || deleted > 0) null else winner.content)
            }
        }
        val messages = rows.values.filter { it.chat !in deletedChats && it.sequence > maxOf(cutoff, clears[it.chat] ?: 0) }
            .sortedWith(compareBy({ it.sequence }, { it.client }))
        val liveTargets = messages.filter { !it.hidden && it.deletedThrough == 0L }.map { it.id }.toSet()
        val reactions = local.reactions.associateBy { it.target to it.user }.toMutableMap()
        restored.reactions.forEach { incoming ->
            val key = incoming.target to incoming.user
            val current = reactions[key]
            if (current != null && current.sequence == incoming.sequence) require(current.emoji == incoming.emoji)
            if (current == null || incoming.sequence > current.sequence) reactions[key] = incoming
        }
        val merged = local.copy(format = 1, media = null, chats = null, clearedThrough = cutoff, chatClears = clears, deletedChats = deletedChats,
            messages = messages, reactions = reactions.values.filter { it.target in liveTargets }
                .sortedWith(compareBy({ it.target }, { it.user })))
        if (local.format == 1 && restored.format == 1) return merged.also { it.validate(local.server, local.owner) }
        val identities = merged.mediaIdentities()
        val media = ((local.media ?: emptyList()) + (restored.media ?: emptyList()))
            .filter { identities[it.attachment] == HistoryMediaIdentity(it.bytes, it.sha256) }
            .distinctBy { it.attachment }.sortedBy { it.attachment }
        val chats = ((local.chats ?: emptyList()) + (restored.chats ?: emptyList()))
            .filter { it.id !in deletedChats }.distinctBy { it.id }.sortedBy { it.id }
        return merged.copy(format = 2, media = media, chats = chats, calls = calls, callDeletedIds = callDeleted, callClearedBeforeMs = callCutoff, callChatClears = callClears).also { it.validate(local.server, local.owner) }
    }
    private fun sameContent(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        return runCatching { Json.parseToJsonElement(a) == Json.parseToJsonElement(b) }.getOrDefault(false)
    }
}
