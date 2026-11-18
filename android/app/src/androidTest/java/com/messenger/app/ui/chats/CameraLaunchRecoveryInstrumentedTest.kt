package com.messenger.app.ui.chats

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.MessageRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CameraLaunchRecoveryInstrumentedTest {
    @Test fun staleNonceAndScopeCannotSendOrRetireCurrentCapture() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture()
            try {
                val model = fixture.model()
                model.registerCameraLaunch(fixture.uri, fixture.source, "current")
                val current = fixture.intent()
                val mismatches = listOf(
                    current.copy(nonce = "old"), current.copy(owner = "another-account"),
                    current.copy(server = "https://another.example"), current.copy(device = "another-device"),
                    current.copy(chat = "another-chat"), current.copy(uri = "content://another/source"),
                    current.copy(source = fixture.source.path + ".other"),
                )
                for (mismatch in mismatches) {
                    fixture.persist(mismatch)
                    fixture.expectCancellation { model.completeCameraLaunch("current", fixture.uri, fixture.source, true) }
                    assertEquals("Stale result must preserve the active intent", mismatch, fixture.intent())
                    assertTrue("Rejected callback must not delete a source owned by the active capture", fixture.source.exists())
                    assertNull(fixture.values["cameraSendDraft"])
                }
                assertEquals(0, fixture.queuedIds.size)
            } finally { fixture.close() }
        }
    }

    @Test fun nativeCancelRetiresOnlyMatchingCaptureAndReplayCannotSend() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture()
            try {
                val model = fixture.model()
                model.registerCameraLaunch(fixture.uri, fixture.source, "cancelled")
                assertNull("An unconfirmed launch must never become an automatic retry", model.cameraRecoveryDraft())
                assertFalse(model.completeCameraLaunch("cancelled", fixture.uri, fixture.source, false))
                assertFalse(fixture.source.exists())
                assertNull(fixture.values["cameraCaptureIntent"])
                fixture.expectCancellation { model.completeCameraLaunch("cancelled", fixture.uri, fixture.source, true) }
                assertEquals(0, fixture.queuedIds.size)
            } finally { fixture.close() }
        }
    }

    @Test fun confirmedCaptureSurvivesCancelledRetryWriteAndModelRecreation() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture()
            try {
                val model = fixture.model()
                model.registerCameraLaunch(fixture.uri, fixture.source, "confirmed")
                fixture.cancelRetryWrite = true
                fixture.expectCancellation { model.completeCameraLaunch("confirmed", fixture.uri, fixture.source, true) }
                assertTrue(fixture.intent().confirmed)
                assertTrue(fixture.source.exists())
                assertNull(model.attachmentError.value)
                fixture.cancelRetryWrite = false
                val recreated = fixture.model()
                val draft = recreated.cameraRecoveryDraft()
                assertNotNull(draft)
                assertEquals(fixture.uri.toString(), draft!!.getString("uri"))
                assertEquals(fixture.source.path, draft.getString("source"))
                assertEquals(0, fixture.queuedIds.size)
            } finally { fixture.close() }
        }
    }

    @Test fun failedAndCancelledEnqueueKeepDurableRetryIdAcrossModelRecreation() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture()
            try {
                val model = fixture.model()
                fixture.queueFailure = IOException("enqueue unavailable")
                assertFalse(model.sendCameraCapture(fixture.uri, null))
                val firstId = fixture.queuedIds.single()
                assertEquals(fixture.source.parentFile!!.name, firstId)
                fixture.queueFailure = CancellationException("screen left")
                val recreated = fixture.model()
                fixture.expectCancellation { recreated.sendCameraCapture(fixture.uri, null) }
                assertNull(recreated.attachmentError.value)
                assertNotNull(recreated.cameraRecoveryDraft())
                fixture.queueFailure = null
                assertTrue(fixture.model().sendCameraCapture(fixture.uri, null))
                assertEquals(listOf(firstId, firstId, firstId), fixture.queuedIds)
                assertNull(fixture.values["cameraSendDraft"])
            } finally { fixture.close() }
        }
    }

    private class Fixture {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val source = File(app.cacheDir, "${UUID.randomUUID()}/capture.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val uri: Uri = Uri.fromFile(source)
        val values = mutableMapOf<String, String>()
        val queuedIds = mutableListOf<String>()
        var queueFailure: Exception? = null
        var cancelRetryWrite = false
        private val stores = mutableListOf<ViewModelStore>()
        private val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("camera-test-account", "camera-test", "Camera test")))
            override fun currentSessionEpoch() = 77
            override fun currentDeviceId() = "camera-test-device"
            override suspend fun reconcileHistoryContext() = Unit
            override suspend fun <T> withWorkspaceWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == 77) block() else null
        }
        private val chats = object : ChatRepository by app.chatRepository {
            override fun observeChat(chatId: String) = flowOf<ChatSummary?>(null)
            override suspend fun getChat(chatId: String): Result<ChatSummary> = Result.failure(IOException("isolated test"))
        }
        private val messages = object : MessageRepository by app.messageRepository {
            override suspend fun syncLatest(chatId: String) = Result.success(Unit)
            override suspend fun chatValue(chatId: String, name: String) = values[name]
            override suspend fun saveChatValue(chatId: String, name: String, value: String?) {
                if (name == "cameraSendDraft" && cancelRetryWrite) throw CancellationException("interrupted retry persistence")
                if (value == null) values.remove(name) else values[name] = value
            }
            override suspend fun queueMedia(chatId: String, id: String, uri: String, kind: String, caption: String?, duration: Long, albumId: String?): Result<Unit> {
                queuedIds.add(id)
                return queueFailure?.let { Result.failure(it) } ?: Result.success(Unit)
            }
        }
        fun model() = ChatDetailViewModel(chats, messages, auth, app.attachmentManager, app.voicePlayerController, "isolated-camera-intent").also {
            stores.add(ViewModelStore().apply { put("camera", it) })
        }
        fun intent() = Json.decodeFromString(CameraLaunchIntent.serializer(), values.getValue("cameraCaptureIntent"))
        fun persist(intent: CameraLaunchIntent) { values["cameraCaptureIntent"] = Json.encodeToString(CameraLaunchIntent.serializer(), intent) }
        suspend fun expectCancellation(block: suspend () -> Unit) {
            try { block(); fail("Stale or cancelled operation must propagate cancellation") }
            catch (_: CancellationException) { }
        }
        fun close() { stores.forEach { it.clear() }; source.delete(); source.parentFile!!.delete() }
    }
}
