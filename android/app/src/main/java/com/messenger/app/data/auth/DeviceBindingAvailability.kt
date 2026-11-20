package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException

/** Creation may fall back to bearer auth; failure to open an enrolled key never may. */
class HardwareDeviceKeyUnavailableException : Exception("Hardware session key storage unavailable")

internal class DeviceBindingAvailability<Key>(
    private val openEnrolled: () -> Key?,
    private val hardwareUnavailable: () -> Boolean,
    private val create: () -> Key,
    private val rememberUnavailable: () -> Unit,
) {
    fun binding(createForLegacy: Boolean): Key? {
        openEnrolled()?.let { return it }
        if (!createForLegacy || hardwareUnavailable()) return null
        return createOrUnbound(create, rememberUnavailable)
    }

    companion object {
        fun <Key> createOrUnbound(create: () -> Key, rememberUnavailable: () -> Unit): Key? =
            try { create() }
            catch (_: HardwareDeviceKeyUnavailableException) { rememberUnavailable(); null }
    }
}

internal const val HARDWARE_BINDING_REQUIRED = "hardware_session_binding_required"

/** Only a server rejection, never local hardware discovery, becomes a terminal reason. */
internal fun deviceBindingFailureCode(code: String, hardwareUnavailable: Boolean): String =
    if (code == "session_revoked" && hardwareUnavailable) HARDWARE_BINDING_REQUIRED else code

internal fun canReuseDeviceAccessToken(hasBinding: Boolean, hardwareUnavailable: Boolean): Boolean =
    hasBinding || hardwareUnavailable

internal fun deviceBindingFailure(failure: Exception, hardwareUnavailable: Boolean): Exception {
    if (failure !is AuthApiException.Api) return failure
    val code = deviceBindingFailureCode(failure.code, hardwareUnavailable)
    return if (code == failure.code) failure
        else AuthApiException.Api(code, "This server requires a hardware session key", failure.httpStatus, failure.serverTime)
}
