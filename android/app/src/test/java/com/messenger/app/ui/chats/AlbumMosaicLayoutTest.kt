package com.messenger.app.ui.chats

import kotlin.test.*

class AlbumMosaicLayoutTest {
    @Test fun everySizeAndAspectSetHasOneBoundedNonOverlappingMosaic() {
        for(count in 2..10) for(width in listOf(180f,231f,320f)) for(seed in 0..17) {
            val ratios=(0 until count).map {listOf(.4f,.7f,1f,1.33f,1.8f,3f)[(it*7+seed)%6]}
            val result=albumMosaic(ratios,width)
            assertEquals(count,result.tiles.size)
            assertEquals((0 until count).toList(),result.tiles.map {it.index})
            assertTrue(result.height>0&&result.height<=width*1.51f)
            result.tiles.forEach {tile->
                assertTrue(tile.width>20&&tile.height>20)
                assertTrue(tile.x>=0&&tile.y>=0&&tile.x+tile.width<=width+.01f&&tile.y+tile.height<=result.height+.01f)
            }
            for(a in result.tiles)for(b in result.tiles)if(a.index<b.index) {
                assertTrue(a.x+a.width<=b.x+.01f||b.x+b.width<=a.x+.01f||a.y+a.height<=b.y+.01f||b.y+b.height<=a.y+.01f,"overlap: $count $seed")
            }
        }
    }
    @Test fun twoMediaKeepTheApprovedCompactHeight() {
        val result=albumMosaic(listOf(1f,1f),231f)
        assertEquals(231f*153f/274f,result.height)
        assertEquals(114.5f,result.tiles.first().width)
    }
    @Test fun metadataFallbacksAreFiniteAndNeverRequireDecoding() {
        val result=albumMosaic(listOf(Float.NaN,Float.POSITIVE_INFINITY,0f),231f)
        assertTrue(result.tiles.all {it.width.isFinite()&&it.height.isFinite()})
    }
}
