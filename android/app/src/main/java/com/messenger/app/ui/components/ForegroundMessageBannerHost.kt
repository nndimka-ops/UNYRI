package com.messenger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import com.messenger.app.ui.theme.GlassBackdrop
import com.messenger.app.ui.theme.glassPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.LastMessagePreview
import com.messenger.app.push.ForegroundMessageBanner
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

/** Top overlay hosting the single foreground message banner (Runtime Correction D).
 *  Replace-latest with a subtle crossfade (~140–200ms); tween-based so the
 *  Android "Remove animations" accessibility setting reduces it automatically. */
@Composable
fun ForegroundMessageBannerHost(
    banner: ForegroundMessageBanner?,
    backdrop: GlassBackdrop,
    headerBottom: Float,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = banner,
        contentKey = { it != null },
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 2 })
                .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 })
        },
        modifier = modifier,
        label = "foreground-message-banner",
    ) { current ->
        current?.let { item -> BannerCard(item, backdrop, headerBottom, onOpenChat, onDismiss) }
    }
}

@Composable
private fun BannerCard(
    banner: ForegroundMessageBanner,
    backdrop: GlassBackdrop,
    headerBottom: Float,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val name = banner.senderName ?: stringResource(R.string.chats_unknown_user)
    val previewText = bannerPreviewText(banner.preview)
    Surface(
        onClick = { onOpenChat(banner.chatId); onDismiss() },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(0.5.dp, colors.outlineSecondary),
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .then(if (headerBottom > 0) Modifier.padding(top = with(LocalDensity.current) { headerBottom.toDp() }) else Modifier.statusBarsPadding().padding(top = 56.dp))
            .padding(horizontal = CocoSpacing.md, vertical = CocoSpacing.sm)
            .glassPanel(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), backdrop)
            .pointerInput(banner.generation) {
                var upwardDrag = 0f
                detectVerticalDragGestures(onDragStart = { upwardDrag = 0f }) { change, amount ->
                    upwardDrag += amount
                    if (upwardDrag < -24.dp.toPx()) { change.consume(); onDismiss() }
                }
            }
            .semantics { contentDescription = "$name. $previewText" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            val avatar = banner.avatar
            UserAvatar(userId = avatar?.userId.orEmpty(), avatarVersion = avatar?.version ?: 0,
                hasAvatar = avatar?.hasAvatar == true, name = name, color = colors.accent.copy(alpha = 0.16f),
                contentColor = colors.accentSoft, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(android.R.string.cancel),
                    tint = colors.secondaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun bannerPreviewText(preview: LastMessagePreview): String = when (preview) {
    is LastMessagePreview.Text -> preview.text
    LastMessagePreview.Video -> stringResource(R.string.call_caption_video)
    LastMessagePreview.Photo -> stringResource(R.string.photo_preview)
    LastMessagePreview.Voice -> stringResource(R.string.voice_message_preview)
    is LastMessagePreview.File -> preview.filename ?: stringResource(R.string.file_preview)
    LastMessagePreview.Deleted -> stringResource(R.string.deleted_message_preview)
}
