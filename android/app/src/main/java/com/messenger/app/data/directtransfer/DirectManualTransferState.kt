package com.messenger.app.data.directtransfer

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

private fun strictPrimitive(decoder: Decoder): JsonPrimitive {
    val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
    if (value == null || value.isString) throw SerializationException("Expected an unquoted JSON scalar")
    return value
}
internal object DirectManualLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("DirectManualLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long = strictPrimitive(decoder).longOrNull ?: throw SerializationException("Expected a signed 64-bit JSON integer")
}
internal object DirectManualBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("DirectManualBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean = strictPrimitive(decoder).booleanOrNull ?: throw SerializationException("Expected a JSON boolean")
}
internal object DirectManualIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("DirectManualInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val value = strictPrimitive(decoder).longOrNull ?: throw SerializationException("Expected a JSON integer")
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw SerializationException("Integer outside Int range")
        return value.toInt()
    }
}

/** Pure state only: no consent, peer verification, RTC, storage or cursor authority. */
@Serializable
internal data class DirectManualSnapshot(
    @Serializable(with = DirectManualLongSerializer::class) @SerialName("pause_revision") val pauseRevision: Long,
    @Serializable(with = DirectManualBooleanSerializer::class) val paused: Boolean,
    @Serializable(with = DirectManualLongSerializer::class) @SerialName("ack_pause_revision") val ackPauseRevision: Long,
) {
    init { validated() }
    fun validated() = apply {
        require(pauseRevision >= 0 && ackPauseRevision >= 0 && (pauseRevision != 0L || !paused))
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class DirectManualPauseState(
    @Serializable(with = DirectManualLongSerializer::class) @Required @EncodeDefault val localRevision: Long = 0,
    @Serializable(with = DirectManualBooleanSerializer::class) @Required @EncodeDefault val localPaused: Boolean = false,
    @Serializable(with = DirectManualLongSerializer::class) @Required @EncodeDefault val peerRevision: Long = 0,
    @Serializable(with = DirectManualBooleanSerializer::class) @Required @EncodeDefault val peerPaused: Boolean = false,
    @Serializable(with = DirectManualLongSerializer::class) @Required @EncodeDefault val acknowledgedLocalRevision: Long = 0,
    @Serializable(with = DirectManualLongSerializer::class) @EncodeDefault val publishedLocalRevision: Long = 0,
    val pendingResponseTo: String? = null,
    @Serializable(with = DirectManualBooleanSerializer::class) @EncodeDefault val syncNeeded: Boolean = false,
    @Serializable(with = DirectManualIntSerializer::class) @EncodeDefault val syncProbeCount: Int = 0,
    @Serializable(with = DirectManualLongSerializer::class) @EncodeDefault val nextSyncAt: Long = 0,
    @Serializable(with = DirectManualLongSerializer::class) @EncodeDefault val syncCycleStartedAt: Long = 0,
) {
    init { validated() }
    fun validated() = apply {
        require(localRevision >= 0 && peerRevision >= 0 && acknowledgedLocalRevision in 0..localRevision)
        require(publishedLocalRevision in 0..localRevision)
        require((localRevision != 0L || !localPaused) && (peerRevision != 0L || !peerPaused))
        require(pendingResponseTo == null || canonicalUuid(pendingResponseTo))
        require(syncProbeCount in 0..DirectManualTiming.MAX_PROBES && nextSyncAt >= 0 && syncCycleStartedAt >= 0)
    }
    val manualByteEligible: Boolean get() = runCatching { validated() }.isSuccess && !localPaused && !peerPaused && acknowledgedLocalRevision == localRevision
    val syncLimitReached: Boolean get() = syncProbeCount >= DirectManualTiming.MAX_PROBES
    fun snapshot() = DirectManualSnapshot(localRevision, localPaused, peerRevision)

    fun changingLocal(paused: Boolean, terminal: Boolean = false, verifying: Boolean = false): DirectManualPauseState {
        validated()
        if (terminal || verifying || paused == localPaused) return this
        require(localRevision < Long.MAX_VALUE) { "Manual pause revision exhausted" }
        return copy(localRevision = localRevision + 1, localPaused = paused, syncNeeded = true)
    }

    fun applying(snapshot: DirectManualSnapshot, requestAck: Boolean = false, terminal: Boolean = false): DirectManualReduction {
        validated()
        if (terminal) return DirectManualReduction(this, false, false, false)
        snapshot.validated()
        require(snapshot.ackPauseRevision <= localRevision) { "Future pause acknowledgement" }
        require(snapshot.pauseRevision != peerRevision || snapshot.paused == peerPaused) { "Conflicting pause revision" }
        val peerChanged = snapshot.pauseRevision > peerRevision
        val ackAdvanced = snapshot.ackPauseRevision > acknowledgedLocalRevision
        val next = copy(
            peerRevision = if (peerChanged) snapshot.pauseRevision else peerRevision,
            peerPaused = if (peerChanged) snapshot.paused else peerPaused,
            acknowledgedLocalRevision = maxOf(acknowledgedLocalRevision, snapshot.ackPauseRevision),
        )
        return DirectManualReduction(next, peerChanged, ackAdvanced, requestAck || peerChanged)
    }
}

internal data class DirectManualReduction(
    val state: DirectManualPauseState,
    val peerChanged: Boolean,
    val ackAdvanced: Boolean,
    val needsResponse: Boolean,
)

internal object DirectManualTiming {
    const val MAX_PROBES = 6
    const val RESET_COOLDOWN_MILLIS = 30_000L
    private val delays = longArrayOf(0, 2_000, 4_000, 8_000, 16_000, 32_000)
    fun probeDelayMillis(index: Int): Long { require(index in delays.indices); return delays[index] }
    fun canResetCycle(nowMillis: Long, startedAtMillis: Long): Boolean =
        nowMillis >= 0 && startedAtMillis >= 0 && nowMillis >= startedAtMillis &&
            (startedAtMillis == 0L || nowMillis - startedAtMillis >= RESET_COOLDOWN_MILLIS)
}

internal object DirectManualWire {
    private val piggybackKinds = setOf("accept", "resume", "offer", "answer")
    fun validate(type: String, revision: Long?, paused: Boolean?, ack: Long?, requestAck: Boolean?) {
        val present = listOf(revision, paused, ack).count { it != null }
        if (type == "pause_state") require(present == 3 && requestAck != null)
        else if (type in piggybackKinds) require((present == 0 || present == 3) && requestAck == null)
        else require(present == 0 && requestAck == null)
        if (present == 3) DirectManualSnapshot(requireNotNull(revision), requireNotNull(paused), requireNotNull(ack)).validated()
    }
}

/** GLOBAL mailbox sequence is intentionally absent: it is not a capacity counter. */
@Serializable
internal data class DirectSignalIdentity(val senderDeviceId: String, val clientSignalId: String) {
    init { validated() }
    fun validated() = apply { require(canonicalUuid(senderDeviceId) && canonicalUuid(clientSignalId)) }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class DirectSignalBudget(
    @Serializable(with = DirectManualBooleanSerializer::class) val known: Boolean,
    val identities: List<DirectSignalIdentity>,
    @Serializable(with = DirectManualBooleanSerializer::class) @EncodeDefault val overflowed: Boolean = false,
) {
    init { validated() }
    fun validated() = apply {
        require(identities.size <= MAX_IDENTITIES && identities.distinct().size == identities.size)
        identities.forEach { it.validated() }
    }
    val debits: Int get() = identities.size
    fun recording(identity: DirectSignalIdentity): DirectSignalBudget {
        validated(); identity.validated()
        if (identity in identities) return this
        return if (identities.size < MAX_IDENTITIES) copy(identities = identities + identity) else copy(overflowed = true)
    }
    fun mayFreezeOptional(pendingCount: Int): Boolean = runCatching { validated() }.isSuccess && known && !overflowed && debits < OPTIONAL_LIMIT && pendingCount in 0 until OPTIONAL_PENDING_LIMIT
    fun mayFreezeTerminal(pendingCount: Int): Boolean = pendingCount in 0 until MAX_PENDING
    companion object {
        const val MAX_IDENTITIES = 256
        const val OPTIONAL_LIMIT = 224
        const val MAX_PENDING = 8
        const val OPTIONAL_PENDING_LIMIT = 6
        fun fresh() = DirectSignalBudget(true, emptyList())
        fun unknown() = DirectSignalBudget(false, emptyList())
    }
}
