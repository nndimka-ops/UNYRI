package com.messenger.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import kotlinx.coroutines.flow.collect

/** Finite transforms only; no timer, infinite transition or delayed navigation. */
@Composable
fun cocoPressFeedback(source: MutableInteractionSource, haptic: Boolean = true): Modifier {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(source, view, haptic) {
        if (haptic) source.interactions.collect { event ->
            // Drag/cancellation emits Cancel, never Release. Disabled controls
            // emit no press interactions, so scrolling and loading stay quiet.
            if (event is androidx.compose.foundation.interaction.PressInteraction.Release) CocoHaptics.tap(view)
        }
    }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && ValueAnimator.areAnimatorsEnabled()) 0.975f else 1f,
        tween(150), label = "cocoPress",
    )
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun CocoBrandHeader() {
    var appeared by rememberSaveableBrand() // Kept across configuration changes.
    val interaction = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(
        if (appeared || !ValueAnimator.areAnimatorsEnabled()) 1f else 0f,
        tween(200), label = "cocoBrandEntrance",
    )
    LaunchedEffect(Unit) { appeared = true }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            alpha = 0.75f + progress * 0.25f
            scaleX = 0.97f + progress * 0.03f; scaleY = scaleX
        }) {
        IconButton(onClick = {}, interactionSource = interaction,
            modifier = Modifier.size(72.dp).then(cocoPressFeedback(interaction, haptic = false))) {
            Icon(painterResource(R.drawable.unyri_brand_mark),
                contentDescription = stringResource(R.string.app_name),
                tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(72.dp))
        }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun rememberSaveableBrand() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

/** Keeps subtitle changes within the existing line; never animates list layout. */
@Composable
fun CocoStatusText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.animation.Crossfade(
        targetState = text,
        animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 150 else 0),
        label = "cocoStatus",
    ) { value ->
        Text(value, style = style, color = color, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Bounded, per-open-chat cosmetic state; old/paginated messages never enter. */
class CocoMessageEntranceGate(val openedAt: Long = System.currentTimeMillis()) {
    private val seen = HashSet<String>()
    fun claim(id: String, createdAt: Long, pending: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (!pending || createdAt < openedAt || createdAt > now || now - createdAt > 5000 || seen.size >= 512) return false
        return seen.add(id)
    }
}

@Composable
fun cocoNewMessage(gate: CocoMessageEntranceGate, id: String, createdAt: String, pending: Boolean): Modifier {
    val enter = remember(gate, id) {
        val time = runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0)
        gate.claim(id, time, pending) && ValueAnimator.areAnimatorsEnabled()
    }
    var settled by remember(gate, id) { mutableStateOf(!enter) }
    LaunchedEffect(gate, id) { settled = true }
    val progress by animateFloatAsState(if (settled) 1f else 0f,
        tween(if (ValueAnimator.areAnimatorsEnabled()) 160 else 0), label = "cocoMessageEntrance")
    return Modifier.graphicsLayer { alpha = 0.65f + progress * 0.35f; translationY = (1f - progress) * 4.dp.toPx() }
}
