@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.messenger.app.data.directtransfer



import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi



/** Installation-local scheduling metadata; never exported through chat/history. */

@Serializable

internal data class DirectFileQueueState(

    val ordinal: Long,

    val reservedFutureBytes: Long,

    @Required @EncodeDefault val remoteCapacityBlocked: Boolean = false,

    @Required @EncodeDefault val capacityHintCount: Int = 0,

    @Required @EncodeDefault val nextCapacityHintAt: Long = 0,

    val lastCapacityHintId: String? = null,

    /** Only a fully authenticated incoming peer control may set true. Own POST is not evidence. */
    val identityProofObserved: Boolean? = null,

) {

    init { validated() }
    fun validated(): DirectFileQueueState = apply {

        require(ordinal > 0 && reservedFutureBytes in 0..DirectFileWire.MAX_FILE_BYTES)

        require(capacityHintCount in 0..6 && nextCapacityHintAt >= 0)

        require(lastCapacityHintId == null || canonicalUuid(lastCapacityHintId))

    }

}



/** No fabricated offer/hash or external provider URL can be persisted here. */

@Serializable

internal data class DirectFileAdmissionTicket(

    val id: String,

    val outgoing: Boolean,

    val ordinal: Long,

    val reservedFutureBytes: Long,

    @Required @EncodeDefault val needsReselection: Boolean = false,

    val displayFilename: String? = null,
    val declaredBytes: Long? = null,
    val chatId: String? = null,
    val executionSession: String? = null,

) {

    init { validated() }
    fun validated(): DirectFileAdmissionTicket = apply {

        require(canonicalUuid(id) && ordinal > 0 && reservedFutureBytes in 0..DirectFileWire.MAX_FILE_BYTES)
        require(displayFilename == null || displayFilename.toByteArray(Charsets.UTF_8).size <= 255 && displayFilename == DirectFileDisplayName.safeFilename(displayFilename))
        require(declaredBytes == null || declaredBytes in 0..DirectFileWire.MAX_FILE_BYTES)
        require(chatId == null || canonicalUuid(chatId))
        require(executionSession == null || canonicalUuid(executionSession))

    }

}



@Serializable

internal data class DirectFileAdmissionLedger(

    @Required @EncodeDefault val nextOutboundOrdinal: Long = 1,

    @Required @EncodeDefault val nextInboundOrdinal: Long = 1,

    @Required @EncodeDefault val tickets: List<DirectFileAdmissionTicket> = emptyList(),

) {

    init { validated() }
    fun validated(): DirectFileAdmissionLedger = apply {

        require(nextOutboundOrdinal > 0 && nextInboundOrdinal > 0 && tickets.size <= 16)

        require(tickets.count { it.outgoing } <= 8 && tickets.count { !it.outgoing } <= 8)

        require(tickets.map { it.id }.distinct().size == tickets.size)

        tickets.forEach { it.validated() }

    }

}



/** Same actual Android storage sanitizer, reused for private display metadata. */
internal object DirectFileDisplayName {
    fun safeFilename(value: String?): String {
        var name = value.orEmpty().filterNot(DirectFileOffer::unsafeNameChar).trim().ifBlank { "file" }
        while (name.toByteArray(Charsets.UTF_8).size > 255) name = name.dropLast(Character.charCount(name.codePointBefore(name.length)))
        return name.takeUnless { it.isBlank() || it in setOf(".", "..") } ?: "file"
    }
}

internal data class DirectFileSourceDisplay(val displayFilename: String, val declaredBytes: Long?)
