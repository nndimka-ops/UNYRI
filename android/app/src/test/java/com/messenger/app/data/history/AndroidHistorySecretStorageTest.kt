package com.messenger.app.data.history

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AndroidHistorySecretStorageTest {
    @Test fun `replacement is compare and set and failed persistence retains old key`() {
        val preferences=Preferences();preferences.diskFull=false
        val storage=AndroidHistorySecretStorage(preferences.instance);val name=UUID.randomUUID().toString()
        val old=ByteArray(32){1};val next=ByteArray(32){2}
        storage.write(name,old.copyOf())
        assertFalse(storage.compareAndSet(name,next,old));assertArrayEquals(old,storage.read(name))
        preferences.diskFull=true
        assertThrows(Exception::class.java){storage.compareAndSet(name,old,next)}
        assertArrayEquals(old,Base64.getDecoder().decode(preferences.disk.getValue(name)))
        preferences.diskFull=false
        assertArrayEquals(old,storage.read(name))
        assertTrue(storage.compareAndSet(name,old,next));assertArrayEquals(next,storage.read(name))
    }
    /** Models the Android commit contract: cache changes can precede disk failure. */
    private class Preferences {
        val memory=mutableMapOf<String,String>(); val disk=mutableMapOf<String,String>()
        var diskFull=true; var failBeforeCache=false; var commits=0
        val instance=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when(method.name) {
                "getString" -> memory[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending=mutableMapOf<String,String>()
            var clear = false
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy, method,args ->
                when(method.name) {
                    "putString" -> { pending[args!![0] as String]=args[1] as String; proxy }
                    "clear" -> { clear = true; proxy }
                    "commit" -> {
                        commits++
                        check(!failBeforeCache)
                        if (clear) memory.clear()
                        memory.putAll(pending)
                        if(!diskFull) { disk.clear(); disk.putAll(memory) }
                        !diskFull
                    }
                    else -> error("Unexpected editor operation")
                }
            } as SharedPreferences.Editor
        }
    }
    @Test fun `logout durably wipes all recovery keys across reopening`() {
        val preferences = Preferences(); preferences.diskFull = false
        val storage = AndroidHistorySecretStorage(preferences.instance)
        storage.write("server:alice", ByteArray(32) { 1 }); storage.write("server:bob", ByteArray(32) { 2 })
        storage.eraseAllAccountSecrets()
        assertTrue(preferences.disk.isEmpty())
        assertNull(AndroidHistorySecretStorage(preferences.instance).read("server:alice"))
        assertNull(storage.read("server:bob"))
    }
    @Test fun `logout erases signal identities ratchets trust and namespace pins together`() {
        val preferences = Preferences(); preferences.diskFull = false
        listOf("alice:identity_key_pair", "alice:session:bob", "alice:trust:bob", "namespace_binding:alice", "bob:identity_key_pair").forEach { name ->
            preferences.instance.edit().putString(name, "opaque").commit()
        }
        com.messenger.app.data.signal.AndroidKeystoreSignalStore(preferences.instance).eraseAllAccountSecrets()
        assertTrue(preferences.memory.isEmpty()); assertTrue(preferences.disk.isEmpty())
    }
    @Test fun `failed commit cache is not reported as a saved key even through a new adapter`() {
        val preferences=Preferences(); val name=UUID.randomUUID().toString(); val key=ByteArray(32){7}
        val first=AndroidHistorySecretStorage(preferences.instance)
        assertThrows(Exception::class.java){first.write(name,key.copyOf())}
        assertNotNull(preferences.memory[name]); assertNull(preferences.disk[name])
        val reopened=AndroidHistorySecretStorage(preferences.instance)
        assertThrows(Exception::class.java){reopened.read(name)}
        assertNull(preferences.disk[name])
        preferences.diskFull=false
        assertArrayEquals(key,reopened.read(name))
        assertArrayEquals(key,Base64.getDecoder().decode(preferences.disk.getValue(name)))
        assertEquals(3,preferences.commits)
    }
    @Test fun `failure before cache mutation leaves an absent key and permits a retry`() {
        val preferences=Preferences(); preferences.failBeforeCache=true
        val name=UUID.randomUUID().toString(); val storage=AndroidHistorySecretStorage(preferences.instance)
        assertThrows(Exception::class.java){storage.write(name,ByteArray(32){3})}
        assertNull(storage.read(name)); assertNull(preferences.disk[name])
        preferences.failBeforeCache=false; preferences.diskFull=false
        storage.write(name,ByteArray(32){4})
        assertArrayEquals(ByteArray(32){4},storage.read(name))
    }
}
