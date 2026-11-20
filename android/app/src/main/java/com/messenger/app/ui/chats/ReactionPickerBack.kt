package com.messenger.app.ui.chats

import android.os.Build
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalView

/** Search owns Back before the IME. Registration exists only while the panel is open. */
@Composable
internal fun ReactionPickerSystemBack(enabled: Boolean, onBack: () -> Unit) {
    val view = LocalView.current
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(view, enabled) {
        if (enabled && Build.VERSION.SDK_INT >= 33) {
            val dispatcher = view.findOnBackInvokedDispatcher()
            val callback = OnBackInvokedCallback { latestBack() }
            dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
        } else onDispose { }
    }
}

/** Android 8–12 deliver hardware/system Back to the focused search before hiding its IME. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.reactionPickerBackBeforeIme(onBack: () -> Unit): Modifier =
    onPreInterceptKeyBeforeSoftKeyboard { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode == KeyEvent.KEYCODE_BACK) {
            if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) onBack()
            true
        } else false
    }
