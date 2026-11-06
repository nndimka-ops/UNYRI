package com.messenger.app.ui.chats

import kotlin.math.abs

internal data class AlbumTile(val index: Int, val x: Float, val y: Float, val width: Float, val height: Float)
internal data class AlbumMosaic(val width: Float, val height: Float, val tiles: List<AlbumTile>)

/** Presentation-only, bounded to ten inputs. Choose a compact justified
 * arrangement from the real aspect ratios. No image decoding or I/O here. */
internal fun albumMosaic(ratios: List<Float>, width: Float, gap: Float = 2f): AlbumMosaic {
    require(ratios.size in 1..10 && width.isFinite() && width >= 80f)
    val safe = ratios.map { if (it.isFinite() && it > 0) it.coerceIn(.6f, 2.1f) else 1f }
    if (safe.size == 1) return AlbumMosaic(width, (width / safe[0]).coerceIn(width * .5f, width * 1.2f),
        listOf(AlbumTile(0,0f,0f,width,(width / safe[0]).coerceIn(width * .5f, width * 1.2f))))
    if (safe.size == 2) {
        val h = width * 153f / 274f
        val split = (width-gap) * (safe[0]/safe.sum()).coerceIn(.35f,.65f)
        return AlbumMosaic(width,h,listOf(AlbumTile(0,0f,0f,split,h),AlbumTile(1,split+gap,0f,width-split-gap,h)))
    }
    if (safe.size == 3 && safe[0] < .8f) {
        val h = width * 1.05f;val left = (width-gap)*.51f;val right=width-left-gap;val half=(h-gap)/2
        return AlbumMosaic(width,h,listOf(AlbumTile(0,0f,0f,left,h),AlbumTile(1,left+gap,0f,right,half),AlbumTile(2,left+gap,half+gap,right,half)))
    }
    val candidates = mutableListOf<List<Int>>()
    fun partitions(left: Int, rows: List<Int>) {
        if (left == 0) { if (rows.size in 2..4) candidates += rows;return }
        if (rows.size == 4) return
        for (count in 1..minOf(3,left)) partitions(left-count,rows+count)
    }
    partitions(safe.size,emptyList())
    fun heights(rows: List<Int>): List<Float> { var offset=0;return rows.map { count ->
        val h=(width-gap*(count-1))/safe.subList(offset,offset+count).sum();offset+=count;h
    } }
    val target = width * when(safe.size) { 3->1.05f;4->1f;in 5..6->1.08f;else->1.3f }
    val rows = candidates.minBy { counts ->
        val hs=heights(counts)
        abs(hs.sum()+gap*(counts.size-1)-target) +
            hs.sumOf { maxOf(0f,width*.24f-it).toDouble() }.toFloat()*4f +
            counts.count { it==1 }*width*.07f + (hs.max()-hs.min())*.12f
    }
    val hs=heights(rows);val total=hs.sum()+gap*(rows.size-1)
    val scale = minOf(1f, width*1.5f/total)
    val tiles=mutableListOf<AlbumTile>();var index=0;var y=0f
    rows.forEachIndexed { row, count ->
        val h=hs[row]*scale;val sum=safe.subList(index,index+count).sum();var x=0f
        repeat(count) { column ->
            val w=if(column==count-1)width-x else (width-gap*(count-1))*safe[index]/sum
            tiles += AlbumTile(index,x,y,w,h);x+=w+gap;index++
        }
        y+=h+gap
    }
    return AlbumMosaic(width,y-gap,tiles)
}
