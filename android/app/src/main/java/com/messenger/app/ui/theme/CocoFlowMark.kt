package com.messenger.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.messenger.app.R

/** Approved UNYRI artwork. Preserve the existing finite layer transform and bounds. */
@Composable
fun CocoFlowMark(modifier: Modifier = Modifier, size: Dp = 72.dp, animated: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var appeared by rememberSaveable { mutableStateOf(false) }
    val motion = animated && ValueAnimator.areAnimatorsEnabled()
    val angle by animateFloatAsState(
        if (!motion) 0f else if (pressed) 5f else if (!appeared) -5f else 0f,
        tween(if (motion) 180 else 0), label = "coco-logo-tilt",
    )
    LaunchedEffect(Unit) { appeared = true }
    Image(painter = painterResource(R.drawable.unyri_brand_mark), contentDescription = null,
        modifier = modifier.size(size).graphicsLayer {
        rotationX = angle * 0.65f; rotationY = angle
        cameraDistance = 24.dp.toPx()
    }.then(if (animated) Modifier.clickable(interactionSource = interaction, indication = null, onClick = {}) else Modifier)
        .clearAndSetSemantics {})
}
