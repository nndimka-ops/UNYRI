package com.messenger.app.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Read synchronously at cold start, observe in Compose/notification owners, write off the UI thread. */
class NotificationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("coco-notifications", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NotificationSettings(
        enabled = preferences.getBoolean("enabled", true),
        messages = preferences.getBoolean("messages", true),
        calls = preferences.getBoolean("calls", true),
        messageSound = preferences.getBoolean("message-sound", true),
        inAppSound = preferences.getBoolean("in-app-sound", true),
        inAppVibration = preferences.getBoolean("in-app-vibration", true),
        inAppBanners = preferences.getBoolean("in-app-banners", true),
        previews = preferences.getBoolean("previews", true),
        reminders = preferences.getBoolean("reminders", true),
    ))
    val settings: StateFlow<NotificationSettings> = mutable
    val current: NotificationSettings get() = mutable.value

    @Synchronized
    fun update(transform: (NotificationSettings) -> NotificationSettings) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        preferences.edit().putBoolean("enabled", next.enabled).putBoolean("messages", next.messages)
            .putBoolean("calls", next.calls).putBoolean("message-sound", next.messageSound)
            .putBoolean("in-app-sound", next.inAppSound).putBoolean("in-app-vibration", next.inAppVibration)
            .putBoolean("in-app-banners", next.inAppBanners).putBoolean("previews", next.previews)
            .putBoolean("reminders", next.reminders).apply()
        mutable.value = next
    }
}
