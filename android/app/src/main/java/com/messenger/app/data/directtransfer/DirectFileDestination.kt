package com.messenger.app.data.directtransfer

import com.messenger.app.domain.model.ChatSummary

/** One destination/account snapshot, checked against the current session at use. */
internal data class DirectFileDestinationSnapshot(
    val chatId: String?, val type: String?, val blocked: Boolean, val peerUserId: String?,
    val accountUserId: String?, val epoch: Int,
)

internal fun directFileDestination(chat: ChatSummary?, account: String?, epoch: Int) = DirectFileDestinationSnapshot(
    chat?.chatId, chat?.type, chat?.isBlocked ?: false, chat?.otherUser?.id, account, epoch,
)

internal fun directFileRecipient(snapshot: DirectFileDestinationSnapshot, expectedChat: String,
    currentUser: String?, currentEpoch: Int): String? {
    if (snapshot.epoch != currentEpoch || snapshot.accountUserId != currentUser || currentUser == null || !canonicalUuid(currentUser)) return null
    if (snapshot.chatId != expectedChat || !canonicalUuid(expectedChat) || snapshot.type != "direct" || snapshot.blocked) return null
    return snapshot.peerUserId?.takeIf { canonicalUuid(it) && it != currentUser }
}

internal const val DIRECT_NORMAL_SOURCE_LIMIT = 100L * 1024 * 1024
internal enum class DirectOriginalRoute { NORMAL, DIRECT, UNAVAILABLE }

/** Unknown metadata is never evidence of oversize in an unsupported chat. */
internal fun directOriginalRoute(size: Long, eligible: Boolean): DirectOriginalRoute = when {
    size > DIRECT_NORMAL_SOURCE_LIMIT -> if (eligible) DirectOriginalRoute.DIRECT else DirectOriginalRoute.UNAVAILABLE
    size <= 0 && eligible -> DirectOriginalRoute.DIRECT
    else -> DirectOriginalRoute.NORMAL
}
