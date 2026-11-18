package com.messenger.app.data.directtransfer

import java.util.UUID

internal enum class DirectTransferDirection { OUTBOUND, INBOUND }

/** Resource identity only: never peer proof, consent, an RTC state, or a wire value. */
internal data class DirectSlotLease(
    val direction: DirectTransferDirection,
    val transferId: String,
    val attemptId: String,
    val accountEpoch: Int,
    val generation: Long,
    val token: String = UUID.randomUUID().toString(),
) {
    init { require(canonicalUuid(transferId) && canonicalUuid(attemptId) && canonicalUuid(token) && accountEpoch >= 0 && generation >= 0) }
}

/** Exactly two resource slots. Short monitor guards also cover worker-side lease checks; no await is inside. */
internal class DirectTransferSlots {
    private var outbound: DirectSlotLease? = null
    private var inbound: DirectSlotLease? = null
    private val attached = mutableSetOf<String>()
    private val closing = mutableSetOf<String>()

    @Synchronized fun current(direction: DirectTransferDirection): DirectSlotLease? = if (direction == DirectTransferDirection.OUTBOUND) outbound else inbound
    @Synchronized fun snapshot(): List<DirectSlotLease> = listOfNotNull(outbound, inbound)
    @Synchronized fun owns(lease: DirectSlotLease): Boolean = current(lease.direction) == lease
    @Synchronized fun usable(lease: DirectSlotLease): Boolean = owns(lease) && lease.token !in closing
    @Synchronized fun reserve(direction: DirectTransferDirection, id: String, attempt: String, epoch: Int, generation: Long): DirectSlotLease? {
        val current = current(direction)
        if (current != null) return current.takeIf { it.transferId == id && it.attemptId == attempt && it.accountEpoch == epoch && it.generation == generation && usable(it) }
        val lease = DirectSlotLease(direction, id, attempt, epoch, generation)
        if (direction == DirectTransferDirection.OUTBOUND) outbound = lease else inbound = lease
        return lease
    }
    /** A second constructor cannot attach another PC to the same reservation. */
    @Synchronized fun attach(lease: DirectSlotLease): Boolean = usable(lease) && attached.add(lease.token)
    @Synchronized fun isAttached(lease: DirectSlotLease): Boolean = owns(lease) && lease.token in attached
    @Synchronized fun beginClosing(lease: DirectSlotLease): Boolean = owns(lease) && closing.add(lease.token)
    /** Call only after native close AND owned worker/checkpoint drain acknowledge completion. */
    @Synchronized fun releaseAfterDrain(lease: DirectSlotLease): Boolean {
        if (!owns(lease) || lease.token !in closing) return false
        attached.remove(lease.token); closing.remove(lease.token)
        if (lease.direction == DirectTransferDirection.OUTBOUND) outbound = null else inbound = null
        return true
    }
}
