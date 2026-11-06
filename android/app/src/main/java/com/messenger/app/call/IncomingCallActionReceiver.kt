package com.messenger.app.call

import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.domain.call.CallUiState

/** Routes the incoming-call notification Accept/Reject actions into the
 *  existing app-scoped [com.messenger.app.domain.call.CallController] - never
 *  creates a second controller or a separate PeerConnection. Only the call_id
 *  is trusted from the Intent; the current ringing state is re-verified before
 *  routing so a stale/foreign action can never resurrect a terminal call. */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MessengerApp ?: return
        val callId = intent.getStringExtra(IncomingCallNotificationManager.EXTRA_CALL_ID) ?: return

        if (intent.action != ACTION_REJECT) return
        val pending = goAsync()
        // A broadcast may start a fresh process; restore the authoritative call
        // first, and keep the receiver alive until rejection completes.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    app.callController.showIncomingCallFromPush(callId, "audio")?.join()
                    if (IncomingCallPresentationPolicy.shouldRouteAction(app.callController.state.value, callId)) {
                        app.callController.rejectIncoming()?.join()
                    }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.messenger.app.call.ACCEPT"
        const val ACTION_REJECT = "com.messenger.app.call.REJECT"
    }
}
