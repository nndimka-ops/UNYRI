package com.messenger.app.data.directtransfer

/** Pure bounded admission arithmetic. The coordinator owns the durable transaction and real disk evidence. */
internal object DirectAdmissionPolicy {
    const val MAX_PER_DIRECTION = 8
    const val DISK_MARGIN = 32L * 1024 * 1024

    fun direction(role: String): DirectTransferDirection = when (role) {
        "sender" -> DirectTransferDirection.OUTBOUND
        "receiver" -> DirectTransferDirection.INBOUND
        else -> throw IllegalArgumentException("No transfer role")
    }
    fun unfinished(record: DirectTransferRecord): Boolean = if (record.closedUnavailable) false else when (record.role) {
        "sender" -> !record.terminal || record.completed && !record.mailboxClosed
        "receiver" -> record.consent && !record.terminal
        else -> false
    }
    fun occupiedIds(records: Collection<DirectTransferRecord>, ledger: DirectFileAdmissionLedger, outgoing: Boolean): Set<String> {
        ledger.validated()
        val role = if (outgoing) "sender" else "receiver"
        return records.filter { it.role == role && unfinished(it) }.mapTo(mutableSetOf()) { it.offer.transferId } +
            ledger.tickets.filter { it.outgoing == outgoing }.map { it.id }
    }
    fun mayAdmit(records: Collection<DirectTransferRecord>, ledger: DirectFileAdmissionLedger, outgoing: Boolean, count: Int = 1): Boolean {
        if (count !in 1..MAX_PER_DIRECTION) return false
        return occupiedIds(records, ledger, outgoing).size <= MAX_PER_DIRECTION - count
    }
    fun recovered(ledger: DirectFileAdmissionLedger, records: Collection<DirectTransferRecord>): DirectFileAdmissionLedger {
        ledger.validated()
        fun next(outgoing: Boolean): Long {
            val role = if (outgoing) "sender" else "receiver"
            val maximum = (ledger.tickets.filter { it.outgoing == outgoing }.map { it.ordinal } +
                records.filter { it.role == role }.mapNotNull { it.queue?.ordinal }).maxOrNull() ?: 0L
            require(maximum < Long.MAX_VALUE) { "Queue order exhausted" }
            return maxOf(if (outgoing) ledger.nextOutboundOrdinal else ledger.nextInboundOrdinal, maximum + 1)
        }
        require(occupiedIds(records, ledger, true).size <= MAX_PER_DIRECTION && occupiedIds(records, ledger, false).size <= MAX_PER_DIRECTION)
        return ledger.copy(nextOutboundOrdinal = next(true), nextInboundOrdinal = next(false)).validated()
    }
    fun mayReserveDisk(freeBytes: Long, otherFutureBytes: Long, requestedFutureBytes: Long): Boolean {
        if (freeBytes < DISK_MARGIN || otherFutureBytes < 0 || requestedFutureBytes < 0) return false
        val usable = freeBytes - DISK_MARGIN
        return otherFutureBytes <= usable && requestedFutureBytes <= usable - otherFutureBytes
    }
}
