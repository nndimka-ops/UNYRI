package com.messenger.app.ui.chats

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class HoldCoordinates { var layout: LayoutCoordinates? = null }

/** Stable pointer host; neither phase changes nor recorder startup replace it. */
internal fun Modifier.voiceHoldGesture(enabled: Boolean, start: () -> Unit, finish: () -> Unit,
    cancel: () -> Unit, lock: () -> Unit, progress: (Float, Float) -> Unit = { _, _ -> },
    hitWidth: Dp = 56.dp, interrupted: () -> Unit = lock): Modifier = composed {
    val canStart = rememberUpdatedState(enabled)
    val begin = rememberUpdatedState(start)
    val done = rememberUpdatedState(finish)
    val discard = rememberUpdatedState(cancel)
    val fix = rememberUpdatedState(lock)
    val interruption = rememberUpdatedState(interrupted)
    val movement = rememberUpdatedState(progress)
    val coordinates = remember { HoldCoordinates() }
    onGloballyPositioned { coordinates.layout = it }.pointerInput(hitWidth) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canStart.value) return@awaitEachGesture
            var last = down
            // UIKit allows unlimited movement before the 150 ms recognition point.
            val released = withTimeoutOrNull(150L) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) return@withTimeoutOrNull true
                    last = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!last.pressed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (released == true || !canStart.value || last.position.x <= size.width - hitWidth.toPx() ||
                last.position.y <= size.height - 70.dp.toPx()) return@awaitEachGesture
            fun root(position: Offset) = coordinates.layout?.takeIf { it.isAttached }?.localToRoot(position) ?: position
            val origin = root(last.position)
            val state = RecordingHoldState(size.width / density)
            last.consume()
            movement.value(0f, 0f)
            begin.value()
            var completed = false
            try {
                while (!completed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    change.consume()
                    val delta = (root(change.position) - origin) / density
                    val result = state.move(delta.x, delta.y)
                    movement.value(state.x, state.y)
                    when {
                        result == RecordingHoldState.Outcome.Cancel -> { completed = true; discard.value() }
                        result == RecordingHoldState.Outcome.Lock -> { completed = true; fix.value() }
                        !change.pressed -> { completed = true; state.end(); done.value() }
                    }
                }
            } finally {
                // A stolen/cancelled touch must never send or discard the take.
                if (!completed) { state.end(interrupted = true); interruption.value() }
                movement.value(0f, 0f)
            }
        }
    }
}
