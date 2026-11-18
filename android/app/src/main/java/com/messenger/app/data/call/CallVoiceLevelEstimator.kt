package com.messenger.app.data.call

import kotlin.math.*

data class CallAudioEnergy(val energy: Double, val duration: Double, val bytesReceived: Double)

/** Receiver energy deltas, same -55dB gate / 40dB range and attack/release as iOS. */
class CallVoiceLevelEstimator {
    private var previous: CallAudioEnergy? = null
    private var level = 0f
    fun update(sample: CallAudioEnergy?): Float {
        if (sample == null || listOf(sample.energy, sample.duration, sample.bytesReceived).any { !it.isFinite() }) {
            previous = null; level = 0f; return 0f
        }
        val before = previous
        previous = sample
        if (before == null) { level = 0f; return 0f }
        val energy = sample.energy - before.energy
        val duration = sample.duration - before.duration
        if (energy < 0 || duration < 0 || sample.bytesReceived <= before.bytesReceived) { level = 0f; return 0f }
        val rms = sqrt(max(0.0,energy) / max(duration,.001))
        val target = ((20*log10(max(rms,.00001)) + 55) / 40).coerceIn(0.0,1.0).toFloat()
        level += (target-level) * if(target>level) .5f else .12f
        if(level<.01f) level=0f
        return level
    }
}
