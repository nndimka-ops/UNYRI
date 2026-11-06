package com.messenger.app.push

/** Boundary between the FCM entry point and the two push domains. Pure JVM,
 *  unit-testable with fakes (Этап K.13). */
interface CallPushHandler {
    fun onIncomingCall(event: PushEvent.IncomingCall)
    fun onCallEnded(event: PushEvent.CallEnded)
}

interface MessagePushHandler {
    fun onNewMessage(event: PushEvent.NewMessage)
}

/** Routes a parsed event. Unknown/malformed events are a safe no-op. */
class PushRouter(
    private val callHandler: CallPushHandler,
    private val messageHandler: MessagePushHandler,
) {
    fun route(event: PushEvent) {
        when (event) {
            is PushEvent.IncomingCall -> callHandler.onIncomingCall(event)
            is PushEvent.CallEnded -> callHandler.onCallEnded(event)
            is PushEvent.NewMessage -> messageHandler.onNewMessage(event)
            is PushEvent.AdminNotice -> Unit // User-visible handling lives at the FCM service boundary.
            is PushEvent.Unknown -> Unit
        }
    }
}
