package com.messenger.app.data.signal

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AndroidSignalIndexTest {
    @Test fun `warm receives never enumerate or decrypt the store and mutations keep index coherent`() {
        val prefs = Preferences()
        val storage = AndroidKeystoreSignalStore(prefs.instance)
        assertTrue(storage.keysWithPrefix("").isEmpty())
        assertEquals(1, prefs.allReads)
        repeat(2000) { storage.putBytes("a:prekey_$it", byteArrayOf(1, 2, 3)) }
        storage.putInt("a:counter", 4)
        storage.putNamespaceBinding("binding", byteArrayOf(8))
        storage.commitReceive(mapOf("a:prekey_0" to null, "a:session_1" to byteArrayOf(4)), mapOf("a:counter" to 5))
        storage.remove("a:prekey_1")
        assertEquals(1998, storage.keysWithPrefix("a:prekey_").size)
        assertEquals(setOf("binding"), storage.keysWithPrefix("bind"))
        assertEquals(5, storage.getInt("a:counter"))
        val signal = SignalKeyStore(storage, accountNamespace = { "a" })
        val reads = prefs.stringReads
        repeat(20) { n ->
            assertContentEquals(byteArrayOf(n.toByte()), signal.receiveJournaled("$n") { byteArrayOf(n.toByte()) })
            assertEquals(n + 1, storage.keysWithPrefix("a:receive_").size)
        }
        assertEquals(20, prefs.stringReads - reads, "Admission must only read the specific replay key")
        assertEquals(StoredByteUsage(20, 20), storage.byteUsageWithPrefix("a:receive_"))
        signal.acknowledgeReceive("0")
        assertEquals(StoredByteUsage(19, 19), storage.byteUsageWithPrefix("a:receive_"))
        storage.putBytes("a:receive_1", ByteArray(7))
        assertEquals(StoredByteUsage(19, 25), storage.byteUsageWithPrefix("a:receive_"))
        storage.putInt("a:receive_1", 1)
        assertEquals(StoredByteUsage(18, 18), storage.byteUsageWithPrefix("a:receive_"))
        assertEquals(1, prefs.allReads)
    }

    @Test fun `account switch epoch fence and separately constructed logout share the correct file index`() = runBlocking {
        val prefs = Preferences()
        val storage = AndroidKeystoreSignalStore(prefs.instance)
        var owner = "a"
        var epoch = 1
        val signal = SignalKeyStore(storage, { epoch }, { owner })
        signal.receiveJournaled("one") { byteArrayOf(1) }
        signal.withAccount {
            owner = "b"; epoch++
            assertFailsWith<CancellationException> { signal.receiveJournaled("stale") { error("Stale decrypt") } }
        }
        signal.receiveJournaled("one") { byteArrayOf(2) }
        assertEquals(setOf("a:receive_one"), storage.keysWithPrefix("a:receive_"))
        assertEquals(setOf("b:receive_one"), storage.keysWithPrefix("b:receive_"))
        owner = "a"; epoch++
        assertContentEquals(byteArrayOf(1), signal.receiveJournaled("one") { error("Must replay account a") })
        assertEquals(1, prefs.allReads)
        AndroidKeystoreSignalStore(prefs.instance).eraseAllAccountSecrets()
        assertTrue(storage.keysWithPrefix("").isEmpty())
        signal.receiveJournaled("fresh") { byteArrayOf(3) }
        assertEquals(setOf("a:receive_fresh"), storage.keysWithPrefix(""))
        assertEquals(1, prefs.allReads)
    }

    @Test fun `kill replay rebuilds once and does not advance crypto or lose routing`() {
        val firstPrefs = Preferences()
        val storage = AndroidKeystoreSignalStore(firstPrefs.instance)
        val signal = SignalKeyStore(storage, accountNamespace = { "a" })
        val text = signal.receiveJournaled("one", byteArrayOf(9)) { signal.ensureIdentity(); byteArrayOf(1, 2) }
        val identity = signal.identityKeyPair.serialize()
        val restartedPrefs = Preferences(firstPrefs.disk)
        val restartedStorage = AndroidKeystoreSignalStore(restartedPrefs.instance)
        val restarted = SignalKeyStore(restartedStorage, accountNamespace = { "a" })
        assertEquals(StoredByteUsage(1, 2), restartedStorage.byteUsageWithPrefix("a:receive_"))
        assertContentEquals(text, restarted.receiveJournaled("one") { error("Replay must not decrypt") })
        assertContentEquals(identity, restarted.identityKeyPair.serialize())
        assertContentEquals(byteArrayOf(9), restarted.retainedReceiveRouting().single())
        restarted.acknowledgeReceive("one")
        assertTrue(restarted.retainedReceiveRouting().isEmpty())
        assertEquals(StoredByteUsage(0, 0), restartedStorage.byteUsageWithPrefix("a:receive_"))
        assertEquals(1, restartedPrefs.allReads)
    }

    @Test fun `different account preference files keep independent indexes even for identical key names`() {
        val a = Preferences(); val b = Preferences()
        val first = AndroidKeystoreSignalStore(a.instance)
        val second = AndroidKeystoreSignalStore(b.instance)
        first.putBytes("receive_one", byteArrayOf(1))
        second.putBytes("receive_one", byteArrayOf(2, 3))
        first.eraseAllAccountSecrets()
        assertTrue(first.keysWithPrefix("").isEmpty())
        assertEquals(StoredByteUsage(1, 2), second.byteUsageWithPrefix("receive_"))
        assertContentEquals(byteArrayOf(2, 3), second.getBytes("receive_one"))
        assertEquals(1, a.allReads); assertEquals(1, b.allReads)
    }

    @Test fun `legacy migration moves every index entry and keeps byte sizes without more getAll`() {
        val prefs = Preferences(mapOf("identity_key_pair" to Base64.getEncoder().encodeToString(byteArrayOf(1)), "counter" to 2))
        val storage = AndroidKeystoreSignalStore(prefs.instance)
        storage.migrateLegacy("a")
        assertEquals(setOf("a:identity_key_pair", "a:counter"), storage.keysWithPrefix(""))
        assertContentEquals(byteArrayOf(1), storage.getBytes("a:identity_key_pair"))
        assertEquals(StoredByteUsage(1, 1), storage.byteUsageWithPrefix("a:"))
        storage.migrateLegacy("b")
        assertTrue(storage.keysWithPrefix("b:").isEmpty())
        assertEquals(1, prefs.allReads)
    }

    @Test fun `entry cap refuses before decrypt and permits replay and capacity released by ack`() {
        val prefs = Preferences()
        val storage = AndroidKeystoreSignalStore(prefs.instance)
        val signal = SignalKeyStore(storage)
        repeat(512) { signal.receiveJournaled("$it") { byteArrayOf(1) } }
        assertFailsWith<IllegalStateException> { signal.receiveJournaled("overflow") { error("At cap") } }
        assertContentEquals(byteArrayOf(1), signal.receiveJournaled("0") { error("Replay at cap") })
        signal.acknowledgeReceive("0")
        signal.receiveJournaled("next") { byteArrayOf(2) }
        assertEquals(512, storage.byteUsageWithPrefix("receive_").entries)
        assertEquals(1, prefs.allReads)
    }

    @Test fun `32 MiB cap includes proposed plaintext and discarding a chat releases capacity`() {
        val storage = AndroidKeystoreSignalStore(Preferences().instance)
        val signal = SignalKeyStore(storage)
        val key = "${receiveChatTag("chat")}_large"
        signal.receiveJournaled(key) { ByteArray(32 * 1024 * 1024 - 1) }
        assertFailsWith<IllegalStateException> { signal.receiveJournaled("too-large") { signal.ensureIdentity(); byteArrayOf(1, 2) } }
        assertFalse(storage.contains("receive_too-large"))
        assertFalse(signal.hasIdentity(), "Oversized plaintext must discard staged crypto writes too")
        signal.receiveJournaled("last-byte") { byteArrayOf(1) }
        assertFailsWith<IllegalStateException> { signal.receiveJournaled("full") { error("At byte cap") } }
        signal.discardReceiveChat("chat")
        assertEquals(StoredByteUsage(1, 1), storage.byteUsageWithPrefix("receive_"))
        signal.receiveJournaled("after-discard") { byteArrayOf(3) }
    }

    @Test fun `failed commit invalidates cached metadata to match preferences memory and kill restores durable state`() {
        val prefs = Preferences()
        val storage = AndroidKeystoreSignalStore(prefs.instance)
        storage.putBytes("old", byteArrayOf(1))
        prefs.failCommit = true
        assertFailsWith<IllegalStateException> { storage.commitReceive(mapOf("new" to byteArrayOf(2)), emptyMap()) }
        assertEquals(setOf("old", "new"), storage.keysWithPrefix(""))
        assertEquals(2, prefs.allReads)
        val killed = AndroidKeystoreSignalStore(Preferences(prefs.disk).instance)
        assertEquals(setOf("old"), killed.keysWithPrefix(""))
    }

    /** Counts the expensive encrypted-preferences operations, without Android Keystore. */
    private class Preferences(initial: Map<String, Any> = emptyMap()) {
        val disk = initial.toMutableMap()
        private val memory = initial.toMutableMap()
        var allReads = 0
        var stringReads = 0
        var failCommit = false
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> { allReads++; memory.toMap() }
                "contains" -> memory.containsKey(args!![0])
                "getString" -> { stringReads++; memory[args!![0]] ?: args[1] }
                "getInt" -> memory[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            var clear = false
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { changes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { changes[args!![0] as String] = null; proxy }
                    "clear" -> { clear = true; proxy }
                    "commit", "apply" -> {
                        if (clear) memory.clear()
                        changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                        if (!failCommit) { disk.clear(); disk.putAll(memory) }
                        if (method.name == "commit") !failCommit else null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
