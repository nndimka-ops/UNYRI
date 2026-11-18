package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors

/** A source-neutral label; forwarding never implies an authenticated original author. */
@Composable
internal fun ForwardedMessageLabel(modifier: Modifier = Modifier) {
    val color = LocalCocoColors.current.secondaryText
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(forwardedArrow, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(stringResource(R.string.forwarded), color = color, fontSize = 11.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val forwardedArrow: ImageVector by lazy {
    ImageVector.Builder("ForwardedMessage", 20.dp, 20.dp, 20f, 20f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 15f)
            curveTo(3f, 8f, 8f, 7f, 15f, 7f)
            moveTo(11f, 3f); lineTo(15f, 7f); lineTo(11f, 11f)
        }
    }.build()
}
