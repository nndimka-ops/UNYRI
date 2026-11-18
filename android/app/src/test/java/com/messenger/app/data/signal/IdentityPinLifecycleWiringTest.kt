package com.messenger.app.data.signal

import java.io.File
import kotlin.test.*

/** Framework lifecycle wiring supplements the native crypto/persistence tests. */
class IdentityPinLifecycleWiringTest {
    private fun source(path: String): String = listOf(File("src/main/java/com/messenger/app/$path"), File("app/src/main/java/com/messenger/app/$path")).first { it.isFile }.readText()
    @Test fun everySessionWipeKeepsInstallationTrustAndOnlyExplicitDeletionQueuesErase() {
        val auth = source("data/auth/AuthRepositoryImpl.kt")
        assertEquals(1, Regex("requestAccountSecurityErase\\(").findAll(auth).count())
        val deletion = auth.substringAfter("override suspend fun deleteAccount").substringBefore("suspend fun handleRevokedAccessToken")
        assertTrue(deletion.indexOf("api.deleteAccount(token, password)") < deletion.indexOf("requestAccountSecurityErase"))
        assertTrue(deletion.indexOf("requestAccountSecurityErase") < deletion.indexOf("endLocalSession()"))
        val teardown = auth.substringAfter("private suspend fun endLocalSession()")
        assertTrue(teardown.indexOf("sessionEpoch.incrementAndGet()") < teardown.indexOf("clearRoom { clearAccountData() }"))
        val app = source("MessengerApp.kt")
        assertTrue(app.contains("AndroidKeystoreSignalStore(this, \"local_security_decisions\")"))
        assertTrue(app.contains("AndroidKeystoreSignalStore(this@MessengerApp).eraseAllAccountSecrets()"))
        val cleanup = app.substringAfter("clearAccountData = {").substringBefore("accountWorkspaces.record")
        assertTrue(cleanup.contains("pendingAccountSecurityErase()?.let"))
        assertTrue(cleanup.indexOf("identityPins.eraseAccount(owner)") < cleanup.indexOf("tokenStore.completeAccountSecurityErase()"))
        val shared = source("data/auth/AccountWorkspaces.kt")
        assertTrue(shared.contains("\"local_security_decisions\""))
        assertFalse(shared.substringAfter("fun removeInactive").substringBefore("internal object").contains("eraseAccount"))
        assertFalse(source("ui/auth/AuthViewModel.kt").contains("eraseAccount"))
        assertFalse(source("ui/search/SearchViewModel.kt").contains("eraseAccount"))
    }
    @Test fun interruptedExplicitEraseIsReplayedBeforeAnySessionRestores() {
        val app = source("MessengerApp.kt")
        assertTrue(app.indexOf("pendingAccountSecurityErase()?.let") < app.indexOf("authRepository = AuthRepositoryImpl"))
        val tokens = source("data/secure/SecureTokenStore.kt")
        assertTrue(tokens.contains("putString(\"pending_account_security_erase\", owner).commit()"))
        assertFalse(tokens.substringAfter("fun clearSession()").substringBefore("companion object").contains("pending_account_security_erase"))
    }
}
