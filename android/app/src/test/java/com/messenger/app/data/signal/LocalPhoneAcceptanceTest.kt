package com.messenger.app.data.signal

import com.messenger.app.data.api.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import kotlin.test.assertTrue

/** Explicit opt-in: sends one real encrypted test message to an owned phone. */
class LocalPhoneAcceptanceTest {
    @Test fun sendEncryptedMessageToLocalPhone() = runBlocking {
        val recipient = System.getenv("COCO_ACCEPTANCE_RECIPIENT")
        assumeTrue("Physical acceptance is opt-in", !recipient.isNullOrBlank())
        val client = OkHttpClient()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val base = "http://127.0.0.1:8080"
        val auth = AuthApi(client, json, base).register(RegisterRequestDto(
            username = "localcheck_" + UUID.randomUUID().toString().take(8),
            password = UUID.randomUUID().toString(), displayName = "COCO Local Test",
            installationId = UUID.randomUUID().toString(),
        ))
        val keys = KeysApi(client, json, base)
        val store = SignalKeyStore(InMemorySecureKeyValueStore())
        store.ensureIdentity()
        val signed = store.generateSignedPreKey()
        val kyber = store.generateKyberPreKey()
        keys.uploadKeys(auth.accessToken, UploadKeysRequestDto(
            b64(store.identityKeyPair.publicKey.serialize()), store.localRegistrationId,
            SignedPreKeyUploadDto(signed.id, b64(signed.keyPair.publicKey.serialize()), b64(signed.signature)),
            KyberPreKeyUploadDto(kyber.id, b64(kyber.keyPair.publicKey.serialize()), b64(kyber.signature)),
        ))
        val chat = ChatsApi(client, json, base).createDirect(auth.accessToken, recipient!!)
        val bundles = keys.getBundles(auth.accessToken, recipient).devices
        assertTrue(bundles.isNotEmpty())
        val envelopes = bundles.map { b ->
            val address = SignalProtocolAddress(recipient, b.signalDeviceId)
            val bundle = PreKeyBundle(b.registrationId, b.signalDeviceId,
                b.oneTimePrekeyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
                b.oneTimePrekeyPublic?.let { ECPublicKey(decode(it)) },
                b.signedPrekeyId, ECPublicKey(decode(b.signedPrekeyPublic)), decode(b.signedPrekeySignature),
                IdentityKey(decode(b.identityKey)), b.kyberPrekeyId, KEMPublicKey(decode(b.kyberPrekeyPublic)), decode(b.kyberPrekeySignature))
            SessionBuilder(store, store, store, store, address).process(bundle)
            val encrypted = SessionCipher(store, store, store, store, store, address)
                .encrypt("COCO: проверка локального сообщения без FCM".toByteArray(Charsets.UTF_8))
            EnvelopeDto(b.deviceId, if (encrypted is PreKeySignalMessage) 1 else 2, b64(encrypted.serialize()))
        }
        val sent = MessagesApi(client, json, base).sendMessage(auth.accessToken, chat.chatId,
            UUID.randomUUID().toString(), envelopes)
        assertTrue(sent.sequence > 0)
        println("LOCAL_ACCEPTANCE chat=${chat.chatId} message=${sent.id}")
    }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String) = Base64.getDecoder().decode(value)
}
