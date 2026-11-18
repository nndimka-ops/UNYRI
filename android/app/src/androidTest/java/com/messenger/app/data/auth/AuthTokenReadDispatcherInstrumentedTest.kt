package com.messenger.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.secure.SecureTokenStore
import com.messenger.app.domain.model.AuthSessionState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted preferences, isolated file and API interceptor; never the active account. */
@RunWith(AndroidJUnit4::class)
class AuthTokenReadDispatcherInstrumentedTest {
    @Test fun validTokenReadDoesNotBlockUiHeartbeat() = runBlocking<Unit> {
        val fixture = fixture()
        val pending = fixture.scope.async { fixture.repository.currentAccessToken() }
        try {
            assertTrue("The real encrypted read must start", fixture.context.entered.await(3, TimeUnit.SECONDS))
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("A blocked encrypted read must leave Main responsive", heartbeat.await(1, TimeUnit.SECONDS))
            fixture.context.release.countDown()
            assertEquals("probe-access", withTimeout(3_000) { pending.await() })
            assertEquals("All three token fields must be read", 3, fixture.context.readOnMain.size)
            assertFalse(fixture.context.readOnMain.any { it })
            assertEquals(0, fixture.requests.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun cancellationDuringTokenSnapshotDoesNotReturnOrEraseSession() = runBlocking<Unit> {
        val fixture = fixture()
        val pending = fixture.scope.async { fixture.repository.currentAccessToken() }
        try {
            assertTrue(fixture.context.entered.await(3, TimeUnit.SECONDS))
            pending.cancel()
            fixture.context.release.countDown()
            try {
                withTimeout(3_000) { pending.await() }
                fail("Cancelled token lookup must not return credentials")
            } catch (_: CancellationException) { }
            assertTrue(fixture.repository.authState.value is AuthSessionState.LoggedIn)
            assertEquals("probe-refresh", withContext(Dispatchers.IO) { fixture.store.refreshToken() })
            assertEquals(0, fixture.requests.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun queuedLogoutKeepsOriginalCallbackContextAndClearsSession() = runBlocking<Unit> {
        val fixture = fixture()
        val token = fixture.scope.async { fixture.repository.currentAccessToken() }
        try {
            assertTrue(fixture.context.entered.await(3, TimeUnit.SECONDS))
            val logout = fixture.scope.async { fixture.repository.logout() }
            val queued = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { queued.countDown() }
            assertTrue("Logout must suspend on the mutex without blocking Main", queued.await(1, TimeUnit.SECONDS))
            fixture.context.release.countDown()
            withTimeout(5_000) { token.await(); logout.await() }
            assertTrue("Only reads move to IO; session callbacks keep their caller", fixture.cleanupOnMain.get())
            assertEquals(AuthSessionState.LoggedOut, fixture.repository.authState.value)
            assertNull(fixture.repository.currentAccessToken())
            assertNull(withContext(Dispatchers.IO) { fixture.store.refreshToken() })
        } finally {
            fixture.close()
        }
    }

    private suspend fun fixture(): Fixture = withContext(Dispatchers.IO) {
        Fixture(ApplicationProvider.getApplicationContext())
    }

    private class Fixture(base: Context) {
        val context = ReadContext(base, "isolated-token-dispatch-${UUID.randomUUID()}")
        val store = SecureTokenStore(context, context.file)
        val requests = AtomicInteger()
        val cleanupOnMain = AtomicBoolean()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("isolated fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository: AuthRepositoryImpl
        init {
            store.saveSession("probe-access", Instant.now().plusSeconds(3600), "probe-refresh", "owner", "probe", "Probe", "device")
            repository = AuthRepositoryImpl(AuthApi(client, Json, "http://127.0.0.1"), store,
                clearAccountData = { cleanupOnMain.set(Looper.myLooper() == Looper.getMainLooper()) })
            context.observing.set(true)
        }
        suspend fun close() {
            context.release.countDown()
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            context.observing.set(false)
            withContext(Dispatchers.IO) { context.baseContext.deleteSharedPreferences(context.file) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** The delegate stays genuine EncryptedSharedPreferences; the wrapper only observes its backing reads. */
    private class ReadContext(base: Context, val file: String) : ContextWrapper(base) {
        val observing = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readOnMain = CopyOnWriteArrayList<Boolean>()
        private val blockFirst = AtomicBoolean(true)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            if (name != file) return delegate
            return object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    if (observing.get()) {
                        readOnMain += Looper.myLooper() == Looper.getMainLooper()
                        if (blockFirst.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(8, TimeUnit.SECONDS)) { "Isolated token read gate timed out" }
                        }
                    }
                    return delegate.getString(key, defValue)
                }
            }
        }
    }
}
