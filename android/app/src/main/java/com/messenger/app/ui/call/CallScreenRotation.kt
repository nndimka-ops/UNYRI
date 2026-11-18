package com.messenger.app.ui.call

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.messenger.app.OrientationLease

@Suppress("DEPRECATION")
@Composable
internal fun rememberCallScreenDegrees(): Int {
    val context = LocalContext.current
    val window = remember(context) { context.getSystemService(WindowManager::class.java) }
    var degrees by remember(window) { mutableIntStateOf(window.defaultDisplay.rotation * 90) }
    DisposableEffect(window) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) { degrees = window.defaultDisplay.rotation * 90 }
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return degrees
}

/** One lease for the whole full-screen presentation, including sheets and terminal stages. */
@Composable
internal fun CallScreenRotation(fullScreen: Boolean) {
    val activity = LocalContext.current as? Activity
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, fullScreen, lifecycle) {
        if (activity != null && fullScreen) OrientationLease.acquire(activity, OrientationLease.Call)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Home can release the owner before a PiP attempt fails; restore on return.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && activity != null && fullScreen)
                OrientationLease.acquire(activity, OrientationLease.Call)
        }
        if (fullScreen) lifecycle.addObserver(observer)
        onDispose {
            if (fullScreen) lifecycle.removeObserver(observer)
            if (activity != null && fullScreen) OrientationLease.release(activity, OrientationLease.Call)
        }
    }
}
