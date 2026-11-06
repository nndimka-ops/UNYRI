package com.messenger.app.ui.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.chats.Avatar

/** PROFILE.1: renders the account owner's uploaded avatar when bytes are
 *  available, otherwise falls back to the initials [Avatar]. Decoding happens
 *  here (Android layer) - the ViewModel only holds a plain [ByteArray], so it
 *  stays JVM-testable. No image-loading library required. */
@Composable
internal fun AvatarImage(
    bytes: ByteArray?,
    name: String,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val bitmap = com.messenger.app.ui.components.rememberAvatarBitmap(bytes)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Avatar(name = name, color = color, contentColor = contentColor)
    }
}
