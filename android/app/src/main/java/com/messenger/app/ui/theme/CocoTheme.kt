package com.messenger.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Exposes the active COCO semantic color tokens to any composable. */
val LocalChatTextSize = androidx.compose.runtime.compositionLocalOf { defaultChatAppearanceSettings().fontSize }

val LocalCocoColors = staticCompositionLocalOf { CocoMonochromeColors }
val LocalAppColorStyle = staticCompositionLocalOf { AppColorStyle.MONOCHROME }

/** Legacy parameters cannot re-enable a light palette, including restored preferences. */
@Suppress("UNUSED_PARAMETER")
fun themeModeIsDark(mode: ThemeMode, isSystemDark: Boolean): Boolean = true

@Composable
fun cocoDarkMode(mode: ThemeMode): Boolean = themeModeIsDark(mode, false)

private fun darkScheme(c: CocoColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    background = c.background,
    onBackground = c.primaryText,
    surface = c.surface,
    onSurface = c.primaryText,
    surfaceVariant = c.elevated,
    onSurfaceVariant = c.secondaryText,
    error = c.danger,
    onError = c.onDanger,
    outline = c.divider,
    secondary = c.secondaryText,
    onSecondary = c.surface,
    primaryContainer = c.elevated,
    onPrimaryContainer = c.primaryText,
    secondaryContainer = c.elevated,
    onSecondaryContainer = c.primaryText,
    tertiary = c.accent,
    onTertiary = c.onAccent,
    tertiaryContainer = c.elevated,
    onTertiaryContainer = c.primaryText,
    surfaceTint = Color.Transparent,
    surfaceDim = c.background,
    surfaceBright = c.surface,
    surfaceContainerLowest = c.background,
    surfaceContainerLow = c.elevated,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceVariant,
    surfaceContainerHighest = c.surfaceVariant,
    inverseSurface = c.primaryText,
    inverseOnSurface = c.background,
    inversePrimary = c.onAccent,
    errorContainer = Color(0xFF3B1F24),
    onErrorContainer = c.danger,
)

/** Apply a newly approved saved design once; subsequent user choices win. */
internal fun adoptSavedCapsulePattern(preferences: android.content.SharedPreferences) {
    val previousRevision = preferences.getInt("capsule-pattern-spec-version", 0)
    if (previousRevision >= CocoSavedPattern.revision) return
    val editor = preferences.edit()
        .putBoolean("subtle-pattern", CocoSavedPattern.enabledByDefault)
        .putInt("capsule-pattern-spec-version", CocoSavedPattern.revision)
    // An explicitly approved full preset also adopts its text size once.
    // Background-only revisions retain this marker and preserve later choices.
    if (previousRevision < CocoSavedPattern.textSizeRevision) {
        editor.putInt("chat-text-size", CocoSavedPattern.defaultChatTextSize.coerceIn(13, 22))
    }
    editor.apply()
}

/**
 * The single COCO FLOW theme entry point (Этап N.1 §4). Wrap every screen
 * root in this - it provides both the COCO tokens and the Material3 mapping.
 */
@Composable
fun CocoTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = androidx.compose.runtime.remember(context) {
        context.getSharedPreferences("coco-appearance", android.content.Context.MODE_PRIVATE).also { prefs ->
            // Adopt the approved pattern-free default once. Later user choices stay intact.
            if (!prefs.getBoolean("capsule-app-style-adopted", false)) {
                val oldTextSize = prefs.getInt("chat-text-size", defaultChatAppearanceSettings().fontSize)
                prefs.edit().putBoolean("subtle-pattern", false)
                    .putInt("chat-text-size", oldTextSize.coerceIn(13,22))
                    .putBoolean("capsule-app-style-adopted", true).apply()
            }
            // After the earlier pattern-free adoption, including on a clean install.
            adoptSavedCapsulePattern(prefs)
        }
    }
    val appearance = androidx.compose.runtime.remember(preferences) {
        androidx.compose.runtime.mutableStateOf(readChatAppearanceSettings(preferences))
    }
    val colorStyle = androidx.compose.runtime.remember(preferences) {
        androidx.compose.runtime.mutableStateOf(AppColorStylePreference(preferences).current())
    }
    androidx.compose.runtime.DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == null || key in CHAT_APPEARANCE_PREFERENCE_KEYS) {
                appearance.value = readChatAppearanceSettings(prefs)
            }
            if (key == null || key == APP_COLOR_STYLE_KEY) {
                colorStyle.value = AppColorStylePreference(prefs).current()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Register before refreshing, so a preference edit cannot fall between the initial read and subscription.
        appearance.value = readChatAppearanceSettings(preferences)
        colorStyle.value = AppColorStylePreference(preferences).current()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val resolvedAppearance = androidx.compose.runtime.remember(appearance.value, colorStyle.value) {
        resolveChatAppearance(appearance.value, colorStyle.value)
    }
    val colors = appColorPalette(colorStyle.value)
    val patternSpec = androidx.compose.runtime.remember { CocoPatternSpec() }
    // A new ColorScheme identity invalidates Material3 static locals even when every color is unchanged.
    val scheme = androidx.compose.runtime.remember(colors) { darkScheme(colors) }
    CompositionLocalProvider(LocalCocoColors provides colors, LocalAppColorStyle provides colorStyle.value,
        LocalCocoPatternEnabled provides appearance.value.patterned,
        LocalCocoPatternSpec provides patternSpec, LocalChatTextSize provides appearance.value.fontSize,
        LocalChatAppearanceSettings provides appearance.value, LocalChatAppearanceStyle provides resolvedAppearance) {
        MaterialTheme(colorScheme = scheme, typography = CocoTypography,
            shapes = androidx.compose.material3.Shapes(
                small = CocoShapes.small, medium = CocoShapes.large,
                large = CocoShapes.large, extraLarge = CocoShapes.radiusDialog)) {
            CocoSidebarAppearanceRoot(content = content)
        }
    }
}

/** Convenience accessor for the active semantic tokens inside [CocoTheme]. */
object CocoTheme {
    val colors: CocoColors
        @Composable get() = LocalCocoColors.current
}

/** Unused placeholder kept minimal - contrast-safe overlay for call controls. */
val CocoScrim = Color(0x99000000)
