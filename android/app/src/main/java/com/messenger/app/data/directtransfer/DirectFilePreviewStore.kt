package com.messenger.app.data.directtransfer

import com.messenger.app.data.local.chat.ChatWorkspaceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Presentation reads only: never admits a transfer or changes its state. */
internal suspend fun ChatWorkspaceDao.previewOffers(chat: String, clients: Set<String>): Map<String, String> =
    withContext(Dispatchers.IO) {
        val found = mutableMapOf<String, String>()
        for (client in clients) {
            val raw = get(chat, "direct_offer:$client") ?: get(chat, "direct_history:$client")
            val offer = DirectFileOffer.decodeStored(raw)
            if (offer?.offerClientMessageId == client) found[client] = offer.stored()
        }
        if (found.size == clients.size) return@withContext found
        var after = ""
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = directPreviewPage(chat, after)
            for (row in page) {
                val value = runCatching { DirectFileOffer.codec.parseToJsonElement(row.value) as? JsonObject }.getOrNull()
                val offer = DirectFileOffer.decode(value?.get("offer")) ?: continue
                if (row.name == "direct_transfer:${offer.transferId}" && offer.offerClientMessageId in clients) {
                    found.putIfAbsent(offer.offerClientMessageId, offer.stored())
                }
            }
            if (page.size < 64 || found.size == clients.size) break
            after = page.last().name
        }
        found
    }
