package com.messenger.app.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaViewportTest {
    @Test fun widePhotoCannotBeDraggedIntoVerticalBlackSpace() {
        assertEquals(540f to 0f, mediaPanBounds(1080f, 1920f, 4000f, 2000f, 2f))
    }
    @Test fun tallPhotoCannotDisappearHorizontally() {
        assertEquals(0f to 960f, mediaPanBounds(1080f, 1920f, 1000f, 4000f, 2f))
    }
    @Test fun unzoomedAndInvalidImagesNeverPan() {
        assertEquals(0f to 0f, mediaPanBounds(1080f, 1920f, 1000f, 4000f, 1f))
        assertEquals(0f to 0f, mediaPanBounds(1080f, 1920f, 0f, 0f, 2f))
        assertEquals(0f to 0f, mediaPanBounds(Float.NaN, 1920f, 1000f, 4000f, 2f))
    }
}
