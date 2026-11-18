package com.messenger.app.data.signal

internal class MalformedSignalCiphertextException(cause: IllegalArgumentException) : Exception(cause)
internal class UnsupportedSignalMessageTypeException : Exception("Unsupported Signal message type")

/**
 * The subset of [SignalSessionManager] that [com.messenger.app.data.sync.MessageIngestionPipeline]
 * depends on for decrypting incoming envelopes - kept narrow so the pipeline's
 * orchestration logic (dedup, session-epoch abort, cursor advance, delivered
 * batching) is unit-testable with a fake instead of real Signal Protocol
 * crypto (which is already exercised end-to-end by SignalCryptoTest.kt).
 */
interface MessageDecryptor {
    suspend fun isLocallyExcluded(dto: com.messenger.app.data.api.MessageDto): Boolean = false
    suspend fun retainedMessages(): List<com.messenger.app.data.api.MessageDto> = emptyList()
    suspend fun retainedPlaintext(dto: com.messenger.app.data.api.MessageDto): ByteArray? = null
    suspend fun decryptRetained(dto: com.messenger.app.data.api.MessageDto, signalDeviceId: Int): ByteArray =
        decrypt(dto.senderId, signalDeviceId, dto.messageType, dto.ciphertext)
    suspend fun acknowledge(dto: com.messenger.app.data.api.MessageDto) = Unit
    suspend fun resolveSignalDeviceId(userId: String, deviceId: String, accessToken: String): Int
    suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray
}
