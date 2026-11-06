package com.messenger.app.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal class ReactionBubbleGeometry {
    var bodyHeight by mutableFloatStateOf(0f)
    var reactionWidth by mutableFloatStateOf(0f)
}

/** One contour, with no fill below the unused part of the main message. */
internal fun Modifier.reactionBubbleContour(geometry: ReactionBubbleGeometry, outgoing: Boolean, highlightColor: Color? = null) = drawWithCache {
    val w = size.width
    val h = size.height
    val body = geometry.bodyHeight.coerceIn(0f, h).takeIf { it > 0f } ?: h
    val extension = h - body
    val left = (w - geometry.reactionWidth).coerceIn(0f, w)
    val r = minOf(24.dp.toPx(), w / 2, body / 2)
    // The pocket joins the horizontal body edge in one tangent-continuous curve.
    // No straight neck is inserted above its rounded lower corner.
    val lobeRadius = minOf(16.dp.toPx(), geometry.reactionWidth / 2, extension * 0.85f)
    val lobeTransitionWidth = minOf(18.dp.toPx(), left, geometry.reactionWidth * 0.55f)
    val k = 0.55228475f
    val path = Path()
    fun x(value: Float) = if (outgoing) value else w - value
    fun move(a: Float, b: Float) = path.moveTo(x(a), b)
    fun line(a: Float, b: Float) = path.lineTo(x(a), b)
    fun curve(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = path.cubicTo(x(a), b, x(c), d, x(e), f)
    move(r, 0f)
    line(w - r, 0f); curve(w - r + r * k, 0f, w, r - r * k, w, r)
    if (extension > 0.5f && geometry.reactionWidth > 0f && left > 0.5f) {
        line(w, h - lobeRadius)
        curve(w, h - lobeRadius + lobeRadius * k,
            w - lobeRadius + lobeRadius * k, h,
            w - lobeRadius, h)
        line(left + lobeRadius, h)
        curve(left + lobeRadius - lobeRadius * k, h,
            left, h - lobeRadius + lobeRadius * k,
            left, h - lobeRadius)
        val minCorner = minOf(8.dp.toPx(), left / 2f)
        val joinX = maxOf((left - lobeTransitionWidth).coerceAtLeast(0f), minCorner)
        val joinHeight = (h - lobeRadius - body).coerceAtLeast(0f)
        curve(left, body + joinHeight * 0.45f,
            joinX + (left - joinX) * 0.55f, body, joinX, body)
        // Keep a visible rounded body corner on short bubbles without
        // sending the transition backwards when two chips nearly fill it.
        val corner = minOf(r, joinX)
        val handle = corner * k
        line(corner, body); curve(corner - handle, body, 0f, body - corner + handle, 0f, body - corner)
    } else {
        val bottom = if (extension > 0.5f && geometry.reactionWidth > 0f) h else body
        line(w, bottom - r); curve(w, bottom - r + r * k, w - r + r * k, bottom, w - r, bottom)
        line(r, bottom); curve(r - r * k, bottom, 0f, bottom - r + r * k, 0f, bottom - r)
    }
    line(0f, r); curve(0f, r - r * k, r - r * k, 0f, r, 0f); path.close()
    val fill = Brush.linearGradient(if (highlightColor != null) listOf(highlightColor, highlightColor) else if (outgoing) ChatMessageMaterial.outgoing else ChatMessageMaterial.incoming)
    onDrawBehind {
        drawPath(path, fill)
        // Quiet edge only; no luminous outer stroke or additional blur pass.
        drawPath(path, if (outgoing) ChatMessageMaterial.outline else Color.White.copy(alpha = 0.035f), style = Stroke(0.5.dp.toPx()))
    }
}

internal object ChatMessageMaterial {
    val outgoing = listOf(Color(0xFF353053), Color(0xFF302C4D), Color(0xFF2B2846))
    val incoming = listOf(Color(0xFF202735), Color(0xFF1B222E), Color(0xFF191F2A))
    val outline = Color(0xFF8A81B4).copy(alpha = 0.10f)
    val reaction = Color(0xFF26223F)
    val reactionOutline = Color(0xFF9A8BC3).copy(alpha = 0.12f)
}

/** Lens reference: CSS elliptical corners, uniformly normalized for short messages. */
internal class LensMessageShape(private val outgoing: Boolean, private val attachment: Boolean = false) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                               density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val values = if (attachment) floatArrayOf(10f,17f,28f,25f,18f,24f,24f,16f)
                     else floatArrayOf(10f,18f,35f,26f,19f,25f,29f,15f)
        val r = values.map { with(density) { it.dp.toPx() } }.toFloatArray()
        val w = size.width; val h = size.height
        val scale = minOf(1f, w / (r[0]+r[2]), w / (r[4]+r[6]), h / (r[1]+r[7]), h / (r[3]+r[5]))
        for (i in r.indices) r[i] *= scale
        val path = Path(); val k = .55228475f
        fun x(v: Float) = if (outgoing) w-v else v
        fun line(a:Float,b:Float) = path.lineTo(x(a),b)
        fun curve(a:Float,b:Float,c:Float,d:Float,e:Float,f:Float) = path.cubicTo(x(a),b,x(c),d,x(e),f)
        path.moveTo(x(r[0]),0f); line(w-r[2],0f)
        curve(w-r[2]+r[2]*k,0f,w,r[3]-r[3]*k,w,r[3]);line(w,h-r[5])
        curve(w,h-r[5]+r[5]*k,w-r[4]+r[4]*k,h,w-r[4],h);line(r[6],h)
        curve(r[6]-r[6]*k,h,0f,h-r[7]+r[7]*k,0f,h-r[7]);line(0f,r[1])
        curve(0f,r[1]-r[1]*k,r[0]-r[0]*k,0f,r[0],0f);path.close()
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

internal object LensMessageMaterial {
    val incoming = listOf(Color(0xFF2C4352),Color(0xFF1B2D3B),Color(0xFF192834))
    val outgoing = listOf(Color(0xFF453A59),Color(0xFF302D46),Color(0xFF26263B))
    val attachment = listOf(Color(0xFF263E4D),Color(0xFF1A2938))
    val text = Color(0xFFF1F3F7)
    val metadata = Color(0xFFA4ADBC)
    val reaction = Color(0xFF2B3249)
}

internal fun Modifier.lensMessageSurface(outgoing:Boolean, attachment:Boolean, highlight:Color? = null) = drawWithCache {
    val shape = LensMessageShape(outgoing,attachment)
    val path = (shape.createOutline(size,layoutDirection,this) as androidx.compose.ui.graphics.Outline.Generic).path
    val palette = if(highlight != null) listOf(highlight,highlight) else if(attachment) LensMessageMaterial.attachment
                  else if(outgoing) LensMessageMaterial.outgoing else LensMessageMaterial.incoming
    val dx = if(outgoing && !attachment) -.819152f else .819152f
    val dy = .573576f
    val length = size.width * kotlin.math.abs(dx) + size.height * dy
    val start = androidx.compose.ui.geometry.Offset(size.width/2-dx*length/2,size.height/2-dy*length/2)
    val end = androidx.compose.ui.geometry.Offset(size.width/2+dx*length/2,size.height/2+dy*length/2)
    val fill = if(palette.size == 3) Brush.linearGradient(0f to palette[0],.65f to palette[1],1f to palette[2],start=start,end=end)
               else Brush.linearGradient(palette,start=start,end=end)
    val border = if(outgoing && !attachment) Color(0xFF716381) else Color(0xFF4B6676)
    onDrawBehind {
        drawPath(path,fill)
        drawPath(path,border,style=Stroke(1.dp.toPx()))
    }
}
