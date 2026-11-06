package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.domain.model.Message

/** A single receipt slot used by adaptive text and the compact standalone file footer. */
@Composable
internal fun CompactMessageMetadata(message: Message, outgoing: Boolean, color: Color) {
    val receiptSlot = with(LocalDensity.current) { (CapsuleChatStyle.timeSize * 1.8f).sp.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(CapsuleChatStyle.metaGap.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(message.createdAt), color = color, fontSize = CapsuleChatStyle.timeSize.sp,
            lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp, maxLines = 1, softWrap = false,
            style = MaterialTheme.typography.labelSmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        // Reserve the double-check width for every delivery state. A pending clock
        // becoming two ticks must not move the text or push metadata onto a new row.
        if (outgoing) Box(Modifier.widthIn(min = receiptSlot)) { MessageReceipt(message.status) }
        if (message.isEdited) Text(stringResource(R.string.edited), color = color,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Type/size or transfer status and the receipt share one line at normal text scales.
 * A narrow slot or large system font uses two lines. The choice depends on geometry,
 * not progress text, so a pending/sent transition cannot change the number of lines.
 */
@Composable
internal fun CompactFileMetadataLine(subtitle: String, color: Color, outgoing: Boolean,
    metadata: @Composable () -> Unit) {
    val minimumInlineWidth = with(LocalDensity.current) { 112.sp.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= minimumInlineWidth) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, modifier = Modifier.weight(1f).padding(end = 4.dp),
                    color = color, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                metadata()
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text(subtitle, color = color, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.align(if (outgoing) Alignment.End else Alignment.Start)) { metadata() }
            }
        }
    }
}
