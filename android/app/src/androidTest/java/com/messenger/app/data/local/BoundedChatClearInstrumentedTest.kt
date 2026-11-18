package com.messenger.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.data.chat.*
import com.messenger.app.data.local.chat.*
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class BoundedChatClearInstrumentedTest {
    @Test fun atomicClearRestartDuplicateAndNewMessages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bounded-clear-test-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, MessengerDatabase::class.java, name).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { installDeletedChatGuards(db) }
        }).build()
        val owner = ChatClearOwner("alice", "https://test.invalid", "device")
        val auth = object : AuthRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("alice", "alice", "Alice")))
            override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
            override suspend fun login(username: String, password: String) = Result.success(Unit)
            override suspend fun logout() { }
            override suspend fun currentAccessToken() = "test"
            override fun currentDeviceId() = "device"
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == 1) block() else null
            override suspend fun clearRoom(block: suspend () -> Unit) = block()
        }
        val chat = java.util.UUID.randomUUID().toString(); val request = java.util.UUID.randomUUID().toString()
        fun message(id: String, sequence: Long?) = MessageEntity(id, sequence?.let { "s-$it" }, chat, "alice", id, sequence,
            "2026-10-10T12:00:00Z", if (sequence == null) "pending" else "sent")
        var db = open()
        try {
            db.messageDao().upsert(message("old", 10)); db.messageDao().upsert(message("old-pending", null))
            db.workspaceDao().put(ChatWorkspaceValue(chat, "draft", "old draft"))
            fun store() = RoomChatClearStore(db, auth, { owner }, { 10L }, { "2026-10-10T11:00:00Z" }, { work -> work() })
            val command = store().prepare(owner, chat, true, request, 1)
            assertEquals(10L, command.request.through_sequence)
            assertTrue(db.messageDao().observeMessages(chat).first().isEmpty())
            assertNull(db.workspaceDao().get(chat, "draft"))
            assertFalse(db.deletedChatDao().contains(chat))
            db.close(); db = open()
            assertEquals(command, store().pending(owner).single())
            db.messageDao().upsert(message("stale", 9)) // Trigger rejects stale history.
            db.messageDao().upsert(message("future", 11)); db.messageDao().upsert(message("new-pending", null))
            db.workspaceDao().put(ChatWorkspaceValue(chat, "draft", "new draft"))
            store().acknowledge(command, 1)
            assertEquals(command, store().prepare(owner, chat, true, request, 1))
            assertEquals(setOf("future", "new-pending"), db.messageDao().observeMessages(chat).first().map { it.clientMessageId }.toSet())
            assertEquals("new draft", db.workspaceDao().get(chat, "draft"))
            assertTrue(store().pending(owner).isEmpty())
            assertTrue(db.workspaceDao().historyArchiveValues().none { it.chatId == RoomChatClearStore.OUTBOX || it.chatId == RoomChatClearStore.DONE })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
