package com.messenger.app.push

/** Device preferences only: changing alerts never changes delivery, unread state or call state. */
data class NotificationSettings(
    val enabled: Boolean = true,
    val messages: Boolean = true,
    val calls: Boolean = true,
    val messageSound: Boolean = true,
    val inAppBanners: Boolean = true,
) {
    val messageAlertsEnabled: Boolean get() = enabled && messages
    val callAlertsEnabled: Boolean get() = enabled && calls
    val foregroundBannersEnabled: Boolean get() = messageAlertsEnabled && inAppBanners
}
