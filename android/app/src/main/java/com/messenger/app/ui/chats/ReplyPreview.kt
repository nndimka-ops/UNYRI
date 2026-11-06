package com.messenger.app.ui.chats

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.data.local.ChatImageCache
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AttachmentMediaType
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

internal fun replyMediaKind(attachment: Attachment?): String = when {
    attachment == null -> "text"
    attachment.mimeType.startsWith("video/") -> "video"
    attachment.mediaType == AttachmentMediaType.VOICE -> "voice"
    attachment.mediaType == AttachmentMediaType.IMAGE -> "image"
    else -> "file"
}

@Composable private fun replyKindLabel(kind: String): String = stringResource(when (kind) {
    "image" -> R.string.photo_preview
    "video" -> R.string.media_video
    "voice" -> R.string.voice_message_preview
    else -> R.string.file_preview
})

@Composable internal fun ReplyComposerBanner(name: String, targetText: String, caption: String?, attachment: Attachment?, isEdit: Boolean, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    val kind = replyMediaKind(attachment)
    val label = replyKindLabel(kind)
    val detail = if (kind == "text") targetText else buildString {
        append(if (kind == "file") attachment?.filename?.takeIf { it.isNotBlank() } ?: label else label)
        if (kind == "voice" || kind == "video") attachment?.durationMs?.takeIf { it > 0 }?.let { append(" · "); append(videoTime(it)) }
        caption?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
    }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp)
        .testTag("reply-composer-banner").clip(RoundedCornerShape(10.dp))
        .background(colors.deepPanel.copy(alpha = .5f)).replyAccentLine(colors.secondaryText.copy(alpha = .48f))
        .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind != "text") ReplyMediaBadge(attachment, kind)
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (isEdit) stringResource(R.string.edit) else name, color = colors.primaryText,
                fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cocoMessagePresentationText(detail, inline = true).replace('\n', ' '), color = colors.secondaryText, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(25.dp).background(colors.primaryText.copy(alpha = .055f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(CocoIcons.Close, stringResource(if (isEdit) R.string.dismiss else R.string.reply_cancel), tint = colors.secondaryText, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable private fun ReplyMediaBadge(attachment: Attachment?, kind: String) {
    val colors = LocalCocoColors.current
    // Small cached previews only. Never initiate media downloads or create a player.
    val encoded = attachment?.thumbnailBase64?.takeIf { it.length <= 128 * 1024 }
    val path = attachment?.localPath?.takeIf { kind == "image" }
    val key = remember(encoded, path, kind) {
        when {
            kind !in listOf("image", "video") -> null
            encoded != null -> "reply-128:$kind:thumbnail:$encoded"
            path != null -> "reply-128:$kind:file:$path"
            else -> null
        }
    }
    val decoded by produceState(key to key?.let(ChatImageCache::peek), key) {
        value = key to key?.let { ChatImageCache.load(it) {
            runCatching {
                val bytes = encoded?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) else BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) else BitmapFactory.decodeFile(path, options)
            }.getOrNull()
        } }
    }
    // A changed reply target must never briefly display the previous target's bitmap.
    val bitmap = decoded.takeIf { it.first == key }?.second
    Box(Modifier.size(36.dp).testTag("reply-media-$kind").clip(RoundedCornerShape(8.dp))
        .background(colors.primaryText.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(when (kind) { "image" -> CocoIcons.Image; "video" -> CocoIcons.Videocam; "voice" -> CocoIcons.Mic; else -> CocoIcons.File }, null, tint = colors.secondaryText, modifier = Modifier.size(19.dp))
        if (kind == "video" && bitmap != null) Icon(CocoIcons.Play, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .35f), RoundedCornerShape(6.dp)))
    }
}

@Composable internal fun ReplyQuote(author: String, preview: String, contentType: String, modifier: Modifier = Modifier, attachment: Attachment? = null) {
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    val colors = LocalCocoColors.current
    val kind = if (attachment != null) replyMediaKind(attachment) else when (contentType) {
        "image" -> "image"
        "video" -> "video"
        "voice" -> "voice"
        "file" -> "file"
        else -> "text"
    }
    val detail = when {
        kind == "image" && preview in listOf("Photo", "Image", "Attachment", "") -> replyKindLabel("image")
        kind == "video" && preview in listOf("Video", "Attachment", "") -> replyKindLabel("video")
        kind == "voice" && preview in listOf("Voice message", "Attachment", "") -> replyKindLabel("voice")
        kind == "file" && preview in listOf("File", "Attachment", "") -> attachment?.filename?.takeIf { it.isNotBlank() } ?: replyKindLabel("file")
        else -> preview
    }
    Row(modifier.testTag("reply-quote").clip(RoundedCornerShape(9.dp)).background(colors.deepPanel.copy(alpha = .42f))
        .replyAccentLine(colors.secondaryText.copy(alpha = .48f)).padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind != "text") ReplyMediaBadge(attachment, kind)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(author, color = colors.primaryText, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                fontFamily = appearance.messageFontFamily,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cocoMessagePresentationText(detail, inline = true).replace('\n', ' '), color = colors.secondaryText, fontSize = 12.sp, lineHeight = 15.sp,
                fontFamily = appearance.messageFontFamily,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Drawn at the final content height; large text never stretches a fixed-height line or button. */
private fun Modifier.replyAccentLine(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    val inset = 6.dp.toPx()
    drawRoundRect(color, topLeft = Offset(0f, inset), size = Size(2.dp.toPx(), (size.height - 2f * inset).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(1.dp.toPx()))
}
