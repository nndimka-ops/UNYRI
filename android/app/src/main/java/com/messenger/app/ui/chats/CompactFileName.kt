package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.data.message.MessageTextPolicy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import java.text.BreakIterator
import java.util.Locale

/** One natural-height line. The parent's click/long-click remains the only action. */
@Composable
internal fun CompactFileName(name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val presentation = remember(name) { boundedFileNamePresentation(name) }
    val safeName = if (presentation.kind == MessageTextPolicy.PresentationKind.TEXT) presentation.text
        else stringResource(R.string.file_preview)
    BoxWithConstraints(modifier) {
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Upload progress is deliberately not a key. Re-measure only when text or its geometry changes.
        val visibleName = remember(safeName, style, availableWidth, density.density, density.fontScale, direction, measurer) {
            compactFileName(safeName, availableWidth) { candidate ->
                measurer.measure(candidate, style = style, softWrap = false, maxLines = 1,
                    overflow = TextOverflow.Clip).size.width
            }
        }
        Text(visibleName, style = style, color = color, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = safeName })
    }
}

/** Preserve a small complete suffix while bounding pathological metadata before layout. */
internal fun boundedFileNamePresentation(name: String): MessageTextPolicy.PresentationResult {
    val result = MessageTextPolicy.presentationResult(name, inline = true)
    if (result.kind != MessageTextPolicy.PresentationKind.TEXT || !result.truncated) return result
    // Read at most 64 suffix scalars; do not walk graphemes or measure the raw name.
    val suffixScalars = if (name.length >= 128) 64 else minOf(64, name.codePointCount(0, name.length))
    val suffix = name.substring(name.offsetByCodePoints(name.length, -suffixScalars))
    val extension = splitCompactFileName("x" + suffix).extension
    val checkedExtension = MessageTextPolicy.presentationResult(extension, inline = true)
    if (extension.isEmpty() || checkedExtension.kind != MessageTextPolicy.PresentationKind.TEXT || checkedExtension.truncated) return result
    val combined = MessageTextPolicy.presentationResult(result.text + extension, inline = true)
    return result.copy(text = if (combined.truncated) "…" + extension else combined.text)
}

internal data class FileNameParts(val stem: String, val extension: String)

/** Dotfiles do not lose their leading dot; common tar compounds retain their complete suffix. */
internal fun splitCompactFileName(name: String): FileNameParts {
    val lower = name.lowercase(Locale.ROOT)
    val compound = COMPOUND_FILE_EXTENSIONS.firstOrNull { lower.endsWith(it) && name.length > it.length }
    val extensionStart = compound?.let { name.length - it.length }
        ?: name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex }
    return if (extensionStart == null) FileNameParts(name, "")
    else FileNameParts(name.substring(0, extensionStart), name.substring(extensionStart))
}

/**
 * Measured middle elision without changing the font. The extension is never shortened when
 * even "…<extension>" fits. An extremely narrow cell can show just the complete extension.
 * If that cannot fit either, retain a balanced portion of the whole name; a zero-width slot
 * shows no visual text but still exposes the bounded accessible name.
 */
internal fun compactFileName(name: String, maxWidthPx: Int, measureWidth: (String) -> Int): String {
    if (maxWidthPx <= 0 || name.isEmpty()) return ""
    // Bound old/imported metadata before the first native measure or grapheme walk.
    val displayName = boundedFileNamePresentation(name).text.replace('\r', ' ').replace('\n', ' ')
    val widths = HashMap<String, Int>(8)
    fun fits(value: String) = widths.getOrPut(value) { measureWidth(value).coerceAtLeast(0) } <= maxWidthPx
    if (fits(displayName)) return displayName
    val parts = splitCompactFileName(displayName)
    val preserveExtension = parts.extension.isNotEmpty() && fits(FILE_NAME_ELLIPSIS + parts.extension)
    if (!preserveExtension && parts.extension.isNotEmpty() && fits(parts.extension)) return parts.extension
    if (!fits(FILE_NAME_ELLIPSIS)) return ""
    val clusters = fileNameGraphemes(if (preserveExtension) parts.stem else displayName)
    val suffix = if (preserveExtension) parts.extension else ""
    var best = FILE_NAME_ELLIPSIS + suffix
    var low = 0
    var high = clusters.size - 1
    while (low <= high) {
        val kept = (low + high) ushr 1
        val front = (kept + 1) / 2
        val back = kept / 2
        val candidate = buildString {
            for (index in 0 until front) append(clusters[index])
            append(FILE_NAME_ELLIPSIS)
            for (index in clusters.size - back until clusters.size) append(clusters[index])
            append(suffix)
        }
        if (fits(candidate)) {
            best = candidate
            low = kept + 1
        } else high = kept - 1
    }
    return best
}

/** Character boundaries plus joining rules missing from older Android/JDK BreakIterator tables. */
internal fun fileNameGraphemes(name: String): List<String> {
    if (name.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(name) }
    val result = ArrayList<String>()
    var previous = iterator.first()
    var next = iterator.next()
    var regionalCount = 0
    while (next != BreakIterator.DONE) {
        val piece = name.substring(previous, next)
        val first = Character.codePointAt(piece, 0)
        val lastPiece = result.lastOrNull()
        val previousCodePoint = lastPiece?.let { Character.codePointBefore(it, it.length) }
        val join = lastPiece != null && (isFileNameExtender(first) || first == 0x200D || previousCodePoint == 0x200D ||
            (isRegionalIndicator(first) && regionalCount % 2 == 1))
        if (join) result[result.lastIndex] = lastPiece + piece else result.add(piece)
        regionalCount = when {
            isRegionalIndicator(first) -> regionalCount + piece.codePointCount(0, piece.length)
            isFileNameExtender(first) -> regionalCount
            else -> 0
        }
        previous = next
        next = iterator.next()
    }
    return result
}

private fun isFileNameExtender(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF || codePoint in 0x1F3FB..0x1F3FF
}

private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF
private const val FILE_NAME_ELLIPSIS = "…"
private val COMPOUND_FILE_EXTENSIONS = listOf(".tar.bz2", ".tar.lzma", ".tar.zst", ".tar.gz", ".tar.xz", ".tar.lz", ".tar.br")
