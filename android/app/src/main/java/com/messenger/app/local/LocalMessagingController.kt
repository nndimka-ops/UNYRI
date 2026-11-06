package com.messenger.app.local

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocalMessagingController(
    private val context: Context,
    private val auth: AuthRepository,
    val connection: StateFlow<SocketConnectionState>,
) {
    private val prefs = context.getSharedPreferences("local_messaging", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled
    val running = MutableStateFlow(false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
        if (value) resumeFromVisibleActivity()
        else context.stopService(Intent(context, LocalMessagingService::class.java))
    }

    /** Only invoked by a visible activity or an explicit user toggle. */
    fun resumeFromVisibleActivity() {
        if (!_enabled.value || auth.authState.value !is AuthSessionState.LoggedIn) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocalMessagingService::class.java))
        } catch (e: IllegalStateException) {
            running.value = false
            android.util.Log.w("LocalMessaging", "Foreground start unavailable", e)
        } catch (e: SecurityException) {
            running.value = false
            android.util.Log.w("LocalMessaging", "Foreground start denied", e)
        }
    }
}
