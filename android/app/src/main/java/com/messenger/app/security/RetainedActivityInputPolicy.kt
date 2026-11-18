package com.messenger.app.security

/** A whitelist, not a URI/prefix blocklist: framework navigation state is never retained. */
internal object RetainedActivityInputPolicy {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val STREAM = "android.intent.extra.STREAM"
    val routeKeys = setOf("notification_workspace", "notification_owner_id", "notification_device_id",
        "notification_chat_id", "notification_message_id", "notification_route_proof")

    fun keys(verifiedRoute: Boolean, action: String?): Set<String> = buildSet {
        if (verifiedRoute) addAll(routeKeys)
        if (action == SEND || action == SEND_MULTIPLE) add(STREAM)
    }
}
