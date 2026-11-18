package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/** Soft stays underneath the single finite sharp-image fade. No work at rest. */
@Composable internal fun DevelopingMediaImage(id: String, path: String?, preview: ConversationMediaPreview, description: String?) {
    val allowed = rememberMediaWorkAllowed()
    var missingOnScreen by remember(id) { mutableStateOf(false) }
    SideEffect { if (allowed && path == null) missingOnScreen = true }
    val opacity = remember(id, path) { Animatable(if (preview.sharp != null) 1f else 0f) }
    val reduce = LocalChatMotionSettings.current?.reduceMotion == true
    LaunchedEffect(preview.sharp, allowed) {
        if (preview.sharp != null) {
            if (missingOnScreen && allowed) opacity.animateTo(1f, tween(if (reduce) 120 else 240)) else opacity.snapTo(1f)
        }
    }
    preview.soft?.let {
        Image(it.asImageBitmap(), description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f)))
    }
    preview.sharp?.let {
        Image(it.asImageBitmap(), description, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value })
    }
}
