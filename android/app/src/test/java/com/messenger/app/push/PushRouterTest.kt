package com.messenger.app.push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushRouterTest {

    private class RecordingCallHandler : CallPushHandler {
        val incoming = mutableListOf<PushEvent.IncomingCall>()
        val ended = mutableListOf<PushEvent.CallEnded>()
        override fun onIncomingCall(event: PushEvent.IncomingCall) { incoming += event }
        override fun onCallEnded(event: PushEvent.CallEnded) { ended += event }
    }

    private class RecordingMessageHandler : MessagePushHandler {
        val messages = mutableListOf<PushEvent.NewMessage>()
        override fun onNewMessage(event: PushEvent.NewMessage) { messages += event }
    }

    @Test
    fun `incoming call routes to call handler`() {
        val calls = RecordingCallHandler()
        val messages = RecordingMessageHandler()
        val router = PushRouter(calls, messages)
        val event = PushEvent.IncomingCall("6f9619ff-8b86-d011-b42d-00c04fc964ff", "audio")
        router.route(event)
        assertEquals(listOf(event), calls.incoming)
        assertEquals(0, calls.ended.size)
        assertEquals(0, messages.messages.size)
    }

    @Test
    fun `call ended routes to call handler`() {
        val calls = RecordingCallHandler()
        val messages = RecordingMessageHandler()
        val router = PushRouter(calls, messages)
        val event = PushEvent.CallEnded("6f9619ff-8b86-d011-b42d-00c04fc964ff", "cancelled")
        router.route(event)
        assertEquals(listOf(event), calls.ended)
        assertEquals(0, calls.incoming.size)
    }

    @Test
    fun `new message routes to message handler`() {
        val calls = RecordingCallHandler()
        val messages = RecordingMessageHandler()
        val router = PushRouter(calls, messages)
        val event = PushEvent.NewMessage("chat", "msg", "1")
        router.route(event)
        assertEquals(listOf(event), messages.messages)
        assertEquals(0, calls.incoming.size)
        assertEquals(0, calls.ended.size)
    }

    @Test
    fun `unknown event is a no-op`() {
        val calls = RecordingCallHandler()
        val messages = RecordingMessageHandler()
        val router = PushRouter(calls, messages)
        router.route(PushEvent.Unknown("future", mapOf("a" to "b")))
        assertEquals(0, calls.incoming.size)
        assertEquals(0, calls.ended.size)
        assertEquals(0, messages.messages.size)
    }
}
