package com.messenger.app.data.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ChatPreviewInstrumentedTest {
    @Test fun detailedThumbnailFitsExistingWireBudget() {
        val source = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(101, 88, 255))
            val simple = Base64.decode(ImageProcessor.createThumbnailBase64(source), Base64.NO_WRAP)
            val preview = BitmapFactory.decodeByteArray(simple, 0, simple.size)
            assertEquals(512, preview.width)
            assertTrue(simple.size <= 16 * 1024)
            preview.recycle()
            val random = Random(17)
            val pixels = IntArray(source.width * source.height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val noisy = Base64.decode(ImageProcessor.createThumbnailBase64(source), Base64.NO_WRAP)
            assertTrue(noisy.isNotEmpty() && noisy.size <= 16 * 1024)
            val noisyPreview = BitmapFactory.decodeByteArray(noisy, 0, noisy.size)
            assertNotNull(noisyPreview)
            assertTrue(noisyPreview.width <= 512)
            noisyPreview.recycle()
            val dense = Bitmap.createBitmap(pixels, 0, 256, 256, 256, Bitmap.Config.ARGB_8888)
            try {
                val denseBytes = Base64.decode(ImageProcessor.createThumbnailBase64(dense), Base64.NO_WRAP)
                assertTrue(denseBytes.isNotEmpty() && denseBytes.size <= 16 * 1024)
            } finally { dense.recycle() }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun localPreviewKeepsRequestedDetailAndAppliesExifWithoutChangingOriginal() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-size-exif-test.jpg")
        val source = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.GREEN)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = file.readBytes()
            val preview = ImageProcessor.chatPreview(file, 768)!!
            assertEquals(384, preview.width)
            assertEquals(768, preview.height)
            preview.recycle()
            assertArrayEquals(original, file.readBytes())
        } finally { source.recycle(); file.delete() }
    }

    @Test fun smallPhotoIsNeverUpscaled() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-small-test.png")
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val preview = ImageProcessor.chatPreview(file, 768)!!
            assertEquals(80, preview.width)
            assertEquals(40, preview.height)
            preview.recycle()
        } finally { source.recycle(); file.delete() }
    }
}
