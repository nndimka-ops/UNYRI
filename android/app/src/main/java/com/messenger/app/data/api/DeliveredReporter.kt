package com.messenger.app.data.api

/**
 * The subset of [MessagesApi] that [com.messenger.app.data.sync.MessageIngestionPipeline]
 * depends on for reporting its own explicit post-decrypt delivered watermark -
 * kept narrow so the pipeline is unit-testable with a fake instead of a real
 * network client.
 */
interface DeliveredReporter {
    suspend fun markDelivered(accessToken: String, chatId: String, sequence: Long): ReadReceiptResponseDto
}
