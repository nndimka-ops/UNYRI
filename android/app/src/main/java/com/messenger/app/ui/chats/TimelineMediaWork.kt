package com.messenger.app.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.data.local.VideoFrameFailures

internal fun mediaWorkAllowed(started: Boolean, covered: Boolean, visible: Boolean) = started && !covered && visible
/** A static poster is not playback. Partial overlays must not leave visible rows black. */
internal fun mediaPreviewWorkAllowed(started: Boolean, contentAvailable: Boolean, visible: Boolean) =
    started && contentAvailable && visible

@Stable internal class TimelineMediaWork {
    var started by mutableStateOf(false)
    var resumed by mutableStateOf(false)
    val openedAt = android.os.SystemClock.uptimeMillis()
    var connectionChangedAt = openedAt
    private var connection: Any? = null
    val reactionAdmission = MediaReactionAdmission()
    private val intents = LinkedHashMap<Pair<String, String>, Long>()
    fun connection(value: Any) { if (connection != value) { connection = value; connectionChangedAt = android.os.SystemClock.uptimeMillis() } }
    fun reactionIntent(message: String, emoji: String) { intents[message to emoji] = android.os.SystemClock.uptimeMillis(); while (intents.size > 32) intents.remove(intents.keys.first()) }
    fun takeReactionIntent(message: String, emoji: String): Boolean = intents.remove(message to emoji)?.let { android.os.SystemClock.uptimeMillis() - it < 2000 } == true
    var covered by mutableStateOf(false)
    var contentAvailable by mutableStateOf(true)
    var visible by mutableStateOf(emptySet<Any>())
}
internal val LocalTimelineMediaWork = staticCompositionLocalOf<TimelineMediaWork?> { null }
internal val LocalMediaRowKey = staticCompositionLocalOf<Any?> { null }

/** Sample the current layout at the caller's observation time, using its actual or reserved inset. */
internal fun visibleTimelineRows(layout: LazyListLayoutInfo, header: Int, composer: Int, lift: Int = 0,
    bottomOcclusion: Int = 0): Sequence<LazyListItemInfo> =
    layout.visibleItemsInfo.asSequence().filter { it.offset < layout.viewportEndOffset - composer - bottomOcclusion &&
        it.offset + it.size > layout.viewportStartOffset + header + lift }

/** A single lifecycle observer per list. Never freeze lifecycle in a row binding. */
@Composable internal fun rememberTimelineMediaWork(list: LazyListState, covered: Boolean, header: Int, composer: Int,
    contentAvailable: Boolean = true, lift: ChatKeyboardLift? = null): TimelineMediaWork {
    val work = remember(list) { TimelineMediaWork() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(work, lifecycle) {
        fun update() {
            work.started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            work.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            VideoFrameFailures.lifecycle(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); work.started = false }
    }
    SideEffect { work.covered = covered; work.contentAvailable = contentAvailable }
    LaunchedEffect(work, header, composer) {
        snapshotFlow {
            val layout = list.layoutInfo
            (lift?.visibleRows(layout, header, composer) ?: visibleTimelineRows(layout, header, composer)).map { it.key }.toSet()
        }.collect { work.visible = it }
    }
    return work
}

@Composable internal fun rememberMediaWorkAllowed(): Boolean {
    val work = LocalTimelineMediaWork.current ?: return true
    val key = LocalMediaRowKey.current ?: return false
    return mediaWorkAllowed(work.started, work.covered, key in work.visible)
}

@Composable internal fun rememberMediaPreviewWorkAllowed(): Boolean {
    val work = LocalTimelineMediaWork.current ?: return true
    val key = LocalMediaRowKey.current ?: return false
    return mediaPreviewWorkAllowed(work.started, work.contentAvailable, key in work.visible)
}

@Composable internal fun rememberGridMediaWork(list: androidx.compose.foundation.lazy.grid.LazyGridState, covered: Boolean): TimelineMediaWork {
    val work = remember(list) { TimelineMediaWork() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(work, lifecycle) {
        fun update() { work.started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED); VideoFrameFailures.lifecycle(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); work.started = false }
    }
    SideEffect { work.covered = covered }
    LaunchedEffect(work) { snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }.collect { work.visible = it } }
    return work
}

@Composable internal fun rememberViewerForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            VideoFrameFailures.lifecycle(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return started
}
