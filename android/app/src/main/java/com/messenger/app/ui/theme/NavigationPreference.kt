package com.messenger.app.ui.theme

import android.content.Context

/** "Show Calls tab" navigation preference (Phase 8 §11/§36). Local,
 *  non-sensitive — plain SharedPreferences is sufficient. Device-global (not
 *  account-scoped), matching [ThemePreference]/[LanguagePreference]: the
 *  current app has no account-scoped settings layer and adding one for a
 *  single toggle is not warranted in this phase. Default resolves to ON. */
class NavigationPreference(context: Context) {
    private val prefs = context.getSharedPreferences("coco_navigation", Context.MODE_PRIVATE)

    fun showSavedMessages(): Boolean = prefs.getBoolean("show_saved_messages", true)
    fun setShowSavedMessages(show: Boolean) { prefs.edit().putBoolean("show_saved_messages", show).apply() }

    fun showSearchTab(): Boolean = prefs.getBoolean("show_search_tab", true)
    fun setShowSearchTab(show: Boolean) { prefs.edit().putBoolean("show_search_tab", show).apply() }

    fun showTabLabels(): Boolean = prefs.getBoolean("show_tab_labels", true)
    fun setShowTabLabels(show: Boolean) { prefs.edit().putBoolean("show_tab_labels", show).apply() }

    fun showCallsTab(): Boolean = prefs.getBoolean(KEY_SHOW_CALLS, true)

    fun setShowCallsTab(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_CALLS, show).apply()
    }

    private companion object {
        const val KEY_SHOW_CALLS = "show_calls_tab"
    }
}
