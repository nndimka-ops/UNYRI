package com.messenger.app.ui.call

import com.messenger.app.data.call.AudioRouteKind

/** What the audio output button draws. The phone's own receiver is a phone, never headphones. */
enum class CallOutputGlyph { SPEAKER, SPEAKER_OFF, PHONE, HEADPHONES, BLUETOOTH }

/**
 * [toggleTo] non-null: a tap switches straight to that route (no accessory connected, iOS-style
 * speaker toggle). Null: a tap opens the route picker (a headset or Bluetooth device is present).
 */
data class CallOutputPresentation(val glyph: CallOutputGlyph, val highlighted: Boolean, val toggleTo: AudioRouteKind?)

/**
 * Owner, 14 Nov 2026: "кнопки на андроид что-то за наушники, если они не подключены". The receiver
 * was drawn with a headphones glyph and called "Наушник". Rules (same as iOS, call screen decisions):
 * - no accessory: the button is a speaker toggle. Audio call: dark speaker on the receiver, white
 *   speaker on the loudspeaker. Video call: dark speaker on the loudspeaker, white slashed speaker
 *   on the receiver. A tap toggles. A phone without a receiver (tablet) shows a dark speaker and
 *   has nothing to toggle.
 * - a headset or Bluetooth device present: the glyph shows the current device, headsets are never
 *   highlighted, and a tap opens the picker.
 */
object CallAudioOutputPresentation {
    private val accessories = setOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED_HEADSET)

    fun present(current: AudioRouteKind, video: Boolean, available: Collection<AudioRouteKind>, hasEarpiece: Boolean): CallOutputPresentation {
        if (available.none { it in accessories }) {
            if (!hasEarpiece) return CallOutputPresentation(CallOutputGlyph.SPEAKER, highlighted = false, toggleTo = null)
            val onSpeaker = current == AudioRouteKind.SPEAKER
            val toggle = if (onSpeaker) AudioRouteKind.EARPIECE else AudioRouteKind.SPEAKER
            return if (video) CallOutputPresentation(if (onSpeaker) CallOutputGlyph.SPEAKER else CallOutputGlyph.SPEAKER_OFF, !onSpeaker, toggle)
            else CallOutputPresentation(CallOutputGlyph.SPEAKER, onSpeaker, toggle)
        }
        val glyph = when (current) {
            AudioRouteKind.SPEAKER -> CallOutputGlyph.SPEAKER
            AudioRouteKind.EARPIECE -> CallOutputGlyph.PHONE
            AudioRouteKind.BLUETOOTH -> CallOutputGlyph.BLUETOOTH
            AudioRouteKind.WIRED_HEADSET -> CallOutputGlyph.HEADPHONES
        }
        return CallOutputPresentation(glyph, CallPresentationPolicy.outputHighlighted(current, video, hasEarpiece), toggleTo = null)
    }
}
