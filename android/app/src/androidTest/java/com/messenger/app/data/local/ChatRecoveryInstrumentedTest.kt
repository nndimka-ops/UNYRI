package com.messenger.app.data.local

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.ui.chats.ChatDetailUiState
import com.messenger.app.ui.chats.ChatDetailViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRecoveryInstrumentedTest {
    @Test fun savedChatOpensWithoutRequestingAnAccessToken() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app,
            com.messenger.app.data.local.chat.MessengerDatabase::class.java).build()
        val state = kotlinx.coroutines.flow.MutableStateFlow<com.messenger.app.domain.model.AuthSessionState>(
            com.messenger.app.domain.model.AuthSessionState.LoggedIn(
                com.messenger.app.domain.model.AuthUser("isolated-owner", "owner", "Owner")))
        val auth = object : com.messenger.app.domain.repository.AuthRepository by app.authRepository {
            override val authState = state
            override suspend fun currentAccessToken(): String? = error("Offline open must not request a token")
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = block()
        }
        val api = com.messenger.app.data.api.ChatsApi(okhttp3.OkHttpClient(),
            kotlinx.serialization.json.Json, "http://127.0.0.1")
        val repository = com.messenger.app.data.chat.ChatRepositoryImpl(api, db.chatDao(), auth)
        try {
            db.chatDao().upsertChatMetadata("saved", "saved", "2026-01-01", null)
            assertEquals("saved", repository.getOrCreateSavedChat().getOrThrow().chatId)
            state.value = com.messenger.app.domain.model.AuthSessionState.LoggedOut
            assertTrue(repository.getOrCreateSavedChat().isFailure)
        } finally { db.close() }
    }

    @Test fun cachedChatSurvivesOfflineAndFailedEditKeepsComposerMode() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val cached = ChatSummary("isolated-recovery-test", "direct", null, "2026-01-01", null)
        val chats = object : ChatRepository by app.chatRepository {
            override fun observeChat(chatId: String) = flowOf(cached)
            override suspend fun getChat(chatId: String): Result<ChatSummary> = Result.failure(IllegalStateException("offline"))
        }
        var fail = true
        val messages = object : MessageRepository by app.messageRepository {
            override suspend fun syncLatest(chatId: String) = Result.success(Unit)
            override suspend fun editMessage(clientMessageId: String, newText: String): Result<Unit> =
                if (fail) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
        }
        val model = ChatDetailViewModel(chats, messages, app.authRepository, app.attachmentManager, app.voicePlayerController, cached.chatId)
        val holder = ViewModelStore().apply { put("recovery", model) }
        try {
            val loaded = withTimeout(3_000) { model.uiState.first { it is ChatDetailUiState.Loaded } }
            assertEquals(cached, (loaded as ChatDetailUiState.Loaded).chat)
            val target = Message("isolated-message", "isolated-message", cached.chatId, "me", "old", 1, "2026-01-01", MessageStatus.SENT)
            model.startEdit(target)
            assertFalse(model.sendComposerText("new draft"))
            assertEquals(target, model.editTarget.value)
            fail = false
            assertTrue(model.sendComposerText("new draft"))
            assertNull(model.editTarget.value)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.clear() }
        }
    }
}
