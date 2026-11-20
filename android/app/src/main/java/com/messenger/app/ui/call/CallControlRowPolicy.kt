package com.messenger.app.ui.call

/** B-067: only usable actions participate in a centred row. No Android dependencies. */
object CallControlRowPolicy {
    enum class Control(val tag: String) {
        VIDEO("video"), CAMERA("camera"), MUTE("mute"), END("end"), OUTPUT("output"),
    }

    fun audio(videoAvailable: Boolean, live: Boolean, ended: Boolean): List<Control> = buildList {
        if (ended) return@buildList
        if (videoAvailable) add(Control.VIDEO)
        if (live) add(Control.MUTE)
        add(Control.END)
        if (live) add(Control.OUTPUT)
    }

    fun videoMedia(live: Boolean, ended: Boolean): List<Control> =
        if (live && !ended) listOf(Control.CAMERA, Control.MUTE) else emptyList()

    fun videoCall(live: Boolean, ended: Boolean): List<Control> = buildList {
        if (ended) return@buildList
        add(Control.END)
        if (live) add(Control.OUTPUT)
    }

    fun video(live: Boolean, ended: Boolean): List<Control> =
        videoMedia(live, ended) + videoCall(live, ended)

    data class Position(val x: Int, val y: Int)
    data class Geometry(val positions: List<Position>, val height: Int)

    /** Integer pixels match native rounding. Each wrapped row is centred independently. */
    fun geometry(count: Int, width: Int, size: Int, spacing: Int, rowGap: Int, maxPerRow: Int = 4): Geometry {
        require(count >= 0 && width >= 0 && size > 0 && spacing >= 0 && rowGap >= 0 && maxPerRow > 0)
        if (count == 0) return Geometry(emptyList(), 0)
        val capacity = ((width + spacing) / (size + spacing)).coerceIn(1, maxPerRow)
        val positions = List(count) { index ->
            val row = index / capacity
            val rowCount = minOf(capacity, count - row * capacity)
            val rowWidth = rowCount * size + (rowCount - 1) * spacing
            Position((width - rowWidth) / 2 + (index % capacity) * (size + spacing), row * (size + rowGap))
        }
        return Geometry(positions, positions.last().y + size)
    }

    data class Motion(val fadeMillis: Int, val initialScale: Float, val move: Boolean)

    fun motion(reduced: Boolean): Motion = if (reduced) Motion(150, 1f, false) else Motion(180, .8f, true)
}
