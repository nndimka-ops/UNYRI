package com.messenger.app.data.history

import org.junit.Assert.*
import org.junit.Test

class HistoryRecoveryKeysTest {
    private val server="11111111-1111-4111-8111-111111111111"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val other="33333333-3333-4333-8333-333333333333"
    private val key=ByteArray(32){it.toByte()}
    private class Storage : HistorySecretStorage {
        val rows=mutableMapOf<String,ByteArray>(); var writes=0; var locked=false; var loseWrite=false
        override fun read(name:String):ByteArray? { check(!locked); return rows[name]?.copyOf() }
        override fun write(name:String,value:ByteArray) { writes++; if(!loseWrite) rows[name]=value.copyOf() }
    }
    @Test fun `key persists with readback and returned bytes cannot mutate storage`() {
        val storage=Storage(); val keys=HistoryRecoveryKeys(storage,server,owner)
        assertNull(keys.load()); keys.saveConfirmed(key)
        keys.load()!!.fill(0)
        assertArrayEquals(key,keys.load()); assertArrayEquals(ByteArray(32){it.toByte()},key)
    }
    @Test fun `account and stable server namespaces are independent`() {
        val storage=Storage(); HistoryRecoveryKeys(storage,server,owner).saveConfirmed(key)
        assertNull(HistoryRecoveryKeys(storage,other,owner).load())
        assertNull(HistoryRecoveryKeys(storage,server,other).load())
    }
    @Test fun `same key is idempotent but another key never overwrites`() {
        val storage=Storage(); val keys=HistoryRecoveryKeys(storage,server,owner)
        keys.saveConfirmed(key); keys.saveConfirmed(key)
        assertThrows(Exception::class.java){keys.saveConfirmed(ByteArray(32){9})}
        assertEquals(1,storage.writes); assertArrayEquals(key,keys.load())
    }
    @Test fun `unavailable secure storage is not treated as empty`() {
        val storage=Storage(); storage.locked=true; val keys=HistoryRecoveryKeys(storage,server,owner)
        assertThrows(Exception::class.java){keys.load()}
        assertThrows(Exception::class.java){keys.saveConfirmed(key)}
        assertEquals(0,storage.writes)
    }
    @Test fun `corrupt record does not trigger replacement`() {
        val storage=Storage(); storage.rows["history-v1:$server:$owner"]=ByteArray(3)
        val keys=HistoryRecoveryKeys(storage,server,owner)
        assertThrows(Exception::class.java){keys.saveConfirmed(key)}; assertEquals(0,storage.writes)
    }
    @Test fun `lost write cannot report saved key`() {
        val storage=Storage(); storage.loseWrite=true
        assertThrows(Exception::class.java){HistoryRecoveryKeys(storage,server,owner).saveConfirmed(key)}
    }
    @Test fun `invalid owner or key fails before persistence`() {
        val storage=Storage()
        assertThrows(Exception::class.java){HistoryRecoveryKeys(storage,"../server",owner)}
        assertThrows(Exception::class.java){HistoryRecoveryKeys(storage,server,owner).saveConfirmed(ByteArray(16))}
        assertEquals(0,storage.writes)
    }
}
