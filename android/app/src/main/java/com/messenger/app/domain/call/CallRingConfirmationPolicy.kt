package com.messenger.app.domain.call

import com.messenger.app.data.api.AuthApiException

/** One retry for delivery failures; authorization/conflict/other 4xx are final. */
object CallRingConfirmationPolicy {
    /** Notification enablement is necessary but not proof of an alert. A silent
     * call confirms only with an available visible presentation path. */
    fun presented(
        appNotificationsEnabled: Boolean,
        channelEnabled: Boolean,
        channelImportance: Int,
        dndAllowsCalls: Boolean,
        alertSurfaceAvailable: Boolean,
    ): Boolean = appNotificationsEnabled && channelEnabled && channelImportance >= 3 && dndAllowsCalls && alertSurfaceAvailable

    /** No assumptions about a shade-only notification waking a dark display. */
    fun visualSurfaceAvailable(
        activityVisible: Boolean, screenInteractive: Boolean, channelImportance: Int,
        fullScreenRequested: Boolean, fullScreenPermitted: Boolean, sdk: Int,
        headsUpAllowed: Boolean, displayCanShowHeadsUp: Boolean,
    ): Boolean = (activityVisible && screenInteractive) ||
        (channelImportance >= 4 && headsUpAllowed && displayCanShowHeadsUp &&
            (screenInteractive || (sdk >= 34 && fullScreenRequested && !fullScreenPermitted)))

    fun retry(error: Exception): Boolean = when (error) {
        is AuthApiException.Network -> error.httpStatus == null || error.httpStatus in 500..599
        is AuthApiException.Api -> error.httpStatus in 500..599
        is java.io.IOException -> true
        else -> false
    }

    /** Android IMPORTANCE_DEFAULT is 3; MIN/LOW are quiet even when enabled. */
    fun wouldAlert(
        appNotificationsEnabled: Boolean,
        channelEnabled: Boolean,
        channelImportance: Int,
        soundAllowed: Boolean,
        vibrationAllowed: Boolean,
        dndAllowsCalls: Boolean,
    ): Boolean = appNotificationsEnabled && channelEnabled && channelImportance >= 3 &&
        (soundAllowed || vibrationAllowed) && dndAllowsCalls

    /** Affinity must describe the Person actually posted, never its display name,
     * app address-book membership or Person.isImportant (which is not starred). */
    enum class CallerAffinity { UNKNOWN, CONTACT, STARRED }

    /** Zen may expose SILENT externally while retaining the user's NORMAL mode
     * internally. An allowed priority call uses that persisted user choice;
     * genuine user silence and unavailable/invalid settings remain silent. */
    fun effectiveRingerMode(external: Int, persistedUserMode: Int?, interruptionFilter: Int,
        dndAllowsCalls: Boolean): Int = if (interruptionFilter == 2 && dndAllowsCalls &&
            persistedUserMode != null && persistedUserMode in 0..2) persistedUserMode else external

    /** Android Policy call senders: ANY=0, CONTACTS=1, STARRED=2. Bypass and the
     * CATEGORY_CALL exception apply only in priority mode, never NONE/ALARMS. */
    fun dndAllowsCalls(interruptionFilter: Int, channelBypassesDnd: Boolean,
        priorityCallsAllowed: Boolean = false, priorityCallSenders: Int = -1,
        caller: CallerAffinity = CallerAffinity.UNKNOWN): Boolean = when (interruptionFilter) {
        1 -> true
        2 -> channelBypassesDnd || (priorityCallsAllowed && when (priorityCallSenders) {
            0 -> true
            1 -> caller != CallerAffinity.UNKNOWN
            2 -> caller == CallerAffinity.STARRED
            else -> false
        })
        else -> false
    }
}
