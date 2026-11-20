package com.messenger.app.data.identity

import android.content.SharedPreferences

/** Called once for each encrypted workspace when its token store opens. */
internal object PeerCacheUpgrade {
    fun clearDirectoryOnce(prefs: SharedPreferences) {
        if (prefs.getBoolean("b033_peer_logins_cleared", false)) return
        // Contacts are memory-only; directory is the persisted people cache.
        check(prefs.edit().remove("offline_directory")
            .putBoolean("b033_peer_logins_cleared", true).commit())
    }
}
