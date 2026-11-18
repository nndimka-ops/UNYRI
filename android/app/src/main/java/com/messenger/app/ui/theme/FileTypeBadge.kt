package com.messenger.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.messenger.app.data.attachment.FilePresentation

@Composable
fun FileTypeBadge(mime: String, filename: String?, compact: Boolean = false) {
    val label = FilePresentation.label(mime, filename)
    val color = when (label) {
        "PDF" -> Color(0xFFFF8585)
        "DOC", "TXT" -> Color(0xFF8ABBFF)
        "XLS" -> Color(0xFF77D5AF)
        "PPT" -> Color(0xFFFFBB80)
        "ZIP" -> Color(0xFFC6ABFF)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.background(color.copy(alpha = if (compact) 0.9f else 0.12f), RoundedCornerShape(8.dp)).padding(if (compact) 8.dp else 6.dp)) {
        Icon(when (label) { "VIDEO" -> CocoIcons.Videocam; "AUDIO" -> CocoIcons.Mic; "IMG" -> CocoIcons.Photo; else -> CocoIcons.File },
            contentDescription = null, tint = if (compact) LocalCocoColors.current.onAccent else color, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
        if (!compact) Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}
