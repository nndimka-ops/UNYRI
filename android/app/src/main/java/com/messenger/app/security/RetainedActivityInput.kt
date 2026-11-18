package com.messenger.app.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.messenger.app.MainActivity
import com.messenger.app.push.NotificationIntents

/** Raw inputs go only to explicit ingress handlers, never to NavController's activity intent. */
internal object RetainedActivityInput {
    data class Captured(val retained: Intent, val profile: PublicProfileLink?)

    /** Profile links leave the boundary as a typed value, never as Navigation data or extras. */
    fun capture(context: Context, source: Intent, pendingNotification: Intent? = null): Captured = Captured(
        create(context, source, pendingNotification), PublicProfileLink.parse(source.action, source.dataString))

    fun create(context: Context, source: Intent, pendingNotification: Intent? = null): Intent {
        val retained = Intent(context, MainActivity::class.java).setAction(source.action).setFlags(source.flags)
        source.categories?.forEach(retained::addCategory)
        val verified = NotificationIntents.verifiedCopy(context, source)
            ?: pendingNotification?.let { NotificationIntents.verifiedCopy(context, it) }
        val allowed = RetainedActivityInputPolicy.keys(verified != null, source.action)
        verified?.extras?.let { extras ->
            // The copy contains only normalized fields and the verified OS capability.
            extras.keySet().filter { it in allowed }.forEach { key ->
                if (key == NotificationIntents.EXTRA_PROOF) {
                    @Suppress("DEPRECATION")
                    retained.putExtra(key, extras.getParcelable<android.app.PendingIntent>(key))
                } else retained.putExtra(key, extras.getString(key))
            }
        }
        if (RetainedActivityInputPolicy.STREAM in allowed) {
            retained.type = source.type
            @Suppress("DEPRECATION")
            val streams = runCatching {
                val values = if (source.action == Intent.ACTION_SEND) listOfNotNull(source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                else source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                values.filter { it.scheme == "content" }.distinct().take(10)
            }.getOrDefault(emptyList())
            if (source.action == Intent.ACTION_SEND) streams.firstOrNull()?.let { retained.putExtra(Intent.EXTRA_STREAM, it) }
            else retained.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))
        }
        // No data, selector, clip intents or unlisted extras survive, regardless of URI casing.
        return retained
    }
}
