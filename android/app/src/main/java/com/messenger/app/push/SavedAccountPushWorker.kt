package com.messenger.app.push

import android.content.Context
import androidx.work.*
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.auth.AccountWorkspaceContext
import com.messenger.app.data.auth.AccountWorkspaces
import com.messenger.app.data.secure.SecureTokenStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** No credentials or tokens in WorkManager data; each retry reopens its saved owner's store. */
class SavedAccountPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAvailability.tokenForBindingWorker(applicationContext) ?: return@withContext Result.success()
            val storage = FirebaseInstallationContext(applicationContext)
            val accounts = AccountWorkspaces(storage)
            val sessions = mutableMapOf<SavedPushAccount, SecureTokenStore>()
            val journal = SavedPushBindingJournal(storage)
            fun session(account: SavedPushAccount) = sessions.getOrPut(account) {
                SecureTokenStore(AccountWorkspaceContext(storage, account.workspace))
            }
            fun saved(): List<SavedPushAccount> = accounts.accounts().map { SavedPushAccount(it.key, it.origin) }
            fun current(owner: SavedPushOwner): Boolean {
                if (owner.account !in saved()) return false
                val scoped = AccountWorkspaceContext(storage, owner.account.workspace)
                val origin = scoped.getSharedPreferences("server-endpoint", Context.MODE_PRIVATE).getString("origin", null)
                return origin == owner.account.origin && session(owner.account).let {
                    it.userId() == owner.user && it.deviceId() == owner.device && it.refreshToken() != null &&
                        it.currentPushSessionGeneration() == owner.generation
                }
            }
            val client = SavedAccountPushClient(OkHttpClient.Builder()
                .dns(com.messenger.app.data.api.AndroidSystemDns.create(storage))
                .callTimeout(8, TimeUnit.SECONDS).build(), ApiJson)
            val bindings = SavedAccountPushBindings(
                accounts = ::saved,
                owner = { account ->
                    require(ServerEndpoint.normalize(account.origin, BuildConfig.ALLOW_LOCAL_HTTP) == account.origin)
                    val store = session(account)
                    store.refreshMutex.withLock {
                        if (store.refreshToken() == null) null else {
                            val user = checkNotNull(store.userId())
                            val device = checkNotNull(store.deviceId())
                            SavedPushOwner(account, user, device, store.pushSessionGeneration())
                        }
                    }
                },
                acknowledged = journal::acknowledged,
                register = { owner, value ->
                    val store = session(owner.account)
                    client.register(owner, object : SavedPushSession {
                        override val refreshMutex = store.refreshMutex
                        override fun isCurrent() = current(owner)
                        override fun accessToken() = store.accessToken()
                        override fun expiresAt() = store.accessTokenExpiresAt()
                        override fun refreshToken() = store.refreshToken()
                        override fun refreshRequestId() = store.refreshRequestId()
                        override fun saveTokens(access: String, expiry: Instant, refresh: String) = store.saveTokens(access, expiry, refresh)
                    }, value)
                },
                isCurrent = ::current,
                acknowledge = { owner, value ->
                    journal.acknowledge(owner, value)
                    android.util.Log.i("PushRegistration", "saved binding acknowledged")
                },
                mutex = PushBindingWrites.mutex,
            )
            if (bindings.reconcile(token)) Result.success() else {
                android.util.Log.w("PushRegistration", "saved bindings pending")
                Result.retry()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("PushRegistration", "saved bindings pending: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, replace: Boolean = false): Operation {
            val work = OneTimeWorkRequestBuilder<SavedAccountPushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            return WorkManager.getInstance(context).enqueueUniqueWork("installation-push-bindings-v1",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
    }
}

/** Acknowledgements are independent of the selected account and survive partial failures/restarts. */
internal class SavedPushBindingJournal(context: Context, name: String = "installation-push-bindings-v1") {
    private val prefs = FirebaseInstallationContext(context).getSharedPreferences(name, Context.MODE_PRIVATE)
    fun acknowledged(owner: SavedPushOwner, token: String): Boolean = prefs.getString(owner.account.workspace, null) == fingerprint(owner, token)
    fun acknowledge(owner: SavedPushOwner, token: String) {
        check(prefs.edit().putString(owner.account.workspace, fingerprint(owner, token)).commit())
    }
    private fun fingerprint(owner: SavedPushOwner, token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(owner.account.origin, owner.user, owner.device, owner.generation, token).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
