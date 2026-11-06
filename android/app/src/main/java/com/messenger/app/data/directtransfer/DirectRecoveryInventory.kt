@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.messenger.app.data.directtransfer

import com.messenger.app.data.local.chat.ChatWorkspaceDao
import com.messenger.app.data.local.chat.ChatWorkspaceRecoveryConflict
import com.messenger.app.data.local.chat.ChatWorkspaceRecoveryLookup
import com.messenger.app.data.local.chat.ChatWorkspaceRecoveryRow
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lookup metadata only. The canonical record remains the sole protocol/consent authority. */
@Serializable
internal data class DirectRecoveryReference(
    val id: String,
    val chat: String,
    val outgoing: Boolean,
) {
    init { require(canonicalUuid(id) && canonicalUuid(chat)) }
    fun matches(record: DirectTransferRecord): Boolean = id == record.offer.transferId && chat == record.chatId &&
        record.role == if (outgoing) "sender" else "receiver"
}

/** version is assigned only after the entire legacy ceiling has been proven. */
@Serializable
internal data class DirectRecoveryInventoryState(
    @Required val afterRowId: Long,
    @Required val upperRowId: Long,
    @Required val ready: Boolean,
    val version: Int? = null,
    @Required val members: List<DirectRecoveryReference> = emptyList(),
    @Required val outboundOrdinal: Long = 0,
    @Required val inboundOrdinal: Long = 0,
) {
    init { validated() }

    fun validated(): DirectRecoveryInventoryState = apply {
        require(afterRowId >= 0 && upperRowId >= afterRowId)
        require(if (ready) version == 1 && afterRowId == upperRowId else version == null)
        require(outboundOrdinal in 0 until Long.MAX_VALUE && inboundOrdinal in 0 until Long.MAX_VALUE)
        require(members.size <= 16 && members.count { it.outgoing } <= 8 && members.count { !it.outgoing } <= 8)
        require(members.map { it.id }.distinct().size == members.size)
        members.forEach { require(canonicalUuid(it.id) && canonicalUuid(it.chat)) }
    }

    fun observing(record: DirectTransferRecord): DirectRecoveryInventoryState {
        val id = record.offer.transferId
        val old = members.firstOrNull { it.id == id }
        if (old != null) require(old.matches(record))
        val kept = members.filterNot { it.id == id }
        val next = if (DirectAdmissionPolicy.unfinished(record)) kept + DirectRecoveryReference(id, record.chatId, record.role == "sender") else kept
        val ordinal = record.queue?.ordinal ?: 0
        return copy(members = next.sortedBy { it.id },
            outboundOrdinal = if (record.role == "sender") maxOf(outboundOrdinal, ordinal) else outboundOrdinal,
            inboundOrdinal = if (record.role == "receiver") maxOf(inboundOrdinal, ordinal) else inboundOrdinal)
    }

    companion object {
        const val MAX_BYTES = 4096
        val codec = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
        fun decode(raw: String): DirectRecoveryInventoryState {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            return codec.decodeFromString(serializer(), raw).validated()
        }
        fun encode(value: DirectRecoveryInventoryState): String = codec.encodeToString(serializer(), value.validated()).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        }
        fun fresh(upper: Long) = DirectRecoveryInventoryState(0, upper, upper == 0L, if (upper == 0L) 1 else null)
    }
}

internal data class DirectRecoverySnapshot(val records: Map<String, DirectTransferRecord>)

/**
 * One account/epoch instance, serialized by the existing coordinator gate.
 * Each journal call is a bounded local operation; no whole scan holds a Room/session transaction.
 */
internal class DirectRecoveryInventoryStore(
    private val workspace: ChatWorkspaceDao,
    private val auth: AuthRepository,
    private val epoch: Int,
    ownerKey: String,
    private val decodeRecord: (String) -> DirectTransferRecord?,
    private val ensureCurrent: () -> Unit,
) {
    private val indexChat = "__direct_transfer_recovery__"
    private val indexName = "direct_recovery:$ownerKey"
    private var state: DirectRecoveryInventoryState? = null
    private var raw: String? = null
    private var dirty = false
    private data class UncertainSave(val reference: DirectRecoveryReference, val wasIndexed: Boolean)
    private val uncertain = linkedMapOf<String, UncertainSave>()
    var needsRecovery: Boolean = true
        private set
    val hasUncertainWrites: Boolean get() = uncertain.isNotEmpty()

    init { require(ownerKey.isNotEmpty() && ownerKey.length <= 128) }

    private class JournalValue<T>(val value: T)
    private suspend fun <T> journal(body: suspend () -> T): T {
        ensureCurrent()
        val result = auth.withWorkspaceWrite(epoch) {
            ensureCurrent()
            val value = body()
            ensureCurrent()
            JournalValue(value)
        } ?: throw CancellationException("Direct recovery account changed")
        return result.value
    }

    private suspend fun current(allowCreate: Boolean): DirectRecoveryInventoryState {
        ensureCurrent()
        if (state != null && !dirty) return requireNotNull(state)
        val found = journal { workspace.get(indexChat, indexName) }
        if (found != null) {
            val decoded = DirectRecoveryInventoryState.decode(found)
            for (member in decoded.members) uncertain.computeIfPresent(member.id) { _, save -> save.copy(wasIndexed = true) }
            ensureCurrent(); state = decoded; raw = found; dirty = false
            return decoded
        }
        require(allowCreate && state == null) { "Missing direct recovery inventory" }
        val upper = journal { workspace.directRecoveryUpperRowId() }
        require(upper >= 0)
        val fresh = DirectRecoveryInventoryState.fresh(upper)
        commit(fresh, null, emptyList())
        return fresh
    }

    private suspend fun commit(next: DirectRecoveryInventoryState, expected: String?, page: List<ChatWorkspaceRecoveryRow>,
                               retirements: List<ChatWorkspaceValue> = emptyList()) {
        val encoded = DirectRecoveryInventoryState.encode(next)
        try {
            journal {
                if (retirements.isEmpty()) workspace.checkpointDirectRecovery(ChatWorkspaceValue(indexChat, indexName, encoded), expected, page)
                else {
                    require(page.isEmpty())
                    workspace.checkpointDirectRecoveryValues(ChatWorkspaceValue(indexChat, indexName, encoded), expected, retirements)
                }
            }
            ensureCurrent(); state = next; raw = encoded; dirty = false
        } catch (failure: Throwable) {
            dirty = true; needsRecovery = true
            throw failure
        }
    }

    private fun checked(row: ChatWorkspaceRecoveryRow): DirectTransferRecord {
        val record = decodeRecord(row.value) ?: throw IllegalStateException("Invalid direct recovery record")
        require(row.chatId == record.chatId && row.name == "direct_transfer:${record.offer.transferId}")
        return record
    }

    suspend fun recover(apply: (DirectRecoverySnapshot) -> Unit = {}): DirectRecoverySnapshot {
        var conflicts = 0
        while (true) {
            ensureCurrent()
            try {
                var before = current(allowCreate = true)
                if (!before.ready) {
                    val page = journal { workspace.directRecoveryPage(before.afterRowId, before.upperRowId) }
                    require(page.size <= 8)
                    var cursor = before.afterRowId
                    var after = before
                    for (row in page) {
                        require(row.rowId > cursor && row.rowId <= before.upperRowId)
                        after = after.observing(checked(row)); cursor = row.rowId
                    }
                    val finished = page.isEmpty() || cursor == before.upperRowId
                    after = after.copy(afterRowId = if (finished) before.upperRowId else cursor,
                        ready = finished, version = if (finished) 1 else null)
                    commit(after, raw, page)
                    conflicts = 0
                    yield() // Account change/cancellation and unrelated local work run between pages.
                    continue
                }
                val expected = requireNotNull(raw)
                // Durable index binding wins over the same ID's uncertain attempted-save metadata.
                val references = (uncertain.values.map { it.reference } + before.members).associateBy { it.id }.values.toList()
                require(references.size <= 16)
                val indexedIds = before.members.mapTo(mutableSetOf()) { it.id }
                val requiredIds = indexedIds + uncertain.filterValues { it.wasIndexed }.keys
                val rows = journal { workspace.directRecoveryRecords(indexChat, indexName, expected,
                    references.map { ChatWorkspaceRecoveryLookup(it.chat, "direct_transfer:${it.id}", it.id in requiredIds) }) }
                val byKey = rows.associateBy { it.chatId to it.name }
                val restored = mutableMapOf<String, DirectTransferRecord>()
                val closedRetirements = mutableListOf<ChatWorkspaceValue>()
                for (reference in references) {
                    val value = byKey[reference.chat to "direct_transfer:${reference.id}"]
                    if (value == null) { require(reference.id !in requiredIds); continue }
                    val record = decodeRecord(value.value) ?: throw IllegalStateException("Invalid indexed direct record")
                    require(value.chatId == reference.chat && value.name == "direct_transfer:${reference.id}" && reference.matches(record))
                    if (DirectAdmissionPolicy.unfinished(record) != (reference.id in indexedIds)) {
                        // Older versions indexed this exact closed-unavailable disposition.
                        // A positive canonical read is required; missing/other mismatches still fail closed.
                        require(reference.id in indexedIds && record.closedUnavailable &&
                            (record.role == "sender" || record.role == "receiver" && record.consent))
                        closedRetirements += value
                    }
                    restored[reference.id] = record
                }
                if (closedRetirements.isNotEmpty()) {
                    var next = before
                    for (value in closedRetirements) {
                        val record = requireNotNull(decodeRecord(value.value))
                        next = next.observing(record)
                    }
                    // Index-only CAS: retain canonical JSON, all original/partial files and ordinals.
                    commit(next, expected, emptyList(), closedRetirements)
                }
                val snapshot = DirectRecoverySnapshot(restored)
                ensureCurrent(); apply(snapshot); ensureCurrent()
                uncertain.clear(); needsRecovery = false
                return snapshot
            } catch (conflict: ChatWorkspaceRecoveryConflict) {
                dirty = true; needsRecovery = true
                if (++conflicts >= 3) throw conflict
                yield()
            }
        }
    }

    fun requireCovered(records: Collection<DirectTransferRecord>) {
        ensureCurrent()
        val known = requireNotNull(state).validated()
        require(known.ready && !dirty)
        records.filter(DirectAdmissionPolicy::unfinished).forEach { record ->
            require(known.members.any { it.matches(record) }) { "Unindexed direct transfer" }
        }
    }

    fun ordinalBounds(): Pair<Long, Long> {
        ensureCurrent()
        val known = requireNotNull(state).validated()
        require(known.ready && !dirty && !needsRecovery)
        return known.outboundOrdinal to known.inboundOrdinal
    }

    suspend fun put(record: DirectTransferRecord, encodedRecord: String) {
        ensureCurrent()
        val before = current(allowCreate = false)
        val next = before.observing(record)
        val encoded = if (next == before) null else DirectRecoveryInventoryState.encode(next)
        if (encoded != null) require((before.members.map { it.id } + uncertain.keys + record.offer.transferId).distinct().size <= 16)
        try {
            journal { workspace.putDirectTransferRecovery(ChatWorkspaceValue(record.chatId, "direct_transfer:${record.offer.transferId}", encodedRecord),
                encoded?.let { ChatWorkspaceValue(indexChat, indexName, it) }, raw) }
            ensureCurrent()
            if (encoded != null) { state = next; raw = encoded }
        } catch (failure: Throwable) {
            if (encoded != null) {
                uncertain[record.offer.transferId] = UncertainSave(
                    DirectRecoveryReference(record.offer.transferId, record.chatId, record.role == "sender"),
                    uncertain[record.offer.transferId]?.wasIndexed == true || before.members.any { it.id == record.offer.transferId })
                dirty = true; needsRecovery = true
            }
            throw failure
        }
    }
}
