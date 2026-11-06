package com.messenger.app.conference
data class ConferenceSelection(val anchor: String?, val page: Int, val pin: String?)
enum class ConferenceGridMode { PAGES, SCROLL, FIT }

/** Shared by the actual native grid and pure tests; IDs are stable arrival-ordered remotes. */
object ConferencePresentationPolicy {
    /** Keep only the measured page plus its focused tile, never the whole room. */
    fun videoDemand(grid: Set<String>, focused: String?): Set<String> = if (focused == null) grid else grid + focused
    fun mirrorsPreview(local: Boolean, frontCamera: Boolean): Boolean = local && frontCamera
    /** Local playback choices never alter another participant's microphone/camera publication. */
    fun subscribe(audio: Boolean, screenVisible: Boolean, visible: Boolean, mutedForMe: Boolean, hiddenForMe: Boolean, hostBlocked: Boolean = false): Boolean =
        if (audio) !mutedForMe && !hostBlocked else screenVisible && visible && !hiddenForMe
    /** LiveKit's empty source list means unrestricted sources, not a microphone ban. */
    fun microphonePermissionBlocked(canPublish: Boolean, hasSourceRestriction: Boolean, allowsMicrophone: Boolean): Boolean =
        !canPublish || (hasSourceRestriction && !allowsMicrophone)
    /** A failed old toggle cannot resurrect capture after a block, even if already allowed again. */
    fun microphoneAfterFailure(previous: Boolean, blocked: Boolean, oldConsent: Long, currentConsent: Long): Boolean =
        previous && !blocked && oldConsent == currentConsent
    fun acceptsPresentation(owner: Long, currentOwner: Long, active: Boolean, sessionOwned: Boolean, minimized: Boolean): Boolean =
        owner == currentOwner && active && sessionOwned && !minimized
    /** A response started before/during an owner edit cannot overwrite that edit's result. */
    fun acceptsMetadata(startRevision: Long, currentRevision: Long, settingsBusy: Boolean): Boolean =
        startRevision == currentRevision && !settingsBusy
    /** Explicit display choice is independent of server capacity and never alters capture. */
    fun displayCount(requested: Int, fallback: Int): Int = if (requested == 0) fallback.coerceIn(1, 30) else requested.coerceIn(1, 30)
    fun fitColumns(count: Int, width: Float, height: Float): Int {
        if (count <= 1) return 1
        val ratio = (width / height.coerceAtLeast(1f)).coerceIn(.3f, 4f)
        return kotlin.math.ceil(kotlin.math.sqrt(count * ratio)).toInt().coerceIn(1, minOf(6, count))
    }
    /** Fill the last row locally, so choosing five really fits five before the next scroll block. */
    fun span(index: Int, blockSize: Int, columns: Int): Int =
        if (index % blockSize == blockSize - 1) columns - (blockSize - 1) % columns else 1
    fun visibleIds(areas: List<Pair<String, Float>>, limit: Int): Set<String> = areas.filter { it.second > 0f }
        .sortedByDescending { it.second }.take(limit.coerceIn(1, 30)).mapTo(linkedSetOf()) { it.first }
    fun rebase(old: List<String>, current: List<String>, anchor: String?, page: Int, pin: String?): ConferenceSelection {
        if (current.isEmpty()) return ConferenceSelection(null, 0, null)
        val resolved = anchor?.takeIf { it in current } ?: current[old.indexOf(anchor).coerceIn(0, current.lastIndex)]
        return ConferenceSelection(resolved, page.coerceAtLeast(0), pin?.takeIf { it in current })
    }
    fun pageCount(remoteCount: Int, size: Int): Int { require(size > 0); return maxOf(1, (remoteCount.coerceAtLeast(0) + size - 1) / size) }
    fun page(ids: List<String>, anchor: String?, previous: Int, size: Int): Int {
        val index = ids.indexOf(anchor)
        return (if (index >= 0) index / size else previous).coerceIn(0, pageCount(ids.size, size) - 1)
    }
    fun columns(count: Int, landscape: Boolean): Int = if (landscape) minOf(3, maxOf(1, count)) else if (count <= 2) 1 else 2
}
