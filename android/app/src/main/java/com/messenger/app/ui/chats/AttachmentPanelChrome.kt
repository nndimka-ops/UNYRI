package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/** Static panel chrome shared by selection and review; media owns its own lifecycle. */
@Composable
internal fun AttachmentPanelHeader(title: String, onClose: () -> Unit, enabled: Boolean = true) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CocoIcons.Attachment, null, tint = colors.categoryViolet,
            modifier = Modifier.background(colors.categoryViolet.copy(alpha = .12f), RoundedCornerShape(12.dp)).padding(9.dp).size(22.dp))
        Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), color = colors.primaryText,
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(CocoIcons.Close, stringResource(R.string.cancel), tint = colors.secondaryText,
                modifier = Modifier.background(colors.surfaceVariant, CircleShape).padding(7.dp).size(18.dp))
        }
    }
}

@Composable
internal fun AttachmentPanelButton(label: String, icon: ImageVector, accent: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val colors = LocalCocoColors.current
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) accent else accent.copy(alpha = .12f),
            contentColor = if (primary) colors.onAccent else accent)) {
        Icon(icon, null, Modifier.size(21.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}
