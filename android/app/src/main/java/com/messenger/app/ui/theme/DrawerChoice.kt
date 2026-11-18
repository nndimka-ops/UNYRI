package com.messenger.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class DrawerChoice { A, C }
internal const val DRAWER_CHOICE_KEY = "drawer-choice-v1"
internal val LocalDrawerChoice = staticCompositionLocalOf { DrawerChoice.A }

/**
 * Owner, 14 Nov 2026: drawer A takes 40% of the screen width. Larger text widens it (up to
 * 1.5x), never narrower than 150 dp and never closer than 64 dp to the right edge.
 * Very large text (font scale 1.6+) keeps the full-width large layout.
 */
internal fun drawerWidthDp(choice: DrawerChoice, screenWidthDp: Float, fontScale: Float): Float {
    if (fontScale >= 1.6f) return (screenWidthDp - 24f).coerceAtLeast(100f)
    val limit = (screenWidthDp - 64f).coerceAtLeast(100f)
    return when (choice) {
        DrawerChoice.A -> (screenWidthDp * .4f * fontScale.coerceIn(1f, 1.5f)).coerceAtLeast(150f).coerceAtMost(limit)
        DrawerChoice.C -> minOf(326f, limit)
    }
}

/** Device-owned, independent of account workspaces and the appearance editor's draft. */
internal class DrawerChoicePreference(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("coco-drawer", Context.MODE_PRIVATE))
    fun load(): DrawerChoice = runCatching { DrawerChoice.valueOf(preferences.getString(DRAWER_CHOICE_KEY, null).orEmpty()) }
        .getOrDefault(DrawerChoice.A)
    fun save(choice: DrawerChoice): Boolean = preferences.edit().putString(DRAWER_CHOICE_KEY, choice.name).commit()
}
