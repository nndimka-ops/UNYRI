package com.messenger.app.ui.call

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val CallShrinkIcon: ImageVector by lazy {
    ImageVector.Builder("CallShrink", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f) {
            moveTo(3f, 8f); lineTo(8f, 8f); lineTo(8f, 3f); moveTo(3f, 3f); lineTo(8f, 8f)
            moveTo(16f, 3f); lineTo(16f, 8f); lineTo(21f, 8f); moveTo(21f, 3f); lineTo(16f, 8f)
            moveTo(3f, 16f); lineTo(8f, 16f); lineTo(8f, 21f); moveTo(3f, 21f); lineTo(8f, 16f)
            moveTo(16f, 21f); lineTo(16f, 16f); lineTo(21f, 16f); moveTo(21f, 21f); lineTo(16f, 16f)
        }
    }.build()
}
internal val CallVideoOffIcon: ImageVector by lazy {
    ImageVector.Builder("CallVideoOff", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f) {
            moveTo(3f, 7f); lineTo(16f, 7f); lineTo(16f, 17f); lineTo(3f, 17f); close()
            moveTo(16f, 10f); lineTo(22f, 7f); lineTo(22f, 17f); lineTo(16f, 14f)
            moveTo(2f, 2f); lineTo(22f, 22f)
        }
    }.build()
}
internal val CallSpeakerOffIcon: ImageVector by lazy {
    ImageVector.Builder("CallSpeakerOff", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f) {
            moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
            moveTo(2f, 2f); lineTo(22f, 22f)
        }
    }.build()
}
