package com.messenger.app.push

import android.content.Context
import com.google.firebase.FirebaseApp

/** Firebase is OPTIONAL in this repo (Этап K - the project/credentials are
 *  absent). This gate keeps the app fully functional without `google-services
 *  .json` while auto-enabling FCM the moment the config exists. */
object FirebaseAvailability {
    private var tokenRecovery: InstallationTokenRecovery? = null

    /** True only when google-services config resources are present (i.e. the
     *  `google-services.json` + gradle plugin produced `google_app_id`). */
    fun isConfigured(context: Context): Boolean =
        context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0

    /** Initializes FirebaseApp only when configured. Returns whether FCM is
     *  usable now. Safe to call once from Application.onCreate. */
    @Synchronized fun initialize(context: Context): Boolean {
        if (!isConfigured(context)) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(FirebaseInstallationContext(context))
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
    suspend fun currentToken(context: Context): String? = token(context, scheduleBindings = true)

    /** The calling WorkManager job is already durable before any SDK reset. */
    internal suspend fun tokenForBindingWorker(context: Context): String? = token(context, scheduleBindings = false)

    private suspend fun token(context: Context, scheduleBindings: Boolean): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!initialize(context)) return@withContext null
        // Persist retry work BEFORE invalidating a token used by any saved account.
        if (scheduleBindings) SavedAccountPushWorker.enqueue(context).result.get()
        val recovery = synchronized(this@FirebaseAvailability) {
            tokenRecovery ?: run {
                val installation = FirebaseApp.getInstance().applicationContext
                val prefs = installation.getSharedPreferences("firebase-installation-storage", Context.MODE_PRIVATE)
                InstallationTokenRecovery(
                    inspected = { prefs.getBoolean("storage-inspected-v2", false) },
                    requiresReset = {
                        !prefs.getBoolean("partitioned-token-repaired", false) &&
                            java.io.File(installation.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().any {
                                it.name.startsWith("account_") && it.name.endsWith("_com.google.android.gms.appid.xml")
                            }
                    },
                    reset = { com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().awaitResult(); Unit },
                    complete = { check(prefs.edit().putBoolean("partitioned-token-repaired", true)
                        .putBoolean("storage-inspected-v2", true).commit()) },
                    prepareReset = { SavedAccountPushWorker.enqueue(installation).result.get(); Unit },
                ).also { tokenRecovery = it }
            }
        }
        if (recovery.beforeToken()) android.util.Log.i("PushRegistration", "repaired partitioned Firebase token")
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.awaitResult()
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resumeWith(Result.success(it)) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        }
}
