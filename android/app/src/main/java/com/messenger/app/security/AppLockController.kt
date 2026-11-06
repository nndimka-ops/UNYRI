package com.messenger.app.security

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppLockState(val enabled: Boolean = false, val timeoutMs: Long = 0, val locked: Boolean = false, val authenticating: Boolean = false, val error: Boolean = false)

/** Native authentication only. No password/PIN or successful-auth state is persisted. */
class AppLockController(private val activity: MainActivity) {
    private val app get() = activity.application as MessengerApp
    private val preferences = activity.getSharedPreferences("native-app-lock", android.content.Context.MODE_PRIVATE)
    private val keyguard get() = activity.getSystemService(KeyguardManager::class.java)
    private val mutable = MutableStateFlow(AppLockState())
    val state: StateFlow<AppLockState> = mutable
    private var owner: String? = null
    private var backgroundAt: Long? = null
    private var generation = 0L
    private var foreground = false
    private var backgroundGeneration = 0L
    private var nativeCredentialPause: Long? = null
    private var cancellation: CancellationSignal? = null
    private var pending: (() -> Unit)? = null
    private var pendingOwner: String? = null
    private var pendingGeneration = 0L
    private data class CredentialProof(val owner: String?, val ticket: Long, val background: Long)
    private var credentialRequest: CredentialProof? = null
    private val credential = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = credentialRequest; credentialRequest = null
        if (request != null) finishAuthentication(result.resultCode == android.app.Activity.RESULT_OK, request.owner, request.ticket, request.background, credentialReturned = nativeCredentialPause == backgroundGeneration)
    }
    private fun currentOwner(): Pair<String,String>? = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id?.let {
        val stable = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') + "|" + it
        stable to "$stable|${app.authRepository.currentSessionEpoch()}"
    }
    fun canReadCurrentOwner(): Boolean = AppLockPolicy.permitsViewedCursor(owner, currentOwner()?.second, mutable.value.locked)
    fun syncOwner() {
        val current = currentOwner()
        if (current?.second == owner) return
        generation++; cancellation?.cancel(); cancellation = null; pending = null
        owner = current?.second; backgroundAt = null; nativeCredentialPause = null
        val enabled = preferences.getBoolean("device|enabled", false)
        val delay = preferences.getLong("device|timeout", 0L)
        mutable.value = AppLockState(enabled, delay, locked = AppLockPolicy.protectsOwner(enabled, owner))
        secureWindow()
    }
    private fun secureWindow() {
        // Global snapshot protection also covers the unshielded login screen.
        if (preferences.getBoolean("device|enabled",false)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun onForeground() {
        foreground = true
        syncOwner()
        val state = mutable.value
        if (AppLockPolicy.shouldLock(AppLockPolicy.protectsOwner(state.enabled,owner), backgroundAt, SystemClock.elapsedRealtime(), state.timeoutMs)) mutable.value = state.copy(locked = true)
        backgroundAt = null; secureWindow()
    }
    private fun invalidateAuthentication() {
        generation++; pending = null; cancellation?.cancel(); cancellation = null
        mutable.value = mutable.value.copy(authenticating = false)
    }
    fun onUserLeave() { invalidateAuthentication() }
    fun onBackground() {
        foreground = false
        syncOwner(); backgroundAt = SystemClock.elapsedRealtime(); backgroundGeneration++
        val credentialPause = credentialRequest?.background?.plus(1) == backgroundGeneration
        if (credentialPause) nativeCredentialPause = backgroundGeneration
        else { nativeCredentialPause = null; invalidateAuthentication() }
        if (AppLockPolicy.protectsOwner(mutable.value.enabled,owner) && mutable.value.timeoutMs == 0L) mutable.value = mutable.value.copy(locked = true)
        // FLAG_SECURE remains set throughout background and native authentication.
        secureWindow()
    }
    fun setEnabled(enabled: Boolean) = authenticate {
        preferences.edit().putBoolean("device|enabled", enabled).apply()
        mutable.value = mutable.value.copy(enabled = enabled, locked = false, error = false)
        secureWindow()
    }
    fun setTimeout(timeoutMs: Long) {
        require(timeoutMs in listOf(0L,60_000L,300_000L))
        authenticate {
            preferences.edit().putLong("device|timeout", timeoutMs).apply()
            mutable.value = mutable.value.copy(timeoutMs = timeoutMs, locked = false, error = false)
        }
    }
    fun unlock(after: () -> Unit = {}) = authenticate { mutable.value = mutable.value.copy(locked = false, error = false); after() }
    private fun authenticate(success: () -> Unit) {
        syncOwner()
        if (owner == null || mutable.value.authenticating) return
        if (!keyguard.isDeviceSecure) { mutable.value = mutable.value.copy(error = true); return }
        pendingOwner = owner; pendingGeneration = ++generation; pending = success
        val requestOwner = pendingOwner; val requestGeneration = pendingGeneration; val requestBackground = backgroundGeneration
        mutable.value = mutable.value.copy(authenticating = true, error = false)
        if (Build.VERSION.SDK_INT >= 28) {
            cancellation = CancellationSignal()
            val builder = BiometricPrompt.Builder(activity).setTitle(activity.getString(R.string.app_lock_auth_title))
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                // API 28/29 cannot combine the modern authenticator flags.
                builder.setNegativeButton(activity.getString(R.string.app_lock_system_pin), activity.mainExecutor) { _, _ -> launchCredential(requestOwner, requestGeneration, requestBackground) }
            }
            builder.build().authenticate(cancellation!!, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finishAuthentication(true, requestOwner, requestGeneration, requestBackground) }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (Build.VERSION.SDK_INT < 30 && code in listOf(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE)) launchCredential(requestOwner, requestGeneration, requestBackground)
                    else finishAuthentication(false, requestOwner, requestGeneration, requestBackground)
                }
            })
        } else launchCredential(requestOwner, requestGeneration, requestBackground)
    }
    @Suppress("DEPRECATION")
    private fun launchCredential(requestOwner: String?, requestGeneration: Long, requestBackground: Long) {
        if (!AppLockPolicy.accepts(requestOwner, currentOwner()?.second, requestGeneration, generation) || pending == null || credentialRequest != null) return
        val intent = keyguard.createConfirmDeviceCredentialIntent(activity.getString(R.string.app_lock_auth_title), activity.getString(R.string.app_lock_detail))
        if (intent == null) finishAuthentication(false, requestOwner, requestGeneration, requestBackground) else {
            // Keep the expected native credential pause distinct from Home.
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            credentialRequest = CredentialProof(requestOwner, requestGeneration, requestBackground); credential.launch(intent)
        }
    }
    private fun finishAuthentication(success: Boolean, requestOwner: String?, requestGeneration: Long, requestBackground: Long, credentialReturned: Boolean = false) {
        val accepted = AppLockPolicy.accepts(requestOwner, currentOwner()?.second, requestGeneration, generation)
        if (!accepted) { syncOwner(); return }
        if (!AppLockPolicy.acceptsForegroundProof(foreground, requestBackground, backgroundGeneration, credentialReturned)) {
            invalidateAuthentication()
            return // A background callback cannot clear the timer or unlock later.
        }
        val callback = pending; pending = null; cancellation = null
        mutable.value = mutable.value.copy(authenticating = false, error = !success)
        if (success) { backgroundAt = null; callback?.invoke() }
    }
    fun destroy() { generation++; pending = null; cancellation?.cancel(); cancellation = null }
}
