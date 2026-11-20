package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.messenger.app.R
import com.messenger.app.domain.model.ReactionSummary
import kotlin.math.sin

internal fun mediaReactionHeight(font: Float, lineHeight: Float, scale: Float) = (font * lineHeight * scale).toInt().coerceIn(18, 30)
internal fun mediaReactionCount(count: Int): String? = count.takeIf { it >= 2 }?.toString()

/** Measures the wrap inside the picture width; never expands a narrow picture. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MediaReactionChips(reactions: List<ReactionSummary>, width: Dp, mine: Boolean,
    modifier: Modifier = Modifier, owner: String = "", before: List<ReactionSummary> = reactions, onReaction: (String) -> Unit) {
    val allowed = rememberMediaWorkAllowed()
    val work = LocalTimelineMediaWork.current
    var previous by remember(owner) { mutableStateOf(before) }
    var effects by remember(owner) { mutableStateOf(emptyMap<String, Long>()) }
    LaunchedEffect(reactions, allowed) {
        val now = android.os.SystemClock.uptimeMillis()
        val changed = reactions.mapNotNull { item ->
            val old = previous.firstOrNull { it.emoji == item.emoji }
            val own = work?.takeReactionIntent(owner, item.emoji) == true
            val incoming = MediaReactionPolicy.incoming(old?.count ?: 0, old?.reactedByMe == true, item.count, item.reactedByMe)
            if ((own || incoming) && MediaReactionPolicy.mayAnimate(own, allowed, work?.resumed ?: true, work?.covered ?: false,
                    now - (work?.openedAt ?: now), now - (work?.connectionChangedAt ?: now))) item.emoji to now else null
        }.toMap()
        previous = reactions
        if (changed.isNotEmpty()) effects = changed
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val font = com.messenger.app.ui.theme.LocalChatTextSize.current
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    val height = mediaReactionHeight(font.toFloat(), appearance.lineHeight, density.fontScale).dp
    Layout(modifier = modifier, content = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reactions.filter { it.count > 0 }.forEach { reaction -> key(reaction.emoji) {
                MediaReactionChip(reaction, height, mine, effects[reaction.emoji] ?: 0L, owner, onReaction)
            } }
        }
    }) { measurables, constraints ->
        val max = minOf(constraints.maxWidth, width.roundToPx()).coerceAtLeast(0)
        val child = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = max, minHeight = 0))
        layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
}

@Composable private fun MediaReactionChip(reaction: ReactionSummary, height: Dp, mine: Boolean, effect: Long, owner: String, onReaction: (String) -> Unit) {
    val allowed = rememberMediaWorkAllowed()
    val motion = LocalChatMotionSettings.current
    val progress = remember { Animatable(1f) }
    val work = LocalTimelineMediaWork.current
    val token = remember { Any() }
    var consumed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(effect, allowed) {
        val fresh = effect != consumed
        consumed = effect
        if (fresh && effect != 0L && allowed && work?.reactionAdmission?.enter(token) != false) {
            try { progress.snapTo(0f); progress.animateTo(1f, tween(if (motion?.reduceMotion == true) 200 else 400)) }
            finally { work?.reactionAdmission?.leave(token) }
        } else progress.snapTo(1f)
    }
    val label = stringResource(if (reaction.reactedByMe) R.string.media_reaction_remove else R.string.media_reaction_add)
    val emojiSize = height.value * .65f
    Row(Modifier.height(height).reactionChipLanding(owner, reaction.emoji).graphicsLayer {
        val p = progress.value
        if (motion?.reduceMotion != true) { val bump = 1f + .12f * sin(Math.PI.toFloat() * p); scaleX = bump; scaleY = bump }
    }.drawBehind {
        val p = progress.value
        if (p < 1f) {
            val outset = (if (mine) 12 else 9).dp.toPx() * p
            drawRoundRect(Color.White.copy(alpha = (if (mine) .55f else .30f) * (1 - p)),
                topLeft = androidx.compose.ui.geometry.Offset(-outset, -outset),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * outset, size.height + 2 * outset),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + outset), style = Stroke(1.25.dp.toPx()))
        }
    }.background(Color.White.copy(alpha = if (mine) .28f else .16f), CircleShape)
        .semantics { selected = reaction.reactedByMe; contentDescription = reaction.emoji + " " + reaction.count }
        .clickable(onClickLabel = label) { onReaction(reaction.emoji) }.padding(start = 5.dp, end = if (reaction.count >= 2) 6.dp else 5.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(reaction.emoji, fontSize = (emojiSize / androidx.compose.ui.platform.LocalDensity.current.fontScale).sp,
            lineHeight = height.value.sp, modifier = Modifier.width((emojiSize * 1.3f).dp), maxLines = 1,
            style = MaterialTheme.typography.bodySmall.copy(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
        mediaReactionCount(reaction.count)?.let { count ->
            Spacer(Modifier.width(2.dp))
            Text(count, color = Color.White, fontSize = (height.value * .55f / androidx.compose.ui.platform.LocalDensity.current.fontScale).sp,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AlbumMediaReactionChips(messages: List<com.messenger.app.domain.model.Message>, width: Dp, mine: Boolean,
    onReaction: (com.messenger.app.domain.model.Message, String) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    val height = mediaReactionHeight(com.messenger.app.ui.theme.LocalChatTextSize.current.toFloat(), appearance.lineHeight, density.fontScale).dp
    Layout(content = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            messages.filterNot { it.isDeletedForEveryone }.forEach { message ->
                message.reactions.filter { it.count > 0 }.forEach { reaction -> key(message.clientMessageId, reaction.emoji) {
                    MediaReactionChip(reaction, height, mine, 0L, message.clientMessageId) { onReaction(message, it) }
                } }
            }
        }
    }, modifier = Modifier.padding(top = 2.dp)) { children, constraints ->
        val child = children.single().measure(constraints.copy(minWidth = 0, maxWidth = minOf(constraints.maxWidth, width.roundToPx()), minHeight = 0))
        layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
}
