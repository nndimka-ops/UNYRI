package com.messenger.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Foreground/background detection via the standard
 *  `ActivityLifecycleCallbacks` mechanism (Этап J.1-4 п.11) - not an Activity
 *  hack, the same mechanism `ProcessLifecycleOwner` is built on. `true` when
 *  at least one activity is started. */
class AppForegroundTracker(private val app: Application) {

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    private var startedActivities = 0

    fun start() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    _isForeground.value = true
                    android.util.Log.d(TAG, "foreground=true")
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
                if (startedActivities == 0) {
                    _isForeground.value = false
                    android.util.Log.d(TAG, "foreground=false")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private companion object {
        const val TAG = "AppForeground"
    }
}
