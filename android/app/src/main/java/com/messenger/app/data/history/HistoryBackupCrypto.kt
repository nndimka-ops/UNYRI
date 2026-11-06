package com.messenger.app.data.history

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Format 1: nonce(12) || AES-256-GCM ciphertext || tag(16).
 * Recovery material is independent of account passwords and Signal ratchets.
 * This primitive alone is not a complete history archive/import implementation.
 */
object HistoryBackupCrypto {
    const val MAX_PACKET_BYTES = 8 * 1024 * 1024
    data class Context(val serverId: UUID, val userId: UUID, val backupId: UUID) {
        fun aad(): ByteArray = "COCO-HISTORY-BACKUP\u0000v1\u0000$serverId\u0000$userId\u0000$backupId".toByteArray(StandardCharsets.UTF_8)
    }
    fun generateRecoveryKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun formatRecoveryKey(key: ByteArray): String {
        require(key.size == 32)
        return "COCO-H1-" + key.joinToString("") { "%02X".format(it.toInt() and 255) }.chunked(8).joinToString("-")
    }
    fun parseRecoveryKey(value: String): ByteArray {
        val normalized = value.trim().uppercase(java.util.Locale.ROOT)
        require(normalized.matches(Regex("COCO-H1-[0-9A-F]{8}(?:-[0-9A-F]{8}){7}"))) { "Invalid history recovery key" }
        return normalized.removePrefix("COCO-H1-").replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun encrypt(plaintext: ByteArray, key: ByteArray, context: Context): ByteArray =
        seal(plaintext, key, context, ByteArray(12).also { SecureRandom().nextBytes(it) })
    internal fun seal(plaintext: ByteArray, key: ByteArray, context: Context, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PACKET_BYTES - 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.aad())
        return nonce + cipher.doFinal(plaintext)
    }
    fun decrypt(packet: ByteArray, key: ByteArray, context: Context): ByteArray {
        require(key.size == 32 && packet.size in 29..MAX_PACKET_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD(context.aad())
        // JCA authenticates before returning plaintext. Never use partial updates.
        return cipher.doFinal(packet, 12, packet.size - 12)
    }
}
