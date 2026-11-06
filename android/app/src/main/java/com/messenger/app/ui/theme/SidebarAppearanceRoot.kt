package com.messenger.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val defaultSidebarSnapshot by lazy { SidebarAppearanceSettings.defaults.resolved }
internal val LocalSidebarAppearanceSettings = staticCompositionLocalOf { SidebarAppearanceSettings.defaults }
internal val LocalResolvedSidebarStyle = staticCompositionLocalOf { defaultSidebarSnapshot }

/** Read once at the root; only this appearance key invalidates its snapshot. */
@Composable
internal fun CocoSidebarAppearanceRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE) }
    val preference = remember(preferences) { SidebarAppearancePreference(preferences) }
    val settings = remember(preference) { mutableStateOf(preference.load()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SIDEBAR_APPEARANCE_KEY) { settings.value = preference.load() }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        settings.value = preference.load()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val resolved = remember(settings.value) { settings.value.resolved }
    CompositionLocalProvider(LocalSidebarAppearanceSettings provides settings.value,
        LocalResolvedSidebarStyle provides resolved, content = content)
}
