package com.messenger.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.messenger.app.data.sync.SyncCursorStore
import java.time.Instant

/**
 * Encrypted-at-rest storage for auth tokens and session identity, backed by
 * a key in the Android Keystore. Never store tokens in plain SharedPreferences.
 */
class SecureTokenStore(context: Context, prefsName: String = PREFS_FILE_NAME) : SyncCursorStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun screenOwner(): String? = userId()?.let {
        "${com.messenger.app.data.api.ServerEndpoint.current()}|$it|${deviceId()}"
    }

    /** Only called on demand; encrypted storage is already opened for this session. */
    @Synchronized
    fun conferenceRoomOwner(): com.messenger.app.conference.ConferenceRoomOwner? {
        val user = userId() ?: return null
        val device = deviceId() ?: return null
        val origin = com.messenger.app.data.api.ServerEndpoint.current()
        if (runCatching { com.messenger.app.conference.ConferenceInvite.origin(origin) }.getOrNull() != origin) return null
        val nonce = prefs.getString("conference_owner_nonce", null) ?: java.util.UUID.randomUUID().toString().also {
            check(prefs.edit().putString("conference_owner_nonce", it).commit())
        }
        return com.messenger.app.conference.ConferenceRoomOwner(origin, user, device, nonce)
    }

    @Synchronized
    fun readConferenceRoomAccess(owner: com.messenger.app.conference.ConferenceRoomOwner): String? =
        if (conferenceRoomOwner() == owner) prefs.getString("conference_owner_access", null) else null

    @Synchronized
    fun saveConferenceRoomAccess(owner: com.messenger.app.conference.ConferenceRoomOwner, value: String?): Boolean {
        if (conferenceRoomOwner() != owner || (value?.length ?: 0) > 12_288) return false
        val edit = prefs.edit()
        if (value == null) edit.remove("conference_owner_access") else edit.putString("conference_owner_access", value)
        return edit.commit()
    }
    fun offlineScreenOwner(key: String = "profile"): String? = screenOwner()?.let { owner ->
        if (key == "directory") java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) } else owner
    }
    @kotlinx.serialization.Serializable
    private data class OfflineScreen(val owner: String, val body: String)
    @Synchronized
    fun offlineScreen(key: String): String? {
        if (key !in setOf("profile", "devices", "directory")) return null
        val owner = offlineScreenOwner(key) ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<OfflineScreen>(prefs.getString("offline_$key", null) ?: return null)
                .takeIf { it.owner == owner }?.body
        }.getOrNull()
    }
    @Synchronized
    fun saveOfflineScreen(key: String, owner: String?, body: String) {
        if (key !in setOf("profile", "devices", "directory") || owner == null || owner != offlineScreenOwner(key) || body.length > 65536) return
        val encoded = kotlinx.serialization.json.Json.encodeToString(OfflineScreen.serializer(), OfflineScreen(owner, body))
        check(prefs.edit().putString("offline_$key", encoded).commit())
    }

    fun saveTokens(accessToken: String, accessTokenExpiresAt: Instant, refreshToken: String) {
        check(prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, accessTokenExpiresAt.epochSecond)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .remove(KEY_REFRESH_REQUEST_ID)
            .commit()) { "Could not persist refreshed session" }
    }

    fun refreshRequestId(): String {
        prefs.getString(KEY_REFRESH_REQUEST_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        check(prefs.edit().putString(KEY_REFRESH_REQUEST_ID, id).commit()) { "Could not persist refresh attempt" }
        return id
    }

    @Synchronized
    fun saveSession(accessToken: String, expiresAt: Instant, refreshToken: String, userId: String,
                    username: String, displayName: String, deviceId: String, historyBeforeSequence: Long? = null) {
        check(prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken)
            .remove("conference_owner_access").putString("conference_owner_nonce", java.util.UUID.randomUUID().toString())
            .putLong(KEY_ACCESS_EXPIRES_AT, expiresAt.epochSecond).putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId).putString(KEY_USERNAME, username).putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_DEVICE_ID, deviceId).putString(KEY_CACHE_OWNER_ID, userId)
            .putLong("history_before_sequence", historyBeforeSequence ?: 0L)
            .putBoolean("history_choice_pending", (historyBeforeSequence ?: 0L) > 0L)
            .putBoolean("history_boundary_known", historyBeforeSequence != null)
            .remove("history_before_date")
            .remove(KEY_REFRESH_REQUEST_ID).commit()) { "Could not persist session" }
    }

    fun historyBeforeSequence(): Long = if (userId() != null) prefs.getLong("history_before_sequence", 0L) else 0L
    fun historyContext(): com.messenger.app.domain.repository.HistoryContext? {
        if (userId() == null) return null
        val date = prefs.getString("history_before_date", null) ?: return null
        return com.messenger.app.domain.repository.HistoryContext(historyBeforeSequence(), date)
    }
    fun saveHistoryContext(sequence: Long, date: String) {
        val boundary = if (prefs.getBoolean("history_boundary_known", false) || historyBeforeSequence() > 0) historyBeforeSequence() else sequence
        check(prefs.edit().putLong("history_before_sequence", boundary).putString("history_before_date", date)
            .putBoolean("history_boundary_known", true).commit()) { "Could not persist history context" }
    }
    fun needsHistoryChoice(): Boolean = userId() != null && prefs.getBoolean("history_choice_pending", false)
    fun completeHistoryChoice() {
        check(prefs.edit().putBoolean("history_choice_pending", false).commit()) { "Could not save history choice" }
    }

    fun cacheOwnerId(): String? = prefs.getString(KEY_CACHE_OWNER_ID, null) ?: userId()
    fun cacheCleanupPending(): Boolean = prefs.getBoolean(KEY_CACHE_CLEANUP_PENDING, false)
    fun completeCacheCleanup() {
        check(prefs.edit().remove(KEY_CACHE_CLEANUP_PENDING).commit()) { "Could not record cache cleanup" }
    }

    fun deviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    /** Stable, app-generated INSTALLATION identifier - persists across logout
     *  so the same physical installation can be reused by different accounts
     *  (each account gets its own account-scoped `device_id`). */
    fun saveInstallationId(installationId: String) {
        prefs.edit().putString(KEY_INSTALLATION_ID, installationId).apply()
    }

    fun installationId(): String? {
        prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        // P0 one-time migration: the pre-P0 client persisted its installation
        // UUID under KEY_DEVICE_ID (back then `devices.id` WAS the client UUID,
        // so "device id" and "installation id" were the same string). Adopt it
        // as the installation id and clear KEY_DEVICE_ID so the next login
        // stores the real account-scoped server device id instead. This is
        // what lets a pre-migration device row (installation_id backfilled to
        // its old id) be REUSED on the first upgraded login rather than
        // orphaned while a brand-new duplicate device row is created.
        val legacy = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        prefs.edit()
            .putString(KEY_INSTALLATION_ID, legacy)
            .apply()
        return legacy
    }
    fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun userId(): String? = prefs.getString(KEY_USER_ID, null)
    fun username(): String? = prefs.getString(KEY_USERNAME, null)
    fun displayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun accessTokenExpiresAt(): Instant? {
        val epochSeconds = prefs.getLong(KEY_ACCESS_EXPIRES_AT, -1)
        return if (epochSeconds >= 0) Instant.ofEpochSecond(epochSeconds) else null
    }

    /** Durable global-sync cursor (`GET /sync?after_sequence=`) - the
     *  furthest `messages.sequence` this device has durably ingested
     *  (decrypted + written to Room), across every chat. Cleared on
     *  logout: a different real account can never reuse this device_id
     *  (the server rejects it - see Этап D), but clearing defensively
     *  keeps the invariant "no session's data ever leaks into the next"
     *  true even if that server-side guarantee ever changed. */
    override fun lastSyncedSequence(): Long = prefs.getLong(KEY_LAST_SYNCED_SEQUENCE, 0L)

    override fun saveLastSyncedSequence(sequence: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED_SEQUENCE, sequence).apply()
    }

    @Synchronized
    fun clearSession() {
        check(prefs.edit()
            .remove("conference_owner_access").remove("conference_owner_nonce")
            .remove("offline_profile").remove("offline_devices").remove("offline_directory")
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LAST_SYNCED_SEQUENCE)
            .remove(KEY_REFRESH_REQUEST_ID)
            .remove(KEY_CACHE_OWNER_ID)
            .remove("history_before_sequence")
            .remove("history_choice_pending")
            .remove("history_boundary_known").remove("history_before_date")
            .putBoolean(KEY_CACHE_CLEANUP_PENDING, true)
            .commit()) { "Could not clear session" }
    }

    companion object {
        private const val PREFS_FILE_NAME = "messenger_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_REQUEST_ID = "refresh_request_id"
        private const val KEY_CACHE_OWNER_ID = "cache_owner_id"
        private const val KEY_CACHE_CLEANUP_PENDING = "cache_cleanup_pending"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_LAST_SYNCED_SEQUENCE = "last_synced_sequence"
    }
}
