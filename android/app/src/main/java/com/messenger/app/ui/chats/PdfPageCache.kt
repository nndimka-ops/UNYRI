package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A byte AND entry bounded LRU. Values may still be displayed: never recycle on eviction. */
internal class PdfWeightedCache<K, V>(private val maxBytes: Long, private val maxEntries: Int) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    var bytes: Long = 0; private set
    val size: Int get() = entries.size
    init { require(maxBytes > 0 && maxEntries > 0) }
    operator fun get(key: K): V? = entries[key]?.value
    fun put(key: K, value: V, cost: Long) {
        require(cost >= 0)
        entries.remove(key)?.let { bytes -= it.bytes }
        if (cost > maxBytes) return
        entries[key] = Entry(value, cost); bytes += cost
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes || entries.size > maxEntries) {
            bytes -= iterator.next().value.bytes; iterator.remove()
        }
    }
    fun clear() { entries.clear(); bytes = 0 }
}

internal data class PdfPageSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    fun rasterHeight(widthPx: Int): Int = ceil(widthPx.toDouble() * height / width)
        .coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
}

internal data class PdfTileKey(val page: Int, val width: Int, val x: Int, val y: Int)
internal data class PdfTileBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal const val PDF_TILE_EDGE = 512
internal const val PDF_BITMAP_BUDGET = 24L * 1024 * 1024
internal const val PDF_MAX_LIVE_TILES = 512

/** The sole strong owner of raster values, including the allocation in flight.
 * Consumers draw by key in a scoped callback; no bitmap is returned to UI state.
 * Removing a window never recycles a published value (RenderThread may still use it).
 */
internal class PdfTileResidency<K, V>(private val maxBytes: Long, private val maxEntries: Int) {
    init { require(maxBytes > 0 && maxEntries > 0) }
    class Reservation<K> internal constructor(val key: K, val bytes: Long)
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>()
    private var wanted = emptySet<K>()
    private var allocation: Reservation<K>? = null
    private var residentBytes = 0L
    var peakBytes = 0L; private set
    @Synchronized fun bytes(): Long = residentBytes + (allocation?.bytes ?: 0)
    @Synchronized fun keys(): Set<K> = entries.keys.toSet()
    @Synchronized fun setWindow(keys: Set<K>) {
        wanted = keys
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in wanted) { residentBytes -= entry.value.bytes; iterator.remove() }
        }
    }
    @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)
    @Synchronized fun reserve(key: K, cost: Long): Reservation<K>? {
        require(cost > 0)
        if (key !in wanted || key in entries || allocation != null || entries.size >= maxEntries || cost > maxBytes - residentBytes) return null
        return Reservation(key, cost).also { allocation = it; peakBytes = maxOf(peakBytes, bytes()) }
    }
    @Synchronized fun publish(reservation: Reservation<K>, value: V, cost: Long): Boolean {
        if (allocation !== reservation) return false
        allocation = null
        if (cost <= 0 || cost > reservation.bytes || reservation.key !in wanted) return false
        entries[reservation.key] = Entry(value, cost); residentBytes += cost
        return true
    }
    @Synchronized fun cancel(reservation: Reservation<K>) { if (allocation === reservation) allocation = null }
    @Synchronized fun draw(keys: List<K>, block: (K, V) -> Unit) {
        keys.forEach { key -> entries[key]?.let { block(key, it.value) } }
    }
    @Synchronized fun clear() { wanted = emptySet(); entries.clear(); residentBytes = 0; allocation = null }
}

internal data class PdfPageViewport(val index: Int, val size: PdfPageSize, val top: Int)
internal data class PdfRasterGeometry(val height: Float, val factor: Float, val x: Float)
internal fun pdfRasterGeometry(width: Int, scale: Float, rasterWidth: Int, size: PdfPageSize, pan: Float): PdfRasterGeometry {
    val displayWidth = minOf(width * scale, 200000f * size.width / size.height)
    val panLimit = ((displayWidth - width) / 2).coerceAtLeast(0f)
    return PdfRasterGeometry(displayWidth.toDouble().times(size.height).div(size.width).toFloat(),
        displayWidth / rasterWidth, (width - displayWidth) / 2 + pan.coerceIn(-panLimit, panLimit))
}
internal data class PdfTilePlan(val width: Int, val keys: List<PdfTileKey>, val bytes: Long = 0, val limited: Boolean = false)

/** A whole-viewport budget, not one budget per page. Zoom-out samples at the new
 * display scale immediately, even if the finger remains down. Large viewports
 * are resampled until every visible tile fits the same resident/allocation cap.
 */
internal fun pdfTilePlan(pages: List<PdfPageViewport>, width: Int, start: Int, end: Int, scale: Float,
    targetRasterScale: Float, pan: Float, budget: Long = PDF_BITMAP_BUDGET, maxTiles: Int = PDF_MAX_LIVE_TILES): PdfTilePlan {
    require(budget > 0 && maxTiles > 0)
    if (width <= 0 || end <= start || !listOf(scale, targetRasterScale, pan).all { it.isFinite() } || scale <= 0 || targetRasterScale <= 0)
        return PdfTilePlan(1, emptyList())
    var rasterWidth = (width * minOf(scale, targetRasterScale)).roundToInt().coerceIn(1, 32768)
    while (true) {
        val keys = ArrayList<PdfTileKey>()
        var bytes = 0L; var fits = true
        for (page in pages) {
            val geometry = pdfRasterGeometry(width, scale, rasterWidth, page.size, pan)
            val candidates = pdfVisibleTiles(page.index, rasterWidth, page.size.rasterHeight(rasterWidth),
                -geometry.x / geometry.factor, (start.toLong() - page.top).toFloat() / geometry.factor,
                (width - geometry.x) / geometry.factor, (end.toLong() - page.top).toFloat() / geometry.factor, maxTiles + 1)
            for (key in candidates) {
                val bounds = pdfTileBounds(key, page.size)
                val cost = bounds.width.toLong() * bounds.height * 4
                if (keys.size >= maxTiles || cost > budget - bytes) { fits = false; break }
                keys.add(key); bytes += cost
            }
            if (!fits) break
        }
        if (fits) return PdfTilePlan(rasterWidth, keys, bytes)
        if (rasterWidth == 1) return PdfTilePlan(1, emptyList(), limited = true)
        rasterWidth = (rasterWidth / 2).coerceAtLeast(1)
    }
}

internal fun pdfTileBounds(key: PdfTileKey, size: PdfPageSize): PdfTileBounds {
    require(key.width > 0 && key.x >= 0 && key.y >= 0)
    val height = size.rasterHeight(key.width)
    val left = key.x.toLong() * PDF_TILE_EDGE; val top = key.y.toLong() * PDF_TILE_EDGE
    require(left < key.width && top < height)
    return PdfTileBounds(left.toInt(), top.toInt(), minOf(PDF_TILE_EDGE, key.width - left.toInt()),
        minOf(PDF_TILE_EDGE, height - top.toInt()))
}

/** Only the visible rectangle, with one pixel of seam allowance; independent of document length. */
internal fun pdfVisibleTiles(page: Int, width: Int, height: Int, left: Float, top: Float,
    right: Float, bottom: Float, maxTiles: Int = PDF_MAX_LIVE_TILES + 1): List<PdfTileKey> {
    if (width <= 0 || height <= 0 || !listOf(left, top, right, bottom).all { it.isFinite() } ||
        right <= 0 || bottom <= 0 || left >= width || top >= height || right <= left || bottom <= top) return emptyList()
    val x0 = floor((left - 1).coerceAtLeast(0f) / PDF_TILE_EDGE).toInt()
    val y0 = floor((top - 1).coerceAtLeast(0f) / PDF_TILE_EDGE).toInt()
    val x1 = floor((right + 1).coerceAtMost(width - 1f) / PDF_TILE_EDGE).toInt()
    val y1 = floor((bottom + 1).coerceAtMost(height - 1f) / PDF_TILE_EDGE).toInt()
    return buildList { for (y in y0..y1) for (x in x0..x1) {
        if (size >= maxTiles) return@buildList
        add(PdfTileKey(page, width, x, y))
    } }
}

internal enum class PdfFailure { Locked, Invalid, Memory }
internal fun pdfFailure(error: Throwable): PdfFailure = when (error) {
    is SecurityException -> PdfFailure.Locked
    is OutOfMemoryError -> PdfFailure.Memory
    else -> PdfFailure.Invalid
}

/**
 * One renderer/descriptor per reader; all native work serialized off the UI thread.
 * No full-document scan, disk raster cache, polling, or coroutine per document page.
 * Tiles keep native-resolution text at zoom without allocating a whole enlarged page.
 */
internal class PdfPageCache private constructor(private val renderer: PdfRenderer,
    val pageCount: Int, val firstSize: PdfPageSize) {
    private val mutex = Mutex()
    private var closed = false
    private val tiles = PdfTileResidency<PdfTileKey, Bitmap>(PDF_BITMAP_BUDGET, PDF_MAX_LIVE_TILES)
    private val sizes = PdfWeightedCache<Int, PdfPageSize>(4096, 128)
    private val texts = PdfWeightedCache<Int, String>(256L * 1024, 16)

    suspend fun size(page: Int): PdfPageSize = withContext(Dispatchers.IO) { mutex.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        sizes[page] ?: renderer.openPage(page).use { PdfPageSize(it.width, it.height) }
            .also { sizes.put(page, it, 16) }
    } }

    fun setVisibleTiles(keys: List<PdfTileKey>) = tiles.setWindow(keys.toSet())
    fun residentKeys(): Set<PdfTileKey> = tiles.keys()
    fun rasterBytes(): Long = tiles.bytes()
    fun drawTiles(keys: List<PdfTileKey>, draw: (PdfTileKey, Bitmap) -> Unit) = tiles.draw(keys, draw)

    suspend fun render(key: PdfTileKey): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        if (tiles.contains(key)) return@withLock true
        renderer.openPage(key.page).use { page ->
            val bounds = pdfTileBounds(key, PdfPageSize(page.width, page.height))
            val reservation = tiles.reserve(key, bounds.width.toLong() * bounds.height * 4) ?: return@withLock false
            var bitmap: Bitmap? = null
            var published = false
            try {
                bitmap = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val scale = key.width.toFloat() / page.width
                val matrix = Matrix().apply { setScale(scale, scale); postTranslate(-bounds.left.toFloat(), -bounds.top.toFloat()) }
                page.render(bitmap, Rect(0, 0, bounds.width, bounds.height), matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                currentCoroutineContext().ensureActive()
                published = tiles.publish(reservation, bitmap, bitmap.allocationByteCount.toLong())
                published
            } finally {
                if (!published) bitmap?.recycle()
                tiles.cancel(reservation)
            }
        }
    } }

    suspend fun text(page: Int): String = withContext(Dispatchers.IO) { mutex.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        if (Build.VERSION.SDK_INT < 35) return@withLock ""
        texts[page] ?: renderer.openPage(page).use { extractText(it) }
            .also { texts.put(page, it, it.length * 2L) }
    } }

    // PdfRenderer text extraction is a platform API starting at Android 15.
    @RequiresApi(35) private fun extractText(page: PdfRenderer.Page): String = buildString {
        for (content in page.textContents) {
            if (length >= 32768) break
            if (isNotEmpty()) append('\n')
            append(content.text.take(32768 - length))
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock {
        if (!closed) { closed = true; tiles.clear(); sizes.clear(); texts.clear(); renderer.close() }
    } }

    companion object {
        suspend fun open(file: File): PdfPageCache = withContext(Dispatchers.IO) {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
            try {
                require(renderer.pageCount > 0)
                val first = renderer.openPage(0).use { PdfPageSize(it.width, it.height) }
                currentCoroutineContext().ensureActive()
                PdfPageCache(renderer, renderer.pageCount, first)
            } catch (error: Throwable) { renderer.close(); throw error }
        }
    }
}
