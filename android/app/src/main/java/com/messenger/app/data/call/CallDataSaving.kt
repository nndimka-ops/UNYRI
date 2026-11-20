package com.messenger.app.data.call

/** The iOS call-data choice, with the previous quality pair as a read-only fallback. */
enum class CallDataSaving(val wire: String) {
    NEVER("never"), CELLULAR("cellular"), ALWAYS("always");

    companion object {
        const val KEY = "callDataSaving.v1"

        fun resolved(stored: String?, legacyVideo: String?, legacyAudio: String?): CallDataSaving =
            entries.firstOrNull { it.wire == stored }
                ?: if (legacyVideo == CallQuality.ECONOMY.wire || legacyAudio == CallQuality.ECONOMY.wire) ALWAYS else NEVER
    }

    /** Metered Wi-Fi and an unknown path receive the same conservative tier as cellular. */
    fun tier(networkAvailable: Boolean, cellular: Boolean, metered: Boolean, constrained: Boolean): CallNetworkTier {
        if (this == ALWAYS || constrained) return CallNetworkTier.SAVING
        if (!networkAvailable || cellular || metered) {
            return if (this == CELLULAR) CallNetworkTier.SAVING else CallNetworkTier.CELLULAR
        }
        return CallNetworkTier.WIFI
    }

    /** Keep a source capable of the best tier this choice can reach without restarting the camera. */
    fun captureProfile(): CallMediaProfile = if (this == ALWAYS) CallMediaLadder.saving else CallMediaLadder.wifi
}
