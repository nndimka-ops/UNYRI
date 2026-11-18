package com.messenger.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.messenger.app.ui.chats.rememberChatMotionSettings
import com.messenger.app.R
import kotlinx.coroutines.flow.collect

/** Finite transforms only; no timer, infinite transition or delayed navigation. */
@Composable
fun cocoPressFeedback(source: MutableInteractionSource, haptic: Boolean = true): Modifier =
    cocoPressFeedback(source, haptic, CocoPressStyle.Primary)

@Composable
internal fun cocoQuietPressFeedback(source: MutableInteractionSource, haptic: Boolean = true): Modifier =
    cocoPressFeedback(source, haptic, CocoPressStyle.Quiet)

internal enum class CocoPressStyle(val scale: Float, val opacity: Float) {
    Primary(.975f, 1f), Quiet(.985f, .82f), Brand(.95f, .85f),
}

internal val CocoEaseOut = CubicBezierEasing(0f, 0f, .58f, 1f)

@Composable
private fun cocoPressFeedback(source: MutableInteractionSource, haptic: Boolean, style: CocoPressStyle): Modifier {
    val view = androidx.compose.ui.platform.LocalView.current
    val motion = rememberChatMotionSettings()
    LaunchedEffect(source, view, haptic, motion.active) {
        if (haptic && motion.active) source.interactions.collect { event ->
            // Drag/cancellation emits Cancel, never Release. Disabled controls
            // emit no press interactions, so scrolling and loading stay quiet.
            if (event is androidx.compose.foundation.interaction.PressInteraction.Release) CocoHaptics.tap(view)
        }
    }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !motion.calm) style.scale else 1f,
        tween(if (motion.calm) 0 else 150, easing = CocoEaseOut), label = "cocoPress",
    )
    val opacity by animateFloatAsState(if (pressed && motion.active) style.opacity else 1f,
        tween(if (motion.calm) 0 else 150, easing = CocoEaseOut), label = "cocoPressOpacity")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = opacity }
}

@Composable
fun CocoBrandHeader(onClick: (() -> Unit)? = null) {
    val motion = rememberChatMotionSettings()
    var appeared by rememberSaveableBrand() // Kept across configuration changes.
    val interaction = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(
        if (appeared || motion.calm) 1f else 0f,
        tween(if (motion.calm) 0 else 200, easing = CocoEaseOut), label = "cocoBrandEntrance",
    )
    LaunchedEffect(Unit) { appeared = true }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.graphicsLayer {
            alpha = 0.75f + progress * 0.25f
            scaleX = 0.97f + progress * 0.03f; scaleY = scaleX
        }) {
        val mark: @Composable () -> Unit = {
            Icon(painterResource(R.drawable.unyri_brand_mark),
                contentDescription = stringResource(R.string.app_name),
                tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(72.dp))
        }
        // Auth's decorative logo has no action. Only expose a button when a caller supplies one.
        if (onClick == null) mark() else IconButton(onClick = onClick, interactionSource = interaction,
            modifier = Modifier.size(72.dp).then(cocoPressFeedback(interaction, false, CocoPressStyle.Brand))) { mark() }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun rememberSaveableBrand() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

/** Identity updates and a scaled one-line footprint, as on iOS. */
@Composable
fun CocoStatusText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    val minHeight = with(LocalDensity.current) { 14.sp.toDp() }
    Text(text, style = style, color = color, maxLines = 1, modifier = Modifier.heightIn(min = minHeight),
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    val motion = rememberChatMotionSettings()
    val enter = remember(gate, id) {
        val time = runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0)
        gate.claim(id, time, pending) && !motion.calm
    }
    var settled by remember(gate, id) { mutableStateOf(!enter) }
    LaunchedEffect(gate, id) { settled = true }
    val progress by animateFloatAsState(if (settled) 1f else 0f,
        tween(if (motion.calm) 0 else 160, easing = CocoEaseOut), label = "cocoMessageEntrance")
    return Modifier.graphicsLayer { alpha = 0.65f + progress * 0.35f; translationY = (1f - progress) * 4.dp.toPx() }
}
