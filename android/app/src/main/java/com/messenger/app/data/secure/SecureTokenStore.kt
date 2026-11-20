package com.messenger.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.messenger.app.data.auth.DeviceBindingAvailability
import com.messenger.app.data.sync.SyncCursorStore
import java.time.Instant

/**
 * Encrypted-at-rest storage for auth tokens and session identity, backed by
 * a key in the Android Keystore. Never store tokens in plain SharedPreferences.
 */
class SecureTokenStore(context: Context, prefsName: String = PREFS_FILE_NAME) : SyncCursorStore {
    private val deviceKeyContext = context
    private var cachedDeviceBinding: com.messenger.app.data.auth.DeviceSessionBinding? = null
    private val installationPreferences = context.getSharedPreferences("installation-identity", Context.MODE_PRIVATE)

    private val prefs: SharedPreferences = WorkspaceSecrets.open(context, prefsName)

    init {
        com.messenger.app.data.identity.PeerCacheUpgrade.clearDirectoryOnce(prefs)
    }

    // EncryptedSharedPreferences wrappers differ, but Android shares their underlying
    // preference file object. Active auth, inactive delivery and push migration must
    // serialize refresh-token rotation for that same saved session.
    internal val refreshMutex = synchronized(refreshLocks) {
        refreshLocks.getOrPut(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)) { kotlinx.coroutines.sync.Mutex() }
    }

    /** Existing alias + missing key is terminal; copied encrypted preferences must never re-enroll. */
    @Synchronized
    fun deviceSessionBinding(createForLegacy: Boolean = false): com.messenger.app.data.auth.DeviceSessionBinding? {
        return DeviceBindingAvailability(
            openEnrolled = {
                prefs.getString("device_session_key", null)?.let { alias ->
                    cachedDeviceBinding?.takeIf { it.alias == alias }
                        ?: com.messenger.app.data.auth.DeviceSessionBinding.open(alias, prefs.getString("device_session_protection", null)).also { cachedDeviceBinding = it }
                }
            },
            hardwareUnavailable = ::deviceSessionHardwareUnavailable,
            create = ::createLegacyBinding,
            rememberUnavailable = { check(prefs.edit().putBoolean("device_session_hardware_unavailable", true).commit()) },
        ).binding(createForLegacy)
    }

    private fun createLegacyBinding(): com.messenger.app.data.auth.DeviceSessionBinding {
        val binding = com.messenger.app.data.auth.DeviceSessionBinding.create(deviceKeyContext)
        // A prepared key is not yet enrolled. Expire the cached legacy access atomically,
        // so a lost enrollment response forces another signed refresh before WebSocket use.
        try { check(prefs.edit().putString("device_session_key", binding.alias).putString("device_session_protection", binding.key.protection)
            .putLong(KEY_ACCESS_EXPIRES_AT, 0).commit()) }
        catch (error: Exception) { binding.erase(); throw error }
        cachedDeviceBinding = binding
        return binding
    }

    fun hasDeviceSessionBinding(): Boolean = prefs.contains("device_session_key")
    fun deviceSessionHardwareUnavailable(): Boolean = prefs.getBoolean("device_session_hardware_unavailable", false)
    fun signInHardwareUnavailable(): Boolean = prefs.getBoolean("pending_device_session_hardware_unavailable", false)
    @Synchronized
    fun createSignInBinding(): com.messenger.app.data.auth.DeviceSessionBinding? {
        prefs.getString("pending_device_session_key", null)?.let { com.messenger.app.data.auth.DeviceSessionBinding.erase(it) }
        check(prefs.edit().remove("pending_device_session_key").remove("pending_device_session_hardware_unavailable").commit())
        val binding = DeviceBindingAvailability.createOrUnbound(
            create = { com.messenger.app.data.auth.DeviceSessionBinding.create(deviceKeyContext) },
            rememberUnavailable = { check(prefs.edit().putBoolean("pending_device_session_hardware_unavailable", true).commit()) },
        ) ?: return null
        try { check(prefs.edit().putString("pending_device_session_key", binding.alias).commit()) }
        catch (error: Exception) { binding.erase(); throw error }
        return binding
    }
    @Synchronized
    fun discardSignInBinding(binding: com.messenger.app.data.auth.DeviceSessionBinding?) {
        if (binding == null) {
            check(prefs.edit().remove("pending_device_session_hardware_unavailable").commit())
            return
        }
        // A crash or interrupted cache cleanup must leave an erasable durable pending alias.
        if (prefs.getString("device_session_key", null) == binding.alias) return
        binding.erase()
        if (prefs.getString("pending_device_session_key", null) == binding.alias) {
            check(prefs.edit().remove("pending_device_session_key").commit())
        }
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
    fun eraseOfflineDirectory() { check(prefs.edit().remove("offline_directory").commit()) }

    fun saveTokens(accessToken: String, accessTokenExpiresAt: Instant, refreshToken: String) {
        val localExpiry = if (hasDeviceSessionBinding()) com.messenger.app.data.auth.DeviceAccessExpiry.localExpiry(accessToken) else accessTokenExpiresAt
        check(prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, localExpiry.epochSecond)
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
                    username: String, displayName: String, deviceId: String, historyBeforeSequence: Long? = null, signupPending: Boolean = false,
                    deviceBinding: com.messenger.app.data.auth.DeviceSessionBinding? = null) {
        val localExpiry = if (deviceBinding != null) com.messenger.app.data.auth.DeviceAccessExpiry.localExpiry(accessToken) else expiresAt
        val oldAlias = prefs.getString("device_session_key", null)
        check(prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken)
            .putBoolean("device_session_hardware_unavailable", deviceBinding == null && signInHardwareUnavailable())
            .remove("pending_device_session_key")
            .remove("pending_device_session_hardware_unavailable")
            .apply {
                if (deviceBinding == null) { remove("device_session_key"); remove("device_session_protection") }
                else { putString("device_session_key", deviceBinding.alias); putString("device_session_protection", deviceBinding.key.protection) }
            }
            .remove("conference_owner_access").putString("conference_owner_nonce", java.util.UUID.randomUUID().toString())
            .putString(KEY_PUSH_SESSION, java.util.UUID.randomUUID().toString())
            .putLong(KEY_ACCESS_EXPIRES_AT, localExpiry.epochSecond).putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId).putString(KEY_USERNAME, username).putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_DEVICE_ID, deviceId).putString(KEY_CACHE_OWNER_ID, userId)
            .putLong("history_before_sequence", historyBeforeSequence ?: 0L)
            .putBoolean("history_choice_pending", (historyBeforeSequence ?: 0L) > 0L)
            .putBoolean("signup_recovery_pending", signupPending || prefs.getBoolean("signup_recovery_${com.messenger.app.data.api.ServerEndpoint.current()}|$userId", false)).apply { if (signupPending) putBoolean("signup_recovery_${com.messenger.app.data.api.ServerEndpoint.current()}|$userId", true) }
            .putBoolean("history_boundary_known", historyBeforeSequence != null)
            .remove("history_before_date")
            .remove(KEY_REFRESH_REQUEST_ID).commit()) { "Could not persist session" }
        if (oldAlias != null && oldAlias != deviceBinding?.alias) com.messenger.app.data.auth.DeviceSessionBinding.erase(oldAlias)
        cachedDeviceBinding = deviceBinding
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

    /** Durable intent survives token cleanup and a crash during an explicit account erase. */
    fun requestAccountSecurityErase(owner: String) {
        check(prefs.edit().putString("pending_account_security_erase", owner).commit()) { "Could not record account security erase" }
    }
    fun pendingAccountSecurityErase(): String? = prefs.getString("pending_account_security_erase", null)
    fun completeAccountSecurityErase() {
        check(prefs.edit().remove("pending_account_security_erase").commit()) { "Could not complete account security erase" }
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
        check(installationPreferences.edit().putString(KEY_INSTALLATION_ID, installationId).commit())
        prefs.edit().putString(KEY_INSTALLATION_ID, installationId).apply()
    }

    fun installationId(): String? {
        installationPreferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        prefs.getString(KEY_INSTALLATION_ID, null)?.let {
            saveInstallationId(it)
            return it
        }
        // P0 one-time migration: the pre-P0 client persisted its installation
        // UUID under KEY_DEVICE_ID (back then `devices.id` WAS the client UUID,
        // so "device id" and "installation id" were the same string). Adopt it
        // as the installation id and clear KEY_DEVICE_ID so the next login
        // stores the real account-scoped server device id instead. This is
        // what lets a pre-migration device row (installation_id backfilled to
        // its old id) be REUSED on the first upgraded login rather than
        // orphaned while a brand-new duplicate device row is created.
        val legacy = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        saveInstallationId(legacy)
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

    /** Stable through refresh/restart; renewed on login even when the server reuses device_id.
     * Call initialization while holding refreshMutex, on the storage dispatcher. */
    internal fun pushSessionGeneration(): String = prefs.getString(KEY_PUSH_SESSION, null) ?: java.util.UUID.randomUUID().toString().also {
        check(prefs.edit().putString(KEY_PUSH_SESSION, it).commit())
    }
    internal fun currentPushSessionGeneration(): String? = prefs.getString(KEY_PUSH_SESSION, null)

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
    fun signupRecoveryPending(): Boolean = prefs.getBoolean("signup_recovery_pending", false)
    @Synchronized
    fun setSignupRecoveryPending(value: Boolean) { check(prefs.edit().putBoolean("signup_recovery_pending", value).putBoolean("signup_recovery_${com.messenger.app.data.api.ServerEndpoint.current()}|${userId()}", value).commit()) }

    @Synchronized
    fun clearSession() {
        // Erase hardware authorization before removing the durable alias. Retry remains possible after failure.
        prefs.getString("device_session_key", null)?.let { com.messenger.app.data.auth.DeviceSessionBinding.erase(it) }
        prefs.getString("pending_device_session_key", null)?.let { com.messenger.app.data.auth.DeviceSessionBinding.erase(it) }
        cachedDeviceBinding = null
        check(prefs.edit()
            .remove("device_session_key").remove("device_session_protection")
            .remove("pending_device_session_key")
            .remove("device_session_hardware_unavailable").remove("pending_device_session_hardware_unavailable")
            .remove("conference_owner_access").remove("conference_owner_nonce")
            .remove("offline_profile").remove("offline_devices").remove("offline_directory")
            .remove("signup_recovery_pending")
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LAST_SYNCED_SEQUENCE)
            .remove(KEY_REFRESH_REQUEST_ID)
            .remove(KEY_PUSH_SESSION)
            .remove(KEY_CACHE_OWNER_ID)
            .remove("history_before_sequence")
            .remove("history_choice_pending")
            .remove("history_boundary_known").remove("history_before_date")
            .putBoolean(KEY_CACHE_CLEANUP_PENDING, true)
            .commit()) { "Could not clear session" }
    }

    companion object {
        private val refreshLocks = java.util.WeakHashMap<SharedPreferences, kotlinx.coroutines.sync.Mutex>()
        private const val PREFS_FILE_NAME = "messenger_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_REQUEST_ID = "refresh_request_id"
        private const val KEY_PUSH_SESSION = "push_session_generation"
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
