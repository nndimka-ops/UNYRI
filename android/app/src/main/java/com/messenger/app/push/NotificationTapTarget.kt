package com.messenger.app.push

import kotlinx.serialization.Serializable
import java.util.UUID

/** User taps have structure/recipient checks only. No expiry or registration checks. */
@Serializable
data class NotificationTapTarget(val workspace: String, val owner: String, val device: String,
    val chat: String, val message: String) {
    fun valid(): Boolean = com.messenger.app.data.auth.AccountWorkspaceLayout.validKey(workspace) &&
        listOf(owner, device, chat, message).all(::canonicalUuid)
    fun addressedTo(workspace: String, owner: String?, device: String?): Boolean =
        valid() && this.workspace == workspace && this.owner == owner && this.device == device
    companion object {
        fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
