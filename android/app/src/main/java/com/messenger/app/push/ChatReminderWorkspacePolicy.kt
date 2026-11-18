package com.messenger.app.push

import com.messenger.app.data.auth.AccountWorkspaceLayout

/** A matching user UUID alone is insufficient across independently hosted servers. */
internal object ChatReminderWorkspacePolicy {
    fun accepts(recorded: String?, active: String): Boolean =
        if (recorded == null) active == AccountWorkspaceLayout.LEGACY else recorded == active

    fun uri(workspace: String, owner: String, chat: String, message: String): String =
        "coco://reminder/$workspace/$owner/$chat/$message"

    fun notificationTag(workspace: String, owner: String, chat: String, message: String): String =
        "reminder:$workspace:$owner:$chat:$message"
}
