package com.messenger.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp

/**
 * The ONE shared avatar component (E.1): resolves avatar bytes through the
 * app-scoped [com.messenger.app.data.avatar.AvatarRepository] (cache keyed by
 * userId + avatarVersion), and falls back to a colored circle + first letter
 * when there is no avatar, on version removal, or on download failure - never
 * a broken-image icon.
 */
@Composable
fun UserAvatar(
    userId: String,
    avatarVersion: Long,
    hasAvatar: Boolean,
    name: String,
    color: Color,
    contentColor: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    fallbackBrush: androidx.compose.ui.graphics.Brush? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as MessengerApp).avatarRepository }
    androidx.compose.runtime.key(userId, avatarVersion, hasAvatar) {
    val bytes by produceState<ByteArray?>(initialValue = repository.cached(userId, avatarVersion, hasAvatar), userId, avatarVersion, hasAvatar) {
        value = if (!hasAvatar || avatarVersion <= 0) null else repository.resolve(userId, avatarVersion, hasAvatar)
    }
    val bitmap = rememberAvatarBitmap(bytes)

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Surface(shape = CircleShape, color = color, modifier = modifier.size(size)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(fallbackBrush ?: androidx.compose.ui.graphics.SolidColor(color), CircleShape)) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                )
            }
        }
    }
}
}
