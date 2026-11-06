package com.messenger.app.data.push

import android.content.Context

/** Local pending-token storage for FCM registration (Этап K.2). The FCM token
 *  may be produced BEFORE login (`onNewToken`), so it must survive a logout
 *  (it's the same physical installation's token) while the "uploaded for the
 *  current account" flag is cleared on logout. The token is a device
 *  identifier, not an auth credential - plain prefs are sufficient. */
class PushTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun savePendingToken(token: String) {
        prefs.edit().putString(KEY_PENDING_TOKEN, token).apply()
    }

    fun pendingToken(): String? = prefs.getString(KEY_PENDING_TOKEN, null)

    fun uploadedToken(): String? = prefs.getString(KEY_UPLOADED_TOKEN, null)

    fun markUploaded(token: String) {
        prefs.edit().putString(KEY_UPLOADED_TOKEN, token).apply()
    }

    /** Whether the current FCM token has been uploaded for the current
     *  session. After logout this is cleared so the next login re-uploads. */
    fun isUploaded(): Boolean {
        val pending = pendingToken() ?: return false
        return pending == uploadedToken()
    }

    fun isUploadedFor(token: String, owner: String): Boolean =
        uploadedToken() == token && prefs.getString("uploaded_owner", null) == owner

    fun markUploadedFor(token: String, owner: String) {
        prefs.edit().putString(KEY_UPLOADED_TOKEN, token).putString("uploaded_owner", owner).apply()
    }

    fun clearUploadedFlag() {
        check(prefs.edit().remove(KEY_UPLOADED_TOKEN).remove("uploaded_owner").commit()) { "Could not erase push account binding" }
    }

    private companion object {
        const val PREFS_FILE = "messenger_push_prefs"
        const val KEY_PENDING_TOKEN = "pending_token"
        const val KEY_UPLOADED_TOKEN = "uploaded_token"
    }
}
