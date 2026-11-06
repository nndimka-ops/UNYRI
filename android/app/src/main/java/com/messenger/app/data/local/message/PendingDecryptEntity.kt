package com.messenger.app.data.local.message

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.messenger.app.data.api.MessageDto

/**
 * P1-4: the durable, account-local record of an encrypted envelope whose
 * decryption failed. It stores ONLY what is required to rerun decryption
 * later through the SAME [com.messenger.app.data.sync.MessageIngestionPipeline]
 * path: the opaque ciphertext + addressing metadata. It never stores
 * plaintext, and never any Signal private key (those live exclusively in the
 * Keystore-backed Signal namespace).
 *
 * Account isolation is inherited from the fact that this table lives in Room
 * and is wiped by `clearAllTables()` on logout — exactly like the `messages`
 * projection — so Alice's pending envelope can never be retried under Bob.
 * `retryCount`/`lastErrorClass` bound the event-driven retry (no timer).
 */
@Entity(tableName = "pending_decrypts")
data class PendingDecryptEntity(
    @PrimaryKey val clientMessageId: String,
    val serverId: String,
    val chatId: String,
    val senderId: String,
    val senderDeviceId: String,
    val sequence: Long,
    val createdAt: String,
    val messageType: Int,
    val ciphertext: String,
    val kind: String,
    val targetMessageId: String?,
    val retryCount: Int = 0,
    val lastErrorClass: String? = null,
) {
    fun toDto(): MessageDto = MessageDto(
        id = serverId,
        chatId = chatId,
        clientMessageId = clientMessageId,
        senderId = senderId,
        senderDeviceId = senderDeviceId,
        sequence = sequence,
        createdAt = createdAt,
        messageType = messageType,
        ciphertext = ciphertext,
        kind = kind,
        targetMessageId = targetMessageId,
    )
}
