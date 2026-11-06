package com.messenger.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Applies [locale] (or the system locale when null) to a context's
 *  configuration (Этап N.1 §19/§24). Used from Activity.attachBaseContext so
 *  every screen renders in the chosen language without a heavy framework. */
fun Context.applyLocale(locale: Locale?): Context {
    val current = locale ?: Locale.getDefault()
    // Override only locale: copying the full configuration freezes orientation,
    // window bounds and font scale in this wrapped context.
    val config = Configuration()
    config.setLocale(current)
    return createConfigurationContext(config)
}
