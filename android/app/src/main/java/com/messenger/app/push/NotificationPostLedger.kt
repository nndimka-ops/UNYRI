package com.messenger.app.push

import android.content.Context
import kotlinx.coroutines.sync.Mutex

/** Account workspace storage, ids only, bounded across cold FCM process starts. */
class NotificationPostLedger(context: Context) {
    private val prefs = context.getSharedPreferences("message-notification-posts", Context.MODE_PRIVATE)
    private val transaction = NotificationPostTransaction(
        load = { prefs.getString(it, "").orEmpty().split(",") },
        save = { owner, values ->
            if (!prefs.edit().putString(owner, values.joinToString(",")).commit())
                android.util.Log.w("NotificationPost", "Durable dedup write unavailable")
        },
        mutex = writes,
    )
    suspend fun postIfNew(owner: String, id: String, post: suspend () -> Boolean): Boolean =
        transaction.post(owner, id, post)

    private companion object {
        // Include separate ledger instances opened for inactive account pushes.
        val writes = Mutex()
    }
}
