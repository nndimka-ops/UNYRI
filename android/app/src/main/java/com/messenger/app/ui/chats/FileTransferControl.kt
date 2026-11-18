package com.messenger.app.ui.chats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/** Only this small control animates when transfer progress changes. No idle loop. */
@Composable
internal fun FileTransferControl(
    percent: Int, waiting: Boolean, label: String,
    mediaOverlay: Boolean = false, statusDescription: String? = null, onCancel: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val foreground = if (mediaOverlay) Color.White else colors.accent
    val background = if (mediaOverlay) Color.Black.copy(alpha = .58f) else colors.accent.copy(alpha = .12f)
    val progress by animateFloatAsState(
        targetValue = if (waiting) 0f else percent.coerceIn(0, 100) / 100f,
        animationSpec = tween(160), label = "file-transfer-progress",
    )
    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)
        .then(if (mediaOverlay) Modifier.testTag("pending_media_cancel") else Modifier).semantics {
        if (statusDescription != null) stateDescription = statusDescription
        else if (!waiting) stateDescription = "${percent.coerceIn(0, 100)}%"
    }) {
        Box(Modifier.size(36.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(34.dp),
                color = foreground, trackColor = foreground.copy(alpha = .18f), strokeWidth = 2.dp)
            Icon(CocoIcons.Close, label, tint = foreground, modifier = Modifier.size(18.dp))
        }
    }
}
