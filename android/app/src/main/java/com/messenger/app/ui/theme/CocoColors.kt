package com.messenger.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// COCO FLOW semantic color tokens (Этап N.1 §3).
// NEVER scatter hex colors in composables - consume these tokens only.
// Accent is reserved for: primary actions, selected navigation, outgoing
// bubbles, unread badge, active toggle, links/interactive highlights.
// ---------------------------------------------------------------------------

data class CocoColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val elevated: Color,
    val inputSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val onAccent: Color,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val danger: Color,
    val onDanger: Color,
    val success: Color,
    val warning: Color,
    val divider: Color,
    val scrim: Color,
    val avatarTints: List<Color>,
    // --- COCO Glass Flow additions (dark-first; the light palette overrides
    // --- the translucent/panel values below). All alpha values are precomputed
    // --- (no runtime hex parsing) - ARGB in the low 32 bits. ---
    /** Dedicated call-screen background (COCO Glass Flow call UI). Dark mode
     *  uses the near-black #07080D; the call surface is always a shade darker
     *  than the app background so the in-call identity reads as the focal
     *  layer. */
    val callBackground: Color = Color(0xFF0A0A0A),
    val elevatedBackground: Color = Color(0xFF202020),
    val deepPanel: Color = Color(0xFF202020),
    val accentBright: Color = Color(0xFFF1F1F1),
    val accentSoft: Color = Color(0xFFDEDEDE),
    val accentPressed: Color = Color(0xFFB0B0B0),
    val supportBlue: Color = Color(0xFFDEDEDE),
    val supportCyan: Color = Color(0xFFDEDEDE),
    val mutedText: Color = Color(0xFFB0B0B0),
    val veryMutedText: Color = Color(0xFF737373),
    val dangerPressed: Color = Color(0xFFB72B3C),
    val glassPrimary: Color = Color(0xFF282828),
    val glassSecondary: Color = Color(0xFF2A2A2A),
    val glassFloating: Color = Color(0xFF202020),
    val glassHighlight: Color = Color(0x0FFFFFFF),
    val glassPressed: Color = Color(0x14FFFFFF),
    val outlineSubtle: Color = Color(0x14FFFFFF),
    val outlineSecondary: Color = Color(0x1FFFFFFF),
    val outlineActive: Color = Color(0xFF737373),
    val outlineSelected: Color = Color(0xFFDEDEDE),
    val glowAccentSoft: Color = Color.Transparent,
    val glowAccentStrong: Color = Color.Transparent,
    /** Category tints from the iOS settings, shared across feature screens. */
    val categoryBlue: Color = accent,
    val categoryMint: Color = accent,
    val categoryViolet: Color = accent,
    val categoryAmber: Color = accent,
    val categoryRose: Color = accent,
    val isMonochrome: Boolean = true,
)

val CocoDarkColors = CocoColors(
    background = Color(CocoSavedPattern.backgroundArgb),
    surface = Color(0xFF282828),
    surfaceVariant = Color(0xFF2A2A2A),
    elevated = Color(0xFF202020),
    inputSurface = Color(0xFF282828),
    primaryText = Color(0xFFF1F1F1),
    secondaryText = Color(0xFFB0B0B0),
    accent = Color(0xFFDEDEDE),
    onAccent = Color(0xFF222222),
    outgoingBubble = Color(0xFFD4D4D4),
    onOutgoingBubble = Color(0xFF171717),
    incomingBubble = Color(0xFF2A2A2A),
    onIncomingBubble = Color(0xFFF1F1F1),
    danger = Color(0xFFFF4D5F),
    onDanger = Color(0xFFFFFFFF),
    success = Color(0xFF38D996),
    warning = Color(0xFFF2B84B),
    divider = Color(0xFF494949),
    scrim = Color(0xB3000000),
    avatarTints = listOf(Color(0xFFB0B0B0), Color(0xFF969696), Color(0xFF858585), Color(0xFFC1C1C1), Color(0xFFAAAAAA)),
)

/** The former palette remains immutable for the sidebar being edited separately. */
val CocoMonochromeColors = CocoDarkColors

/** Color roles follow the current iOS interface; saved chat surfaces are independent. */
val CocoColorfulColors = CocoDarkColors.copy(
    surface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFF242426),
    inputSurface = Color(0xFF242426),
    divider = Color(0xFF343437),
    accent = Color(0xFF7BC9EC),
    onAccent = Color(0xFF14232D),
    accentBright = Color(0xFF80D7FA),
    accentSoft = Color(0xFFAFA4F7),
    accentPressed = Color(0xFF5BAACD),
    supportBlue = Color(0xFF7BC9EC),
    supportCyan = Color(0xFF80D7FA),
    outlineActive = Color(0xFF7BC9EC),
    outlineSelected = Color(0xFFAFA4F7),
    categoryBlue = Color(0xFF7BC9EC),
    categoryMint = Color(0xFF83D9B7),
    categoryViolet = Color(0xFFAFA4F7),
    categoryAmber = Color(0xFFE6BD73),
    categoryRose = Color(0xFFE995AD),
    avatarTints = listOf(Color(0xFF7BC9EC), Color(0xFF83D9B7), Color(0xFFAFA4F7), Color(0xFFE6BD73), Color(0xFFE995AD)),
    isMonochrome = false,
)

internal fun appColorPalette(style: AppColorStyle): CocoColors = when (style) {
    AppColorStyle.COLORFUL -> CocoColorfulColors
    AppColorStyle.MONOCHROME -> CocoMonochromeColors
}
