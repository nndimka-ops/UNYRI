package com.messenger.app.ui.chats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class ChatMotionSettings(
    val active: Boolean = false,
    val durationScale: Float = 1f,
    val lowPower: Boolean = false,
    val touchExploration: Boolean = false,
    val visibilityEpoch: Long = 0,
    val receiptVisibility: ReceiptVisibilityState? = null,
) {
    val reduceMotion: Boolean get() = durationScale <= 0f
    val calm: Boolean get() = reduceMotion || lowPower || !active
}

/** All covering list windows share one gate, including handoffs between dialogs. */
internal data class ChatListModalCoverage(
    val contactMenu: Boolean = false,
    val deleteConfirmation: Boolean = false,
    val batchDeleteConfirmation: Boolean = false,
    val filterPopup: Boolean = false,
    val errorDialog: Boolean = false,
) {
    val covered: Boolean get() = contactMenu || deleteConfirmation || batchDeleteConfirmation || filterPopup || errorDialog
    val headerVisible: Boolean get() = !covered
}

/** Non-finite/negative system values must never start a cosmetic loop. */
internal fun chatAnimatorScale(value: Float): Float =
    if (value.isFinite() && value > 0f) value else 0f

/** A screen provides this to rows, avoiding one system observer per receipt/avatar. */
internal val LocalChatMotionSettings = staticCompositionLocalOf<ChatMotionSettings?> { null }

@Composable
internal fun rememberChatMotionSettings(visible: Boolean = true): ChatMotionSettings {
    LocalChatMotionSettings.current?.let { return if (visible) it else it.copy(active = false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibility = remember(context, lifecycle) {
        ReceiptVisibilityState(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), SystemClock::uptimeMillis)
    }
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    fun snapshot(): ChatMotionSettings {
        val visible = visibility.update(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        return ChatMotionSettings(
            active = visible.active,
            durationScale = chatAnimatorScale(runCatching { Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)),
            lowPower = power?.isPowerSaveMode == true,
            touchExploration = accessibility?.isTouchExplorationEnabled == true,
            visibilityEpoch = visible.epoch,
            receiptVisibility = visibility,
        )
    }
    var settings by remember(context, lifecycle) { mutableStateOf(snapshot()) }
    DisposableEffect(context, lifecycle, accessibility) {
        fun refresh() { settings = snapshot() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { refresh() }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(lifecycleObserver)
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        accessibility?.addTouchExplorationStateChangeListener(touchListener)
        refresh()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
            context.unregisterReceiver(receiver)
            accessibility?.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return if (visible) settings else settings.copy(active = false)
}
