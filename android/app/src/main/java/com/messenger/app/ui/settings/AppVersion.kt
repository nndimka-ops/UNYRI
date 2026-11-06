package com.messenger.app.ui.settings

/** Pure version-string formatting (Phase 6 §20/§21): the Settings About
 *  section reads real build metadata from `BuildConfig` and formats it here so
 *  the string is deterministic and unit-testable without touching the real app
 *  version. Never a hardcoded version, never a git SHA. */
object AppVersion {
    fun display(versionName: String, versionCode: Int): String = "Version $versionName ($versionCode)"
}
