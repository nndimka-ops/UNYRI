package com.messenger.app.data.directtransfer

/** Portable inert metadata only. Never a protocol journal or proof of consent. */
internal object DirectFileHistory {
    const val PREFIX = "direct_history:"
    fun key(client: String): String { require(canonicalUuid(client)); return PREFIX + client }
    fun validate(offer: DirectFileOffer, client: String, sender: String, owner: String): DirectFileOffer {
        offer.validated()
        require(offer.offerClientMessageId == client && offer.senderUserId == sender &&
            owner in setOf(offer.senderUserId, offer.recipientUserId))
        return offer
    }
    fun presentation(value: com.messenger.app.data.local.chat.ChatWorkspaceValue, chat: String): DirectFilePresentation? {
        if (value.chatId != chat || !value.name.startsWith(PREFIX)) return null
        val offer = DirectFileOffer.decodeStored(value.value) ?: return null
        if (value.name != key(offer.offerClientMessageId)) return null
        return DirectFilePresentation(offer, "other_device", "other", reason = "history_only")
    }
}
