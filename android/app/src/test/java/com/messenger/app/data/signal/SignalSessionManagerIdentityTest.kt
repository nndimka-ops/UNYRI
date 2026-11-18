package com.messenger.app.data.signal

import com.messenger.app.data.api.DeviceBundleDto
import com.messenger.app.data.api.KeysApi
import com.messenger.app.data.local.signal.PeerIdentityDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
/**
 * M.2 identity-rotation regression: proves the inbound NPE root cause is gone
 * (a rotated-identity inbound PreKeySignalMessage surfaces IdentityChangedException,
 * never a NullPointerException), the outbound deterministic bundle check marks
 * CHANGED before any session is used, and Accept resets to UNVERIFIED with a
 * working fresh session.
 */
class SignalSessionManagerIdentityTest {
    @Test fun `one preflight records every changed recipient without creating sessions`() = runBlocking {
        val local = freshStore().also { it.ensureIdentity() }
        val old = freshStore().also { it.ensureIdentity() }
        val changed = freshStore().also { it.ensureIdentity() }
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(local, dao, "me")
        for (id in listOf(1, 2)) local.saveIdentity(SignalProtocolAddress("peer", id), old.identityKeyPair.publicKey)
        val bundles = listOf(1, 2).map { dto(buildBundle(changed, it), changed.identityKeyPair.publicKey, deviceId = "device-$it") }
        val failure = runCatching { manager.verifyBundleIdentitiesOrThrow("peer", bundles) }.exceptionOrNull()
        assertTrue(failure is IdentityChangedException)
        assertEquals(listOf(1, 2), (failure as IdentityChangedException).signalDeviceIds)
        for (id in listOf(1, 2)) {
            assertEquals(IDENTITY_CHANGED, dao.get("peer", id)!!.status)
            assertFalse(local.containsSession(SignalProtocolAddress("peer", id)))
        }
    }


    @Test fun `first incoming message registers safety number without trusting it as verified`() = runBlocking {
        val sender = freshStore().also { it.ensureIdentity() }
        val receiver = freshStore()
        val receiverAddress = SignalProtocolAddress("receiver", 1)
        SessionBuilder(sender, sender, sender, sender, receiverAddress).process(buildBundle(receiver, 1))
        val encrypted = cipherFor(sender, receiverAddress).encrypt("incoming".toByteArray())
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(receiver, dao, "receiver")
        assertEquals("incoming", String(manager.decrypt("sender", 1, 1, b64(encrypted.serialize()))))
        val peer = dao.get("sender", 1)!!
        assertEquals(IDENTITY_UNVERIFIED, peer.status)
        assertTrue(!peer.safetyNumber.isNullOrBlank())
        assertNull(dao.get("unrelated", 1))
    }

    @Test fun `first encrypted send pins identity inside outbox gate without blocking chat writes`() = runBlocking {
        kotlinx.coroutines.withTimeout(10000) {
            val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
            val alice = freshStore().also { it.ensureIdentity() }
            val bob = freshStore()
            val bundle = buildBundle(bob, 1)
            val json = Json { ignoreUnknownKeys = true }
            val body = json.encodeToString(com.messenger.app.data.api.UserBundlesResponseDto.serializer(),
                com.messenger.app.data.api.UserBundlesResponseDto(listOf(dto(bundle, bob.identityKeyPair.publicKey))))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(null, body)).build()
            }.build()
            val dao = InMemoryPeerIdentityDao()
            val identity = IdentityVerificationManager(alice, dao, { "alice" }, this,
                currentEpoch = { 1 }, withSessionWrite = { epoch, block -> gate.write(epoch, block); Unit })
            val manager = SignalSessionManager(alice, KeysApi(client, json, "https://unit.invalid"), identity)
            val envelopes = gate.write(1) { manager.buildEnvelopesForUser("bob", "test-token", "first send".toByteArray()) }!!
            val decrypted = cipherFor(bob, SignalProtocolAddress("alice", 1))
                .decrypt(PreKeySignalMessage(unB64(envelopes.single().ciphertext)))
            assertEquals("first send", String(decrypted))
            assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
            assertEquals("chat saved", gate.write(1) { "chat saved" })
            // The changed-key path also nests a trust write and must fail promptly.
            val replacement = freshStore()
            val changed = dto(buildBundle(replacement, 1), replacement.identityKeyPair.publicKey)
            try {
                gate.write(1) { manager.verifyBundleIdentitiesOrThrow("bob", listOf(changed)) }
                fail("expected identity change")
            } catch (_: IdentityChangedException) { }
            assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
        }
    }

    @Test fun `decrypt cannot write into logged out or replacement namespace during native callbacks`() = runBlocking {
        for (replacement in listOf("", "new:device")) {
            var owner = "old:device"
            var switchOnRead = false
            var switched = false
            val backing = InMemorySecureKeyValueStore()
            val hooked = object : SecureKeyValueStore by backing {
                override fun getBytes(key: String): ByteArray? {
                    val value = backing.getBytes(key)
                    if (switchOnRead && key.contains("session_")) {
                        owner = replacement
                        switchOnRead = false
                        switched = true
                    }
                    return value
                }
            }
            val receiver = SignalKeyStore(hooked) { owner }
            val sender = freshStore().also { it.ensureIdentity() }
            val receiverAddress = SignalProtocolAddress("receiver", 1)
            SessionBuilder(sender, sender, sender, sender, receiverAddress).process(buildBundle(receiver, 1))
            val first = cipherFor(sender, receiverAddress).encrypt("first".toByteArray())
            val manager = managerFor(receiver, InMemoryPeerIdentityDao(), "receiver")
            assertEquals("first", String(manager.decrypt("sender", 1, 1, b64(first.serialize()))))
            val next = cipherFor(sender, receiverAddress).encrypt("next".toByteArray())
            switchOnRead = true
            try {
                manager.decrypt("sender", 1, if (next is PreKeySignalMessage) 1 else 2, b64(next.serialize()))
                fail("account switch must abort decryption")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue("native callback was reached", switched)
            assertTrue("no keys may be written outside original namespace",
                backing.keysWithPrefix("").all { it.startsWith("old:device:") })
        }
    }

    @Test fun `identity warning retains operation epoch even without outer room lease`() = runBlocking {
        var epoch = 1
        val gate = com.messenger.app.data.auth.SessionWriteGate { epoch }
        val store = freshStore().also { it.ensureIdentity() }
        val dao = InMemoryPeerIdentityDao()
        val identity = IdentityVerificationManager(store, dao, { "me" }, this,
            currentEpoch = { epoch }, withSessionWrite = { captured, block -> gate.write(captured, block); Unit })
        val remote = freshStore().also { it.ensureIdentity() }.identityKeyPair.publicKey
        identity.withOperation {
            epoch = 2
            identity.markChanged("peer", 1, remote)
            identity.recordFirstContact("peer", 1)
        }
        assertNull(dao.get("peer", 1))
        identity.withOperation { identity.markChanged("peer", 1, remote) }
        assertEquals(IDENTITY_CHANGED, dao.get("peer", 1)!!.status)
    }

    @Test fun `healthy prekey counts still republish same identity after login`() = runBlocking {
        val local = freshStore(); local.ensureIdentity(); local.generateSignedPreKey(); local.generateKyberPreKey()
        local.generateOneTimePreKeys(100)
        val before = local.identityKeyPair.serialize()
        val posts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().method == "POST") {
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); posts += buffer.readUtf8()
                "{\"one_time_prekey_count\":100}"
            } else "{\"count\":100}"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, body)).build()
        }.build()
        val identity = IdentityVerificationManager(local, InMemoryPeerIdentityDao(), { "local" }, this)
        val manager = SignalSessionManager(local, KeysApi(client, Json { ignoreUnknownKeys = true }, "https://unit.invalid"), identity)
        manager.bootstrapKeysIfNeeded("first-session")
        manager.bootstrapKeysIfNeeded("session-after-unblock")
        assertEquals(2, posts.size)
        assertEquals(posts[0], posts[1])
        assertArrayEquals(before, local.identityKeyPair.serialize())
        assertEquals(100, local.oneTimePreKeyCountLocal())
    }

    @Test fun `mailbox encrypts only fresh exact device and consumes only its target bundle`() = runBlocking {
        val peer = "33333333-3333-4333-8333-333333333333"
        val target = "44444444-4444-4444-8444-444444444444"
        val otherDevice = "66666666-6666-4666-8666-666666666666"
        val local = freshStore().also { it.ensureIdentity() }; val remote = freshStore(); val unrelated = freshStore()
        val targetDto = dto(buildBundle(remote, 1), remote.identityKeyPair.publicKey, target)
        val unrelatedDto = dto(buildBundle(unrelated, 2), unrelated.identityKeyPair.publicKey, otherDevice)
        // An unrelated changed key must not be observed or marked by this dedicated route.
        val oldOther = freshStore().also { it.ensureIdentity() }
        local.saveIdentity(SignalProtocolAddress(peer, 2), oldOther.identityKeyPair.publicKey)
        // The exact target was locally approved; unrelated devices remain unreviewed.
        local.saveIdentity(SignalProtocolAddress(peer, 1), remote.identityKeyPair.publicKey)
        val paths = mutableListOf<String>(); val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            val values = if (chain.request().url.encodedPath.startsWith("/keys/devices/")) listOf(targetDto, unrelatedDto) else listOf(targetDto)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, json.encodeToString(com.messenger.app.data.api.UserBundlesResponseDto.serializer(), com.messenger.app.data.api.UserBundlesResponseDto(values)))).build()
        }.build()
        val dao = InMemoryPeerIdentityDao()
        val manager = SignalSessionManager(local, KeysApi(client, json, "https://fixture.invalid"), IdentityVerificationManager(local, dao, { "alice" }, this))
        val first = manager.buildEnvelopeForDevice(peer, target, "token", "media-key-only".toByteArray())
        assertEquals(target, first.recipientDeviceId); assertEquals(1, first.messageType)
        assertEquals("media-key-only", String(cipherFor(remote, SignalProtocolAddress("alice", 1)).decrypt(PreKeySignalMessage(unB64(first.ciphertext)))))
        assertEquals(listOf("/keys/devices/$peer", "/keys/bundles/$peer/$target"), paths)
        assertFalse(local.containsSession(SignalProtocolAddress(peer, 2))); assertNull(dao.get(peer, 2))
        // Signal intentionally repeats PreKey envelopes until the peer acknowledges.
        // Complete that real ratchet handshake before asserting the Whisper wire type.
        val acknowledgement = cipherFor(remote, SignalProtocolAddress("alice", 1)).encrypt("ack".toByteArray())
        assertEquals("ack", String(manager.decrypt(peer, 1, if (acknowledgement is PreKeySignalMessage) 1 else 2, b64(acknowledgement.serialize()))))
        val second = manager.buildEnvelopeForDevice(peer, target, "token", "second".toByteArray())
        assertEquals(2, second.messageType)
        assertEquals("second", String(cipherFor(remote, SignalProtocolAddress("alice", 1)).decrypt(org.signal.libsignal.protocol.message.SignalMessage(unB64(second.ciphertext)))))
        assertEquals(1, paths.count { it == "/keys/bundles/$peer/$target" })
    }
    @Test fun `mailbox first contact rotation between discovery and target claim fails before crypto`() = runBlocking {
        val peer = "33333333-3333-4333-8333-333333333333"; val target = "44444444-4444-4444-8444-444444444444"
        for (changedDeviceId in listOf(false, true)) {
            val local = freshStore().also { it.ensureIdentity() }; val first = freshStore(); val second = freshStore(); val dao = InMemoryPeerIdentityDao()
            val discovered = dto(buildBundle(first, 1), first.identityKeyPair.publicKey, target)
            val claimed = if (changedDeviceId) dto(buildBundle(first, 2), first.identityKeyPair.publicKey, target) else dto(buildBundle(second, 1), second.identityKeyPair.publicKey, target)
            val paths = mutableListOf<String>(); val json = Json { ignoreUnknownKeys = true }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                paths += chain.request().url.encodedPath
                val value = if (chain.request().url.encodedPath.startsWith("/keys/devices/")) discovered else claimed
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(okhttp3.ResponseBody.create(null, json.encodeToString(com.messenger.app.data.api.UserBundlesResponseDto.serializer(), com.messenger.app.data.api.UserBundlesResponseDto(listOf(value))))).build()
            }.build()
            val manager = SignalSessionManager(local, KeysApi(client, json, "https://fixture.invalid"), IdentityVerificationManager(local, dao, { "alice" }, this))
            assertTrue(runCatching { manager.buildEnvelopeForDevice(peer, target, "token", byteArrayOf(1)) }.isFailure)
            assertEquals(listOf("/keys/devices/$peer", "/keys/bundles/$peer/$target"), paths)
            assertFalse(local.containsSession(SignalProtocolAddress(peer, 1))); assertFalse(local.containsSession(SignalProtocolAddress(peer, 2)))
            assertNull(local.getIdentity(SignalProtocolAddress(peer, 1))); assertNull(dao.get(peer, 1))
        }
    }
    @Test fun `mailbox missing target never trusts or consumes another device`() = runBlocking {
        val peer = "33333333-3333-4333-8333-333333333333"; val missing = "44444444-4444-4444-8444-444444444444"
        val local = freshStore().also { it.ensureIdentity() }; val remote = freshStore(); val dao = InMemoryPeerIdentityDao()
        val other = dto(buildBundle(remote, 2), remote.identityKeyPair.publicKey, "66666666-6666-4666-8666-666666666666")
        val paths = mutableListOf<String>(); val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain -> paths += chain.request().url.encodedPath
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, json.encodeToString(com.messenger.app.data.api.UserBundlesResponseDto.serializer(), com.messenger.app.data.api.UserBundlesResponseDto(listOf(other))))).build() }.build()
        val manager = SignalSessionManager(local, KeysApi(client, json, "https://fixture.invalid"), IdentityVerificationManager(local, dao, { "alice" }, this))
        assertTrue(runCatching { manager.buildEnvelopeForDevice(peer, missing, "token", byteArrayOf(1)) }.isFailure)
        assertEquals(listOf("/keys/devices/$peer"), paths); assertNull(dao.get(peer, 2)); assertFalse(local.containsSession(SignalProtocolAddress(peer, 2)))
    }
    @Test fun `mailbox exact prekey404 cannot fall back to batch consuming route`() = runBlocking {
        val peer = "33333333-3333-4333-8333-333333333333"; val target = "44444444-4444-4444-8444-444444444444"
        val local = freshStore().also { it.ensureIdentity() }; val remote = freshStore(); val dao = InMemoryPeerIdentityDao()
        val targetDto = dto(buildBundle(remote, 1), remote.identityKeyPair.publicKey, target)
        val paths = mutableListOf<String>(); val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            val discovery = chain.request().url.encodedPath.startsWith("/keys/devices/")
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(if (discovery) 200 else 404).message("fixture")
                .body(okhttp3.ResponseBody.create(null, if (discovery) json.encodeToString(com.messenger.app.data.api.UserBundlesResponseDto.serializer(), com.messenger.app.data.api.UserBundlesResponseDto(listOf(targetDto))) else "{}" )).build()
        }.build()
        val manager = SignalSessionManager(local, KeysApi(client, json, "https://fixture.invalid"), IdentityVerificationManager(local, dao, { "alice" }, this))
        assertTrue(runCatching { manager.buildEnvelopeForDevice(peer, target, "token", byteArrayOf(1)) }.exceptionOrNull() is com.messenger.app.data.api.AuthApiException.Api)
        assertEquals(listOf("/keys/devices/$peer", "/keys/bundles/$peer/$target"), paths); assertFalse(local.containsSession(SignalProtocolAddress(peer, 1)))
    }

    private fun freshStore() = SignalKeyStore(InMemorySecureKeyValueStore())

    private fun buildBundle(remote: SignalKeyStore, deviceId: Int): PreKeyBundle {
        remote.ensureIdentity()
        if (!remote.hasSignedPreKey()) remote.generateSignedPreKey()
        if (!remote.hasKyberPreKey()) remote.generateKyberPreKey()
        val signedPreKey = remote.loadSignedPreKey(1)
        val kyberPreKey = remote.loadKyberPreKey(1)
        val oneTime = remote.generateOneTimePreKeys(1).first()
        return PreKeyBundle(
            remote.localRegistrationId,
            deviceId,
            oneTime.id,
            oneTime.keyPair.publicKey,
            signedPreKey.id,
            signedPreKey.keyPair.publicKey,
            signedPreKey.signature,
            remote.identityKeyPair.publicKey,
            kyberPreKey.id,
            kyberPreKey.keyPair.publicKey,
            kyberPreKey.signature,
        )
    }

    private fun cipherFor(local: SignalKeyStore, remoteAddress: SignalProtocolAddress) =
        SessionCipher(local, local, local, local, local, remoteAddress)

    /** A KeysApi that is never exercised by these tests (decrypt and the
     *  deterministic bundle check do not touch the network) - pointed at an
     *  unroutable URL so any accidental call fails fast and loudly. */
    private fun dummyKeysApi(): KeysApi =
        KeysApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, "http://127.0.0.1:1")

    private fun managerFor(
        keyStore: SignalKeyStore,
        dao: PeerIdentityDao,
        myUserId: String,
    ) = SignalSessionManager(
        keyStore = keyStore,
        keysApi = dummyKeysApi(),
        identityVerification = IdentityVerificationManager(
            keyStore = keyStore,
            dao = dao,
            myUserIdProvider = { myUserId },
            scope = CoroutineScope(SupervisorJob()),
        ),
    )

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unB64(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)

    private fun dto(bundle: PreKeyBundle, identityKey: IdentityKey, deviceId: String = "bob-device"): DeviceBundleDto =
        DeviceBundleDto(
            deviceId = deviceId,
            signalDeviceId = bundle.deviceId,
            registrationId = bundle.registrationId,
            identityKey = b64(identityKey.serialize()),
            signedPrekeyId = bundle.signedPreKeyId,
            signedPrekeyPublic = b64(bundle.signedPreKey.serialize()),
            signedPrekeySignature = b64(bundle.signedPreKeySignature),
            kyberPrekeyId = bundle.kyberPreKeyId,
            kyberPrekeyPublic = b64(bundle.kyberPreKey.serialize()),
            kyberPrekeySignature = b64(bundle.kyberPreKeySignature),
            oneTimePrekeyId = bundle.preKeyId,
            oneTimePrekeyPublic = bundle.preKey?.let { b64(it.serialize()) },
        )

    @Test
    fun `inbound rotated identity throws IdentityChangedException and marks CHANGED (NPE regression)`() = runBlocking {
        val aliceStore = freshStore()
        aliceStore.ensureIdentity()
        val bobOld = freshStore()
        val bobAddress = SignalProtocolAddress("bob", 1)

        // Alice pins Bob's ORIGINAL identity and establishes a session.
        SessionBuilder(aliceStore, aliceStore, aliceStore, aliceStore, bobAddress).process(buildBundle(bobOld, 1))
        val first = cipherFor(aliceStore, bobAddress).encrypt("hi".toByteArray())
        cipherFor(bobOld, SignalProtocolAddress("alice", 1)).decrypt(first as PreKeySignalMessage)

        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")
        val aliceAddress = SignalProtocolAddress("alice", 1)

        // Bob "reinstalls": a brand-new identity on the SAME address.
        val bobNew = freshStore()
        bobNew.ensureIdentity()
        SessionBuilder(bobNew, bobNew, bobNew, bobNew, aliceAddress).process(buildBundle(aliceStore, 1))
        val rotated = cipherFor(bobNew, aliceAddress).encrypt("rotated".toByteArray()) as PreKeySignalMessage

        try {
            manager.decrypt("bob", 1, 1, b64(rotated.serialize()))
            fail("expected IdentityChangedException")
        } catch (e: IdentityChangedException) {
            assertEquals(listOf(1), e.signalDeviceIds)
        }

        // The peer must be durably marked CHANGED, not left as a generic failure.
        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
        // The new key (not the old one) must be the stored CHANGED identity.
        assertTrue(dao.get("bob", 1)!!.identityKeyB64!!.startsWith(b64(bobNew.identityKeyPair.publicKey.serialize()).substring(0, 16)))
    }

    @Test
    fun `outbound deterministic check marks CHANGED for a changed bundle`() = runBlocking {
        val aliceStore = freshStore()
        aliceStore.ensureIdentity()
        val bobOld = freshStore()
        val bobAddress = SignalProtocolAddress("bob", 1)

        // Alice pins Bob's ORIGINAL identity.
        val oldBundle = buildBundle(bobOld, 1)
        SessionBuilder(aliceStore, aliceStore, aliceStore, aliceStore, bobAddress).process(oldBundle)

        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        // A bundle advertising a DIFFERENT identity for the SAME address.
        val bobNew = freshStore()
        bobNew.ensureIdentity()
        val changedDto = dto(buildBundle(bobNew, 1), bobNew.identityKeyPair.publicKey)

        try {
            manager.verifyBundleIdentitiesOrThrow("bob", listOf(changedDto))
            fail("expected IdentityChangedException")
        } catch (e: IdentityChangedException) {
            assertEquals(listOf(1), e.signalDeviceIds)
        }
        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
    }

    @Test
    fun `outbound deterministic check passes an unchanged bundle`() = runBlocking {
        val aliceStore = freshStore()
        aliceStore.ensureIdentity()
        val bob = freshStore()
        val bobAddress = SignalProtocolAddress("bob", 1)

        val bundle = buildBundle(bob, 1)
        SessionBuilder(aliceStore, aliceStore, aliceStore, aliceStore, bobAddress).process(bundle)

        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        // Same identity -> no throw, no CHANGED row.
        val sameDto = dto(bundle, bob.identityKeyPair.publicKey)
        manager.verifyBundleIdentitiesOrThrow("bob", listOf(sameDto))
        assertNull(dao.get("bob", 1))
    }

    @Test
    fun `outbound deterministic check passes a never-before-seen device (new device is not a key change)`() = runBlocking {
        val aliceStore = freshStore()
        aliceStore.ensureIdentity()

        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        // A brand-new device id/address we have never pinned -> trust-on-first-use.
        val bobNew = freshStore()
        bobNew.ensureIdentity()
        val freshBundle = buildBundle(bobNew, 3)
        val freshDto = dto(freshBundle, bobNew.identityKeyPair.publicKey, deviceId = "bob-new-device")

        manager.verifyBundleIdentitiesOrThrow("bob", listOf(freshDto))
        assertNull(dao.get("bob", 3))
    }

    @Test
    fun `accept resets CHANGED to UNVERIFIED and deletes the stale session`() = runBlocking {
        val aliceStore = freshStore()
        aliceStore.ensureIdentity()
        val bobOld = freshStore()
        val bobAddress = SignalProtocolAddress("bob", 1)

        SessionBuilder(aliceStore, aliceStore, aliceStore, aliceStore, bobAddress).process(buildBundle(bobOld, 1))
        // Establish a real session both ways.
        val first = cipherFor(aliceStore, bobAddress).encrypt("hi".toByteArray())
        cipherFor(bobOld, SignalProtocolAddress("alice", 1)).decrypt(first as PreKeySignalMessage)
        assertTrue(aliceStore.containsSession(bobAddress))

        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")
        val identityManager = manager.identityVerification

        val bobNew = freshStore()
        bobNew.ensureIdentity()
        identityManager.markChanged("bob", 1, bobNew.identityKeyPair.publicKey)
        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)

        identityManager.acceptNewKey("bob", 1)

        assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
        assertFalse("accept must delete the stale session for the affected address", aliceStore.containsSession(bobAddress))

        // A fresh session with the accepted key must then work end-to-end.
        val aliceAddress = SignalProtocolAddress("alice", 1)
        SessionBuilder(bobNew, bobNew, bobNew, bobNew, aliceAddress).process(buildBundle(aliceStore, 1))
        val post = cipherFor(bobNew, aliceAddress).encrypt("after accept".toByteArray()) as PreKeySignalMessage
        val plaintext = cipherFor(aliceStore, bobAddress).decrypt(PreKeySignalMessage(post.serialize()))
        assertEquals("after accept", String(plaintext, Charsets.UTF_8))
    }
}
