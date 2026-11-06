package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MediaViewerIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()
    val Back = icon("MediaBack") { moveTo(15f, 4f); lineTo(7f, 12f); lineTo(15f, 20f) }
    val Forward = icon("MediaForward") {
        moveTo(14f, 3f); lineTo(22f, 10f); lineTo(14f, 17f); lineTo(14f, 12f)
        curveTo(8f, 12f, 4f, 14f, 2f, 20f); curveTo(2f, 11f, 6f, 7f, 14f, 7f); close()
    }
    val Download = icon("MediaDownload") {
        moveTo(12f, 2f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(3f, 15f); lineTo(3f, 21f); lineTo(21f, 21f); lineTo(21f, 15f)
    }
    val Replay = icon("MediaReplay") {
        moveTo(9f, 2f); lineTo(5f, 5f); lineTo(9f, 8f)
        moveTo(5f, 5f); curveTo(19f, 0f, 27f, 17f, 15f, 22f); curveTo(6f, 25f, 0f, 17f, 3f, 10f)
    }
    val Speaker = icon("MediaSpeaker") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
        moveTo(19f, 4f); curveTo(25f, 9f, 25f, 15f, 19f, 20f)
    }
    val Muted = icon("MediaMuted") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(17f, 9f); lineTo(23f, 15f); moveTo(23f, 9f); lineTo(17f, 15f)
    }
}

@Composable internal fun MediaSkipIcon(forward: Boolean, label: String) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(MediaViewerIcons.Replay, label, tint = Color.White, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (forward) -1f else 1f })
        Text("10", color = Color.White, fontSize = 10.sp, modifier = Modifier.offset(y = 2.dp))
    }
}
