package com.messenger.app.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager

/** One device policy for every native window; screenshot blocking is opt-in. */
object ScreenshotPrivacy {
    fun apply(activity: Activity) {
        val prefs = activity.getSharedPreferences("native-app-lock", Context.MODE_PRIVATE)
        if (prefs.getBoolean("device|enabled", false) || prefs.getBoolean("device|prevent_screenshots", false))
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
    }
}
