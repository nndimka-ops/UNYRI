package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalGlassBackdrop
import com.messenger.app.ui.theme.glassPanel

/** Static material approximation; no frame clock, image copy, or blur work at rest. */
internal fun Modifier.viewerGlass(shape: Shape = CircleShape): Modifier = composed {
    val material = if (LocalGlassBackdrop.current?.blurEnabled == true) this.glassPanel(shape)
        else this.background(Brush.verticalGradient(listOf(Color(0xE62A2A2A), Color(0xD9202020))), shape)
    material.border(.5.dp, Color.White.copy(alpha = .10f), shape)
}

@Composable internal fun ViewerGlyphButton(icon: ImageVector, label: String, enabled: Boolean = true,
    glass: Boolean = false, width: Dp = 48.dp, iconSize: Dp = 23.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(width, 48.dp).then(if (glass) Modifier.viewerGlass() else Modifier)) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else .5f), modifier = Modifier.size(iconSize))
    }
}

@Composable internal fun MediaViewerHeader(sender: String?, date: String?, page: Int, count: Int,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ViewerGlyphButton(MediaViewerIcons.Back, stringResource(R.string.dismiss), glass = true, onClick = onClose)
        // A flexible slot centers the content-sized pill and keeps the counter at the trailing edge.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
          if (!sender.isNullOrBlank() || !date.isNullOrBlank()) {
            Column(Modifier.viewerGlass().heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 3.dp).testTag("media-metadata")
                .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                if (!sender.isNullOrBlank()) Text(sender, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!date.isNullOrBlank()) Text(date, fontSize = 12.sp, lineHeight = 14.sp, color = Color.White.copy(alpha = .75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
        if (count > 1) Text("${page + 1} / $count", color = Color.White, fontSize = 13.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.viewerGlass().heightIn(min = 32.dp).wrapContentHeight()
                .padding(horizontal = 12.dp).testTag("media-page-counter"))
        else Spacer(Modifier.size(48.dp))
    }
}

@Composable internal fun MediaViewerPhotoToolbar(enabled: Boolean, forwardLabel: String, onForward: () -> Unit,
    onEdit: () -> Unit, onText: (() -> Unit)?, more: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ViewerGlyphButton(MediaViewerIcons.Forward, forwardLabel, enabled, glass = true, iconSize = 19.dp, onClick = onForward)
        Spacer(Modifier.weight(1f))
        Row(Modifier.viewerGlass().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ViewerGlyphButton(MediaViewerIcons.Markup, stringResource(R.string.media_markup), enabled, width = 50.dp, iconSize = 20.dp, onClick = onEdit)
            if (onText != null) ViewerGlyphButton(MediaViewerIcons.TextScan, stringResource(R.string.viewer_text_in_photo), enabled, width = 50.dp, iconSize = 20.dp, onClick = onText)
        }
        Spacer(Modifier.weight(1f))
        more()
    }
}
