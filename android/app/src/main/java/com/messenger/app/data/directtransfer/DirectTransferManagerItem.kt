package com.messenger.app.data.directtransfer

/** Deliberate display-only projection for this installation. No protocol authority. */
enum class DirectTransferManagerGroup { ACTIVE, QUEUED, HELD, ATTENTION, COMPLETED }

data class DirectTransferManagerItem(
    val id: String,
    val chatId: String,
    val outgoing: Boolean,
    val presentation: DirectFilePresentation?,
    val ordinal: Long,
    val group: DirectTransferManagerGroup,
    val queuePosition: Int? = null,
    val canCancel: Boolean = false,
    val canOpen: Boolean = false,
    val canRetry: Boolean = false,
    val canRestartFromZero: Boolean = false,
    val preparing: Boolean = false,
    /** Pre-offer display metadata only; never a path or protocol authority. */
    val displayFilename: String? = null,
    val declaredBytes: Long? = null,
)
