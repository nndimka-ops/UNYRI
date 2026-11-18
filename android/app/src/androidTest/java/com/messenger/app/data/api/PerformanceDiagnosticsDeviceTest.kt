package com.messenger.app.data.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class PerformanceDiagnosticsDeviceTest {
    @Test fun boundedSnapshotOptOutAndSessionFence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val name = "performance-device-test"
        val preferences = app.getSharedPreferences(name, 0)
        preferences.edit().clear().putBoolean("enabled", true).commit()
        val auth = TestAuth()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var recorder: PerformanceDiagnostics
        var created = false
        try {
            instrumentation.runOnMainSync { recorder = PerformanceDiagnostics(app, auth, scope, name); created = true }
            instrumentation.waitForIdleSync()
            repeat(700) { recorder.network(2.0, 200) }
            val report = requireNotNull(recorder.snapshot())
            assertEquals("android", report.getString("platform"))
            assertEquals(600, report.getJSONArray("events").length())
            assertTrue(report.getLong("dropped") >= 100)
            assertTrue(report.toString().toByteArray().size < 256 * 1024)
            assertEquals(64, recorder.snapshot(true)!!.getJSONArray("events").length())
            assertFalse(report.toString().contains("test-user"))
            recorder.network(Double.NaN, 0)
            assertFalse(recorder.snapshot().toString().contains("NaN"))
            auth.epoch++
            assertNull(recorder.snapshot()) // Even before the asynchronous auth collector runs.
            instrumentation.runOnMainSync { auth.authState.value = AuthSessionState.LoggedOut }
            instrumentation.waitForIdleSync()
            assertNull(recorder.snapshot())
            preferences.edit().putBoolean("enabled", false).commit()
            instrumentation.waitForIdleSync()
            assertNull(recorder.snapshot())
        } finally {
            instrumentation.runOnMainSync { if (created) recorder.close(); scope.cancel() }
            preferences.edit().clear().commit()
        }
    }
    private class TestAuth : AuthRepository {
        var epoch = 1
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("test-user", "test", "test")))
        override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
        override suspend fun login(username: String, password: String) = Result.success(Unit)
        override suspend fun logout() { epoch++; authState.value = AuthSessionState.LoggedOut }
        override suspend fun currentAccessToken(): String? = null
        override fun currentDeviceId(): String? = null
        override fun currentSessionEpoch() = epoch
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epoch == epochAtStart) block() else null
        override suspend fun clearRoom(block: suspend () -> Unit) = block()
    }
}
