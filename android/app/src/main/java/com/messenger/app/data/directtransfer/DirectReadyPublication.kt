package com.messenger.app.data.directtransfer

import java.util.concurrent.ConcurrentHashMap

/** Same-publisher memory only. Missing/restarted entries always mean possibly sent, never disposable. */
internal class DirectReadyPublication {
    private data class Entry(val transfer: String, val attempt: String, val sender: String, val generation: Long,
        @Volatile var attempted: Boolean = false)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** Called INSIDE the existing freeze transformation, before durable pending becomes visible. */
    fun beforeFreezePublication(control: DirectFileControl, generation: Long) {
        if (!ready(control)) return
        if (entries.size >= MAX_ENTRIES) return // conservative unknown; no legacy credit/retirement is invented
        entries.putIfAbsent(control.clientSignalId, Entry(control.transferId, control.attemptId, control.senderDeviceId, generation))
    }

    fun mayRetire(frozen: FrozenDirectSignal, eligible: (Long) -> Boolean): Boolean {
        val entry = exact(frozen) ?: return false
        return synchronized(entry) { !entry.attempted && !eligible(entry.generation) }
    }

    /** Invoked after actual API rate/concurrency waits, immediately before creating the HTTP call. */
    fun beforeHttpStart(frozen: FrozenDirectSignal, eligible: (Long) -> Boolean) {
        val entry = exact(frozen) ?: return // unknown or recovered ciphertext must retain exact retry semantics
        synchronized(entry) {
            if (!entry.attempted && !eligible(entry.generation)) throw DirectReadyRetired()
            entry.attempted = true // irreversible, including an ambiguous/cancelled HTTP attempt
        }
    }

    fun removed(frozen: FrozenDirectSignal) { exact(frozen)?.let { entries.remove(frozen.control.clientSignalId, it) } }

    private fun exact(frozen: FrozenDirectSignal): Entry? {
        val control = frozen.control
        if (!ready(control) || frozen.envelope.clientSignalId != control.clientSignalId) return null
        return entries[control.clientSignalId]?.takeIf {
            it.transfer == control.transferId && it.attempt == control.attemptId && it.sender == control.senderDeviceId
        }
    }
    private fun ready(control: DirectFileControl) = control.type in setOf("accept", "resume", "offer", "answer")
    companion object { const val MAX_ENTRIES = 4096 }
}

internal class DirectReadyRetired : Exception("Never-attempted ready control was superseded")
