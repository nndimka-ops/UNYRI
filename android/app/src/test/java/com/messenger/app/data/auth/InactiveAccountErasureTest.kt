package com.messenger.app.data.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InactiveAccountErasureTest {
    @Test fun offlineRemovalQueuesOwnRefreshBeforeErasingAndRetainsLocalSecurity() = runTest {
        val events = mutableListOf<String>()
        InactiveAccountErasure.run(null, "inactive-refresh", { events += "queued:$it" },
            { events += "security" }, { events += "files-and-secrets" })
        assertEquals(listOf("queued:inactive-refresh", "files-and-secrets"), events)
    }

    @Test fun queuePersistenceFailureKeepsTheWorkspaceForRetry() = runTest {
        var erased = false
        try {
            InactiveAccountErasure.run(null, "refresh", { error("Storage full") }, {}, { erased = true })
            fail("Must preserve the only revocation credential when journaling fails")
        } catch (_: IllegalStateException) { }
        assertFalse(erased)
    }

    @Test fun deletedAccountErasesSecurityButOtherTerminalReasonsRetainIt() = runTest {
        for (code in listOf("account_deleted", "account_suspended", "session_revoked", "invalid_refresh_token")) {
            val events = mutableListOf<String>()
            assertTrue(isTerminalAuthCode(code))
            InactiveAccountErasure.run(code, "old-refresh", { fail("Terminal sessions need no logout") },
                { events += "security" }, { events += "workspace" })
            assertEquals(if (code == "account_deleted") listOf("security", "workspace") else listOf("workspace"), events)
        }
    }

    @Test fun temporaryFailuresAndPushHintsCannotAuthorizeErasure() {
        for (code in listOf("session_check", "unauthorized", "internal_error", "rate_limited", "invalid_access_token")) assertFalse(isTerminalAuthCode(code))
    }

    @Test fun authenticatedLegacyRevocationReasonsEndSessionWithoutErasingAccountSecurity() {
        assertEquals("session_revoked", sessionRevocationReason("device_revoked"))
        assertEquals("session_revoked", sessionRevocationReason(null))
        assertEquals("account_deleted", sessionRevocationReason("account_deleted"))
    }
}
