package com.messenger.app.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.messenger.app.domain.call.CallUiState

/** A visible fullscreen call owns its whole rectangle, not only its buttons. */
internal object CallTouchPolicy {
    fun privateContentVisible(callPrivateContentVisible: Boolean, conferenceExpanded: Boolean): Boolean =
        callPrivateContentVisible && !conferenceExpanded

    fun ownsBackdrop(state: CallUiState, expanded: Boolean): Boolean = when (state) {
        CallUiState.Idle, is CallUiState.Ended -> false
        is CallUiState.Failed -> true
        is CallUiState.IncomingRinging, is CallUiState.OutgoingRinging,
        is CallUiState.Connecting, is CallUiState.Connected, is CallUiState.Reconnecting -> expanded
    }
}

/**
 * Keep the same composition across minimize/restore so native video surfaces stay
 * mounted. A pointer node makes this the top sibling's hit-test path even where
 * the video/placeholder has no clickable content. Do not consume child events:
 * consuming in Final would cancel ordinary buttons and preview drag recognizers.
 */
@Composable
internal fun CallTouchLayer(
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize().then(if (fullscreen) Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent()
            }
        } else Modifier),
        content = content,
    )
}

/** Epoch, rather than the final boolean, also fences a restore/minimize round trip. */
internal class CallTouchOwnership(private var privateContentVisible: Boolean) {
    var revision: Long = 0
        private set

    fun update(privateContentVisible: Boolean) {
        if (this.privateContentVisible != privateContentVisible) {
            this.privateContentVisible = privateContentVisible
            revision++
        }
    }
}

/**
 * Ancestor of both navigation and call layers. Compose retains a gesture's DOWN
 * hit path: if a call expands while a finger is already on a chat photo, simply
 * adding the call above it cannot cancel that original photo click. Only cancel
 * gestures crossing an ownership change; fresh call controls and minimized chat
 * input retain their normal event delivery. Suspends between input events.
 */
internal fun Modifier.callTouchTransitionGuard(privateContentVisible: Boolean): Modifier = composed {
    val ownership = remember { CallTouchOwnership(privateContentVisible) }
    SideEffect { ownership.update(privateContentVisible) }
    pointerInput(ownership) {
        awaitPointerEventScope {
            var gestureActive = false
            var startedAtRevision = ownership.revision
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!gestureActive && event.changes.any { it.pressed }) {
                    gestureActive = true
                    startedAtRevision = ownership.revision
                }
                if (gestureActive && startedAtRevision != ownership.revision) {
                    event.changes.forEach { it.consume() }
                }
                if (event.changes.none { it.pressed }) gestureActive = false
            }
        }
    }
}
