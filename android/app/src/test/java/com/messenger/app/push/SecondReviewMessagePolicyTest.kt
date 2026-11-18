package com.messenger.app.push

import com.messenger.app.data.api.MessageDto
import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.*
import kotlin.test.*

class SecondReviewMessagePolicyTest {
    private val dto = MessageDto("message", "chat", "client", "peer", "device", 4,
        "2026-11-13T00:00:00Z", 1, "ciphertext")
    @Test fun `known socket lifecycle kinds never present before any local row exists`() {
        for (kind in listOf("edit", "delete", "reaction", "unknown")) {
            val event = PushEvent.NewMessage.fromSocket(dto.copy(kind = kind), "owner")
            assertEquals(kind, event.operationKind)
            assertFalse(event.mayPresent)
        }
    }
    @Test fun `content and FCM fallback remain eligible but FCM cannot assert a trusted kind`() {
        assertTrue(PushEvent.NewMessage.fromSocket(dto, "owner").mayPresent)
        val id = "11111111-1111-4111-8111-111111111111"
        val event = PushPayloadParser.parse(mapOf("event" to "new_message", "chat_id" to id,
            "message_id" to id, "sequence" to "1", "kind" to "edit")) as PushEvent.NewMessage
        assertNull(event.operationKind); assertTrue(event.mayPresent)
    }
    @Test fun `all active call phases silence either accounts notifications`() {
        val call = CallInfo("call", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.INCOMING)
        val connecting = CallUiState.Connecting(call)
        for (state in listOf(CallUiState.OutgoingRinging(call), CallUiState.IncomingRinging(call), connecting,
            CallUiState.Connected(call, 0), CallUiState.Reconnecting(call, connecting))) {
            assertTrue(MessageNotificationPolicy.callActive(state))
        }
        for (state in listOf(CallUiState.Idle, CallUiState.Ended(call, CallEndReason.HANGUP),
            CallUiState.Failed(call, CallFailureReason.NETWORK))) assertFalse(MessageNotificationPolicy.callActive(state))
    }
    @Test fun `shared sound policy honors live call and preference independently`() {
        assertTrue(MessageNotificationPolicy.silent(soundEnabled = true, forced = false, activeCall = true))
        assertTrue(MessageNotificationPolicy.silent(soundEnabled = false, forced = false, activeCall = false))
        assertTrue(MessageNotificationPolicy.silent(soundEnabled = true, forced = true, activeCall = false))
        assertFalse(MessageNotificationPolicy.silent(soundEnabled = true, forced = false, activeCall = false))
    }
}
