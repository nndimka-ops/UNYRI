@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.messenger.app.ui.chats

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.os.Build
import android.view.WindowInsetsAnimation
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoHaptics
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class EmojiInputMode { Hidden, System, Emoji, Returning }

@Stable
internal class EmojiInputState {
    var mode by mutableStateOf(EmojiInputMode.Hidden)
        private set
    val showingEmoji: Boolean get() = mode == EmojiInputMode.Emoji
    val reservesKeyboard: Boolean get() = showingEmoji || mode == EmojiInputMode.Returning
    internal var connection: InputConnection? = null
    internal val composerGeometry = LiveChatBounds()
    internal val composerBounds: Rect? get() = composerGeometry.bounds

    fun focusChanged(focused: Boolean) {
        if (!focused) mode = EmojiInputMode.Hidden
        else if (mode == EmojiInputMode.Hidden) mode = EmojiInputMode.System
    }
    fun toggle() { mode = if (showingEmoji) EmojiInputMode.Returning else EmojiInputMode.Emoji }
    fun systemArrived() { if (mode == EmojiInputMode.Returning) mode = EmojiInputMode.System }
    fun dismiss() { mode = EmojiInputMode.Hidden }
    fun chatAction(blur: () -> Unit, action: () -> Unit) {
        if (reservesKeyboard) { dismiss(); blur() }
        action()
    }
    fun insert(emoji: String) { if (showingEmoji) connection?.commitText(emoji, 1) }
    fun backspace() {
        if (!showingEmoji) return
        // The editor owns grapheme deletion, selected ranges, composing text and undo.
        connection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
}

/** Intercept only the upstream IME. The field's downstream edit/undo session stays mounted. */
@Composable
internal fun EmojiEditorSession(state: EmojiInputState, content: @Composable () -> Unit) {
    val emoji = state.showingEmoji
    val view = LocalView.current
    val interceptor = remember(state, emoji, view) {
        PlatformTextInputInterceptor { request, next ->
            if (emoji) {
                val connection = request.createInputConnection(EditorInfo())
                state.connection = connection
                try {
                    // Hide after the previous platform session has been cancelled.
                    // A hide before focus/session replacement can be undone by Samsung's IME.
                    androidx.core.view.ViewCompat.getWindowInsetsController(view)
                        ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                    awaitCancellation()
                } finally {
                    if (state.connection === connection) state.connection = null
                    connection.closeConnection()
                }
            } else next.startInputMethod(request)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

internal fun emojiReservedBottom(ime: Int, learned: Int, available: Int, reserve: Boolean): Int =
    maxOf(ime, if (reserve) learned.coerceIn(0, available.coerceAtLeast(0)) else 0)

/** A docked IME contributes a settled bottom overlap beyond the navigation bar.
 * Floating/hardware keyboards do not; landscape/small keyboards need no arbitrary dp cutoff. */
internal fun dockedEmojiImeHeight(current: Int, target: Int, navigation: Int, available: Int): Int? =
    current.takeIf { it == target && it > navigation && it <= available }

private data class KeyboardMotionSpec(val duration: Int = 285,
    val interpolator: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f))

private class KeyboardMotion {
    // AOSP InsetsController's synchronized IME defaults until the first observation.
    // https://android.googlesource.com/platform/frameworks/base/+/58e58fd32c19fa19177fabb9cb0223a5155d260a/core/java/android/view/InsetsController.java
    var opening = KeyboardMotionSpec()
    var closing = KeyboardMotionSpec()
}

/** Observe above Compose's inset owner, without replacing its animation callback. */
@Composable
private fun rememberKeyboardMotion(): KeyboardMotion {
    val motion = remember { KeyboardMotion() }
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        if (Build.VERSION.SDK_INT >= 30) root.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onStart(animation: WindowInsetsAnimation,
                    bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) {
                        val spec = KeyboardMotionSpec(animation.durationMillis.toInt().coerceIn(0, 1000),
                            animation.interpolator ?: motion.opening.interpolator)
                        if (root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) motion.opening = spec
                        else motion.closing = spec
                    }
                    return bounds
                }
                override fun onProgress(insets: android.view.WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>): android.view.WindowInsets = insets
            })
        onDispose { if (Build.VERSION.SDK_INT >= 30) root.setWindowInsetsAnimationCallback(null) }
    }
    return motion
}

/** Cache loading and learned keyboard metrics must not restart the timeline's content. */
@Composable
private fun ChatInputViewportContent(state: EmojiInputState, focused: Boolean, onDismiss: () -> Unit,
    lift: ChatKeyboardLift, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .onGloballyPositioned { contentOrigin = it.boundsInWindow().topLeft }
        .pointerInput(state.showingEmoji, focused) {
            if (!state.showingEmoji && !focused) return@pointerInput
            awaitEachGesture {
                // Dismiss after children finish their click, so a voice row stays under the up event.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (state.composerBounds?.contains(contentOrigin + down.position) == false) {
                    var moved = false
                    var releasedAt = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        moved = moved || event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                        releasedAt = event.changes.maxOf { it.uptimeMillis }
                    } while (event.changes.any { it.pressed })
                    // A scroll or message-menu long press owns its gesture.
                    if (!moved && releasedAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        state.dismiss(); dismiss()
                    }
                }
            }
        }) { content() }
}

/** One inset owner for both IME and emoji; max(), never two stacked keyboard paddings. */
@Composable
internal fun EmojiKeyboardHost(state: EmojiInputState, focused: Boolean, onDismiss: () -> Unit,
    store: EmojiRecentsStore = rememberEmojiRecentsStore(),
    ime: WindowInsets = WindowInsets.ime, target: WindowInsets = WindowInsets.imeAnimationTarget,
    nav: WindowInsets = WindowInsets.navigationBars,
    lift: ChatKeyboardLift = remember { ChatKeyboardLift() },
    content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val stored by store.state.collectAsState()
    var observedHeightDp by remember { mutableStateOf<Float?>(null) }
    val mode = state.mode
    val motion = rememberKeyboardMotion()
    val reservation = remember { Animatable(0f) }
    var panelMounted by remember { mutableStateOf(false) }
    LaunchedEffect(lift) {
        snapshotFlow { lift.pixels == 0 || lift.topSpaceAbandoned }.collect { reset ->
            if (reset) lift.resetTopScroll()
        }
    }
    val dismiss by rememberUpdatedState(onDismiss)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { state.dismiss(); dismiss() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(mode) {
        // Hardware/floating keyboards may never report a docked inset.
        if (mode == EmojiInputMode.Returning) { delay(1000); state.systemArrived() }
    }
    BackHandler(state.reservesKeyboard) { state.dismiss(); onDismiss() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(mode, ime, target, density) {
            if (mode == EmojiInputMode.Emoji) {
                // Samsung can finish showing the old platform session after focus
                // replacement. Observe that late show without reading frames in composition.
                snapshotFlow { ime.getBottom(density) > 0 || target.getBottom(density) > 0 }
                    .collect { visible ->
                        if (visible) androidx.core.view.ViewCompat.getWindowInsetsController(view)
                            ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                    }
            }
        }
        val available = constraints.maxHeight
        LaunchedEffect(ime, target, nav, density, available) {
            snapshotFlow {
                dockedEmojiImeHeight(ime.getBottom(density), target.getBottom(density), nav.getBottom(density), available)
            }.collect { settled ->
                if (settled != null) {
                    val dp = with(density) { settled.toDp().value }
                    observedHeightDp = dp
                    store.rememberHeight(dp)
                    state.systemArrived()
                }
            }
        }
        // Prefer actual geometry even on the first tap before an asynchronous disk load.
        val heightDp = observedHeightDp ?: stored.saved.keyboardHeightDp ?: 336f
        val metricsReady = observedHeightDp != null || stored.loaded
        val learned = with(density) { heightDp.dp.roundToPx() }
        // A cold cache must not first show 336dp and then resize to its saved short height.
        // Actual docked geometry is immediately usable; otherwise mount once the async load completes.
        val reserve = state.reservesKeyboard && metricsReady
        LaunchedEffect(reserve, learned, available) {
            val navigation = nav.getBottom(density)
            val current = chatKeyboardLift(ime.getBottom(density), navigation, 0).toFloat()
            // Hold the current footprint before the system IME starts leaving.
            if (reserve && current > reservation.value) reservation.snapTo(current)
            if (state.showingEmoji && reserve) panelMounted = true
            val destination = if (reserve) (learned.coerceIn(0, available) - navigation).coerceAtLeast(0).toFloat() else 0f
            val spec = if (reserve) motion.opening else motion.closing
            reservation.animateTo(destination, tween(spec.duration, easing = Easing { spec.interpolator.getInterpolation(it) }))
            if (!reserve) panelMounted = false
        }
        val readLift = remember(ime, nav, density, reservation) { {
            val navigation = nav.getBottom(density)
            chatKeyboardLift(ime.getBottom(density), navigation,
                if (reservation.value > 0f) navigation + reservation.value.toInt() else 0)
        } }
        SideEffect { lift.read = readLift }
        val imeGone by remember(ime, target, density) {
            derivedStateOf { ime.getBottom(density) == 0 && target.getBottom(density) == 0 }
        }
        CompositionLocalProvider(LocalChatKeyboardLift provides lift) {
            ChatInputViewportContent(state, focused, onDismiss, lift, content)
        }
        if ((state.showingEmoji || (panelMounted && mode == EmojiInputMode.Hidden)) && metricsReady && imeGone) {
            EmojiInputPanel(state::insert, state::backspace,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().layout { measurable, limits ->
                    val height = (reservation.value.toInt() + nav.getBottom(density)).coerceIn(0, limits.maxHeight)
                    val panel = measurable.measure(limits.copy(minHeight = height, maxHeight = height))
                    layout(panel.width, panel.height) { panel.placeRelative(0, 0) }
                },
                store, bottomPadding = with(density) { nav.getBottom(density).toDp() })
        }
    }
}

/** Keep the reserved geometry while the IME leaves, but mount only one input surface. */
internal fun emojiPanelReady(emoji: Boolean, metricsReady: Boolean, ime: Int, target: Int): Boolean =
    emoji && metricsReady && ime == 0 && target == 0

@Composable
internal fun EmojiInputPanel(onInsert: (String) -> Unit, onBackspace: () -> Unit,
    modifier: Modifier = Modifier, store: EmojiRecentsStore = rememberEmojiRecentsStore(),
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    val secondaryInk = Color(0xFF9A9AA0) // Native secondary label sampled in emoji-03, on #202020.
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("recent") }
    // Freeze the displayed recent section for this panel session, as on iOS. A selection must
    // not move rows under the finger; the shared persisted list updates for the next opening.
    val stored by store.state.collectAsState()
    val alreadyLoaded = remember(store) { stored.saved.recents.takeIf { stored.loaded } }
    val recents = alreadyLoaded ?: stored.initialRecents.orEmpty()
    val sections = remember(recents) {
        (if (recents.isEmpty()) emptyList() else listOf(recentEmojiCategory(recents))) + emojiCategories
    }
    val sectionRows = remember(sections) {
        var row = 0
        sections.associate { section -> section.id to row.also { row += 1 + (section.emojis.size + 7) / 8 } }
    }
    Column(modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .background(colors.deepPanel).padding(bottom = bottomPadding).testTag("emoji-keyboard")) {
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("emoji-keyboard-grid"),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            sections.forEach { section ->
                item(key = section.id) {
                    Text(stringResource(section.label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = secondaryInk, lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)
                            .semantics { heading() })
                }
                section.emojis.chunked(8).forEachIndexed { index, row ->
                    item(key = "${section.id}:$index") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                            row.forEach { emoji ->
                                val label = stringResource(R.string.emoji_insert, emoji)
                                EmojiKey(emoji, label, Modifier.weight(1f)) {
                                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                    onInsert(emoji); store.use(emoji)
                                }
                            }
                            repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            sections.forEach { section ->
                val label = stringResource(section.label)
                Box(Modifier.weight(1f).heightIn(min = 38.dp).clip(CircleShape)
                    .background(if (selected == section.id) colors.primaryText.copy(alpha = .12f) else Color.Transparent)
                    .clickable(role = Role.Tab) {
                        CocoHaptics.select(view)
                        selected = section.id
                        scope.launch { list.scrollToItem(sectionRows.getValue(section.id)) }
                    }.semantics { contentDescription = label; this.selected = selected == section.id }
                    .testTag("emoji-tab-${section.id}"), contentAlignment = Alignment.Center) {
                    Icon(EmojiPanelIcons.categories.getValue(section.id), null,
                        tint = if (selected == section.id) colors.primaryText else secondaryInk,
                        modifier = Modifier.size(17.dp))
                }
            }
            EmojiBackspace(onBackspace)
        }
    }
}

@Composable
private fun EmojiKey(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1.25f else 1f,
        androidx.compose.animation.core.tween(80), label = "emoji-press")
    Box(modifier.heightIn(min = 44.dp).clickable(interactionSource = interaction, indication = null,
        role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 31.sp, lineHeight = 38.sp,
            modifier = Modifier.padding(vertical = 2.dp).graphicsLayer { scaleX = scale; scaleY = scale })
    }
}

@Composable
internal fun EmojiBackspace(action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    val view = LocalView.current
    val delete = { view.playSoundEffect(android.view.SoundEffectConstants.CLICK); latest() }
    val latestDelete by rememberUpdatedState(delete)
    val label = stringResource(R.string.emoji_delete)
    Box(Modifier.width(52.dp).heightIn(min = 38.dp)
        .semantics { contentDescription = label; role = Role.Button; onClick { latestDelete(); true } }
        .testTag("emoji-keyboard-backspace")
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                latestDelete()
                coroutineScope {
                    val repeat = launch { delay(450); while (true) { latestDelete(); delay(90) } }
                    try { tryAwaitRelease() } finally { repeat.cancel() }
                }
            })
        }, contentAlignment = Alignment.Center) {
        Icon(EmojiPanelIcons.backspace, null, tint = LocalCocoColors.current.primaryText, modifier = Modifier.size(19.dp))
    }
}

/** Static vector ink matching the SF category silhouettes; no font-symbol fallbacks. */
internal object EmojiPanelIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val keyboard by lazy { icon("EmojiKeyboard") {
        moveTo(3f, 4f); lineTo(21f, 4f); quadTo(23f, 4f, 23f, 6f); lineTo(23f, 18f)
        quadTo(23f, 20f, 21f, 20f); lineTo(3f, 20f); quadTo(1f, 20f, 1f, 18f)
        lineTo(1f, 6f); quadTo(1f, 4f, 3f, 4f); close()
        for (y in listOf(8f, 12f)) for (x in listOf(5f, 8.5f, 12f, 15.5f, 19f)) { moveTo(x, y); lineTo(x, y + .2f) }
        moveTo(6f, 16f); lineTo(18f, 16f)
    } }
    val backspace by lazy { icon("EmojiBackspace") {
        moveTo(9f, 4f); lineTo(22f, 4f); lineTo(22f, 20f); lineTo(9f, 20f); lineTo(1f, 12f); close()
        moveTo(11f, 8f); lineTo(18f, 16f); moveTo(18f, 8f); lineTo(11f, 16f)
    } }
    val categories by lazy { mapOf(
        "recent" to ChatControls.clock,
        "people" to ChatControls.smile,
        "nature" to icon("EmojiPaw") {
            moveTo(7f, 15f); curveTo(9f, 9f, 15f, 9f, 17f, 15f); curveTo(23f, 24f, 14f, 20f, 12f, 20f)
            curveTo(10f, 20f, 1f, 24f, 7f, 15f); close()
            for ((x, y) in listOf(3f to 9f, 8f to 4f, 15f to 4f, 20f to 9f)) {
                moveTo(x + 2f, y); arcTo(2f, 3f, 0f, true, true, x - 2f, y); arcTo(2f, 3f, 0f, true, true, x + 2f, y)
            }
        },
        "food" to icon("EmojiFood") {
            for (x in listOf(3f, 6f, 9f)) { moveTo(x, 2f); lineTo(x, 9f) }
            moveTo(3f, 9f); quadTo(6f, 13f, 9f, 9f); moveTo(6f, 11f); lineTo(6f, 22f)
            moveTo(20f, 2f); curveTo(13f, 9f, 14f, 14f, 20f, 14f); lineTo(20f, 22f); close()
        },
        "activity" to icon("EmojiBall") {
            moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f); arcTo(10f, 10f, 0f, true, true, 22f, 12f)
            moveTo(12f, 7f); lineTo(17f, 11f); lineTo(15f, 17f); lineTo(9f, 17f); lineTo(7f, 11f); close()
            moveTo(12f, 7f); lineTo(12f, 2f); moveTo(17f, 11f); lineTo(22f, 9f)
            moveTo(15f, 17f); lineTo(18f, 21f); moveTo(9f, 17f); lineTo(6f, 21f); moveTo(7f, 11f); lineTo(2f, 9f)
        },
        "travel" to icon("EmojiCar") {
            moveTo(3f, 10f); lineTo(6f, 3f); lineTo(18f, 3f); lineTo(21f, 10f); close()
            moveTo(2f, 10f); lineTo(22f, 10f); lineTo(22f, 19f); lineTo(2f, 19f); close()
            moveTo(4f, 19f); lineTo(4f, 22f); moveTo(20f, 19f); lineTo(20f, 22f)
            moveTo(5f, 14f); lineTo(7f, 14f); moveTo(17f, 14f); lineTo(19f, 14f)
        },
        "objects" to icon("EmojiBulb") {
            moveTo(8f, 17f); curveTo(8f, 12f, 5f, 12f, 5f, 8f); curveTo(5f, -1f, 19f, -1f, 19f, 8f)
            curveTo(19f, 12f, 16f, 12f, 16f, 17f); close(); moveTo(8f, 20f); lineTo(16f, 20f)
            moveTo(10f, 23f); lineTo(14f, 23f)
        },
        "symbols" to icon("EmojiHeart") {
            moveTo(12f, 21f); curveTo(-10f, 7f, 7f, -5f, 12f, 6f); curveTo(17f, -5f, 34f, 7f, 12f, 21f); close()
        },
        "flags" to icon("EmojiFlag") {
            moveTo(4f, 23f); lineTo(4f, 2f); curveTo(9f, -1f, 15f, 7f, 22f, 3f)
            lineTo(22f, 15f); curveTo(15f, 19f, 9f, 11f, 4f, 14f)
        }
    ) }
}
