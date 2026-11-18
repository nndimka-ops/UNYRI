package com.messenger.app.data.directtransfer

internal object DirectPreviewFixture {
    fun offer(client: String = "10000000-0000-4000-8000-000000000002") = DirectFileOffer(
        transferId = "10000000-0000-4000-8000-000000000001", offerClientMessageId = client,
        senderUserId = "10000000-0000-4000-8000-000000000003", senderDeviceId = "10000000-0000-4000-8000-000000000004",
        recipientUserId = "10000000-0000-4000-8000-000000000005", offerNonce = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        filename = "b023-report.pdf", mimeType = "application/pdf", size = 1024, sha256 = "a".repeat(64), expiresAt = 999999,
    )
}
