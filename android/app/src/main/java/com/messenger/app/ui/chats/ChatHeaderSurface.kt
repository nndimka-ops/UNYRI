package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.messenger.app.ui.components.cocoChromeMaterial
import com.messenger.app.ui.components.rememberHeaderChromeStyle

/** Content-fitted islands, independent of the full-width, static contrast gradient. */
@Composable
internal fun ChatHeaderSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.cocoChromeMaterial(CircleShape, rememberHeaderChromeStyle())) { content() }
}
