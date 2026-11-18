package com.messenger.app.data.websocket

import com.messenger.app.data.call.CallSignalingClientImpl
import com.messenger.app.data.call.CallSignalingEvent
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallRingIndicator
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.FakeCallApi
import com.messenger.app.domain.call.FakeWebRtcEngine
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CallRingingWireContractsTest {
    @Test fun capabilityUpgradeAndPeerRingingWireShapeReachTheCallController() = runBlocking {
        withTimeout(5_000L) {
            MockWebServer().use { server ->
                val remote = CompletableDeferred<WebSocket>()
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { remote.complete(webSocket) }
                }))
                val auth = object : AuthRepository by FakeAuthRepository() {
                    override suspend fun currentAccessToken() = "test-session"
                }
                val transport = MessagingSocketClient(OkHttpClient(), server.url("/ws").toString().replaceFirst("http://", "ws://"), auth, Json)
                val callClient = CallSignalingClientImpl(transport, Json)
                val controllerScope = CoroutineScope(coroutineContext + SupervisorJob())
                val row = CallDto("call-0", "me", "other", "audio", "ringing", createdAt = "now", peerRingingExpected = true)
                val api = FakeCallApi().apply { createExpected = true; getCallResult = row }
                val controller = CallController(api, callClient, FakeWebRtcEngine(), controllerScope)
                    .also { it.elapsedRealtimeMs = { 1000L } }
                try {
                    transport.start()
                    val socket = remote.await()
                    val upgrade = server.takeRequest(2, TimeUnit.SECONDS)
                    assertNotNull(upgrade)
                    assertEquals("call-ring-ack", upgrade!!.getHeader("X-Unyri-Capabilities"))
                    assertEquals("Bearer test-session", upgrade.getHeader("Authorization"))
                    controller.startCall("other", "Peer", CallMediaKind.AUDIO)
                    controller.state.first { it is CallUiState.OutgoingRinging }
                    // Fake REST creates call-0; the real socket delivery must
                    // be routed through the controller's active-call filter.
                    assertEquals(CallRingIndicator.Phase.SENDING, controller.ringIndicator.value?.phase)
                    val foreign = async(start = CoroutineStart.UNDISPATCHED) {
                        callClient.events.first { it == CallSignalingEvent.PeerRinging("foreign") }
                    }
                    socket.send("""{"type":"call.peer_ringing"}""")
                    socket.send("""{"type":"call.future","call_id":"foreign"}""")
                    socket.send("""{"type":"call.peer_ringing","call_id":"foreign"}""")
                    foreign.await(); yield()
                    assertEquals(CallRingIndicator.Phase.SENDING, controller.ringIndicator.value?.phase)
                    socket.send("""{"type":"call.peer_ringing","call_id":"call-0"}""")
                    val confirmed = controller.ringIndicator.first { it?.phase == CallRingIndicator.Phase.RINGING }
                    assertTrue(confirmed!!.rang)
                    assertTrue(confirmed.ringbackAllowed)
                    assertTrue(controller.state.value is CallUiState.OutgoingRinging)
                } finally {
                    controller.terminateForLogout()
                    controllerScope.cancel()
                    transport.stop()
                }
            }
        }
    }
}
