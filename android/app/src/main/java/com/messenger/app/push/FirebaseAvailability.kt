package com.messenger.app.push

import android.content.Context
import com.google.firebase.FirebaseApp

/** Firebase is OPTIONAL in this repo (Этап K - the project/credentials are
 *  absent). This gate keeps the app fully functional without `google-services
 *  .json` while auto-enabling FCM the moment the config exists. */
object FirebaseAvailability {

    /** True only when google-services config resources are present (i.e. the
     *  `google-services.json` + gradle plugin produced `google_app_id`). */
    fun isConfigured(context: Context): Boolean =
        context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0

    /** Initializes FirebaseApp only when configured. Returns whether FCM is
     *  usable now. Safe to call once from Application.onCreate. */
    fun initialize(context: Context): Boolean {
        if (!isConfigured(context)) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
    suspend fun currentToken(context: Context): String? {
        if (!initialize(context)) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { if (continuation.isActive) continuation.resumeWith(Result.success(it)) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        }
    }

}
