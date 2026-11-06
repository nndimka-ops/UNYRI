package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class ChatClearOwner(val user: String, val server: String, val device: String)
@Serializable
data class ChatClearCommand(val owner: ChatClearOwner, val chatId: String, val request: ChatsApi.ClearHistoryRequest)

interface ChatClearStore {
    /** Atomically captures immutable bounds, journals the command and clears current local rows/drafts. */
    suspend fun prepare(owner: ChatClearOwner, chatId: String, everyone: Boolean, requestId: String, epoch: Int): ChatClearCommand
    suspend fun pending(owner: ChatClearOwner): List<ChatClearCommand>
    suspend fun acknowledge(command: ChatClearCommand, epoch: Int)
}

/** Commands live outside UI lifetime. No state lock is held during a network request. */
class BoundedChatClearQueue(
    private val scope: CoroutineScope,
    private val current: () -> Pair<ChatClearOwner, Int>?,
    private val store: ChatClearStore,
    private val applyCallClears: suspend () -> Unit,
    private val send: suspend (ChatClearCommand, Int) -> Unit,
) {
    private val flushMutex = Mutex()
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    init { scope.launch { for (wake in wakes) try { flush() } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() } catch (_: Exception) { } } }

    private fun requireOwner(ticket: Pair<ChatClearOwner, Int>) {
        if (current() != ticket) throw CancellationException("Chat clear session changed")
    }
    suspend fun submit(chatId: String, everyone: Boolean, requestId: String) {
        val ticket = current() ?: throw CancellationException("Signed out")
        scope.async {
            requireOwner(ticket)
            store.prepare(ticket.first, chatId, everyone, requestId, ticket.second)
            requireOwner(ticket)
            applyCallClears()
            trigger()
        }.await()
    }
    fun trigger() { wakes.trySend(Unit) }
    suspend fun recoverLocal() { applyCallClears() }
    suspend fun flush() = flushMutex.withLock {
        val ticket = current() ?: return@withLock
        applyCallClears()
        requireOwner(ticket)
        // Finite snapshot; each command is attempted once per wake, no recursive retries.
        for (command in store.pending(ticket.first)) {
            requireOwner(ticket)
            try { send(command, ticket.second) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: com.messenger.app.data.api.AuthApiException.Api) {
                // An unsupported endpoint must stay queued; never fall back to unbounded /delete.
                if (error.code != "chat_not_found") continue
            }
            catch (_: Exception) { continue }
            requireOwner(ticket)
            store.acknowledge(command, ticket.second)
        }
    }
}
