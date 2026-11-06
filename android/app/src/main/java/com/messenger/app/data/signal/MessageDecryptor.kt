package com.messenger.app.data.signal

/**
 * The subset of [SignalSessionManager] that [com.messenger.app.data.sync.MessageIngestionPipeline]
 * depends on for decrypting incoming envelopes - kept narrow so the pipeline's
 * orchestration logic (dedup, session-epoch abort, cursor advance, delivered
 * batching) is unit-testable with a fake instead of real Signal Protocol
 * crypto (which is already exercised end-to-end by SignalCryptoTest.kt).
 */
interface MessageDecryptor {
    suspend fun resolveSignalDeviceId(userId: String, deviceId: String, accessToken: String): Int
    suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray
}
