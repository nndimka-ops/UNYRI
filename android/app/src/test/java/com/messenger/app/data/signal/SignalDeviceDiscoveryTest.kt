package com.messenger.app.data.signal

import com.messenger.app.data.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import org.junit.Test
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import kotlin.test.*

/** Real libsignal sessions and real API encoding; only HTTP responses are local. */
class SignalDeviceDiscoveryTest {
    @Test fun revokedOwnDeviceNeverRejoinsFanoutOrDecryptsAfterApproval() = runBlocking {
        val disk = InMemorySecureKeyValueStore()
        val security = com.messenger.app.data.secure.LocalSecurityDecisions(disk) { "account" }
        withManager(security) { manager, _, setPeers, _, _ ->
            val revoked = Peer(2, "revoked-own"); val good = Peer(3, "good-own")
            setPeers(listOf(revoked, good))
            manager.buildFanout("local", "token", byteArrayOf(1))
            manager.identityVerification.acceptNewKey("local", 2)
            manager.identityVerification.acceptNewKey("local", 3)
            assertEquals(2, manager.buildFanout("local", "token", byteArrayOf(1)).envelopes.size)
            manager.revokeOwnDevice("local", "revoked-own", "token")
            val fanout = manager.buildFanout("local", "token", byteArrayOf(2))
            assertEquals(listOf("good-own"), fanout.envelopes.map { it.recipientDeviceId })
            assertEquals(listOf("revoked-own"), fanout.withheldDeviceIds)
            // Accepting a new key cannot undo an explicit removal.
            manager.identityVerification.acceptNewKey("local", 2)
            assertEquals(listOf("good-own"), manager.buildFanout("local", "token", byteArrayOf(3)).envelopes.map { it.recipientDeviceId })
            assertEquals("Device locally revoked", assertFails { manager.decrypt("local", 2, 2, "AA==") }.message)
            // Reuse the revoked device identity under an unrelated claimed peer.
            setPeers(listOf(revoked))
            assertFailsWith<IdentityChangedException> { manager.buildFanout("server-spoofed-user", "token", byteArrayOf(8)) }
            setPeers(listOf(revoked, good))
            assertFails { manager.resolveSignalDeviceId("local", "revoked-own", "token") }
            assertEquals("Device locally revoked", assertFails { manager.reviewDevice("local", "revoked-own", "token") }.message)
            val dto = com.messenger.app.data.sync.testMessageDto("journal", sequence = 1, senderId = "local").copy(senderDeviceId = "revoked-own")
            assertFails { manager.retainedPlaintext(dto) }
            assertEquals("Device locally revoked", assertFails { manager.approveDevice(DeviceTrustSnapshot("local", "revoked-own", 2, b64(revoked.store.identityKeyPair.publicKey.serialize()), false, null), 0) }.message)
            val restored = com.messenger.app.data.secure.LocalSecurityDecisions(disk) { "account" }
            assertTrue(restored.isRevoked("local", identity = revoked.store.identityKeyPair.publicKey.serialize()))
            assertTrue(restored.isRevoked("local", signal = 2))
            setPeers(listOf(good, Peer(2, "new-route")))
            assertEquals(listOf("good-own"), manager.buildFanout("local", "token", byteArrayOf(4)).envelopes.map { it.recipientDeviceId })
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private class Peer(val id: Int, val device: String) {
        val store = SignalKeyStore(InMemorySecureKeyValueStore()).apply { ensureIdentity(); generateSignedPreKey(); generateKyberPreKey() }
        private val signed = store.loadSignedPreKey(1)
        private val kyber = store.loadKyberPreKey(1)
        private val oneTime = store.generateOneTimePreKeys(1).first()
        fun dto(claim: Boolean) = DeviceBundleDto(device,id,store.localRegistrationId,
            Base64.getEncoder().encodeToString(store.identityKeyPair.publicKey.serialize()), signed.id,
            Base64.getEncoder().encodeToString(signed.keyPair.publicKey.serialize()), Base64.getEncoder().encodeToString(signed.signature),
            kyber.id, Base64.getEncoder().encodeToString(kyber.keyPair.publicKey.serialize()), Base64.getEncoder().encodeToString(kyber.signature),
            if (claim) oneTime.id else null, if (claim) Base64.getEncoder().encodeToString(oneTime.keyPair.publicKey.serialize()) else null)
    }
    private suspend fun withManager(localSecurity: com.messenger.app.data.secure.LocalSecurityDecisions? = null, block: suspend (SignalSessionManager, MutableList<String>, (List<Peer>) -> Unit, (Int) -> Unit, (List<Peer>?) -> Unit) -> Unit) {
        val paths = mutableListOf<String>()
        var peers = listOf(Peer(1,"peer-1")); var discoveryStatus = 200; var claimPeers: List<Peer>? = null
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            synchronized(paths) { paths += path }
            val discovery = path.startsWith("/keys/devices/")
            val status = if (discovery) discoveryStatus else 200
            val body = if (status == 200) json.encodeToString(UserBundlesResponseDto.serializer(),
                UserBundlesResponseDto((if (discovery) peers else claimPeers ?: peers).map { it.dto(!discovery) }))
                else "{\"error\":{\"code\":\"${if(status==429) "rate_limited" else "not_found"}\",\"message\":\"Test\"}}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                .body(ResponseBody.create(null,body)).build()
        }.build()
        val store = SignalKeyStore(InMemorySecureKeyValueStore()).apply { ensureIdentity() }
        val identity = IdentityVerificationManager(store, InMemoryPeerIdentityDao(), { "local" }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
        val manager = SignalSessionManager(store, KeysApi(client,json,"https://unit.invalid"),identity,localSecurity)
        try { block(manager,paths,{peers=it},{discoveryStatus=it},{claimPeers=it}) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun `forty messages reuse session without forty one time prekey claims`() = runBlocking {
        withManager { manager,paths,setPeers,_,_ ->
            val peer = Peer(1,"peer-1");setPeers(listOf(peer))
            repeat(40) { i ->
                val message = "queued-$i"
                val envelope = manager.buildEnvelopesForUser("peer","token",message.toByteArray()).single()
                val cipher = SessionCipher(peer.store,peer.store,peer.store,peer.store,peer.store,SignalProtocolAddress("local",1))
                val bytes = Base64.getDecoder().decode(envelope.ciphertext)
                val plain = if(envelope.messageType==MESSAGE_TYPE_PREKEY) cipher.decrypt(PreKeySignalMessage(bytes)) else cipher.decrypt(SignalMessage(bytes))
                assertEquals(message,String(plain))
            }
            assertEquals(40,paths.count { it.startsWith("/keys/devices/") })
            assertEquals(1,paths.count { it.startsWith("/keys/bundles/") })
        }
    }
    @Test fun `changed identity blocks existing session without claiming new keys`() = runBlocking {
        withManager { manager,paths,setPeers,_,_ ->
            manager.buildEnvelopesForUser("peer","token","one".toByteArray())
            setPeers(listOf(Peer(1,"peer-1")))
            assertFailsWith<IdentityChangedException> { manager.buildEnvelopesForUser("peer","token","blocked".toByteArray()) }
            assertEquals(1,paths.count { it.startsWith("/keys/bundles/") })
        }
    }
    @Test fun `new devices wait for approval while trusted devices continue then revocations apply`() = runBlocking {
        withManager { manager,paths,setPeers,_,_ ->
            val first=Peer(1,"one");val second=Peer(2,"two")
            setPeers(listOf(first));manager.buildEnvelopesForUser("peer","token",byteArrayOf(1))
            setPeers(listOf(first,second));assertEquals(listOf("one"),manager.buildEnvelopesForUser("peer","token",byteArrayOf(2)).map { it.recipientDeviceId })
            manager.identityVerification.acceptNewKey("peer", 2)
            setPeers(listOf(second));assertEquals(listOf("two"),manager.buildEnvelopesForUser("peer","token",byteArrayOf(3)).map { it.recipientDeviceId })
            assertEquals(2,paths.count { it.startsWith("/keys/bundles/") })
        }
    }
    @Test fun `own pending devices are withheld and current and peer devices never appear there`() = runBlocking {
        withManager { manager,_,setPeers,_,_ ->
            setPeers(listOf(Peer(1,"self"), Peer(2,"pending-own")))
            val fanout = manager.buildFanout("local", "token", byteArrayOf(1), "self")
            assertTrue(fanout.envelopes.isEmpty()); assertEquals(listOf("pending-own"), fanout.withheldDeviceIds)
            manager.identityVerification.acceptNewKey("local", 2)
            val approved = manager.buildFanout("local", "token", byteArrayOf(2), "self")
            assertEquals(listOf("pending-own"), approved.envelopes.map { it.recipientDeviceId }); assertTrue(approved.withheldDeviceIds.isEmpty())
        }
    }
    @Test fun `identity list is rechecked after establishment response changes`() = runBlocking {
        withManager { manager,_,setPeers,_,setClaim ->
            val trusted=Peer(1,"one");setPeers(listOf(trusted));manager.buildEnvelopesForUser("peer","token",byteArrayOf(1))
            val added=Peer(2,"two");setPeers(listOf(trusted,added));setClaim(listOf(Peer(1,"one"),added))
            assertEquals(listOf("one"), manager.buildEnvelopesForUser("peer","token",byteArrayOf(2)).map { it.recipientDeviceId })
        }
    }
    @Test fun `current own device never establishes a session with itself`() = runBlocking {
        withManager { manager,paths,setPeers,_,_ ->
            setPeers(listOf(Peer(1,"self")))
            assertTrue(manager.buildEnvelopesForUser("local","token",byteArrayOf(1),excludeDeviceId="self").isEmpty())
            assertEquals(0,paths.count { it.startsWith("/keys/bundles/") })
        }
    }
    @Test fun `first contact admits one stable multi-device snapshot without an approval dead end`() = runBlocking {
        withManager { manager,_,setPeers,_,_ ->
            setPeers(listOf(Peer(1,"first"),Peer(2,"second")))
            assertEquals(listOf("first","second"),manager.buildEnvelopesForUser("new-peer","token",byteArrayOf(1)).map { it.recipientDeviceId })
        }
    }
    @Test fun `first contact key substitution between discovery and claim cannot establish the replacement`() = runBlocking {
        withManager { manager,_,setPeers,_,setClaim ->
            setPeers(listOf(Peer(1,"first"))); setClaim(listOf(Peer(1,"first")))
            assertFailsWith<IdentityChangedException> { manager.buildEnvelopesForUser("new-peer","token",byteArrayOf(1)) }
        }
    }
    @Test fun `rate limits do not fall back to one time key claims`() = runBlocking {
        withManager { manager,paths,_,setStatus,_ ->
            setStatus(429)
            val error=assertFailsWith<AuthApiException.Api> { manager.buildEnvelopesForUser("peer","token",byteArrayOf(1)) }
            assertEquals("rate_limited",error.code)
            assertEquals(0,paths.count { it.startsWith("/keys/bundles/") })
        }
    }
    @Test fun `old server without discovery still creates a working session`() = runBlocking {
        withManager { manager,paths,_,setStatus,_ ->
            setStatus(404)
            assertEquals(1,manager.buildEnvelopesForUser("peer","token",byteArrayOf(1)).size)
            assertTrue(paths.any { it.startsWith("/keys/bundles/") })
        }
    }
}
