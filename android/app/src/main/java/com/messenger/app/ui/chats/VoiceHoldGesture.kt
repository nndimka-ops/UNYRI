package com.messenger.app.ui.chats

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** The surface survives the switch from composer to recording, preserving pointer ownership. */
internal fun Modifier.voiceHoldGesture(enabled: Boolean, start: () -> Unit, finish: () -> Unit,
    cancel: () -> Unit, lock: () -> Unit, progress: (Float, Float) -> Unit = { _, _ -> },
    hitWidth: androidx.compose.ui.unit.Dp = 64.dp): Modifier = composed {
    val canStart = rememberUpdatedState(enabled)
    val begin = rememberUpdatedState(start)
    val done = rememberUpdatedState(finish)
    val discard = rememberUpdatedState(cancel)
    val fix = rememberUpdatedState(lock)
    val movement = rememberUpdatedState(progress)
    pointerInput(hitWidth) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canStart.value || down.position.x < size.width - hitWidth.toPx()) return@awaitEachGesture
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            held.consume()
            movement.value(0f, 0f)
            begin.value()
            var completed = false
            try {
                while (!completed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    val delta = change.position - down.position
                    movement.value((-delta.x / 80.dp.toPx()).coerceIn(0f, 1f), (-delta.y / 64.dp.toPx()).coerceIn(0f, 1f))
                    when {
                        delta.x < -80.dp.toPx() -> { discard.value(); completed = true }
                        delta.y < -64.dp.toPx() -> { fix.value(); completed = true }
                        !change.pressed -> { done.value(); completed = true }
                    }
                }
            } finally {
                // Navigation/background handlers preserve interrupted recordings separately.
                if (!completed) done.value()
                movement.value(0f, 0f)
            }
        }
    }
}
