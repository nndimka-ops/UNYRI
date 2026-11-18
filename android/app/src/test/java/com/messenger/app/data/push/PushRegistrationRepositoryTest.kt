package com.messenger.app.data.push

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationRepositoryTest {
    @Test fun `returning process revalidates persisted binding and deduplicates only its own acknowledgement`() = runTest {
        var persisted = "same-token" to "serverA|userA|deviceA|1"
        var uploads = 0
        fun restoredProcess() = PushRegistrationRepository(backgroundScope, { persisted.second },
            { "same-token" }, { "access" }, { _, _ -> uploads++ }, {},
            { token, owner -> persisted == (token to owner) }, { token, owner -> persisted = token to owner }, {})
        val first = restoredProcess()
        first.ensureRegistered(); runCurrent()
        assertEquals("A saved flag cannot prove the server still accepts this installation", 1, uploads)
        first.ensureRegistered(); runCurrent()
        assertEquals(1, uploads)
        val returned = restoredProcess()
        returned.ensureRegistered(); runCurrent()
        assertEquals("Session epochs restart at 1 after a workspace process restart", 2, uploads)
    }
    @Test fun `explicit acquisition recovers missed callback and failed upload on reconnect`() = runTest {
        var uploads = 0; var acquired = 0; var saved: String? = null; var ack: Pair<String,String>? = null
        val repository = PushRegistrationRepository(backgroundScope, { "serverA|userA" },
            { acquired++; "real-token" }, { "access" }, { _, _ -> uploads++; if (uploads == 1) error("network") },
            { saved = it }, { token, owner -> ack == (token to owner) }, { token, owner -> ack = token to owner }, { ack = null })
        repository.ensureRegistered(); runCurrent()
        assertEquals("real-token", saved); assertNull(ack)
        repository.ensureRegistered(); runCurrent(); assertEquals(2, uploads)
        repository.ensureRegistered(); runCurrent(); assertEquals(2, uploads); assertEquals(3, acquired)
    }
    @Test fun `late old account acknowledgement is discarded and unavailable firebase never uploads`() = runTest {
        var owner: String? = "serverA|userA"; var ack: Pair<String,String>? = null; var configured = true; var uploads = 0
        val gate = CompletableDeferred<Unit>()
        val repository = PushRegistrationRepository(backgroundScope, { owner }, { if (configured) "real-token" else null }, { "access" },
            { _, _ -> uploads++; withContext(NonCancellable) { gate.await() } }, {},
            { token, identity -> ack == (token to identity) }, { token, identity -> ack = token to identity }, { ack = null })
        repository.ensureRegistered(); runCurrent()
        repository.onLogout(); owner = "serverB|userB"; gate.complete(Unit); runCurrent(); assertNull(ack)
        configured = false; repository.ensureRegistered(); runCurrent(); assertEquals(1, uploads)
        configured = true; repository.ensureRegistered(); runCurrent(); assertEquals("serverB|userB", ack?.second)
    }
}
