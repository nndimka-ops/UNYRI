package com.messenger.app.data.profile

import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundAccountActivityReporterTest {
    @Test fun `presence replaces minute HTTP contacts and disconnect restores fallback`() = runTest {
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, FakeAuthRepository()) { "https://first.example" }
        val reporter = ForegroundAccountActivityReporter(repo, backgroundScope)
        val activity = Any()
        reporter.setRealtimeConnected(true); reporter.resumed(activity); runCurrent()
        assertTrue(reporter.isResumed.value)
        advanceTimeBy(180_000); runCurrent(); assertEquals(0, remote.activityCount)
        reporter.setRealtimeConnected(false); runCurrent(); assertEquals(1, remote.activityCount)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, remote.activityCount)
        reporter.setRealtimeConnected(true); runCurrent()
        advanceTimeBy(180_000); runCurrent(); assertEquals(2, remote.activityCount)
        reporter.paused(activity); reporter.setRealtimeConnected(false)
        advanceTimeBy(180_000); runCurrent(); assertEquals(2, remote.activityCount)
    }
    @Test fun onlyResumedActivitiesGenerateForegroundAndMinuteContacts() = runTest {
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, FakeAuthRepository()) { "https://first.example" }
        val reporter = ForegroundAccountActivityReporter(repo, backgroundScope)
        reporter.sessionChanged(); runCurrent()
        assertEquals(0, remote.activityCount)
        val main = Any(); val call = Any()
        reporter.resumed(main); runCurrent()
        assertEquals(1, remote.activityCount)
        advanceTimeBy(59_999); runCurrent(); assertEquals(1, remote.activityCount)
        advanceTimeBy(1); runCurrent(); assertEquals(2, remote.activityCount)
        reporter.resumed(call); reporter.paused(main)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, remote.activityCount)
        reporter.paused(call)
        advanceTimeBy(180_000); runCurrent(); assertEquals(3, remote.activityCount)
        reporter.resumed(main); runCurrent(); assertEquals(4, remote.activityCount)
    }

    @Test fun pauseDuringTokenRefreshNeverSendsActivity() = runTest {
        val token = CompletableDeferred<String>()
        val fake = FakeAuthRepository()
        val auth = object : AuthRepository by fake { override suspend fun currentAccessToken(): String = token.await() }
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, auth) { "https://first.example" }
        val reporter = ForegroundAccountActivityReporter(repo, backgroundScope)
        val activity = Any()
        reporter.resumed(activity); runCurrent()
        reporter.paused(activity); token.complete("qa-token"); runCurrent()
        assertEquals(0, remote.activityCount)
    }

    @Test fun pauseCancelsInFlightContactAndAccountChangeStartsFreshLoop() = runTest {
        val auth = FakeAuthRepository()
        val remote = FakeAccountAutoDeleteRemote()
        var cancelled = false
        remote.beforeActivity = { try { awaitCancellation() } finally { cancelled = true } }
        val repo = AccountAutoDeleteRepository(remote, auth) { "https://first.example" }
        val reporter = ForegroundAccountActivityReporter(repo, backgroundScope)
        val activity = Any()
        reporter.resumed(activity); runCurrent()
        reporter.paused(activity); runCurrent(); assertTrue(cancelled)
        assertEquals(0, remote.activityCount)
        remote.beforeActivity = {}
        reporter.resumed(activity); runCurrent(); assertEquals(1, remote.activityCount)
        auth.switchSession("another"); auth.setToken("new-token"); reporter.sessionChanged(); runCurrent()
        assertEquals(2, remote.activityCount)
        assertEquals(listOf("token", "new-token"), remote.activityTokens)
        auth.logout(); reporter.sessionChanged()
        advanceTimeBy(120_000); runCurrent(); assertEquals(2, remote.activityCount)
    }
}
