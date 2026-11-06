package com.messenger.app.data.call

/** Limits, not targets: WebRTC remains free to reduce the rate on a congested link. */
enum class CallQuality(val wire: String) {
    AUTO("auto"), ECONOMY("economy"), HIGH("high");
    companion object { fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: AUTO }
    val video: CallVideoLimits get() = when (this) {
        AUTO -> CallVideoLimits(1280, 720, 30, 1_500_000)
        ECONOMY -> CallVideoLimits(640, 360, 15, 300_000)
        HIGH -> CallVideoLimits(1920, 1080, 30, 2_500_000)
    }
    val audioBitrate: Int get() = when (this) { AUTO -> 32_000; ECONOMY -> 16_000; HIGH -> 48_000 }
}
data class CallVideoLimits(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

object CallQualityPreferences {
    const val STORE = "call-quality"
    const val VIDEO = "video"
    const val AUDIO = "audio"
}
