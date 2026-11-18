package com.messenger.app.ui.chats

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoEaseOut
import kotlinx.coroutines.delay

internal enum class VoiceTapFeedback { Ignore, Hint, LockedRecording }

internal enum class VoiceRecordKey { Enter, Space, Center }

/** One activation per complete key press; focus loss/disable cancels an armed press. */
internal class VoiceRecordKeyGate {
    private var pressed: VoiceRecordKey? = null
    fun down(key: VoiceRecordKey) { if (pressed == null) pressed = key }
    fun up(key: VoiceRecordKey): Boolean {
        if (pressed != key) return false
        pressed = null
        return true
    }
    fun cancel() { pressed = null }
}

/** Chat-local and monotonic: a delayed expiry or post-hold click cannot revive old feedback. */
@Stable
internal class VoiceHoldHintState(private val now: () -> Long = SystemClock::uptimeMillis) {
    var ticket by mutableLongStateOf(0)
        private set
    var visible by mutableStateOf(false)
        private set
    private var holding = false
    private var endedAt: Long? = null

    fun tap(eligible: Boolean, accessible: Boolean): VoiceTapFeedback {
        if (!eligible || holding || endedAt?.let { now() - it <= 500 } == true) return VoiceTapFeedback.Ignore
        if (accessible) { dismiss(); return VoiceTapFeedback.LockedRecording }
        ticket++; visible = true
        return VoiceTapFeedback.Hint
    }
    fun holdStarted() { holding = true; dismiss() }
    fun holdEnded() { holding = false; endedAt = now(); dismiss() }
    fun expire(request: Long) { if (request == ticket) visible = false }
    fun dismiss() { if (visible) { ticket++; visible = false } }
}

internal suspend fun expireVoiceHoldHint(state: VoiceHoldHintState, request: Long) {
    delay(2000)
    state.expire(request)
}

/** Pointer taps may explain the hold; keyboard and semantic activation always record. */
@Composable
internal fun voiceRecordActivation(state: VoiceHoldHintState, eligible: Boolean, onRecord: () -> Unit): Modifier {
    val gate = remember { VoiceRecordKeyGate() }
    val enabled by rememberUpdatedState(eligible)
    val record by rememberUpdatedState(onRecord)
    val label = stringResource(R.string.voice_record)
    fun activate(): Boolean {
        if (state.tap(enabled, accessible = true) != VoiceTapFeedback.LockedRecording) return false
        record()
        return true
    }
    LaunchedEffect(eligible) { if (!eligible) gate.cancel() }
    DisposableEffect(gate) { onDispose { gate.cancel() } }
    return Modifier.onPreviewKeyEvent { event ->
        val key = when (event.key) {
            Key.Enter, Key.NumPadEnter -> VoiceRecordKey.Enter
            Key.Spacebar -> VoiceRecordKey.Space
            Key.DirectionCenter -> VoiceRecordKey.Center
            else -> null
        }
        if (key == null) false else when (event.type) {
            KeyEventType.KeyDown -> { if (enabled) gate.down(key) else gate.cancel(); true }
            KeyEventType.KeyUp -> { if (gate.up(key)) activate(); true }
            else -> false
        }
    }.onFocusChanged { if (!it.isFocused) gate.cancel() }
        .semantics { onClick(label = label) { activate() } }
}

@Composable
internal fun VoiceHoldHint(state: VoiceHoldHintState, eligible: Boolean, modifier: Modifier = Modifier) {
    val motion = rememberChatMotionSettings()
    LaunchedEffect(state.ticket, eligible, motion.active) {
        if (!eligible || !motion.active) state.dismiss()
        else if (state.visible) expireVoiceHoldHint(state, state.ticket)
    }
    DisposableEffect(state) { onDispose { state.dismiss() } }
    AnimatedVisibility(visible = state.visible && eligible && motion.active, modifier = modifier,
        enter = fadeIn(tween(if (motion.calm) 0 else 150, easing = CocoEaseOut)) +
            slideInVertically(tween(if (motion.calm) 0 else 150, easing = CocoEaseOut)) { it },
        exit = fadeOut(tween(if (motion.calm) 0 else 200, easing = CocoEaseOut)) +
            slideOutVertically(tween(if (motion.calm) 0 else 200, easing = CocoEaseOut)) { it }) {
        Text(stringResource(R.string.voice_hold_hint), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFFF1F1F1), modifier = Modifier.background(Color(0xFF292929), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("voice-hold-hint")
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
}
