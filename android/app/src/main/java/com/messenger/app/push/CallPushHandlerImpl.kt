package com.messenger.app.push

import com.messenger.app.domain.call.CallController

/** FCM call push (Этап K.4/K.9). The payload is only a wake hint: [CallController]
 *  reconciles the authoritative backend call before ringing, and the existing
 *  [com.messenger.app.call.IncomingCallNotificationManager] (observing
 *  `CallController.state`) is the single notification presentation path - so
 *  FCM + WS are deduplicated by construction instead of building a second
 *  incoming-call architecture. */
class CallPushHandlerImpl(
    private val callController: CallController,
) : CallPushHandler {

    override fun onIncomingCall(event: PushEvent.IncomingCall) {
        callController.showIncomingCallFromPush(event.callId, event.mediaKind)
    }

    override fun onCallEnded(event: PushEvent.CallEnded) {
        // cancel/answered_elsewhere/missed/hangup: stop ringing and let the
        // notification manager remove the notification. A late push for an
        // already-terminal call is a no-op (never resurrects it).
        callController.dismissIncomingCallFromPush(event.callId)
    }
}
