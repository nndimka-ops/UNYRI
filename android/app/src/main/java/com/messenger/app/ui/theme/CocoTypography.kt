package com.messenger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// COCO FLOW typography (Этап N.1 §6). System Android font stack - NO custom
// downloadable font. Clear hierarchy: display / screen title / section /
// body / message / caption / timestamp / button / label.
// ---------------------------------------------------------------------------
val CocoTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

// ---------------------------------------------------------------------------
// COCO Glass Flow semantic text roles. Aliases over the single CocoTypography
// so later component phases consume a stable, named role instead of raw
// Material3 styles. Exact sizes are tuned during component phases.
// ---------------------------------------------------------------------------
object CocoTextRoles {
    val largeTitle: TextStyle = CocoTypography.displayLarge
    val screenTitle: TextStyle = CocoTypography.headlineLarge
    val sectionTitle: TextStyle = CocoTypography.titleMedium
    val body: TextStyle = CocoTypography.bodyLarge
    val bodySecondary: TextStyle = CocoTypography.bodyMedium
    val caption: TextStyle = CocoTypography.bodySmall
    val timestamp: TextStyle = CocoTypography.labelSmall
    val tabLabel: TextStyle = CocoTypography.labelMedium
    val buttonLabel: TextStyle = CocoTypography.labelLarge
    val callDuration: TextStyle = CocoTypography.titleMedium
    val status: TextStyle = CocoTypography.bodySmall
}
