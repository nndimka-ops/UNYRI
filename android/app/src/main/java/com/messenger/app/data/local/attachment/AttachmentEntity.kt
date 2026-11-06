package com.messenger.app.data.local.attachment

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local cache row for one attachment, keyed by the server-issued
 *  `attachmentId` (unlike messages, an attachment has no meaningful
 *  client-generated id of its own before `/attachments` returns one).
 *
 *  Threat model note (Этап H п.18): the AES key/nonce/sha256 are stored
 *  here in plain columns, not a separate encrypted store. This mirrors the
 *  EXISTING accepted threat model: `MessageEntity.text` has stored fully
 *  decrypted plaintext in this same unencrypted Room database since Этап E
 *  - the boundary this app relies on is Android's per-app storage sandbox
 *  (a compromised/rooted device already sees decrypted message text
 *  either way), not at-rest DB encryption. Putting the attachment's own
 *  already-Signal-decrypted secret in the same database is therefore not a
 *  new regression, just consistent with a threat model already accepted
 *  two stages ago - a dedicated encrypted key store would protect against
 *  a threat (Room-file-only disclosure) that decrypted message text next
 *  to it already defeats. */
@Entity(tableName = "attachments", indices = [Index(value = ["clientMessageId"]), Index(value = ["chatId"])])
data class AttachmentEntity(
    @PrimaryKey val attachmentId: String,
    val clientMessageId: String,
    val chatId: String,
    val mediaType: String,
    val mimeType: String,
    val filename: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnailBase64: String?,
    /** Decrypted local plaintext cache path - null until downloaded. */
    val localPath: String?,
    /** not_downloaded | downloading | downloaded | failed | corrupt */
    val downloadState: String,
    /** pending | encrypting | uploading | uploaded | sending | sent | failed */
    val uploadState: String,
    val progress: Int = 0,
    val objectKey: String,
    val encryptedSize: Long,
    /** 1 = legacy javax.crypto AES-256-GCM (Этап H), 2 = Tink Streaming
     *  AEAD (Этап H.2). Determines which of keyBase64/nonceBase64 vs
     *  keysetBase64 is populated, and which decrypt code path to use. */
    val encryptionVersion: Int = 1,
    /** v1 only - null for v2 rows. */
    val keyBase64: String?,
    /** v1 only - null for v2 rows. */
    val nonceBase64: String?,
    /** v2 only - base64 cleartext Tink keyset bytes. Null for v1 rows. */
    val keysetBase64: String? = null,
    val sha256Base64: String,
)
