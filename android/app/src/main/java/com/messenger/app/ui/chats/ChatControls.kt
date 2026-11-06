package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Static fills only: no blur, shadow layers, timers or animated shaders.
internal object ChatControls {
    val clock: ImageVector by lazy {
        ImageVector.Builder("TransferClock", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
                moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
            }
        }.build()
    }
    val send: ImageVector by lazy {
        ImageVector.Builder("ComposerSend", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 20f); lineTo(12f, 4f)
                moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f)
            }
        }.build()
    }
    val blue = Color(0xFF72BFFA)
    val blueBack = Color(0xFF152C48)
    val backArrow = Color(0xFF83A7FF)
    val mint = Color(0xFF77E6C6)
    val mintBack = Color(0xFF173E38)
    val coral = Color(0xFFFFA18D)
    val coralBack = Color(0xFF382831)
    val amber = Color(0xFFFFCC72)
    val amberBack = Color(0xFF3A2D19)
    val violet = Brush.linearGradient(listOf(Color(0xFF897AFF), Color(0xFF6558E8)))
    val avatar = Brush.linearGradient(listOf(Color(0xFFB64CDD), Color(0xFF6538B9)))
    val header = Brush.linearGradient(listOf(Color(0xFF202735), Color(0xFF191F2A)))
    val composer = Brush.horizontalGradient(listOf(Color(0xFF151E2C), Color(0xFF1B1D35)))
    val compactComposer = Brush.horizontalGradient(listOf(Color(0xFF151B29), Color(0xFF191C2A)))
    val emojis = listOf("😊", "😂", "❤️", "👍", "🥰", "😍", "😘", "😁", "😅", "😉", "😎", "🤔", "😢", "😭", "😮", "😡", "🙏", "👏", "🔥", "🎉", "✅", "👋", "💜", "🤗")
    val lock: ImageVector by lazy {
        ImageVector.Builder("VoiceLock", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, 0f, 17f, 0f, 17f, 7f); lineTo(17f, 10f)
                moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
                moveTo(12f, 14f); lineTo(12f, 17f)
            }
        }.build()
    }
    val phone: ImageVector by lazy {
        ImageVector.Builder("HeaderPhone", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f,21f); curveTo(10.6f,21f,3f,13.4f,3f,4f)
                curveTo(3f,2.8f,4f,2f,5f,2f); lineTo(8f,2f); lineTo(10f,7f); lineTo(7.5f,9f)
                curveTo(9f,12.3f,11.7f,15f,15f,16.5f); lineTo(17f,14f); lineTo(22f,16f); lineTo(22f,19f)
                curveTo(22f,20f,21.2f,21f,20f,21f); close()
            }
        }.build()
    }
    val mic: ImageVector by lazy {
        ImageVector.Builder("ComposerMic", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f,5f); curveTo(9f,1f,15f,1f,15f,5f); lineTo(15f,11f); curveTo(15f,15f,9f,15f,9f,11f); close()
                moveTo(5.5f,10f); lineTo(5.5f,11f); curveTo(5.5f,20f,18.5f,20f,18.5f,11f); lineTo(18.5f,10f)
                moveTo(12f,18f); lineTo(12f,22f); moveTo(9f,22f); lineTo(15f,22f)
            }
        }.build()
    }
    val smile: ImageVector by lazy {
        ImageVector.Builder("ComposerSmile", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                moveTo(21f,12f); arcTo(9f,9f,0f,true,true,3f,12f); arcTo(9f,9f,0f,true,true,21f,12f)
                moveTo(8f,14.5f); curveTo(10f,18f,14f,18f,16f,14.5f)
                moveTo(8.5f,8.5f); lineTo(8.5f,9f); moveTo(15.5f,8.5f); lineTo(15.5f,9f)
            }
        }.build()
    }
}

@Composable
internal fun ChatControlButton(icon: ImageVector, label: String, tint: Color, fill: Brush,
    enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else .4f),
            modifier = Modifier.size(38.dp).background(fill, CircleShape).padding(8.dp))
    }
}

@Composable
internal fun CompactComposerButton(icon: ImageVector, label: String, tint: Color,
    filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Box(Modifier.size(36.dp).then(if (filled) Modifier.background(ChatControls.violet, CircleShape) else Modifier),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(if (filled) 22.dp else 24.dp))
        }
    }
}
