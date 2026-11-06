package com.messenger.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R

/** Shared presentation only. Every caller retains navigation, haptics and dirty-form guards. */
@Composable
internal fun CocoBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.back),
    reduceMotion: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val animate = !reduceMotion && ValueAnimator.areAnimatorsEnabled()
    val scale by animateFloatAsState(if (enabled && pressed && animate) .94f else 1f,
        animationSpec = tween(if (animate) 180 else 0, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "back-fill-scale")
    val shift by animateFloatAsState(if (enabled && pressed && animate) -3f else 0f,
        animationSpec = tween(if (animate) 180 else 0, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "back-glyph-shift")
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interactions,
        modifier = modifier.size(48.dp)) {
        // Shared native-circle silhouette; logical offset and arrow mirror in RTL.
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .background(if (enabled && pressed) Color(0xFF414141) else Color(0xFF242424), CircleShape)
                .border(.5.dp, Color.White.copy(alpha = .08f), CircleShape))
            Icon(CocoBackArrow, contentDescription = label,
                tint = Color(0xFFF5F5F5).copy(alpha = if (enabled) 1f else .4f),
                modifier = Modifier.size(25.dp).offset(x = shift.dp))
        }
    }
}

private val CocoBackArrow: ImageVector by lazy {
    ImageVector.Builder("CocoShiftBack", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 12f); lineTo(5f, 12f)
            moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
        }
    }.build()
}
