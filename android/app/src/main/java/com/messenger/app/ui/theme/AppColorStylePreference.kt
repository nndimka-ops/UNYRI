package com.messenger.app.ui.theme

import android.content.Context
import android.content.SharedPreferences

/** App colors never change chat geometry, custom bubble colors or encryption. */
enum class AppColorStyle(val preferenceValue: String) {
    COLORFUL("colorful"), MONOCHROME("monochrome");

    /** RGB only. Callers retain the original alpha and semantic status colors. */
    fun materialRgb(rgb: Int): Int {
        if (this == COLORFUL) return rgb
        val grey = (2126 * ((rgb ushr 16) and 255) + 7152 * ((rgb ushr 8) and 255) +
            722 * (rgb and 255) + 5000) / 10000
        return grey * 0x010101
    }

    companion object {
        fun parse(value: String?): AppColorStyle = entries.firstOrNull { it.preferenceValue == value } ?: MONOCHROME
    }
}

internal const val APP_COLOR_STYLE_KEY = "app-color-style-v1"

class AppColorStylePreference internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE))

    /** Missing/old/malformed values use monochrome without overwriting explicit choices. */
    fun current(): AppColorStyle = AppColorStyle.parse(
        runCatching { preferences.getString(APP_COLOR_STYLE_KEY, null) }.getOrNull(),
    )

    fun set(style: AppColorStyle) {
        preferences.edit().putString(APP_COLOR_STYLE_KEY, style.preferenceValue).apply()
    }
}
