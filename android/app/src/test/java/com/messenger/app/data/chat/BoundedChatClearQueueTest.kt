package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.api.AuthApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedChatClearQueueTest {
    private val owner = ChatClearOwner("alice", "https://one.invalid", "device")
    private class Store : ChatClearStore {
        val pending = linkedMapOf<String, ChatClearCommand>()
        val done = linkedMapOf<String, ChatClearCommand>()
        var bound = 10L
        val rows = mutableListOf(2L, 10L)
        override suspend fun prepare(owner: ChatClearOwner, chatId: String, everyone: Boolean, requestId: String, epoch: Int): ChatClearCommand {
            (pending[requestId] ?: done[requestId])?.let { return it }
            val command = ChatClearCommand(owner, chatId, ChatsApi.ClearHistoryRequest(requestId, everyone, bound))
            pending[requestId] = command
            rows.removeAll { it <= bound }
            return command
        }
        override suspend fun pending(owner: ChatClearOwner) = pending.values.filter { it.owner == owner }
        override suspend fun acknowledge(command: ChatClearCommand, epoch: Int) { pending.remove(command.request.request_id); done[command.request.request_id] = command }
    }

    @Test fun `restart replays immutable request while later messages and duplicate intent survive`() = runTest {
        val store = Store()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = BoundedChatClearQueue(firstScope, { owner to 1 }, store, {}, { _, _ -> throw AuthApiException.Network("offline") })
        val submit = async { first.submit("chat", true, "request") }; advanceUntilIdle(); submit.await()
        assertTrue(store.rows.isEmpty()); assertEquals(10L, store.pending["request"]!!.request.through_sequence)
        firstScope.cancel()
        store.rows += 11L; store.bound = 11L
        val sent = mutableListOf<ChatClearCommand>()
        val next = BoundedChatClearQueue(backgroundScope, { owner to 1 }, store, {}, { c, _ -> sent += c })
        next.flush()
        val duplicate = async { next.submit("chat", true, "request") }; runCurrent(); duplicate.await()
        assertEquals(10L, sent.single().request.through_sequence)
        assertEquals(listOf(11L), store.rows)
    }
    @Test fun `slow first network does not prevent second local clear and failure does not starve next command`() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val queue = BoundedChatClearQueue(backgroundScope, { owner to 1 }, store, {}, { c, _ ->
            if (c.chatId == "first") { entered.complete(Unit); gate.await(); throw AuthApiException.Network("offline") }
            sent += c.chatId
        })
        val a = async { queue.submit("first", false, "a") }; runCurrent(); a.await(); entered.await()
        val b = async { queue.submit("second", false, "b") }; runCurrent(); b.await()
        assertEquals(setOf("a", "b"), store.pending.keys)
        gate.complete(Unit); runCurrent()
        assertTrue("second" in sent); assertEquals(setOf("a"), store.pending.keys)
    }
    @Test fun `old owner response cannot acknowledge after account change and unsupported endpoint stays queued`() = runTest {
        val store = Store(); var current = owner to 1
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val queue = BoundedChatClearQueue(backgroundScope, { current }, store, {}, { _, _ -> entered.complete(Unit); gate.await() })
        val work = async { queue.submit("chat", false, "request") }; runCurrent(); work.await(); entered.await()
        current = owner.copy(user = "bob") to 2; gate.complete(Unit); runCurrent()
        assertEquals(setOf("request"), store.pending.keys)
        val oldServer = BoundedChatClearQueue(backgroundScope, { owner to 3 }, store, {}, { _, _ -> throw AuthApiException.Api("not_found", "HTTP 404") })
        oldServer.flush()
        assertEquals(setOf("request"), store.pending.keys)
        val gone = BoundedChatClearQueue(backgroundScope, { owner to 3 }, store, {}, { _, _ -> throw AuthApiException.Api("chat_not_found", "Missing chat") })
        gone.flush(); assertTrue(store.pending.isEmpty())
    }
}
