package com.messenger.app.push

import android.app.PendingIntent
import android.database.sqlite.SQLiteDatabase
import androidx.core.app.NotificationCompat
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.executeCancellableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

object InactiveMessageNotifications {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(6, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun receive(app: MessengerApp, event: PushEvent.NewMessage) = withContext(Dispatchers.IO) {
        val owner = event.userId ?: return@withContext
        val device = event.deviceId ?: return@withContext
        val account = NotificationAccounts(app).find(owner, device) ?: return@withContext
        if (account.key == app.accountWorkspaces.activeKey) return@withContext
        val target = NotificationTapTarget(account.key, owner, device, event.chatId, event.messageId)
        if (!target.valid()) return@withContext
        val security = com.messenger.app.data.secure.LocalSecurityDecisions(
            com.messenger.app.data.signal.AndroidKeystoreSignalStore(account.storage, "local_security_decisions"),
            { com.messenger.app.data.secure.LocalSecurityDecisions.owner(com.messenger.app.data.api.ServerEndpoint.storageIdentity(account.origin, account.storage.getSharedPreferences("server-endpoint", android.content.Context.MODE_PRIVATE).getString("tls_identity_origin", null)), owner) })
        val settings = NotificationSettingsStore(account.storage).current
        val ledger = NotificationPostLedger(account.storage)
        val ledgerOwner = "${account.origin}|$owner"
        val muted = runCatching {
            SQLiteDatabase.openDatabase(account.storage.getDatabasePath("messenger.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT isMuted,mutedUntil,isBlocked FROM chats WHERE chatId=?", arrayOf(event.chatId)).use { row ->
                    row.moveToFirst() && (row.getInt(2) != 0 || db.rawQuery("SELECT userId FROM chat_members WHERE chatId=? AND userId<>?", arrayOf(event.chatId, owner)).use { members ->
                        var blocked = false; while (members.moveToNext()) if (security.blockDecision(members.getString(0)) == true) blocked = true; blocked
                    } || row.getInt(0) != 0 &&
                        (row.isNull(1) || Instant.parse(row.getString(1)).isAfter(Instant.now())))
                }
            }
        }.getOrNull()
        if (event.mayPresent && settings.messageAlertsEnabled && muted != true) ledger.postIfNew(ledgerOwner, event.messageId) {
            NotificationChannels.ensure(app)
            val intent = PendingIntent.getActivity(app, 0, NotificationIntents.open(app, target),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, NotificationChannels.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(app.getString(R.string.notif_new_message))
                .setContentText(if (settings.previews) account.name else app.getString(R.string.notif_tap_to_open)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(genericMessagePublicVersion(app))
                .setContentIntent(intent).setAutoCancel(true).setGroup("chat:${account.key}:${event.chatId}")
            // Inspect the current call after the IO queue wait, immediately at post.
            notification.setSilent(MessageNotificationPolicy.silent(settings.messageSound, muted == null,
                app.isMessageNotificationCallActive()))
            withContext(Dispatchers.Main.immediate) {
                if (account.key == app.accountWorkspaces.activeKey || muted == null && app.foregroundMessageBannerController.hasResumedActivity()) true
                else if (app.foregroundMessageBannerController.presentInactive(target, account.name, settings)) true
                else postNotificationIfPermitted(app, "chat:" + account.key + ":" + owner + ":" + event.chatId, 0, notification.build())
            }
        }
        // Like the iOS extension: provider arrival acknowledges delivery, never read.
        // Use only this saved account's origin and access token, without altering globals.
        withTimeoutOrNull(6500) {
            runCatching {
                val origin = com.messenger.app.data.api.ServerEndpoint.normalize(account.origin, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP)
                suspend fun refresh(rejected: String? = null): String = account.session.refreshMutex.withLock {
                    check(account.session.userId() == owner && account.session.deviceId() == device)
                    val cached = account.session.accessToken()
                    if (rejected != null && cached != null && cached != rejected) return@withLock cached
                    val oldRefresh = account.session.refreshToken() ?: error("Session unavailable")
                    val renewed = com.messenger.app.data.auth.refreshDeviceSession(client, json, origin,
                        account.session.deviceSessionBinding(createForLegacy = true), oldRefresh, account.session.refreshRequestId())
                    check(account.session.userId() == owner && account.session.deviceId() == device && account.session.refreshToken() == oldRefresh)
                    withContext(kotlinx.coroutines.NonCancellable) {
                        account.session.saveTokens(renewed.accessToken, Instant.parse(renewed.accessTokenExpiresAt), renewed.refreshToken)
                    }
                    renewed.accessToken
                }
                suspend fun confirm(access: String): String = client.executeCancellableApi(Request.Builder()
                    .url("$origin/chats/${event.chatId}/delivered").header("Authorization", "Bearer $access")
                    .post("{\"sequence\":${event.sequence.toLong()}}".toRequestBody("application/json".toMediaType())).build(), json)
                val access = account.session.accessToken() ?: refresh()
                try { confirm(access) } catch (error: com.messenger.app.data.api.AuthApiException.Api) {
                    if (com.messenger.app.data.auth.isTerminalAuthFailure(error)) throw error
                    if (error.httpStatus == 401 || error.code == "unauthorized") confirm(refresh(access)) else throw error
                }
            }.onFailure { failure ->
                if (failure is com.messenger.app.data.api.AuthApiException.Api && com.messenger.app.data.auth.isTerminalAuthFailure(failure)) {
                    app.accountWorkspaces.removeInactive(account.key, failure.code)
                }
            }
        }
    }
}
