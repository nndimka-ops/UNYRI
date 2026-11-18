package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException

/** Shared by ingestion and UI reads. No timer: activity/reconnect retries pending work. */
class SessionReceipts(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var epoch = -1
    private var revision = 0L
    private val allowed = mutableMapOf<String, Long>()
    private val refused = mutableMapOf<String, Long>()
    private val reads = ReadReceiptQueue()
    private val deliveries = ReadReceiptQueue()

    @Synchronized private fun prepare(session: Int) {
        if (session < epoch) return
        if (epoch == session) return
        epoch = session; revision = 0; allowed.clear(); refused.clear()
    }
    @Synchronized fun allow(session: Int, chat: String) {
        prepare(session); if (epoch != session) return
        allowed[chat] = ++revision; refused.remove(chat)
    }
    @Synchronized fun ticket(session: Int): Long { prepare(session); return revision }
    @Synchronized fun isRefused(session: Int, chat: String): Boolean {
        prepare(session); return epoch != session || (refused[chat] ?: Long.MIN_VALUE) > now()
    }
    @Synchronized fun refuse(session: Int, chat: String, ticket: Long) {
        if (epoch == session && (allowed[chat] ?: 0) <= ticket) refused[chat] = now() + 300_000
    }

    suspend fun flush(session: Int, delivered: Boolean, isCurrent: () -> Boolean,
        load: suspend () -> Map<String, Long>, saved: suspend (String) -> Long,
        save: suspend (String, Long) -> Unit, send: suspend (String, Long) -> Long,
        priority: String? = null) {
        (if (delivered) deliveries else reads).flush(session, isCurrent, {
            load().entries.sortedBy { if (it.key == priority) 0 else 1 }.associate { it.toPair() }
                .filter { (chat, sequence) -> !isRefused(session, chat) && sequence > saved(chat) }
        }) { chat, sequence ->
            if (!isCurrent() || isRefused(session, chat)) throw ReceiptDeferred()
            val ticket = ticket(session)
            val acknowledged = try { send(chat, sequence) }
            catch (error: AuthApiException.Api) {
                if (error.httpStatus == 409 || error.code == "conflict") refuse(session, chat, ticket)
                throw error
            }
            if (!isCurrent() || acknowledged < sequence) throw ReceiptDeferred()
            save(chat, maxOf(acknowledged, saved(chat)))
        }
    }
    private class ReceiptDeferred : Exception()
    companion object {
        const val READ = "server_read_sequence"
        const val DELIVERED = "server_delivered_sequence"
    }
}
