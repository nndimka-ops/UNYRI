package com.messenger.app.push

import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.data.auth.AccountWorkspaceContext
import com.messenger.app.data.auth.AccountWorkspaceLayout
import com.messenger.app.data.secure.SecureTokenStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SavedPushBindingJournalInstrumentedTest {
    @Test fun accountAcknowledgementsSurviveRestartAndWorkspaceChangesButNotOwnerOrTokenChanges() {
        val base = ApplicationProvider.getApplicationContext<MessengerApp>().baseContext
        val name = "push-binding-fixture-${UUID.randomUUID()}"
        val a = SavedPushOwner(SavedPushAccount(UUID.randomUUID().toString(), "https://a.example"), "alice", "a-device")
        val b = SavedPushOwner(SavedPushAccount(UUID.randomUUID().toString(), "https://b.example"), "bob", "b-device")
        try {
            SavedPushBindingJournal(AccountWorkspaceContext(base, b.account.workspace), name).acknowledge(a, "token")
            val restored = SavedPushBindingJournal(AccountWorkspaceContext(base, a.account.workspace), name)
            assertTrue(restored.acknowledged(a, "token"))
            assertFalse(restored.acknowledged(b, "token"))
            assertFalse(restored.acknowledged(a, "replacement"))
            assertFalse(restored.acknowledged(a.copy(device = "new-device"), "token"))
            assertFalse(restored.acknowledged(a.copy(generation = "new-login"), "token"))
            assertFalse(restored.acknowledged(a.copy(account = a.account.copy(origin = "https://other.example")), "token"))
            restored.acknowledge(b, "token")
            assertTrue(restored.acknowledged(a, "token"))
            assertTrue(SavedPushBindingJournal(base, name).acknowledged(b, "token"))
            assertFalse(base.getSharedPreferences(name, 0).all.values.any { it == "token" })
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun everyWrapperForOneEncryptedSessionSharesRefreshRotationLockButAccountsStayIsolated() {
        val base = ApplicationProvider.getApplicationContext<MessengerApp>().baseContext
        val name = "refresh-lock-fixture-${UUID.randomUUID()}"
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        try {
            val first = SecureTokenStore(AccountWorkspaceContext(base, a), name)
            val same = SecureTokenStore(AccountWorkspaceContext(base, a), name)
            val other = SecureTokenStore(AccountWorkspaceContext(base, b), name)
            assertSame(first.refreshMutex, same.refreshMutex)
            assertNotSame(first.refreshMutex, other.refreshMutex)
        } finally {
            base.deleteSharedPreferences(AccountWorkspaceLayout.preferenceName(a, name))
            base.deleteSharedPreferences(AccountWorkspaceLayout.preferenceName(b, name))
        }
    }

    @Test fun sessionGenerationSurvivesRefreshAndReconstructionButChangesOnLogin() {
        val base = ApplicationProvider.getApplicationContext<MessengerApp>().baseContext
        val name = "session-generation-fixture-${UUID.randomUUID()}"
        try {
            val first = SecureTokenStore(base, name)
            val expiry = java.time.Instant.parse("2100-01-01T00:00:00Z")
            first.saveSession("access", expiry, "refresh", "user", "name", "Name", "device")
            val generation = first.pushSessionGeneration()
            first.saveTokens("new-access", expiry, "new-refresh")
            assertEquals(generation, SecureTokenStore(base, name).pushSessionGeneration())
            first.clearSession()
            first.saveSession("access", expiry, "refresh", "user", "name", "Name", "device")
            assertNotEquals(generation, first.pushSessionGeneration())
        } finally { base.deleteSharedPreferences(name) }
    }
}
