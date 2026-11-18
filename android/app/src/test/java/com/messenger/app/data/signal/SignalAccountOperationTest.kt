package com.messenger.app.data.signal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SignalAccountOperationTest {
    @Test fun `captured outbox epoch cannot enter replacement key account`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val store = SignalKeyStore(backing, accountEpoch = { 3 }, accountNamespace = { "b:device" })
        var entered = false
        assertFailsWith<CancellationException> { store.withAccount(expectedEpoch = 1) { entered = true; store.ensureIdentity() } }
        assertFalse(entered)
        assertFalse(backing.contains("b:device:identity_key_pair"))
    }

    @Test fun `logout between key namespace check and storage access rejects the stale write`() = runBlocking {
        var epoch = 1; var accesses = 0
        val backing = InMemorySecureKeyValueStore()
        val guarded = object : SecureKeyValueStore by backing {
            override fun <T> withAccess(block: () -> T): T {
                if (++accesses == 2) epoch = 3
                return block()
            }
        }
        val store = SignalKeyStore(guarded, accountEpoch = { epoch }, accountNamespace = { "a:device" })
        assertFailsWith<CancellationException> { store.withAccount { store.ensureIdentity() } }
        assertFalse(backing.contains("a:device:identity_key_pair"))
    }
    @Test fun `same account relogin rejects delayed crypto from the logged out session`() = runBlocking {
        var epoch = 1
        val backing = InMemorySecureKeyValueStore()
        val store = SignalKeyStore(backing, accountEpoch = { epoch }, accountNamespace = { "a:device" })
        assertFailsWith<CancellationException> {
            store.withAccount {
                epoch = 3 // logout, then login again with the same account/device namespace
                store.ensureIdentity()
            }
        }
        assertFalse(backing.contains("a:device:identity_key_pair"))
        store.withAccount { assertFalse(store.hasIdentity()) }
    }
    @Test fun `operation started by A cannot create keys for B`() = runBlocking {
        var owner = "a:device"
        val backing = InMemorySecureKeyValueStore()
        val store = SignalKeyStore(backing) { owner }
        assertFailsWith<CancellationException> {
            store.withAccount {
                owner = "b:device"
                store.ensureIdentity()
            }
        }
        assertFalse(backing.contains("b:device:identity_key_pair"))
    }
}
