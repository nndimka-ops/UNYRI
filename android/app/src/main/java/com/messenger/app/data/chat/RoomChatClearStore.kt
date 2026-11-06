package com.messenger.app.data.chat

import androidx.room.withTransaction
import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class RoomChatClearStore(
    private val db: MessengerDatabase,
    private val auth: AuthRepository,
    private val owner: () -> ChatClearOwner?,
    private val syncCursor: () -> Long,
    private val callBoundary: suspend (String) -> String?,
    private val withDeliveryPaused: suspend (suspend () -> ChatClearCommand) -> ChatClearCommand,
) : ChatClearStore {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun prepare(owner: ChatClearOwner, chatId: String, everyone: Boolean, requestId: String, epoch: Int): ChatClearCommand {
        require(runCatching { java.util.UUID.fromString(chatId); java.util.UUID.fromString(requestId) }.isSuccess)
        if (this.owner() != owner || auth.currentSessionEpoch() != epoch) throw CancellationException("Session changed")
        return withDeliveryPaused { auth.withSessionWrite(epoch) {
            if (this.owner() != owner) throw CancellationException("Session changed")
            auth.withWorkspaceWrite(epoch) {
                db.withTransaction {
                    val workspace = db.workspaceDao()
                    (workspace.get(OUTBOX, requestId) ?: workspace.get(DONE, requestId))?.let {
                        val existing = json.decodeFromString<ChatClearCommand>(it)
                        check(existing.owner == owner && existing.chatId == chatId && existing.request.for_everyone == everyone)
                        return@withTransaction existing
                    }
                    val bound = maxOf(syncCursor(), auth.historyBeforeSequence(), db.deletedChatDao().maximumKnownSequence(), 0L)
                    val command = ChatClearCommand(owner, chatId, ChatsApi.ClearHistoryRequest(requestId, everyone, bound, callBoundary(chatId)))
                    workspace.put(ChatWorkspaceValue(OUTBOX, requestId, json.encodeToString(ChatClearCommand.serializer(), command)))
                    db.deletedChatDao().clearPersonal(chatId, bound)
                    db.deletedChatDao().hideCurrentUnsent(chatId)
                    db.deletedChatDao().clearCurrentComposer(chatId)
                    command.request.calls_before?.let { incoming ->
                        val prior = workspace.get(chatId, CALLS_CUTOFF)
                        val cutoff = if (prior != null && java.time.Instant.parse(prior) > java.time.Instant.parse(incoming)) prior else incoming
                        workspace.put(ChatWorkspaceValue(chatId, CALLS_CUTOFF, cutoff))
                    }
                    command
                }
            }
        } ?: throw CancellationException("Session changed") }
    }
    override suspend fun pending(owner: ChatClearOwner): List<ChatClearCommand> = db.workspaceDao().allValues()
        .filter { it.chatId == OUTBOX }.map { json.decodeFromString<ChatClearCommand>(it.value) }.filter { it.owner == owner }
    override suspend fun acknowledge(command: ChatClearCommand, epoch: Int) {
        auth.withSessionWrite(epoch) {
            if (owner() != command.owner) throw CancellationException("Session changed")
            db.withTransaction {
                db.workspaceDao().put(ChatWorkspaceValue(DONE, command.request.request_id, json.encodeToString(ChatClearCommand.serializer(), command)))
                db.workspaceDao().remove(OUTBOX, command.request.request_id)
            }
        } ?: throw CancellationException("Session changed")
    }
    companion object {
        const val OUTBOX = "__bounded_chat_clear_outbox__"
        const val DONE = "__bounded_chat_clear_done__"
        const val CALLS_CUTOFF = "personal_calls_clear"
        suspend fun callClears(db: MessengerDatabase): List<ChatsApi.PersonalClear> = db.workspaceDao().allValues()
            .filter { it.name == CALLS_CUTOFF }.map { ChatsApi.PersonalClear(it.chatId, 0, it.value) }
    }
}
