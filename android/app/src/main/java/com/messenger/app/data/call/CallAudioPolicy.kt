package com.messenger.app.data.call

import com.messenger.app.domain.model.CallMediaKind

/** Pure/JVM-testable audio-route policy (Этап J.1-7) - no Android imports so
 *  the routing + permission rules can be unit-tested without a device.
 *
 *  Rules:
 *  - MIC + (CAMERA for video) are REQUIRED - a denial blocks the call.
 *  - BLUETOOTH_CONNECT (Android 12+/S, API 31+) is OPTIONAL - a denial never
 *    blocks a call, it just means no Bluetooth output route can be named or
 *    selected. Requesting it only on S+ keeps older devices untouched. */
object CallAudioPolicy {
    /** The first call route is private when the hardware provides one. A
     * previous built-in speaker route is not a new call's explicit choice.
     * Active headsets retain priority over another connected headset. */
    fun initialRoute(available: Collection<AudioRouteKind>, current: AudioRouteKind): AudioRouteKind? {
        if (current in listOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED_HEADSET) && current in available) return current
        return listOf(AudioRouteKind.WIRED_HEADSET, AudioRouteKind.BLUETOOTH, AudioRouteKind.EARPIECE, AudioRouteKind.SPEAKER)
            .firstOrNull { it in available }
    }

    /** Keep the actual active headset when multiple devices share its kind;
     * selecting Bluetooth must not silently switch to the first paired item. */
    fun preferredDeviceId(available: List<Pair<Int, AudioRouteKind>>, requested: AudioRouteKind, currentId: Int?): Int? =
        available.firstOrNull { it.first == currentId && it.second == requested }?.first
            ?: available.firstOrNull { it.second == requested }?.first

    const val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val PERMISSION_CAMERA = "android.permission.CAMERA"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    /** Android 12 (API 31) is where BLUETOOTH_CONNECT becomes a runtime
     *  permission for reading Bluetooth device names / selecting them. */
    const val BLUETOOTH_CONNECT_MIN_SDK = 31

    fun requiredCallPermissions(mediaKind: CallMediaKind): List<String> =
        if (mediaKind == CallMediaKind.VIDEO) listOf(PERMISSION_RECORD_AUDIO, PERMISSION_CAMERA)
        else listOf(PERMISSION_RECORD_AUDIO)

    /** Permissions that are REQUIRED merely to ACCEPT an incoming call. CAMERA
     *  is deliberately NOT required here: a user must be able to accept an
     *  incoming VIDEO call with their own camera unavailable - they receive
     *  the remote video, their own camera stays OFF, and audio works. CAMERA
     *  is requested lazily, only when the user turns their own video on. */
    fun requiredCallPermissionsForAccept(mediaKind: CallMediaKind): List<String> =
        listOf(PERMISSION_RECORD_AUDIO)

    /** All permissions requested when accepting an incoming call - only the
     *  microphone (required) plus the optional Bluetooth label permission. */
    fun allCallPermissionsForAccept(mediaKind: CallMediaKind, sdkInt: Int): List<String> =
        requiredCallPermissionsForAccept(mediaKind) + optionalCallPermissions(sdkInt)

    /** Optional permissions to request alongside the required ones - none on
     *  pre-S devices, BLUETOOTH_CONNECT on S+ so the Bluetooth route label
     *  can show the real device name. */
    fun optionalCallPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= BLUETOOTH_CONNECT_MIN_SDK) listOf(PERMISSION_BLUETOOTH_CONNECT) else emptyList()

    /** All permissions to request for a call, required first then optional. */
    fun allCallPermissions(mediaKind: CallMediaKind, sdkInt: Int): List<String> =
        requiredCallPermissions(mediaKind) + optionalCallPermissions(sdkInt)

    /** Of the permissions that were DENIED, which ones actually block the
     *  call? An optional BLUETOOTH_CONNECT denial is not blocking. */
    fun blockingDenials(mediaKind: CallMediaKind, denied: Set<String>): Set<String> {
        val required = requiredCallPermissions(mediaKind).toSet()
        return denied.intersect(required)
    }

    /** Blocking denials for ACCEPTING an incoming call - a CAMERA denial is
     *  never blocking here (the user accepts with their own camera off). Only
     *  a missing microphone blocks. */
    fun blockingDenialsForAccept(mediaKind: CallMediaKind, denied: Set<String>): Set<String> {
        return denied.intersect(requiredCallPermissionsForAccept(mediaKind).toSet())
    }

    /** Fallback label for a route kind when the real device name isn't
     *  available (no permission, or a generic built-in device). */
    fun defaultLabel(kind: AudioRouteKind): String = when (kind) {
        AudioRouteKind.EARPIECE -> "Phone"
        AudioRouteKind.SPEAKER -> "Speaker"
        AudioRouteKind.BLUETOOTH -> "Bluetooth"
        AudioRouteKind.WIRED_HEADSET -> "Wired headset"
    }

    /** A non-empty display label: the device name if provided, otherwise the
     *  kind's fallback. Never returns a blank string. */
    fun label(kind: AudioRouteKind, deviceName: String?): String =
        deviceName?.takeIf { it.isNotBlank() } ?: defaultLabel(kind)
}

/** Per-call automatic routing, separate from volume and media negotiation.
 * Only the local camera is an input; remote video cannot enable speaker.
 * Reset at a call/session boundary, never at renderer/media initialization. */
internal class CallAudioRoutePolicy {
    private var manualRoute: AudioRouteKind? = null
    private var localCameraEnabled = false

    fun reset() {
        manualRoute = null
        localCameraEnabled = false
    }

    fun selectedManually(kind: AudioRouteKind) { manualRoute = kind }

    fun localCameraChanged(enabled: Boolean, available: Collection<AudioRouteKind>, current: AudioRouteKind): AudioRouteKind? {
        localCameraEnabled = enabled
        return desiredRoute(available, current)
    }

    fun devicesChanged(available: Collection<AudioRouteKind>, current: AudioRouteKind): AudioRouteKind? =
        desiredRoute(available, current)

    private fun desiredRoute(available: Collection<AudioRouteKind>, current: AudioRouteKind): AudioRouteKind? {
        val privateDefault = CallAudioPolicy.initialRoute(available, current)
        val external = listOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED_HEADSET)
        val target = when {
            // Never pull sound out of an active connected headset.
            current in external && current in available -> current
            // An explicit choice survives camera on/off and reconnect. A
            // missing headset falls back privately, not to video speaker.
            manualRoute != null -> manualRoute?.takeIf { it in available } ?: privateDefault
            privateDefault in external -> privateDefault
            localCameraEnabled && AudioRouteKind.SPEAKER in available -> AudioRouteKind.SPEAKER
            else -> privateDefault
        }
        return target?.takeIf { it != current }
    }
}
