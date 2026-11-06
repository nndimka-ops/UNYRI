package com.messenger.app.call

import android.media.AudioManager
import android.media.ToneGenerator
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Caller-side ringback (N.2R addendum §16-17): while this device is the
 *  CALLER and the call is [CallUiState.OutgoingRinging], play a lightweight
 *  supervisory ringback tone. This is purely local feedback that the server
 *  reports the callee as ringing — it never proves the callee is physically
 *  ringing, and it is never derived from RTP.
 *
 *  One authoritative ringback owner: it starts/loops ONLY on OutgoingRinging
 *  and stops on every other state (Accept/Reject/Busy/Cancel/Timeout/Failure),
 *  so it can never continue into Connected. */
class CallRingbackPlayer(
    private val callController: CallController,
    private val scope: CoroutineScope,
) {
    private var toneGenerator: ToneGenerator? = null
    private var loopJob: Job? = null
    private var playbackKey: Pair<String, com.messenger.app.data.call.AudioRouteKind>? = null

    fun start() {
        scope.launch {
            callController.state.collect { state ->
                val nextKey = playbackKey(state)
                if (nextKey != playbackKey) {
                    stopLooping()
                    playbackKey = nextKey
                }
                if (nextKey != null) ensureLooping() else stopLooping()
            }
        }
    }

    private fun ensureLooping() {
        if (loopJob != null) return
        loopJob = scope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).also { toneGenerator = it }
            } catch (e: RuntimeException) {
                // Audio hardware unavailable — never crash over ringback.
                null
            }
            while (isActive) {
                tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, TONE_DURATION_MS)
                delay(TONE_PERIOD_MS.toLong())
            }
        }
    }

    private fun stopLooping() {
        loopJob?.cancel()
        loopJob = null
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        /** 0..100 volume for the ringback tone (kept below max so it never
         *  overpowers an active notification ring). */
        const val RINGBACK_VOLUME = 70

        /** The supervisory ringback tone cadence. */
        const val TONE_DURATION_MS = 1500
        const val TONE_PERIOD_MS = 3000

        /** Pure ringback decision (testable): ringback plays ONLY while this
         *  device is the caller and the call is still ringing. Every other
         *  state (Accept/Reject/Busy/Cancel/Timeout/Failure/Connected) stops
         *  it — it can never continue into a connected call. */
        fun shouldPlay(state: CallUiState?): Boolean = state is CallUiState.OutgoingRinging

        fun playbackKey(state: CallUiState?): Pair<String, com.messenger.app.data.call.AudioRouteKind>? =
            (state as? CallUiState.OutgoingRinging)?.let { it.call.callId to it.audioRoute }
    }
}
