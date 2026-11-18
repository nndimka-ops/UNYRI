package com.messenger.app.data.attachment

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.ChatImageCache
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ChatCacheReturnInstrumentedTest {
    @Test fun compareOriginalAndPreparedPreviewDecodeCost() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-decode-cost-qa").apply { mkdirs() }
        val source = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val original = File(root, "original.jpg")
        val prepared = File(root, "prepared.png")
        try {
            val random = kotlin.random.Random(8)
            val stripe = IntArray(4000)
            for (y in 0 until 3000) {
                for (x in stripe.indices) stripe[x] = Color.rgb((x / 16 + y / 12) % 256, random.nextInt(256), y % 256)
                source.setPixels(stripe, 0, 4000, 0, y, 4000, 1)
            }
            original.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val preview = ImageProcessor.chatPreview(original, 768)!!
            try { prepared.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { preview.recycle() }
            val lines = mutableListOf("iteration,original_ns,prepared_ns")
            repeat(32) { i ->
                fun measure(file: File): Long {
                    val start = System.nanoTime()
                    val bitmap = ImageProcessor.chatPreview(file, 768)!!
                    val elapsed = System.nanoTime() - start
                    bitmap.recycle()
                    return elapsed
                }
                var originalNs: Long; var preparedNs: Long
                if (i % 2 == 0) { originalNs = measure(original); preparedNs = measure(prepared) }
                else { preparedNs = measure(prepared); originalNs = measure(original) }
                if (i >= 2) lines.add("$i,$originalNs,$preparedNs")
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "photo-decode-cost.csv").writeText(lines.joinToString("\n"))
            println("Original bytes=${original.length()}, lossless prepared bytes=${prepared.length()}")
        } finally { source.recycle(); root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    @Test fun repeatedReturnAndConcurrentRequestsMeasureRealDecoding() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-return-qa").apply { mkdirs() }
        val decodes = AtomicInteger()
        val source = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        try {
            for (i in 0 until 60) {
                source.eraseColor(Color.rgb(i * 3, 100, 200))
                File(root, "$i.jpg").outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            ChatImageCache.clear()
            suspend fun load(i: Int) = ChatImageCache.load("photo768:${File(root, "$i.jpg").path}") {
                decodes.incrementAndGet()
                ImageProcessor.chatPreview(File(root, "$i.jpg"), 768)
            }
            coroutineScope { (0 until 12).map { async { load(0) } }.awaitAll() }
            assertEquals("Concurrent requests must share one decode", 1, decodes.get())
            repeat(20) { load(0) }
            assertEquals("Resident preview must not decode again", 1, decodes.get())
            for (i in 1 until 60) load(i)
            assertEquals(60, decodes.get())
            val forward = decodes.get()
            for (i in 59 downTo 0) load(i)
            val reverseMisses = decodes.get() - forward
            assertTrue("Long history exceeds the bounded cache", reverseMisses > 0)
            assertTrue("Most recent photos must survive", reverseMisses < 60)
            val beforeResidentReturn = decodes.get()
            for (i in 0 until 8) load(i)
            assertEquals("Recent reverse-scroll photos should remain resident", beforeResidentReturn, decodes.get())
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "chat-cache-return.txt").writeText(
                "photos=60 size=1600x1200 preview=768x576 budget=16MiB\nforward_decodes=$forward reverse_decodes=$reverseMisses recent_8_decodes=${decodes.get()-beforeResidentReturn}\nconcurrent_12_decodes=1 resident_20_decodes=0\n")
        } finally {
            source.recycle()
            ChatImageCache.clear()
            root.listFiles()?.forEach { it.delete() }
            root.delete()
        }
    }
}
