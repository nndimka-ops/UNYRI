package com.messenger.app.data.history

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryBackupCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val context = HistoryBackupCrypto.Context(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
    )
    private val plain = "{\"format\":1,\"messages\":[]}".toByteArray()
    private val packet = "000102030405060708090a0b3c20b074b788a36faf7ba6a793841d1ef0b7e05183596527651ac3ed4f26b5fb3e91ea1fe511cc67e0ec".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun `matches independent AES GCM fixture used by iOS`() {
        assertArrayEquals(packet, HistoryBackupCrypto.seal(plain, key, context, ByteArray(12) { it.toByte() }))
        assertArrayEquals(plain, HistoryBackupCrypto.decrypt(packet, key, context))
    }
    @Test fun `all ciphertext bytes and nonce and tag are authenticated`() {
        packet.indices.forEach { i ->
            val changed = packet.clone(); changed[i] = (changed[i].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { HistoryBackupCrypto.decrypt(changed, key, context) }
        }
    }
    @Test fun `wrong account server backup and key fail closed`() {
        listOf(context.copy(serverId = UUID.randomUUID()), context.copy(userId = UUID.randomUUID()), context.copy(backupId = UUID.randomUUID())).forEach {
            assertThrows(Exception::class.java) { HistoryBackupCrypto.decrypt(packet, key, it) }
        }
        assertThrows(Exception::class.java) { HistoryBackupCrypto.decrypt(packet, ByteArray(32) { 9 }, context) }
        assertThrows(Exception::class.java) { HistoryBackupCrypto.decrypt(packet.copyOf(28), key, context) }
    }
    @Test fun `key representation is separate and strict`() {
        assertArrayEquals(key, HistoryBackupCrypto.parseRecoveryKey(HistoryBackupCrypto.formatRecoveryKey(key)))
        assertThrows(Exception::class.java) { HistoryBackupCrypto.parseRecoveryKey("123456") }
        assertThrows(Exception::class.java) { HistoryBackupCrypto.parseRecoveryKey("COCO-H1-12345678") }
        assertThrows(Exception::class.java) { HistoryBackupCrypto.formatRecoveryKey(ByteArray(16)) }
    }
    @Test fun `fresh encryption uses different random nonce`() {
        val a = HistoryBackupCrypto.encrypt(plain, key, context)
        val b = HistoryBackupCrypto.encrypt(plain, key, context)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, HistoryBackupCrypto.decrypt(a, key, context))
        assertArrayEquals(plain, HistoryBackupCrypto.decrypt(b, key, context))
    }
}
