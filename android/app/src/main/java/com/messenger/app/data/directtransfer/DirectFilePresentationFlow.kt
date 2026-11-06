package com.messenger.app.data.directtransfer

import com.messenger.app.data.local.chat.ChatWorkspaceValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Durable prefix is written on every ACK. Only its UI publication is coalesced. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<ChatWorkspaceValue>>.directFilePresentations(
    chat: String,
    localStops: Flow<Map<String, DirectLocalTransferStop>> = flowOf(emptyMap()),
): Flow<Map<String, DirectFilePresentation>> {
    var lastSignature: Map<String, DirectFilePresentation>? = null
    var publishedAt = 0L
    var lastHistoryValues: List<ChatWorkspaceValue>? = null
    var cachedHistory: Map<String, DirectFilePresentation> = emptyMap()
    return map { values ->
        // Decoding old immutable descriptors never repeats for each live ACK.
        val historyValues = values.filter { it.name.startsWith(DirectFileHistory.PREFIX) }
        if (historyValues != lastHistoryValues) {
            cachedHistory = historyValues.mapNotNull { DirectFileHistory.presentation(it, chat) }
                .associateBy { it.offer.offerClientMessageId }
            lastHistoryValues = historyValues
        }
        // History-only descriptors cannot enter the coordinator's protocol query.
        val projected = cachedHistory.toMutableMap()
        // A trusted existing local journal always wins, including completed files.
        values.filter { it.name.startsWith("direct_transfer:") }.forEach { value ->
            val record = DirectTransferCoordinator.decodeRecord(value.value)
            if (record != null && record.chatId == chat && value.chatId == chat &&
                value.name == DirectTransferCoordinator.key(record.offer.transferId)) {
                projected[record.offer.offerClientMessageId] = record.presentation()
            }
        }
        projected.toMap()
    }
        // Process-local stop publication must render even while Room is stalled.
        // Decode only durable emissions; readonly history still has no actions.
        .combine(localStops) { values, stops ->
            values.mapValues { (_, value) -> value.withLocalStop(stops[value.offer.transferId]) }
        }
        .transformLatest { values ->
            val signature = values.mapValues { (_, value) -> value.copy(offset = 0, ackSampleId = 0, speedBytesPerSecond = 0) }
            if (signature == lastSignature) {
                val remaining = 250 - (System.nanoTime() / 1_000_000 - publishedAt)
                if (remaining > 0) delay(remaining)
            }
            emit(values)
            lastSignature = signature; publishedAt = System.nanoTime() / 1_000_000
        }
}
