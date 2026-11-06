package com.messenger.app.call

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** The exported launcher cannot treat arbitrary Intent extras as a user tap.
 * Only our immutable notification PendingIntent carries this private nonce.
 * Persist it so answering still works after process recreation. */
object NotificationAcceptToken {
    const val EXTRA = "notification_accept_token"
    @Synchronized fun issue(context: Context, callId: String): String {
        val prefs = context.getSharedPreferences("notification_accept", Context.MODE_PRIVATE)
        val existing = prefs.getString("token", null)
        if (prefs.getString("call", null) == callId && existing != null) return existing
        val token = UUID.randomUUID().toString()
        // If persistence fails, verification fails closed after recreation;
        // the incoming call UI still permits an explicit manual answer.
        prefs.edit().putString("call", callId).putString("token", token).commit()
        return token
    }

    @Synchronized fun verify(context: Context, callId: String, supplied: String?): Boolean {
        val prefs = context.getSharedPreferences("notification_accept", Context.MODE_PRIVATE)
        return matches(prefs.getString("call", null), prefs.getString("token", null), callId, supplied)
    }

    internal fun matches(owner: String?, expected: String?, callId: String, supplied: String?): Boolean =
        owner == callId && !expected.isNullOrEmpty() && !supplied.isNullOrEmpty() &&
            MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())
}
