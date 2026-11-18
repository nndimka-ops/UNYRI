package com.messenger.app.ui.chats

/** The same event gates as the iOS chat-list motion contracts. No clocks or UI here. */
internal enum class ChatOpenStyle { Instant, Fade, Settle, Zoom }

internal object ChatOpenTransitionPolicy {
    // Keep the reviewed iOS kill switch: system dismissal gestures must pass our back gate.
    const val zoomEnabled = false
    fun style(viaRow: Boolean, sharedTransitionAvailable: Boolean = true,
        reduceMotion: Boolean, lowPower: Boolean, zoom: Boolean = zoomEnabled): ChatOpenStyle = when {
        !viaRow -> ChatOpenStyle.Instant
        reduceMotion || lowPower -> ChatOpenStyle.Fade
        sharedTransitionAvailable && zoom -> ChatOpenStyle.Zoom
        else -> ChatOpenStyle.Settle
    }
}

internal object ChatListLiftPolicy {
    data class Row(val id: String, val activity: Long, val unread: Int, val muted: Boolean)
    data class Decision(val animateOrder: Boolean = false, val lifted: String? = null, val swept: String? = null)
    const val maxMoves = 3

    fun decide(old: List<Row>, new: List<Row>, live: Boolean, sweepAllowed: (String) -> Boolean): Decision {
        if (!live || old.isEmpty() || new.isEmpty() || kotlin.math.abs(new.size - old.size) > maxMoves) return Decision()
        val oldIndices = mutableMapOf<String, Int>()
        val oldRows = mutableMapOf<String, Row>()
        old.forEachIndexed { index, row ->
            if (row.id !in oldIndices) { oldIndices[row.id] = index; oldRows[row.id] = row }
        }
        val bumped = new.withIndex().filter { (index, row) ->
            oldRows[row.id]?.let { row.activity > it.activity } ?: (index < maxMoves)
        }
        val moved = bumped.filter { (index, row) -> oldIndices[row.id]?.let { index < it } ?: true }
        if (moved.size > maxMoves) return Decision()
        val fresh = bumped.filter { (_, row) -> !row.muted && row.unread > (oldRows[row.id]?.unread ?: 0) }
        return Decision(animateOrder = moved.isNotEmpty(), lifted = moved.singleOrNull()?.value?.id,
            swept = fresh.singleOrNull()?.value?.id?.takeIf(sweepAllowed))
    }
}

internal object ChatSwipePolicy {
    const val slot = 68f
    const val padding = 8f
    const val commitExtra = 88f
    const val hysteresis = 24f
    const val elastic = .55f
    const val magnet = 14f
    const val flick = 350f
    fun openWidth(count: Int): Float = count * slot + padding
    fun displayed(raw: Float, open: Float, armed: Boolean): Float = when {
        raw <= 0 -> 0f
        raw <= open -> raw
        else -> open + (raw - open) * elastic + if (armed) magnet else 0f
    }
    fun armed(raw: Float, open: Float, wasArmed: Boolean): Boolean =
        raw >= open + commitExtra || (wasArmed && raw >= open + commitExtra - hysteresis)
    enum class Release { Close, Open, Commit }
    /** Positive velocity opens, in density-independent units per second. */
    fun release(raw: Float, velocity: Float, open: Float, armed: Boolean): Release = when {
        armed -> Release.Commit
        velocity > flick && raw > 12 -> Release.Open
        velocity < -flick -> Release.Close
        raw >= open * .5f -> Release.Open
        else -> Release.Close
    }
}
