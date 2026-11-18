package com.messenger.app.data.local

internal data class VideoFramePlan(val width: Int, val height: Int, val rotation: Int, val targetUs: Long) {
    val rgbaBytes: Int get() = width * height * 4
    companion object {
        fun create(width: Int, height: Int, rotation: Int, edge: Int, targetUs: Long): VideoFramePlan {
            require(width > 0 && height > 0 && width.toLong() * height <= 150_000_000L)
            val turn = ((rotation % 360) + 360) % 360
            require(turn in listOf(0, 90, 180, 270))
            val w = if (turn == 90 || turn == 270) height else width
            val h = if (turn == 90 || turn == 270) width else height
            val scale = minOf(1.0, edge.coerceIn(1, 768).toDouble() / maxOf(w, h))
            return VideoFramePlan((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), turn, targetUs.coerceAtLeast(0))
        }
    }
}
