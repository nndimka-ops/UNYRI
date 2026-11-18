package com.messenger.app.ui.chats

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Generated preset plus local header presentation. No idle animation. */
internal object CapsuleChatChrome {
    // BEGIN SAVED CHAT CHROME
    const val topHeight = 62f
    const val topIcon = 21f
    const val entryHeight = 40f
    const val entryRadius = 30f
    const val metaGap = 7f
    val topColor = Color.Black.copy(alpha = 1f)
    val topInk = Color.White
    val bottomColor = Color(0xFF000000)
    val field = Color(0xCC000000)
    val bottomInk = Color(0xFFFFFFFF)
    val micColor = Color(0xFFFFFFFF)
    val micInk = Color(0xFF000000)
    val sent = Color(0xFFFFFFFF)
    val delivered = Color(0xFFFFFFFF)
    val read = Color(0xFF24A0FF)
    // END SAVED CHAT CHROME
    val settings:ImageVector by lazy {
        ImageVector.Builder("ChatHeaderGear",24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.7f,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) {
                // Eight teeth and a round hub: the reference options glyph is a gear.
                for (tooth in 0 until 8) {
                    for ((part, offset) in listOf(-22.5, -12.0, -9.0, 9.0, 12.0).withIndex()) {
                        val radius = if (part == 2 || part == 3) 10.0 else 7.7
                        val angle = Math.toRadians(tooth * 45.0 + offset)
                        val x = (12.0 + radius * kotlin.math.cos(angle)).toFloat()
                        val y = (12.0 + radius * kotlin.math.sin(angle)).toFloat()
                        if (tooth == 0 && part == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                close()
                moveTo(15.3f,12f);arcTo(3.3f,3.3f,0f,true,true,8.7f,12f);arcTo(3.3f,3.3f,0f,true,true,15.3f,12f)
            }
        }.build()
    }
}

/** Static contrast behind controls; the top fade draws below the header without reserving space. */
internal fun Modifier.capsuleChromeScrim(top:Boolean, solid:Boolean=true):Modifier=drawWithCache {
    if(top) {
        val color=CapsuleChatChrome.topColor.copy(alpha=1f)
        val fadeHeight=22.dp.toPx()
        val fade=Brush.verticalGradient(listOf(color,color.copy(alpha=0f)),startY=size.height,endY=size.height+fadeHeight)
        val transparentChrome = Brush.verticalGradient(colorStops = arrayOf(
            0f to Color.Black.copy(alpha = .82f), .65f to Color.Black.copy(alpha = .5f), 1f to Color.Transparent),
            endY = size.height)
        onDrawBehind {
            if (solid) {
                drawRect(color)
                drawRect(fade,topLeft=androidx.compose.ui.geometry.Offset(0f,size.height),
                    size=androidx.compose.ui.geometry.Size(size.width,fadeHeight))
            } else drawRect(transparentChrome)
        }
    } else {
        val color=CapsuleChatChrome.bottomColor
        val colors=arrayOf(0f to color.copy(alpha=0f),.5f to color.copy(alpha=2f/3f),1f to color)
        val brush=Brush.verticalGradient(colorStops=colors,endY=size.height)
        onDrawBehind {drawRect(brush)}
    }
}
