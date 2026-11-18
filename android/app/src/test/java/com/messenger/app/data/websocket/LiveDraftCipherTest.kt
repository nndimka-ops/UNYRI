package com.messenger.app.data.websocket

import kotlin.test.*

class LiveDraftCipherTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun `AES256 GCM empty NIST vector has standard combined layout`() {
        val sealed = LiveDraftCipher.seal(ByteArray(32), byteArrayOf(), byteArrayOf(), ByteArray(12))
        assertContentEquals(ByteArray(12) + bytes("530f8afbc74536b9a963b4f1c4cb738b"), sealed)
        assertContentEquals(byteArrayOf(), LiveDraftCipher.open(ByteArray(32), sealed, byteArrayOf()))
    }
    @Test fun `chat stream and revision are authenticated and cannot be swapped`() {
        val aad = "chat|stream|1".toByteArray()
        val body = "Привет 👋".toByteArray()
        val sealed = LiveDraftCipher.seal(ByteArray(32), body, aad)
        assertContentEquals(body, LiveDraftCipher.open(ByteArray(32), sealed, aad))
        for (wrong in listOf("other|stream|1", "chat|other|1", "chat|stream|2")) {
            assertFails { LiveDraftCipher.open(ByteArray(32), sealed, wrong.toByteArray()) }
        }
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertFails { LiveDraftCipher.open(ByteArray(32), sealed, aad) }
    }
    @Test fun `rejects truncated oversized and invalid key input`() {
        assertFails { LiveDraftCipher.open(ByteArray(32), ByteArray(27), byteArrayOf()) }
        assertFails { LiveDraftCipher.open(ByteArray(32), ByteArray(16385), byteArrayOf()) }
        assertFails { LiveDraftCipher.seal(ByteArray(16), byteArrayOf(), byteArrayOf()) }
        assertFails { LiveDraftCipher.seal(ByteArray(32), ByteArray(12001), byteArrayOf()) }
    }
}
