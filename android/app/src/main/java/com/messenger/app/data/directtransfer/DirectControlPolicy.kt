package com.messenger.app.data.directtransfer

/** Pure validation boundary: caller performs no effects before this returns. */
internal object DirectControlPolicy {
    fun validate(record: DirectTransferRecord, value: DirectFileControl, envelope: DirectSignalEnvelope, user: String?, device: String?) {
        require(value.version == 1 && value.transferId == record.offer.transferId && value.chatId == record.chatId && value.offerClientMessageId == record.offer.offerClientMessageId)
        require(value.clientSignalId == envelope.clientSignalId && value.senderUserId == envelope.senderUserId && value.senderDeviceId == envelope.senderDeviceId)
        require(value.recipientDeviceId == device && value.recipientUserId == user && canonicalUuid(value.attemptId))
        require(DirectOfferNonce.matches(record.offer.offerNonce, value.offerNonce)) { "Offer possession proof does not match" }
        if (record.role == "sender" && record.peerDeviceId != null) require(value.senderDeviceId == record.peerDeviceId)
        if (record.role == "receiver") require(value.senderDeviceId == record.offer.senderDeviceId)
        require(record.role in setOf("sender", "receiver"))
        require(value.senderUserId == if (record.role == "sender") record.offer.recipientUserId else record.offer.senderUserId)
        if (value.type == "pause_state") {
            // Outer claim metadata and possession-proof-free pause never bootstrap peer authority.
            require(record.consent && record.peerDeviceId == value.senderDeviceId)
            if (record.role == "receiver") require(record.registered)
        }
        val snapshot = value.validatedManualSnapshot()
        if (snapshot != null) (record.manualPause ?: DirectManualPauseState()).applying(snapshot, terminal = record.terminal)
        val receiverOrigin = record.role == "sender"
        val allowed = if (receiverOrigin) setOf("accept", "resume", "answer", "pause", "pause_state", "cancel", "reject", "complete") else setOf("offer", "pause", "pause_state", "cancel", "complete_ack")
        require(value.type in allowed)
        if (value.type in setOf("offer", "answer")) require(value.sdp != null && DirectPeerPolicy.isDataOnlySdp(value.sdp))
        else require(value.sdp == null)
        if (value.type in setOf("accept", "resume")) require(value.offset != null && value.offset in 0..record.offer.size && value.prefixSha256?.let(::validHash) == true)
        if (value.type in setOf("complete", "complete_ack")) require(value.offset == record.offer.size && value.prefixSha256 == record.offer.sha256)
        if (value.type !in setOf("accept", "resume", "complete", "complete_ack")) require(value.offset == null && value.prefixSha256 == null)
        require(value.reason == null || value.reason in DIRECT_CONTROL_REASONS)
        if (value.type == "pause_state") require(value.reason == null)
    }
}
