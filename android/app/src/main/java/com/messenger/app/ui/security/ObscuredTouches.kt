package com.messenger.app.ui.security

import android.view.MotionEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass

/** A gesture stays rejected even when the overlay disappears before finger-up. */
internal class ObscuredTouchPolicy {
    private var rejected = false
    var rejectCurrentEvent = false; private set
    fun reject(action: Int, flags: Int): Boolean {
        if (action == MotionEvent.ACTION_DOWN) rejected = false
        rejected = rejected || flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        val result = rejected
        rejectCurrentEvent = result
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) rejected = false
        return result
    }
}

/** Attach before a sensitive button's clickable node, never to the hosting window.
 * Observe every native event (including flags appearing after DOWN), then consume
 * at Initial pass before a clickable can act. No window-wide input filtering. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(): Modifier = composed {
    val policy = remember { ObscuredTouchPolicy() }
    motionEventSpy { policy.reject(it.actionMasked, it.flags) }.pointerInput(policy) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (policy.rejectCurrentEvent) event.changes.forEach { it.consume() }
            }
        }
    }
}
