package com.messenger.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private capability identity only. Sending the proof performs no operation. */
class NotificationRouteProofReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
