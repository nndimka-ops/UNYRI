package com.messenger.app.call

import android.content.Context

/** P0.1: persists the call_id that owns the incoming-call notification slot
 *  (id 4301) across process death, so a terminal call push or a process
 *  restart can prove ownership before canceling — a delayed `call_ended` for
 *  an OLD call must never cancel a NEWER call's notification.
 *
 *  Stores ONLY a call_id (no tokens/SDP/ICE/TURN credentials), in a plain
 *  SharedPreferences file. No DB table needed. */
class IncomingNotificationOwnerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The call_id currently owning the incoming-call slot, or null. */
    fun get(): String? = prefs.getString(KEY_CALL_ID, null)

    fun lastFullScreenCall(): String? = prefs.getString("last_full_screen_call", null)
    fun markFullScreenCall(callId: String) { prefs.edit().putString("last_full_screen_call", callId).apply() }

    /** Sets (or clears, when null) the owning call_id. */
    fun set(callId: String?) {
        if (callId == null) {
            prefs.edit().remove(KEY_CALL_ID).apply()
        } else {
            prefs.edit().putString(KEY_CALL_ID, callId).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "incoming_call_notification_owner"
        const val KEY_CALL_ID = "call_id"
    }
}
