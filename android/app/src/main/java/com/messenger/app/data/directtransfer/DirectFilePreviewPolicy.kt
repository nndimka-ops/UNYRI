package com.messenger.app.data.directtransfer

internal enum class DirectFilePosterSource { NONE, OFFER, COMPLETED }

/** Completion alone never authorizes a local decode; the coordinator must supply its checked file. */
internal fun directFilePosterSource(offer: DirectFileOffer, completed: Boolean, checkedFileAvailable: Boolean): DirectFilePosterSource = when {
    completed && checkedFileAvailable && (offer.mimeType.startsWith("image/") || offer.mimeType.startsWith("video/")) -> DirectFilePosterSource.COMPLETED
    offer.safeMediaPreview != null -> DirectFilePosterSource.OFFER
    else -> DirectFilePosterSource.NONE
}
