package com.messenger.app.data.signal

import com.messenger.app.data.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.Base64
import kotlin.test.*

class HostileReceivePersistenceTest {
    @Test fun `clearing a chat cannot purge another chats retained plaintext by prefix`() {
        val disk = InMemorySecureKeyValueStore(); val store = SignalKeyStore(disk)
        store.receiveJournaled("${receiveChatTag("chat")}_one") { byteArrayOf(1) }
        store.receiveJournaled("${receiveChatTag("chat_other")}_two") { byteArrayOf(2) }
        store.discardReceiveChat("chat")
        assertEquals(1,disk.keysWithPrefix("receive_").size)
        assertContentEquals(byteArrayOf(2),store.receiveJournaled("${receiveChatTag("chat_other")}_two") { error("Must replay") })
    }
    @Test fun `conference approval captured in an old account cannot accept a replacement accounts same key`(): Unit = runBlocking {
        var epoch = 1; var owner = "account-a"
        val store = SignalKeyStore(InMemorySecureKeyValueStore(), {epoch}, {owner}).apply { ensureIdentity() }
        val remote = SignalKeyStore(InMemorySecureKeyValueStore()).apply { ensureIdentity() }.identityKeyPair.publicKey
        fun identity(dao: InMemoryPeerIdentityDao) = IdentityVerificationManager(store,dao,{owner},this,{epoch}, {expected,block -> if(expected == epoch) block()})
        val firstDao = InMemoryPeerIdentityDao(); val firstIdentity = identity(firstDao)
        firstIdentity.markChanged("peer",1,remote)
        val snapshot = DeviceTrustSnapshot("peer","device",1,Base64.getEncoder().encodeToString(remote.serialize()),false,"safety")
        val oldManager = SignalSessionManager(store,KeysApi(OkHttpClient(),Json,"https://unit.invalid"),firstIdentity)
        epoch = 2; owner = "account-b"; store.ensureIdentity()
        val secondDao = InMemoryPeerIdentityDao(); identity(secondDao).markChanged("peer",1,remote)
        assertFailsWith<CancellationException> { oldManager.approveDevice(snapshot,1) }
        assertEquals(IDENTITY_CHANGED,secondDao.get("peer",1)?.status)
        assertNull(store.getIdentity(SignalProtocolAddress("peer",1)))
    }
    private fun packet(receiver: SignalKeyStore): MessageDto {
        receiver.ensureIdentity(); receiver.generateSignedPreKey(); receiver.generateKyberPreKey()
        val signed = receiver.loadSignedPreKey(1); val kyber = receiver.loadKyberPreKey(1); val otp = receiver.generateOneTimePreKeys(1).single()
        val sender = SignalKeyStore(InMemorySecureKeyValueStore()).apply { ensureIdentity() }
        val address = SignalProtocolAddress("receiver", 1)
        SessionBuilder(sender,sender,sender,sender,address).process(PreKeyBundle(receiver.localRegistrationId,1,otp.id,otp.keyPair.publicKey,
            signed.id,signed.keyPair.publicKey,signed.signature,receiver.identityKeyPair.publicKey,kyber.id,kyber.keyPair.publicKey,kyber.signature))
        val ciphertext = SessionCipher(sender,sender,sender,sender,sender,address).encrypt("retained plaintext".toByteArray())
        return MessageDto("server", "chat", "client", "sender", "sender-device",1,"2026-01-01",1,Base64.getEncoder().encodeToString(ciphertext.serialize()))
    }
    private fun manager(store: SignalKeyStore) = SignalSessionManager(store, KeysApi(OkHttpClient.Builder().addInterceptor { error("Receive replay must not use HTTP") }.build(),Json,"https://unit.invalid"),
        IdentityVerificationManager(store,InMemoryPeerIdentityDao(), { "receiver" }, CoroutineScope(SupervisorJob())))
    @Test fun `database failure followed by process recreation replays plaintext without another ratchet step`(): Unit = runBlocking {
        val disk = InMemorySecureKeyValueStore(); val receiver = SignalKeyStore(disk)
        val dto = packet(receiver); val first = manager(receiver)
        val bytes = first.decryptRetained(dto,1)
        assertEquals("retained plaintext", String(bytes))
        val state = receiver.loadSession(SignalProtocolAddress("sender",1)).serialize()
        // A Room failure leaves the journal unacknowledged. Recreate every crypto owner.
        val restored = SignalKeyStore(disk); val afterRestart = manager(restored)
        assertContentEquals(bytes, afterRestart.decryptRetained(dto,1))
        assertContentEquals(state, restored.loadSession(SignalProtocolAddress("sender",1)).serialize())
        assertEquals(listOf(dto),afterRestart.retainedMessages())
        val dao = com.messenger.app.data.sync.InMemoryMessageDao()
        val auth = com.messenger.app.data.sync.FakeAuthRepository(token=null)
        val cursor = com.messenger.app.data.sync.InMemorySyncCursorStore()
        val pipeline = com.messenger.app.data.sync.MessageIngestionPipeline(dao,com.messenger.app.data.sync.InMemoryChatDao(),
            com.messenger.app.data.sync.InMemoryAttachmentDao(),com.messenger.app.data.sync.InMemoryReactionDao(),afterRestart,
            auth,com.messenger.app.data.sync.FakeDeliveredReporter(),cursor)
        val offline = com.messenger.app.data.sync.FakeSyncSource()
        com.messenger.app.data.sync.GlobalSyncManager(offline,pipeline,auth,cursor).runCatchUpSync()
        assertEquals("retained plaintext",dao.findByClientMessageId(dto.clientMessageId)?.text)
        assertTrue(offline.calls.isEmpty()); assertEquals(0L,cursor.lastSyncedSequence())
        assertTrue(disk.keysWithPrefix("receive_").isEmpty())
        assertTrue(afterRestart.retainedMessages().isEmpty())
        assertFailsWith<Exception> { afterRestart.decryptRetained(dto,1) }
    }
    @Test fun `failed atomic journal commit leaves session and one time keys usable and runs off caller thread`() = runBlocking {
        val disk = InMemorySecureKeyValueStore(); var fail = true; var writes = 0; var worker: String? = null
        val fault = object : SecureKeyValueStore by disk {
            override fun commitReceive(bytes: Map<String, ByteArray?>, ints: Map<String, Int>) {
                worker = Thread.currentThread().name; writes++
                if (fail) throw java.io.IOException("fixture disk failure")
                disk.commitReceive(bytes, ints)
            }
        }
        val receiver = SignalKeyStore(fault); val dto = packet(receiver); val manager = manager(receiver)
        val caller = Thread.currentThread().name
        assertFailsWith<java.io.IOException> { manager.decryptRetained(dto,1) }
        assertFalse(receiver.containsSession(SignalProtocolAddress("sender",1)))
        assertEquals(1, receiver.oneTimePreKeyCountLocal())
        fail = false
        assertEquals("retained plaintext", String(manager.decryptRetained(dto,1)))
        assertNotEquals(caller, worker)
        manager.acknowledge(dto); val before = writes
        repeat(500) { manager.acknowledge(dto) }
        assertEquals(before, writes)
    }
}
