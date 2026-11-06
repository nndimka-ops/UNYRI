package com.messenger.app.ui.theme

import android.content.Context
import java.util.Locale

/** Language preference (Этап N.1 §24/§64). Stored locally, survives
 *  logout/login. "system" -> null marker (follow the OS locale). */
class LanguagePreference(context: Context) {
    private val prefs = context.getSharedPreferences("coco_language", Context.MODE_PRIVATE)

    fun current(): Locale? = prefs.getString(KEY, null)?.let(::Locale)

    fun set(locale: Locale?) {
        prefs.edit().putString(KEY, locale?.toLanguageTag()).apply()
    }

    private companion object {
        const val KEY = "language_tag"
    }
}
