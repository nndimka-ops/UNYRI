package com.messenger.app.data.directtransfer

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Wire-compatible with iOS DirectFileMediaPreview. Only these bounded JPEGs reach a peer decoder. */
@Serializable
data class DirectFileMediaPreview(
    val kind: String,
    val thumbnail: String,
    val width: Int,
    val height: Int,
    @SerialName("duration_ms") val durationMs: Long? = null,
) {
    val valid: Boolean get() = kind in setOf("image", "video") && width in 1..MAX_PIXEL_EDGE && height in 1..MAX_PIXEL_EDGE &&
        (durationMs == null || kind == "video" && durationMs in 0..MAX_DURATION_MS) &&
        jpegData()?.let { jpegDimensions(it) == (width to height) } == true

    fun jpegData(): ByteArray? {
        if (thumbnail.isEmpty() || thumbnail.length > MAX_BASE64_BYTES) return null
        val bytes = runCatching { Base64.getDecoder().decode(thumbnail) }.getOrNull() ?: return null
        return bytes.takeIf { it.size <= MAX_BYTES && Base64.getEncoder().encodeToString(it) == thumbnail && jpegDimensions(it) != null }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024
        const val MAX_BASE64_BYTES = ((MAX_BYTES + 2) / 3) * 4
        const val MAX_PIXEL_EDGE = 512
        const val MAX_DURATION_MS = 31_536_000_000L

        /** Android's bitmap API exposes only the first image. Reject concatenated/MPO images before it.
         * Walk through entropy escaping and progressive scans to require exactly one JPEG codestream. */
        internal fun singleJpeg(data: ByteArray): Boolean {
            if (jpegDimensions(data) == null) return false
            fun byte(i: Int) = data[i].toInt() and 255
            var index = 2
            var entropy = false
            while (index < data.size) {
                if (entropy) {
                    while (index < data.size && byte(index) != 255) index++
                } else if (byte(index) != 255) return false
                if (index >= data.size) return false
                while (index < data.size && byte(index) == 255) index++
                if (index >= data.size) return false
                val marker = byte(index++)
                if (entropy && (marker == 0 || marker in 208..215)) continue
                if (marker == 217) return index == data.size
                if (marker in setOf(0, 216) || marker in 208..215 || index + 1 >= data.size) return false
                if (marker == 1) continue
                val length = byte(index) * 256 + byte(index + 1)
                if (length < 2 || length > data.size - index) return false
                index += length
                entropy = marker == 218
            }
            return false
        }

        /** Match the iOS marker walk, including SOF precision, component count and SOS rules. */
        fun jpegDimensions(data: ByteArray): Pair<Int, Int>? {
            fun byte(index: Int) = data[index].toInt() and 255
            if (data.size !in 16..MAX_BYTES || byte(0) != 255 || byte(1) != 216 ||
                byte(data.size - 2) != 255 || byte(data.size - 1) != 217) return null
            var index = 2
            var dimensions: Pair<Int, Int>? = null
            while (index < data.size - 2) {
                if (byte(index) != 255) return null
                while (index < data.size && byte(index) == 255) index++
                if (index >= data.size) return null
                val marker = byte(index++)
                if (marker in setOf(0, 216, 217, 1) || marker in 208..215 || index + 1 >= data.size) return null
                val length = byte(index) * 256 + byte(index + 1)
                if (length < 2 || length > data.size - index) return null
                if (marker == 218) return dimensions
                if (marker in setOf(192, 193, 194)) {
                    if (dimensions != null || length < 11 || byte(index + 2) != 8) return null
                    val height = byte(index + 3) * 256 + byte(index + 4)
                    val width = byte(index + 5) * 256 + byte(index + 6)
                    val components = byte(index + 7)
                    if (components !in setOf(1, 3) || length != 8 + 3 * components || width !in 1..MAX_PIXEL_EDGE || height !in 1..MAX_PIXEL_EDGE) return null
                    dimensions = width to height
                } else if (marker in 192..207 && marker !in setOf(196, 200, 204)) return null
                index += length
            }
            return null
        }
    }
}

/** A malformed optional object must never discard the surrounding offer (including durable records). */
internal object OptionalDirectFileMediaPreviewSerializer : KSerializer<DirectFileMediaPreview?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): DirectFileMediaPreview? {
        val input = decoder as JsonDecoder
        val element = input.decodeJsonElement()
        return runCatching {
            // Swift Codable rejects quoted integers; kotlinx.serialization alone accepts them.
            val fields = element.jsonObject
            require(fields["kind"]?.jsonPrimitive?.isString == true && fields["thumbnail"]?.jsonPrimitive?.isString == true)
            for (key in listOf("width", "height")) {
                val number = fields.getValue(key).jsonPrimitive
                require(!number.isString && number.intOrNull != null)
            }
            fields["duration_ms"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let { require(!it.isString && it.longOrNull != null) }
            input.json.decodeFromJsonElement(DirectFileMediaPreview.serializer(), element)
        }
            .getOrNull()?.takeIf { it.valid }
    }
    override fun serialize(encoder: Encoder, value: DirectFileMediaPreview?) {
        val output = encoder as JsonEncoder
        output.encodeJsonElement(value?.takeIf { it.valid }?.let { output.json.encodeToJsonElement(it) } ?: JsonNull)
    }
}

/** Sender budgets and encoder attempts shared with JVM tests; no Android decoder needed. */
internal object DirectPreviewBounds {
    const val MAX_IMAGE_BYTES = 256L * 1024 * 1024
    fun imageAllowed(bytes: Long, width: Int, height: Int) = bytes in 1..MAX_IMAGE_BYTES &&
        width in 1..100_000 && height in 1..100_000 && width.toLong() * height <= 64_000_000L
    fun fit(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && edge > 0)
        val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
    fun encode(width: Int, height: Int, jpeg: (Int, Int, Int) -> ByteArray?): ByteArray? {
        for (edge in listOf(512, 384, 256, 128)) {
            val (w, h) = fit(width, height, edge)
            for (quality in listOf(82, 70, 55)) {
                val bytes = jpeg(w, h, quality) ?: continue
                if (bytes.size <= DirectFileMediaPreview.MAX_BYTES && DirectFileMediaPreview.jpegDimensions(bytes) == (w to h)) return bytes
            }
        }
        return null
    }
}
