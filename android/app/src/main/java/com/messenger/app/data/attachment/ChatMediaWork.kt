package com.messenger.app.data.attachment

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/** Account/chat keyed cancellation; deletion closes admission before draining existing work. */
internal class ChatMediaWork {
    private val lock = Any()
    private val closed = mutableSetOf<Pair<Int, String>>()
    private val jobs = mutableMapOf<Pair<Int, String>, MutableSet<Job>>()
    suspend fun <T> run(epoch: Int, chatId: String, allowed: suspend () -> Boolean, block: suspend () -> T): T = coroutineScope {
        val key = epoch to chatId
        val job = coroutineContext[Job]!!
        synchronized(lock) {
            if (key in closed) throw CancellationException("Chat deleted")
            jobs.getOrPut(key) { mutableSetOf() }.add(job)
        }
        try {
            if (!allowed()) throw CancellationException("Chat deleted")
            withContext(Dispatchers.IO) { block() }
        } finally { synchronized(lock) { jobs[key]?.let { it.remove(job); if (it.isEmpty()) jobs.remove(key) } } }
    }
    suspend fun close(epoch: Int, chatIds: List<String>) {
        val active = synchronized(lock) {
            val keys = chatIds.map { epoch to it }
            closed.addAll(keys)
            keys.flatMap { jobs[it].orEmpty() }.distinct()
        }
        active.forEach { it.cancel(CancellationException("Chat deleted")) }
        active.joinAll()
    }
}
