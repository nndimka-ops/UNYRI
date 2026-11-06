package com.messenger.app.ui.chats

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Generated chrome values from the approved editor48 snapshot. Static rendering only. */
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
        ImageVector.Builder("CapsuleChatSettings",24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.7f,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) {
                moveTo(10f,7f);lineTo(21f,7f);moveTo(3f,17f);lineTo(14f,17f)
                moveTo(10f,7f);arcTo(3f,3f,0f,true,true,4f,7f);arcTo(3f,3f,0f,true,true,10f,7f)
                moveTo(20f,17f);arcTo(3f,3f,0f,true,true,14f,17f);arcTo(3f,3f,0f,true,true,20f,17f)
            }
        }.build()
    }
}

/** Static contrast behind controls; the top fade draws below the header without reserving space. */
internal fun Modifier.capsuleChromeScrim(top:Boolean):Modifier=drawWithCache {
    if(top) {
        val color=CapsuleChatChrome.topColor.copy(alpha=1f)
        val fadeHeight=22.dp.toPx()
        val fade=Brush.verticalGradient(listOf(color,color.copy(alpha=0f)),startY=size.height,endY=size.height+fadeHeight)
        onDrawBehind {
            drawRect(color)
            drawRect(fade,topLeft=androidx.compose.ui.geometry.Offset(0f,size.height),
                size=androidx.compose.ui.geometry.Size(size.width,fadeHeight))
        }
    } else {
        val color=CapsuleChatChrome.bottomColor
        val colors=arrayOf(0f to color.copy(alpha=0f),.5f to color.copy(alpha=2f/3f),1f to color)
        val brush=Brush.verticalGradient(colorStops=colors,endY=size.height)
        onDrawBehind {drawRect(brush)}
    }
}
