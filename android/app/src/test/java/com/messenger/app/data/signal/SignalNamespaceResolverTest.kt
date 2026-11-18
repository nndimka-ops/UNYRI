package com.messenger.app.data.signal

import org.junit.Assert.*
import org.junit.Test

class SignalNamespaceResolverTest {
    private val origin = "http://192.168.0.106:8080"
    private val owner = "account:device"
    @Test fun `existing hashed keys stay selected after bundled URL changes`() {
        val storage = InMemorySecureKeyValueStore()
        val prefix = SignalNamespaceResolver.prefix(origin)
        val keys = SignalKeyStore(storage) { prefix + owner }
        keys.ensureIdentity()
        val identity = keys.identityKeyPair.serialize()
        val resolver = SignalNamespaceResolver(storage)
        assertEquals(prefix + owner, resolver.resolve(origin, owner, prefix))
        val afterUpdate = SignalNamespaceResolver(storage).resolve(origin, owner, "")
        assertEquals(prefix + owner, afterUpdate)
        assertArrayEquals(identity, SignalKeyStore(storage) { afterUpdate }.identityKeyPair.serialize())
    }
    @Test fun `bare keys survive logout login and upgrade without being copied`() {
        val storage = InMemorySecureKeyValueStore()
        val keys = SignalKeyStore(storage) { owner }; keys.ensureIdentity()
        val expected = keys.identityKeyPair.serialize()
        assertEquals(owner, SignalNamespaceResolver(storage).resolve(origin, owner, ""))
        assertEquals(owner, SignalNamespaceResolver(storage).resolve(origin, owner, SignalNamespaceResolver.prefix(origin)))
        assertArrayEquals(expected, keys.identityKeyPair.serialize())
        assertFalse(storage.contains(SignalNamespaceResolver.prefix(origin) + owner + ":identity_key_pair"))
    }
    @Test fun `two existing stores stay separate and a pinned choice cannot oscillate`() {
        val storage = InMemorySecureKeyValueStore(); val prefix = SignalNamespaceResolver.prefix(origin)
        val old = SignalKeyStore(storage) { owner }; old.ensureIdentity()
        val other = SignalKeyStore(storage) { prefix + owner }; other.ensureIdentity()
        val a = old.identityKeyPair.serialize(); val b = other.identityKeyPair.serialize()
        assertEquals(owner, SignalNamespaceResolver(storage).resolve(origin, owner, ""))
        assertEquals(owner, SignalNamespaceResolver(storage).resolve(origin, owner, prefix))
        assertArrayEquals(a, old.identityKeyPair.serialize()); assertArrayEquals(b, other.identityKeyPair.serialize())
    }
    @Test fun `new accounts and servers receive distinct canonical namespaces`() {
        val resolver = SignalNamespaceResolver(InMemorySecureKeyValueStore())
        val first = resolver.resolve(origin, owner, "")
        assertNotEquals(first, resolver.resolve("https://other.invalid", owner, SignalNamespaceResolver.prefix("https://other.invalid")))
        assertNotEquals(first, resolver.resolve(origin, "other:device", ""))
    }
    @Test fun `failed binding write does not allow creating keys in a guessed store`() {
        val base = InMemorySecureKeyValueStore()
        val broken = object : SecureKeyValueStore by base { override fun putNamespaceBinding(key: String, value: ByteArray) {} }
        assertTrue(runCatching { SignalNamespaceResolver(broken).resolve(origin, owner, "") }.isFailure)
    }
    @Test fun `another bundled server cannot adopt bare legacy keys`() {
        val store = InMemorySecureKeyValueStore(); val old = SignalKeyStore(store) { owner }; old.ensureIdentity()
        val remote = SignalNamespaceResolver(store).resolve("https://other.invalid", owner, "")
        assertNotEquals(owner, remote)
        assertFalse(store.contains("$remote:identity_key_pair"))
        assertTrue(old.hasIdentity())
    }

}
