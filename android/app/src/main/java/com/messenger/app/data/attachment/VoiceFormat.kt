package com.messenger.app.data.attachment

/** Pure voice-message presentation helpers (Phase 5 §4/§21/§35) — no Android
 *  framework types, so they are directly unit-testable and mirror iOS's
 *  `VoiceFormat` exactly. All math is deterministic and cheap: the waveform is
 *  stable per attachment id and never regenerated per playback tick. */
object VoiceFormat {
    fun messageWidth(durationMs: Long): Float = 180f + 70f * kotlin.math.sqrt((durationMs.coerceIn(0, 120_000).toFloat() / 120_000f))


    /** Deterministic bar height in dp (6..<28). Stable across renders for the
     *  same seed; never exposes internal IDs and needs no network. */
    fun waveformHeight(seed: Int, bar: Int): Float {
        // Same shape as iOS's FNV-1a-flavoured mixing (wrapping arithmetic).
        val value = seed.toLong() + bar.toLong() * 31L
        val normalized = ((value % 100) + 100) % 100
        return 6f + 22f * (normalized / 100f)
    }

    /** Bar count for a given available waveform width: 2dp bar + 2dp gap per
     *  slot, clamped to the 20–32 signature range (Phase 5 §4). */
    fun barCount(availableWidthDp: Float): Int {
        val n = ((availableWidthDp + 2f) / 4f).toInt()
        return n.coerceIn(20, 32)
    }

    /** Played fraction clamped to 0..1; duration <= 0 is treated as 1ms so the
     *  math can never divide by zero. */
    fun playedFraction(positionMs: Long, durationMs: Long): Float {
        val total = durationMs.coerceAtLeast(1L)
        return (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Duration label: `m:ss`, or `h:mm:ss` once an hour is reached. Negative
     *  input clamps to zero (matches iOS). */
    fun durationText(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
