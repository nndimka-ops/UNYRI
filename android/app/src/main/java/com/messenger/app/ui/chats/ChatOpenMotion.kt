package com.messenger.app.ui.chats

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Account-session navigation feedback; no frame clock, polling or retained row views. */
internal class ChatOpenMotionState {
    var returnedChatId by mutableStateOf<String?>(null)
        private set
    var returnToken by mutableLongStateOf(0L)
        private set
    var holdUntilMillis by mutableLongStateOf(0L)
        private set
    private var consumedGlowToken = 0L

    fun consumeReturnGlow(token: Long): Boolean {
        if (token == 0L || token <= consumedGlowToken) return false
        consumedGlowToken = token
        return true
    }

    fun returned(chatId: String, rowOpen: Boolean) {
        holdUntilMillis = SystemClock.uptimeMillis() + 550L
        returnedChatId = chatId.takeIf { rowOpen }
        returnToken++
    }
}

internal val LocalChatOpenMotion = staticCompositionLocalOf<ChatOpenMotionState?> { null }
internal const val CHAT_OPEN_VIA_ROW = "chat-open-via-row"
private const val CHAT_OPEN_PLAYED = "chat-open-played"

/** Reviewed iOS opening: zoom and its avatar landing remain disabled together.
 * Remember completion on the navigation entry, so a rebuilt chat never replays it.
 */
@Composable
internal fun ChatOpenSurface(
    entryState: SavedStateHandle,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = rememberChatMotionSettings()
    val viaRow = remember(entryState) { entryState.get<Boolean>(CHAT_OPEN_VIA_ROW) == true }
    val style = remember(entryState) {
        ChatOpenTransitionPolicy.style(viaRow, reduceMotion = settings.reduceMotion, lowPower = settings.lowPower)
    }
    val plays = remember(entryState) { viaRow && entryState.get<Boolean>(CHAT_OPEN_PLAYED) != true }
    val alpha = remember(entryState) { Animatable(if (plays) 0f else 1f) }
    val scale = remember(entryState) { Animatable(if (plays && style == ChatOpenStyle.Settle) .965f else 1f) }
    LaunchedEffect(entryState) {
        entryState[CHAT_OPEN_PLAYED] = true
        if (!plays) return@LaunchedEffect
        coroutineScope {
            launch { alpha.animateTo(1f, tween(if (style == ChatOpenStyle.Fade) 180 else 200)) }
            if (style == ChatOpenStyle.Settle) launch {
                scale.animateTo(1f, spring(dampingRatio = .9f, stiffness = 341.5f)) // response .34 s
            }
        }
    }
    Box(modifier.graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }, content = content)
}
