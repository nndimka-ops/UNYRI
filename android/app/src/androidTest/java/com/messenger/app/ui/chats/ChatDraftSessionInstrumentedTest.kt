package com.messenger.app.ui.chats

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.MessageRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ChatDraftSessionInstrumentedTest {
    @Test fun oldScreenCannotEnqueueStagedVoicePhotoOrFileForNewSession() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        var epoch = 100
        var queued = 0
        var actions = 0
        var getChatCalls = 0
        var cancelText = false
        var failCapture = false
        val cameraIds = mutableListOf<String>()
        val workspace = mutableMapOf<String, String>()
        val muteGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val auth = object : AuthRepository by app.authRepository {
            override fun currentSessionEpoch() = epoch
            override suspend fun reconcileHistoryContext() = Unit
            override suspend fun <T> withWorkspaceWrite(epochAtStart: Int, block: suspend () -> T): T? =
                if (epochAtStart == epoch) block() else null
        }
        val chats = object : ChatRepository by app.chatRepository {
            override fun observeChat(chatId: String) = flowOf<ChatSummary?>(null)
            override suspend fun getChat(chatId: String): Result<ChatSummary> { getChatCalls++; return Result.failure(IllegalStateException("isolated test")) }
            override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> { actions++; muteGate.await(); return Result.success(Unit) }
            override suspend fun clearMute(chatId: String): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun reportChat(chatId: String, reason: String, reportedUserId: String?, description: String?): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun getOrCreateSavedChat(): Result<ChatSummary> { actions++; return Result.failure(IllegalStateException("must not open saved chat")) }
        }
        val messages = object : MessageRepository by app.messageRepository {
            override suspend fun chatValue(chatId: String, name: String) = workspace[name]
            override suspend fun saveChatValue(chatId: String, name: String, value: String?) { if (value == null) workspace.remove(name) else workspace[name] = value }
            override suspend fun syncLatest(chatId: String) = Result.success(Unit)
            override suspend fun sendMessage(chatId: String, text: String): Result<Unit> { actions++; return if (cancelText) Result.failure(CancellationException("test cancellation")) else Result.success(Unit) }
            override suspend fun retry(clientMessageId: String): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun markRead(chatId: String, sequence: Long): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun deleteForMe(clientMessageId: String) { actions++ }
            override suspend fun deleteForEveryone(clientMessageId: String): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun setReaction(clientMessageId: String, emoji: String): Result<Unit> { actions++; return Result.success(Unit) }
            override suspend fun forwardMessage(sourceClientMessageId: String, destinationChatId: String): Result<Unit> { actions++; return Result.success(Unit) }
            override fun sendActivity(chatId: String, activity: String) { actions++ }
            override suspend fun scheduleText(chatId: String, text: String, due: Long): Result<Unit> { actions++; return if (cancelText) Result.failure(CancellationException("test cancellation")) else Result.success(Unit) }
            override suspend fun queueMedia(chatId: String, id: String, uri: String, kind: String, caption: String?, duration: Long, albumId: String?): Result<Unit> {
                queued++
                cameraIds.add(id)
                return if (failCapture) Result.failure(java.io.IOException("isolated enqueue failure")) else Result.success(Unit)
            }
            // Voice sends use the atomic draft queue; keep the same enqueue accounting as queueMedia.
            override suspend fun queueVoiceDraft(chatId: String, id: String, uri: String, duration: Long): Result<Unit> =
                queueMedia(chatId, id, uri, "voice", null, duration, null)
        }
        withContext(Dispatchers.Main.immediate) {
            val model = ChatDetailViewModel(chats, messages, auth, app.attachmentManager, app.voicePlayerController, "isolated-old-screen")
            val store = ViewModelStore().apply { put("test", model) }
            val captured = File(app.noBackupFilesDir, "media-drafts/${java.util.UUID.randomUUID()}/capture.jpg")
            try {
                val file = File(app.cacheDir, "isolated-staged-voice.m4a")
                assertTrue(model.sendVoiceDraft(file, 1000, "current"))
                assertEquals(1, queued)
                captured.parentFile!!.mkdirs()
                val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                try { captured.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }
                finally { bitmap.recycle() }
                failCapture = true
                assertFalse(model.sendCameraCapture(Uri.fromFile(captured), null))
                assertNotNull(workspace["cameraSendDraft"])
                val retryId = cameraIds.last()
                failCapture = false
                assertTrue(model.sendCameraCapture(Uri.fromFile(captured), null))
                assertEquals("Camera retry reuses the durable capture ID", retryId, cameraIds.last())
                assertNull(workspace["cameraSendDraft"])
                assertNull(model.attachmentError.value)
                model.sendMessage("current account text")
                assertEquals(1, actions)
                cancelText = true
                model.sendMessage("cancelled current request")
                assertNull("Cancellation is not a user-facing send failure", model.actionError.value)
                try {
                    model.scheduleText("cancelled schedule", Long.MAX_VALUE)
                    fail("Cancellation must propagate without a generic error")
                } catch (_: CancellationException) { }
                assertNull(model.actionError.value)
                cancelText = false
                model.setMute(true) // Suspend before the follow-up getChat request.
                val initialGetChatCalls = getChatCalls
                val currentActions = actions
                epoch++
                try { model.sendCameraCapture(Uri.fromFile(captured), null); fail("Old camera result must not send") }
                catch (_: CancellationException) { }
                muteGate.complete(Unit)
                kotlinx.coroutines.yield()
                assertEquals("Old request must not refresh data using the new session", initialGetChatCalls, getChatCalls)
                assertNull(model.actionError.value)
                try {
                    model.sendVoiceDraft(file, 1000, "old")
                    fail("Old voice callback must be cancelled")
                } catch (_: CancellationException) { }
                var fileCallback = false
                var photoCallback = false
                model.sendFile(Uri.fromFile(file), "old-file") { fileCallback = true }
                model.sendPhoto(Uri.fromFile(file)) { photoCallback = true }
                model.sendMessage("old text")
                model.retry("old")
                model.markRead(4)
                model.deleteForMe("old")
                model.deleteForEveryone("old")
                model.setMute(true)
                model.setBlock("peer", true)
                model.reportChat("spam", "peer", "test")
                model.forwardMessage("old", "destination")
                model.saveMessage("old")
                model.sendActivity("typing")
                assertFalse(model.scheduleText("old", Long.MAX_VALUE))
                val message = com.messenger.app.domain.model.Message("old", "server-id", "isolated-old-screen", "me", "text", 1, "2026-10-09T00:00:00Z", com.messenger.app.domain.model.MessageStatus.SENT)
                model.toggleReaction(message, "❤️")
                model.setQuickReaction(message, "👍")
                assertEquals("Old actions must not use the new account", currentActions, actions)
                assertEquals("No media from the old screen may reach the shared outbox", 3, queued)
                assertFalse(fileCallback)
                assertFalse(photoCallback)
            } finally {
                store.clear()
                captured.delete()
                captured.parentFile!!.delete()
            }
        }
    }
}
