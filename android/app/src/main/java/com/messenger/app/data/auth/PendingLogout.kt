package com.messenger.app.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import com.messenger.app.data.api.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Separate encrypted journal survives account cleanup. WorkManager holds IDs only. */
internal class PendingLogoutJournal(context: Context, name: String = "pending-logouts") {
    private val prefs = EncryptedSharedPreferences.create(context, name,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun save(origin: String, token: String): String {
        val id = UUID.randomUUID().toString()
        val value = org.json.JSONObject().put("origin", origin).put("token", token)
        check(prefs.edit().putString(id, value.toString()).commit())
        return id
    }
    fun ids(): Set<String> = prefs.all.keys
    fun read(id: String): String? = prefs.getString(id, null)
    fun remove(id: String) { check(prefs.edit().remove(id).commit()) }
}

object PendingLogout {
    suspend fun save(context: Context, token: String) = withContext(Dispatchers.IO) {
        val id = PendingLogoutJournal(context).save(ServerEndpoint.current(), token)
        schedule(context, id)
    }
    fun scheduleSaved(context: Context) { PendingLogoutJournal(context).ids().forEach { schedule(context, it) } }
    private fun schedule(context: Context, id: String) {
        val work = OneTimeWorkRequestBuilder<PendingLogoutWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("logout-$id", ExistingWorkPolicy.KEEP, work)
    }
    internal fun read(context: Context, id: String) = PendingLogoutJournal(context).read(id)
    internal fun remove(context: Context, id: String) = PendingLogoutJournal(context).remove(id)
}

class PendingLogoutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        try {
            val raw = PendingLogout.read(applicationContext, id) ?: return@withContext Result.success()
            val item = org.json.JSONObject(raw)
            // Fixed original endpoint, no account interceptor or credential-bearing redirect.
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS).build()
            val body = org.json.JSONObject().put("refresh_token", item.getString("token"))
            val request = Request.Builder().url(item.getString("origin").trimEnd('/') + "/auth/logout-pending")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 401) {
                    PendingLogout.remove(applicationContext, id)
                    Result.success()
                } else Result.retry()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: Exception) { Result.retry() }
    }
}
