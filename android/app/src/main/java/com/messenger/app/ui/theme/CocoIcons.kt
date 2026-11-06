package com.messenger.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// COCO FLOW icon family (Этап N.1 §37-§43). Vector-first only, medium visual
// weight, rounded/soft. Standard glyphs reuse lightweight material-icons-core;
// the brand-distinctive call/media glyphs are custom ImageVectors below so the
// app never depends on material-icons-extended.
// ---------------------------------------------------------------------------

private fun icon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

object CocoIcons {

val LinkedRings: ImageVector by lazy {
    ImageVector.Builder("LinkedRings", 44.dp, 32.dp, 44f, 32f).apply {
        path(fill = null, stroke = SolidColor(Color(0xFF43C9EA)), strokeLineWidth = 5f) {
            moveTo(25f, 16f); curveTo(25f, 9.4f, 20.1f, 4f, 14f, 4f)
            curveTo(7.9f, 4f, 3f, 9.4f, 3f, 16f)
            curveTo(3f, 22.6f, 7.9f, 28f, 14f, 28f)
            curveTo(20.1f, 28f, 25f, 22.6f, 25f, 16f)
        }
        path(fill = null, stroke = SolidColor(Color(0xFF9B6AF0)), strokeLineWidth = 5f) {
            moveTo(41f, 16f); curveTo(41f, 9.4f, 36.1f, 4f, 30f, 4f)
            curveTo(23.9f, 4f, 19f, 9.4f, 19f, 16f)
            curveTo(19f, 22.6f, 23.9f, 28f, 30f, 28f)
            curveTo(36.1f, 28f, 41f, 22.6f, 41f, 16f)
        }
        path(fill = null, stroke = SolidColor(Color(0xFF07080D)), strokeLineWidth = 7f) {
            moveTo(20f, 6f); curveTo(23f, 8f, 25f, 12f, 25f, 16f)
        }
        path(fill = null, stroke = SolidColor(Color(0xFF43C9EA)), strokeLineWidth = 5f) {
            moveTo(20f, 6f); curveTo(23f, 8f, 25f, 12f, 25f, 16f)
        }
    }.build()
}

val ChatFilter: ImageVector by lazy { icon("ChatFilter") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(3f, 5f); lineTo(7f, 5f); moveTo(11f, 5f); lineTo(21f, 5f)
        moveTo(3f, 12f); lineTo(14f, 12f); moveTo(18f, 12f); lineTo(21f, 12f)
        moveTo(3f, 19f); lineTo(5f, 19f); moveTo(9f, 19f); lineTo(21f, 19f)
        moveTo(11f, 5f); curveTo(11f, 7.7f, 7f, 7.7f, 7f, 5f); curveTo(7f, 2.3f, 11f, 2.3f, 11f, 5f)
        moveTo(18f, 12f); curveTo(18f, 14.7f, 14f, 14.7f, 14f, 12f); curveTo(14f, 9.3f, 18f, 9.3f, 18f, 12f)
        moveTo(9f, 19f); curveTo(9f, 21.7f, 5f, 21.7f, 5f, 19f); curveTo(5f, 16.3f, 9f, 16.3f, 9f, 19f)
    }
} }

val ReportFlag: ImageVector by lazy { icon("ReportFlag") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(5f, 22f); lineTo(5f, 3f)
        moveTo(5f, 4f); curveTo(9f, 1f, 13f, 7f, 19f, 4f)
        lineTo(19f, 14f); curveTo(13f, 17f, 9f, 11f, 5f, 14f)
    }
} }

val MutedBell: ImageVector by lazy { icon("MutedBell") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(18f, 13f); lineTo(18f, 9f)
        curveTo(18f, 5.7f, 15.3f, 3f, 12f, 3f)
        curveTo(10.5f, 3f, 9.1f, 3.5f, 8f, 4.5f)
        moveTo(6f, 8f); lineTo(6f, 14f); lineTo(4f, 17f); lineTo(15f, 17f)
        moveTo(10f, 20f); curveTo(11f, 21f, 13f, 21f, 14f, 20f)
        moveTo(3f, 3f); lineTo(21f, 21f)
    }
} }

val Select: ImageVector by lazy { icon("Select") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(7f, 12f); lineTo(10.5f, 15.5f); lineTo(17f, 8.5f)
    }
} }

val Archive: ImageVector by lazy { icon("Archive") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 9f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 9f)
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 9f); lineTo(3f, 9f); close()
        moveTo(9f, 13f); lineTo(15f, 13f)
    }
} }

val RestoreChat: ImageVector by lazy { icon("RestoreChat") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 12f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 12f)
        moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(12f, 3f); lineTo(12f, 15f)
    }
} }

val Delete: ImageVector by lazy { icon("Delete") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
        moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f)
        moveTo(14f, 10f); lineTo(14f, 17f)
    }
} }

val Copy: ImageVector by lazy { icon("Copy") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(8f, 8f); lineTo(21f, 8f); lineTo(21f, 21f); lineTo(8f, 21f); close()
        moveTo(16f, 5f); lineTo(16f, 3f); lineTo(3f, 3f); lineTo(3f, 16f); lineTo(5f, 16f)
    }
} }

val Pin: ImageVector by lazy {
    icon("Pin") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 5f)
            lineTo(15f, 5f); lineTo(15f, 11f); lineTo(18f, 14f)
            lineTo(18f, 16f); lineTo(13f, 16f); lineTo(13f, 22f)
            lineTo(11f, 22f); lineTo(11f, 16f); lineTo(6f, 16f)
            lineTo(6f, 14f); lineTo(9f, 11f); lineTo(9f, 5f)
            lineTo(7f, 5f); close()
        }
    }
}


val CollapseCall: ImageVector by lazy {
    icon("CollapseCall") {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
        }
    }
}

val HideKeyboard: ImageVector by lazy {
    icon("HideKeyboard") {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 15f); lineTo(3f, 15f); close()
            moveTo(6f, 8f); lineTo(8f, 8f)
            moveTo(11f, 8f); lineTo(13f, 8f)
            moveTo(16f, 8f); lineTo(18f, 8f)
            moveTo(7f, 12f); lineTo(17f, 12f)
            moveTo(8f, 18f); lineTo(12f, 21f); lineTo(16f, 18f)
        }
    }
}

val Bookmark: ImageVector by lazy { icon("Bookmark") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 3f)
            horizontalLineTo(7f)
            curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
            verticalLineTo(21f)
            lineTo(12f, 18f)
            lineTo(19f, 21f)
            verticalLineTo(5f)
            curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
            close()
        }
    } }

val Mic: ImageVector by lazy { icon("Mic") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17.3f, 11f)
            curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.72f)
            curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
            horizontalLineTo(17.3f)
            close()
        }
    } }

// A simple continuous slash stays legible at the 21-dp call-control size.
val MicOff: ImageVector by lazy { icon("MicOff") {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(9f, 5.7f)
            verticalLineTo(5f)
            curveTo(9f, 3.34f, 10.34f, 2f, 12f, 2f)
            curveTo(13.66f, 2f, 15f, 3.34f, 15f, 5f)
            verticalLineTo(10.5f)
            moveTo(9f, 9.4f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            curveTo(12.45f, 14f, 12.87f, 13.9f, 13.25f, 13.72f)
            moveTo(6f, 10.5f)
            verticalLineTo(11f)
            curveTo(6f, 14.31f, 8.69f, 17f, 12f, 17f)
            curveTo(13.04f, 17f, 14.02f, 16.74f, 14.87f, 16.28f)
            moveTo(18f, 10.5f)
            verticalLineTo(11f)
            curveTo(18f, 11.67f, 17.89f, 12.31f, 17.69f, 12.91f)
            moveTo(12f, 17f)
            verticalLineTo(21f)
            moveTo(9f, 21f)
            horizontalLineTo(15f)
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    } }

val Videocam: ImageVector by lazy { icon("Videocam") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 10.5f)
            verticalLineTo(7f)
            curveTo(17f, 6.45f, 16.55f, 6f, 16f, 6f)
            horizontalLineTo(4f)
            curveTo(3.45f, 6f, 3f, 6.45f, 3f, 7f)
            verticalLineTo(17f)
            curveTo(3f, 17.55f, 3.45f, 18f, 4f, 18f)
            horizontalLineTo(16f)
            curveTo(16.55f, 18f, 17f, 17.55f, 17f, 17f)
            verticalLineTo(13.5f)
            lineTo(21f, 17.5f)
            verticalLineTo(6.5f)
            lineTo(17f, 10.5f)
            close()
        }
    } }

val Speaker: ImageVector by lazy { icon("Speaker") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 9f)
            verticalLineTo(15f)
            horizontalLineTo(7f)
            lineTo(12f, 20f)
            verticalLineTo(4f)
            lineTo(7f, 9f)
            horizontalLineTo(3f)
            close()
            moveTo(16.5f, 12f)
            curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
            verticalLineTo(16.02f)
            curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
            close()
            moveTo(14f, 3.23f)
            verticalLineTo(5.29f)
            curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
            curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f)
            verticalLineTo(20.77f)
            curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f)
            curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f)
            close()
        }
    } }

val Earpiece: ImageVector by lazy { icon("Earpiece") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 1f)
            curveTo(7.03f, 1f, 3f, 5.03f, 3f, 10f)
            verticalLineTo(17f)
            curveTo(3f, 18.66f, 4.34f, 20f, 6f, 20f)
            horizontalLineTo(9f)
            verticalLineTo(12f)
            horizontalLineTo(5f)
            verticalLineTo(10f)
            curveTo(5f, 6.13f, 8.13f, 3f, 12f, 3f)
            curveTo(15.87f, 3f, 19f, 6.13f, 19f, 10f)
            verticalLineTo(12f)
            horizontalLineTo(15f)
            verticalLineTo(20f)
            horizontalLineTo(18f)
            curveTo(19.66f, 20f, 21f, 18.66f, 21f, 17f)
            verticalLineTo(10f)
            curveTo(21f, 5.03f, 16.97f, 1f, 12f, 1f)
            close()
        }
    } }

val Camera: ImageVector by lazy { icon("Camera") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 15.2f)
            curveTo(13.77f, 15.2f, 15.2f, 13.77f, 15.2f, 12f)
            curveTo(15.2f, 10.23f, 13.77f, 8.8f, 12f, 8.8f)
            curveTo(10.23f, 8.8f, 8.8f, 10.23f, 8.8f, 12f)
            curveTo(8.8f, 13.77f, 10.23f, 15.2f, 12f, 15.2f)
            close()
            moveTo(9f, 2f)
            lineTo(7.17f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            horizontalLineTo(16.83f)
            lineTo(15f, 2f)
            horizontalLineTo(9f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
        }
    } }

// Original outline: clear at small call-control sizes, cached across recompositions.
val SwitchCamera: ImageVector by lazy {
    icon("SwitchCamera") {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(8f, 5f); lineTo(9.5f, 3f); lineTo(14.5f, 3f); lineTo(16f, 5f)
            lineTo(20f, 5f); curveTo(21.1f, 5f, 22f, 5.9f, 22f, 7f)
            lineTo(22f, 19f); lineTo(2f, 19f); lineTo(2f, 7f)
            curveTo(2f, 5.9f, 2.9f, 5f, 4f, 5f); close()
            moveTo(8f, 11f); curveTo(9f, 8f, 14f, 8f, 16f, 11f)
            moveTo(16f, 8.5f); lineTo(16f, 11f); lineTo(13.5f, 11f)
            moveTo(16f, 14f); curveTo(15f, 17f, 10f, 17f, 8f, 14f)
            moveTo(8f, 16.5f); lineTo(8f, 14f); lineTo(10.5f, 14f)
        }
    }
}

val Eye: ImageVector by lazy { icon("Eye") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    } }

val EyeOff: ImageVector by lazy { icon("EyeOff") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
            lineTo(19.56f, 16.75f)
            curveTo(21.07f, 15.49f, 22.26f, 13.86f, 22.99f, 12f)
            curveTo(21.26f, 7.61f, 16.99f, 4.5f, 12f, 4.5f)
            curveTo(10.6f, 4.5f, 9.26f, 4.75f, 8.02f, 5.2f)
            lineTo(10.18f, 7.36f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineTo(4.28f, 6.55f)
            lineTo(4.74f, 7.01f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
            lineTo(16.8f, 19.08f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineTo(9.08f, 11.35f)
            curveTo(8.7f, 11.94f, 8.5f, 12.45f, 8.5f, 13f)
            curveTo(8.5f, 14.93f, 10.07f, 16.5f, 12f, 16.5f)
            curveTo(12.55f, 16.5f, 13.06f, 16.3f, 13.65f, 15.92f)
            lineTo(15.2f, 17.47f)
            curveTo(14.16f, 18.21f, 13.12f, 18.5f, 12f, 18.5f)
            curveTo(8.96f, 18.5f, 6.5f, 16.04f, 6.5f, 13f)
            curveTo(6.5f, 11.88f, 6.79f, 10.84f, 7.29f, 9.93f)
            lineTo(7.53f, 9.8f)
            close()
            moveTo(18.5f, 13f)
            curveTo(18.5f, 14.2f, 18.17f, 15.32f, 17.61f, 16.28f)
            lineTo(18.81f, 17.48f)
            curveTo(19.74f, 16.23f, 20.5f, 14.67f, 20.5f, 13f)
            curveTo(20.5f, 12.38f, 20.38f, 11.79f, 20.17f, 11.24f)
            lineTo(18.5f, 13f)
            close()
        }
    } }

val Attachment: ImageVector by lazy { icon("Attachment") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.5f, 6f)
            verticalLineTo(17.5f)
            curveTo(16.5f, 19.71f, 14.71f, 21.5f, 12.5f, 21.5f)
            curveTo(10.29f, 21.5f, 8.5f, 19.71f, 8.5f, 17.5f)
            verticalLineTo(5f)
            curveTo(8.5f, 3.62f, 9.62f, 2.5f, 11f, 2.5f)
            curveTo(12.38f, 2.5f, 13.5f, 3.62f, 13.5f, 5f)
            verticalLineTo(15.5f)
            curveTo(13.5f, 16.05f, 13.05f, 16.5f, 12.5f, 16.5f)
            curveTo(11.95f, 16.5f, 11.5f, 16.05f, 11.5f, 15.5f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
            verticalLineTo(15.5f)
            curveTo(10f, 16.88f, 11.12f, 18f, 12.5f, 18f)
            curveTo(13.88f, 18f, 15f, 16.88f, 15f, 15.5f)
            verticalLineTo(5f)
            curveTo(15f, 2.79f, 13.21f, 1f, 11f, 1f)
            curveTo(8.79f, 1f, 7f, 2.79f, 7f, 5f)
            verticalLineTo(17.5f)
            curveTo(7f, 20.54f, 9.46f, 23f, 12.5f, 23f)
            curveTo(15.54f, 23f, 18f, 20.54f, 18f, 17.5f)
            verticalLineTo(6f)
            horizontalLineTo(16.5f)
            close()
        }
    } }

val Image: ImageVector by lazy { icon("Image") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            close()
            moveTo(8.5f, 13.5f)
            lineTo(11f, 16.51f)
            lineTo(14.5f, 12f)
            lineTo(19f, 18f)
            horizontalLineTo(5f)
            lineTo(8.5f, 13.5f)
            close()
        }
    } }

val File: ImageVector by lazy { icon("File") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 2f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            verticalLineTo(20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(8f)
            lineTo(14f, 2f)
            horizontalLineTo(6f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9f)
            horizontalLineTo(13f)
            close()
        }
    } }

val EndCall: ImageVector by lazy { icon("EndCall") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 9f)
            curveTo(10.4f, 9f, 8.85f, 9.25f, 7.4f, 9.72f)
            verticalLineTo(12.82f)
            curveTo(7.4f, 13.21f, 7.17f, 13.56f, 6.84f, 13.72f)
            curveTo(5.86f, 14.21f, 4.97f, 14.84f, 4.18f, 15.57f)
            curveTo(4f, 15.75f, 3.75f, 15.85f, 3.48f, 15.85f)
            curveTo(3.2f, 15.85f, 2.95f, 15.74f, 2.77f, 15.56f)
            lineTo(0.29f, 13.08f)
            curveTo(0.11f, 12.9f, 0f, 12.65f, 0f, 12.38f)
            curveTo(0f, 12.1f, 0.11f, 11.85f, 0.29f, 11.67f)
            curveTo(3.34f, 8.78f, 7.46f, 7f, 12f, 7f)
            curveTo(16.54f, 7f, 20.66f, 8.78f, 23.71f, 11.67f)
            curveTo(23.89f, 11.85f, 24f, 12.1f, 24f, 12.38f)
            curveTo(24f, 12.65f, 23.89f, 12.9f, 23.71f, 13.08f)
            lineTo(21.23f, 15.56f)
            curveTo(21.05f, 15.74f, 20.8f, 15.85f, 20.52f, 15.85f)
            curveTo(20.25f, 15.85f, 20f, 15.75f, 19.82f, 15.57f)
            curveTo(19.03f, 14.84f, 18.14f, 14.21f, 17.16f, 13.72f)
            curveTo(16.83f, 13.56f, 16.6f, 13.21f, 16.6f, 12.82f)
            verticalLineTo(9.72f)
            curveTo(15.15f, 9.25f, 13.6f, 9f, 12f, 9f)
            close()
        }
    } }

val Logo: ImageVector by lazy { icon("Logo") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f)
            horizontalLineTo(20f)
            curveTo(21.1f, 5f, 22f, 5.9f, 22f, 7f)
            verticalLineTo(15f)
            curveTo(22f, 16.1f, 21.1f, 17f, 20f, 17f)
            horizontalLineTo(13f)
            lineTo(8f, 21.5f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            curveTo(2.9f, 17f, 2f, 16.1f, 2f, 15f)
            verticalLineTo(7f)
            curveTo(2f, 5.9f, 2.9f, 5f, 4f, 5f)
            close()
            moveTo(7.5f, 9f)
            curveTo(6.67f, 9f, 6f, 9.67f, 6f, 10.5f)
            curveTo(6f, 11.33f, 6.67f, 12f, 7.5f, 12f)
            curveTo(8.33f, 12f, 9f, 11.33f, 9f, 10.5f)
            curveTo(9f, 9.67f, 8.33f, 9f, 7.5f, 9f)
            close()
            moveTo(12f, 9f)
            curveTo(11.17f, 9f, 10.5f, 9.67f, 10.5f, 10.5f)
            curveTo(10.5f, 11.33f, 11.17f, 12f, 12f, 12f)
            curveTo(12.83f, 12f, 13.5f, 11.33f, 13.5f, 10.5f)
            curveTo(13.5f, 9.67f, 12.83f, 9f, 12f, 9f)
            close()
            moveTo(16.5f, 9f)
            curveTo(15.67f, 9f, 15f, 9.67f, 15f, 10.5f)
            curveTo(15f, 11.33f, 15.67f, 12f, 16.5f, 12f)
            curveTo(17.33f, 12f, 18f, 11.33f, 18f, 10.5f)
            curveTo(18f, 9.67f, 17.33f, 9f, 16.5f, 9f)
            close()
        }
    } }

// ---------------------------------------------------------------------------
// N.1A functional glyphs (replace the literal emoji that used to be used as
// production buttons): Security, AudioCall, VideoCall, Search, Photo, Close.
// ---------------------------------------------------------------------------

val Security: ImageVector by lazy { icon("Security") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 1f)
            lineTo(4f, 4f)
            verticalLineTo(10f)
            curveTo(4f, 15.52f, 7.61f, 19.82f, 12f, 23f)
            curveTo(16.39f, 19.82f, 20f, 15.52f, 20f, 10f)
            verticalLineTo(4f)
            close()
            moveTo(10.6f, 16.7f)
            lineTo(7.8f, 13.9f)
            lineTo(9.2f, 12.5f)
            lineTo(10.6f, 13.9f)
            lineTo(15.2f, 9.3f)
            lineTo(16.6f, 10.7f)
            close()
        }
    } }

val AudioCall: ImageVector by lazy { icon("AudioCall") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.62f, 10.79f)
            curveTo(8.06f, 13.62f, 10.38f, 15.94f, 13.21f, 17.38f)
            lineTo(15.41f, 15.18f)
            curveTo(15.69f, 14.9f, 16.08f, 14.82f, 16.43f, 14.93f)
            curveTo(17.55f, 15.3f, 18.75f, 15.5f, 20f, 15.5f)
            curveTo(20.55f, 15.5f, 21f, 15.95f, 21f, 16.5f)
            verticalLineTo(19.5f)
            curveTo(21f, 20.05f, 20.55f, 20.5f, 20f, 20.5f)
            curveTo(10.61f, 20.5f, 3f, 12.89f, 3f, 3.5f)
            curveTo(3f, 2.95f, 3.45f, 2.5f, 4f, 2.5f)
            horizontalLineTo(7f)
            curveTo(7.55f, 2.5f, 8f, 2.95f, 8f, 3.5f)
            curveTo(8f, 4.75f, 8.2f, 5.95f, 8.57f, 7.07f)
            curveTo(8.68f, 7.42f, 8.6f, 7.81f, 8.32f, 8.09f)
            close()
        }
    } }

val VideoCall: ImageVector
    get() = Videocam

val Search: ImageVector by lazy { icon("Search") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(14.71f, 14f)
            horizontalLineTo(15.5f)
            lineTo(20.49f, 19f)
            lineTo(19f, 20.49f)
            lineTo(14f, 15.5f)
            verticalLineTo(14.71f)
            lineTo(13.73f, 14.43f)
            curveTo(12.59f, 15.41f, 11.11f, 16f, 9.5f, 16f)
            curveTo(5.91f, 16f, 3f, 13.09f, 3f, 9.5f)
            curveTo(3f, 5.91f, 5.91f, 3f, 9.5f, 3f)
            curveTo(13.09f, 3f, 16f, 5.91f, 16f, 9.5f)
            curveTo(16f, 11.11f, 15.41f, 12.59f, 14.43f, 13.73f)
            close()
            moveTo(5f, 9.5f)
            curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
            curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
            curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
            curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
            close()
        }
    } }

val Photo: ImageVector by lazy { icon("Photo") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            close()
            moveTo(8.5f, 12f)
            curveTo(7.12f, 12f, 6f, 10.88f, 6f, 9.5f)
            curveTo(6f, 8.12f, 7.12f, 7f, 8.5f, 7f)
            curveTo(9.88f, 7f, 11f, 8.12f, 11f, 9.5f)
            curveTo(11f, 10.88f, 9.88f, 12f, 8.5f, 12f)
            close()
            moveTo(5f, 18f)
            lineTo(9.5f, 13.5f)
            lineTo(11.5f, 15.5f)
            lineTo(14f, 13f)
            lineTo(19f, 18f)
            close()
        }
    } }

val Close: ImageVector by lazy { icon("Close") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 6.41f)
            lineTo(17.59f, 5f)
            lineTo(12f, 10.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(12f, 13.41f)
            lineTo(17.59f, 19f)
            lineTo(19f, 17.59f)
            lineTo(13.41f, 12f)
            close()
        }
    } }

val ArrowBack: ImageVector by lazy { icon("ArrowBack") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            verticalLineTo(11f)
            close()
        }
    } }

val MoreVert: ImageVector by lazy { icon("MoreVert") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            close()
            moveTo(12f, 10f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            close()
            moveTo(12f, 16f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            close()
        }
    } }

val Plus: ImageVector by lazy { icon("Plus") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            verticalLineTo(5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            close()
        }
    } }

val Send: ImageVector by lazy { icon("Send") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2.01f, 21f)
            lineTo(23f, 12f)
            lineTo(2.01f, 3f)
            lineTo(2f, 10f)
            lineTo(17f, 12f)
            lineTo(2f, 14f)
            close()
        }
    } }

val Play: ImageVector by lazy { icon("Play") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f)
            verticalLineTo(19f)
            lineTo(19f, 12f)
            close()
        }
    } }

val Pause: ImageVector by lazy { icon("Pause") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineTo(19f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            horizontalLineTo(14f)
            close()
        }
    } }

val Chat: ImageVector by lazy { icon("Chat") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
            verticalLineTo(22f)
            lineTo(6f, 18f)
            horizontalLineTo(20f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
        }
    } }

val Bluetooth: ImageVector by lazy { icon("Bluetooth") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.71f, 7.71f)
            lineTo(12f, 2f)
            horizontalLineTo(11f)
            verticalLineTo(7.59f)
            lineTo(7.41f, 4f)
            lineTo(6f, 5.41f)
            lineTo(10.59f, 10f)
            lineTo(6f, 14.59f)
            lineTo(7.41f, 16f)
            lineTo(11f, 12.41f)
            verticalLineTo(20f)
            horizontalLineTo(12f)
            lineTo(17.71f, 14.29f)
            lineTo(13.41f, 10f)
            close()
            moveTo(13f, 5.83f)
            lineTo(14.88f, 7.71f)
            lineTo(13f, 9.59f)
            close()
            moveTo(13f, 12.41f)
            lineTo(14.88f, 14.29f)
            lineTo(13f, 16.17f)
            close()
        }
    } }

val Check: ImageVector by lazy { icon("Check") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f)
    }
} }

val Network: ImageVector by lazy { icon("Network") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 12f); lineTo(12f, 21f)
        moveTo(9f, 21f); lineTo(15f, 21f)
        moveTo(8.5f, 7.5f); curveTo(6.6f, 9.4f, 6.6f, 12.6f, 8.5f, 14.5f)
        moveTo(15.5f, 7.5f); curveTo(17.4f, 9.4f, 17.4f, 12.6f, 15.5f, 14.5f)
        moveTo(5.5f, 4.5f); curveTo(1.9f, 8.1f, 1.9f, 13.9f, 5.5f, 17.5f)
        moveTo(18.5f, 4.5f); curveTo(22.1f, 8.1f, 22.1f, 13.9f, 18.5f, 17.5f)
    }
} }

val Storage: ImageVector by lazy { icon("Storage") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 5f); lineTo(19f, 5f); curveTo(20.1f, 5f, 21f, 5.9f, 21f, 7f)
        lineTo(21f, 17f); curveTo(21f, 18.1f, 20.1f, 19f, 19f, 19f)
        lineTo(5f, 19f); curveTo(3.9f, 19f, 3f, 18.1f, 3f, 17f)
        lineTo(3f, 7f); curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f); close()
        moveTo(3f, 13f); lineTo(21f, 13f)
        moveTo(16f, 16f); lineTo(17f, 16f)
    }
} }

val Language: ImageVector by lazy { icon("Language") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(12f, 3f); curveTo(6.7f, 7.5f, 6.7f, 16.5f, 12f, 21f)
        curveTo(17.3f, 16.5f, 17.3f, 7.5f, 12f, 3f)
        moveTo(3f, 12f); lineTo(21f, 12f)
    }
} }

val Devices: ImageVector by lazy { icon("Devices") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(8f, 2f); lineTo(16f, 2f); curveTo(17.1f, 2f, 18f, 2.9f, 18f, 4f)
        lineTo(18f, 20f); curveTo(18f, 21.1f, 17.1f, 22f, 16f, 22f)
        lineTo(8f, 22f); curveTo(6.9f, 22f, 6f, 21.1f, 6f, 20f)
        lineTo(6f, 4f); curveTo(6f, 2.9f, 6.9f, 2f, 8f, 2f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    }
} }

val Headset: ImageVector by lazy { icon("Headset") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            curveTo(6.48f, 3f, 2f, 7.48f, 2f, 13f)
            verticalLineTo(17f)
            curveTo(2f, 18.1f, 2.9f, 19f, 4f, 19f)
            horizontalLineTo(6f)
            verticalLineTo(13f)
            horizontalLineTo(4f)
            curveTo(4f, 8.58f, 7.58f, 5f, 12f, 5f)
            curveTo(16.42f, 5f, 20f, 8.58f, 20f, 13f)
            horizontalLineTo(18f)
            verticalLineTo(19f)
            horizontalLineTo(20f)
            curveTo(21.1f, 19f, 22f, 18.1f, 22f, 17f)
            verticalLineTo(13f)
            curveTo(22f, 7.48f, 17.52f, 3f, 12f, 3f)
            close()
        }
    } }

}
