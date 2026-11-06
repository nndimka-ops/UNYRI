package com.messenger.app.ui.theme

import android.content.Context
import android.content.SharedPreferences

// Legacy values remain source-compatible; COCO now renders only its dark theme.
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemePreference internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("coco_theme", Context.MODE_PRIVATE))

    fun current(): ThemeMode {
        if (prefs.getString(KEY, null) != "dark") prefs.edit().putString(KEY, "dark").apply()
        return ThemeMode.DARK
    }

    @Suppress("UNUSED_PARAMETER")
    fun set(mode: ThemeMode) { prefs.edit().putString(KEY, "dark").apply() }

    private companion object { const val KEY = "theme_mode" }
}
