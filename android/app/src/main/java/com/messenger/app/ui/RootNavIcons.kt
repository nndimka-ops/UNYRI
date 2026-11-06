package com.messenger.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small cached vectors exclusive to root navigation. */
internal object RootNavIcons {
    val Edit by lazy { outline("DrawerEdit") {
        moveTo(15f, 4f); lineTo(20f, 9f); lineTo(8f, 21f); lineTo(3f, 22f); lineTo(4f, 17f); close()
        moveTo(13f, 6f); lineTo(18f, 11f)
    } }
    val Bookmark by lazy { outline("DrawerBookmark") {
        moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 22f); lineTo(12f, 18f); lineTo(6f, 22f); close()
    } }
    val Call by lazy { outline("DrawerCall") {
        moveTo(6f, 3f); lineTo(9f, 7f); lineTo(7f, 10f)
        curveTo(9f, 14f, 11f, 16f, 15f, 17f); lineTo(18f, 15f); lineTo(22f, 18f)
        curveTo(22f, 23f, 18f, 23f, 14f, 21f); curveTo(7f, 18f, 3f, 13f, 2f, 7f)
        curveTo(1f, 4f, 3f, 2f, 6f, 3f); close()
    } }
    private fun outline(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val Server by lazy { outline("DrawerServer") {
        moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 10f); lineTo(4f, 10f); close()
        moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 21f); lineTo(4f, 21f); close()
        moveTo(7f, 6.5f); lineTo(7.1f, 6.5f); moveTo(7f, 17.5f); lineTo(7.1f, 17.5f)
        moveTo(11f, 6.5f); lineTo(17f, 6.5f); moveTo(11f, 17.5f); lineTo(17f, 17.5f)
    } }
    val Devices by lazy { outline("DrawerDevices") {
        moveTo(3f, 4f); lineTo(17f, 4f); lineTo(17f, 14f); lineTo(3f, 14f); close()
        moveTo(10f, 14f); lineTo(10f, 19f); moveTo(6f, 19f); lineTo(13f, 19f)
        moveTo(16f, 11f); lineTo(22f, 11f); lineTo(22f, 21f); lineTo(16f, 21f); close()
    } }
    val Help by lazy { outline("DrawerHelp") {
        moveTo(12f, 2f); curveTo(25.3f, 2f, 25.3f, 22f, 12f, 22f)
        curveTo(-1.3f, 22f, -1.3f, 2f, 12f, 2f); close()
        moveTo(9f, 8f); curveTo(9f, 4.5f, 16f, 4.5f, 16f, 9f)
        curveTo(16f, 12f, 12f, 11f, 12f, 14f)
        moveTo(12f, 17.5f); lineTo(12f, 17.6f)
    } }
    val Flag by lazy { outline("DrawerFlag") {
        moveTo(5f, 22f); lineTo(5f, 3f); curveTo(10f, 0f, 14f, 7f, 20f, 3f)
        lineTo(20f, 13f); curveTo(14f, 17f, 10f, 10f, 5f, 13f)
    } }
    val Exit by lazy { outline("DrawerExit") {
        moveTo(10f, 3f); lineTo(4f, 3f); lineTo(4f, 21f); lineTo(10f, 21f)
        moveTo(10f, 12f); lineTo(22f, 12f); moveTo(17f, 7f); lineTo(22f, 12f); lineTo(17f, 17f)
    } }
    val Chats: ImageVector by lazy {
        ImageVector.Builder("RootChats", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 7f); lineTo(16f, 4.5f)
                curveTo(16f, 3.1f, 15f, 2f, 13.5f, 2f); lineTo(4.5f, 2f)
                curveTo(3.1f, 2f, 2f, 3.1f, 2f, 4.5f); lineTo(2f, 11.5f)
                curveTo(2f, 13f, 3.1f, 14f, 4.5f, 14f); lineTo(5f, 14f)
                lineTo(5f, 17f); lineTo(8f, 14f)
                moveTo(11f, 8f); lineTo(19.5f, 8f)
                curveTo(21f, 8f, 22f, 9f, 22f, 10.5f); lineTo(22f, 17.5f)
                curveTo(22f, 19f, 21f, 20f, 19.5f, 20f); lineTo(19f, 20f)
                lineTo(19f, 23f); lineTo(15.5f, 20f); lineTo(11f, 20f)
                curveTo(9.5f, 20f, 8.5f, 19f, 8.5f, 17.5f); lineTo(8.5f, 10.5f)
                curveTo(8.5f, 9f, 9.5f, 8f, 11f, 8f); close()
            }
        }.build()
    }
    val Settings: ImageVector by lazy {
        ImageVector.Builder("RootSettings", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Six shallow teeth and a round central aperture.
                for (tooth in 0 until 6) {
                    val angles = floatArrayOf(-22f, -11f, 11f, 22f)
                    for (i in angles.indices) {
                        val a = Math.toRadians((tooth * 60f + angles[i] - 90f).toDouble())
                        val r = if (i == 1 || i == 2) 10f else 7.8f
                        val x = 12f + kotlin.math.cos(a).toFloat() * r
                        val y = 12f + kotlin.math.sin(a).toFloat() * r
                        if (tooth == 0 && i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                close()
                moveTo(15.5f, 12f)
                arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 12f)
                arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 12f)
                close()
            }
        }.build()
    }
}
