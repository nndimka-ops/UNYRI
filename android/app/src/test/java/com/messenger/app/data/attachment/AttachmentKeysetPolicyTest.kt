package com.messenger.app.data.attachment

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.proto.AesGcmHkdfStreamingKey
import com.google.crypto.tink.proto.AesGcmHkdfStreamingParams
import com.google.crypto.tink.proto.HashType
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.shaded.protobuf.ByteString
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A peer-supplied attachment keyset is untrusted: only the shape both apps generate opens. */
class AttachmentKeysetPolicyTest {
    private fun keyset(
        segment: Int = 1024 * 1024,
        keyBytes: Int = 32,
        derived: Int = 32,
        hash: HashType = HashType.SHA256,
        status: KeyStatusType = KeyStatusType.ENABLED,
        prefix: OutputPrefixType = OutputPrefixType.RAW,
        keys: Int = 1,
    ): ByteArray {
        val streaming = AesGcmHkdfStreamingKey.newBuilder()
            .setParams(AesGcmHkdfStreamingParams.newBuilder().setCiphertextSegmentSize(segment)
                .setDerivedKeySize(derived).setHkdfHashType(hash))
            .setKeyValue(ByteString.copyFrom(ByteArray(keyBytes) { 7 }))
            .build()
        val data = KeyData.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey")
            .setValue(streaming.toByteString())
            .setKeyMaterialType(KeyData.KeyMaterialType.SYMMETRIC)
        val builder = Keyset.newBuilder().setPrimaryKeyId(1)
        repeat(keys) { index ->
            builder.addKey(Keyset.Key.newBuilder().setKeyData(data).setStatus(status)
                .setKeyId(index + 1).setOutputPrefixType(prefix))
        }
        return builder.build().toByteArray()
    }

    @Test
    fun `keysets generated here and by iOS are accepted`() {
        AttachmentCryptoV2.parseKeysetHandle(AttachmentCryptoV2.serializeKeysetHandle(AttachmentCryptoV2.generateKeysetHandle()))
        // iOS TinkAttachmentKey.encode: key id 1, RAW, 1 MiB segments, 32/32, SHA-256.
        AttachmentCryptoV2.parseKeysetHandle(keyset())
    }

    @Test
    fun `a huge segment size is refused before Tink allocates anything`() {
        assertFailsWith<GeneralSecurityException> { AttachmentCryptoV2.parseKeysetHandle(keyset(segment = Int.MAX_VALUE)) }
    }

    @Test
    fun `any other shape is refused`() {
        AttachmentCryptoV2.ensureRegistered()
        val fourKb = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
        val rejected = listOf(
            AttachmentCryptoV2.serializeKeysetHandle(fourKb),
            keyset(keyBytes = 16),
            keyset(derived = 16),
            keyset(hash = HashType.SHA1),
            keyset(status = KeyStatusType.DISABLED),
            keyset(prefix = OutputPrefixType.TINK),
            keyset(keys = 2),
            byteArrayOf(1, 2, 3),
            ByteArray(20_000),
        )
        rejected.forEachIndexed { index, bytes ->
            assertFailsWith<GeneralSecurityException>("case $index") { AttachmentCryptoV2.parseKeysetHandle(bytes) }
        }
    }
}
