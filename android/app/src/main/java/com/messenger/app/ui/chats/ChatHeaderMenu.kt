package com.messenger.app.ui.chats

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.LocalCocoColors
import java.util.function.Consumer

internal data class ChatHeaderAction(val title: String, val icon: ImageVector, val tint: Color, val fill: Color = Color.Transparent,
    val group: Int = 0, val enabled: Boolean = true, val checked: Boolean? = null, val action: () -> Unit)

internal enum class HeaderMenuEntry { Search, Media, Pins, Reminders, Video, Schedule, Typing, Mute, Block, Report }

/** iOS ChatScreen.headerMenuItems: saved/group menus keep only actions they can perform. */
internal fun headerMenuEntries(hasPeer: Boolean, saved: Boolean, blocked: Boolean,
    draft: String, editing: Boolean, replying: Boolean): List<HeaderMenuEntry> = buildList {
    addAll(listOf(HeaderMenuEntry.Search, HeaderMenuEntry.Media, HeaderMenuEntry.Pins, HeaderMenuEntry.Reminders))
    if (hasPeer && !blocked) add(HeaderMenuEntry.Video)
    if (draft.isNotBlank() && !editing && !replying) add(HeaderMenuEntry.Schedule)
    if (!saved) add(HeaderMenuEntry.Typing)
    if (hasPeer) addAll(listOf(HeaderMenuEntry.Mute, HeaderMenuEntry.Block, HeaderMenuEntry.Report))
}

/** Reserve scrollable actions before the preview; tiny windows use the reaction shortcut. */
internal fun parityMessageMenuBudget(height: Float, rows: Int, reactionHeight: Float, preview: Boolean): MessageMenuBudget {
    val available = height.coerceAtLeast(0f)
    val reactions = reactionHeight > 0f && available >= 180f && reactionHeight <= available * .3f
    val reactionSpace = if (reactions) reactionHeight else 0f
    val fixed = minOf(available, 8f + if (reactions) 8f else 0f)
    val content = (available - fixed - reactionSpace).coerceAtLeast(0f)
    val count = rows.coerceAtLeast(1) + if (reactionHeight > 0f && !reactions) 1 else 0
    val desired = count * 46f + 8f
    val reserve = minOf(content, maxOf(48f, minOf(desired, available * .55f)))
    val previewLimit = if (preview && available >= 140f) minOf(220f, (content - reserve).coerceAtLeast(0f)) else 0f
    val menu = minOf(desired, (content - previewLimit).coerceAtLeast(0f))
    return MessageMenuBudget(false, reactions, menu, previewLimit, fixed)
}

/** Clear the host input once on entry; focus loss persists its ordinary draft. */
@Composable
internal fun DismissChatMenuKeyboard() {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
}

internal data class MessageMenuFocusRequest(val revision: Long = 0, val pending: Boolean = false) {
    fun requested(inputReady: Boolean) = MessageMenuFocusRequest(revision + 1, inputReady)
    fun cancelled() = copy(pending = false)
    fun canFocus(ticket: Long, menuOpen: Boolean, inputReady: Boolean, windowFocused: Boolean) =
        pending && ticket == revision && !menuOpen && inputReady && windowFocused
}

/** A selected Reply/Edit owns one focus request, never an ordinary menu dismissal. */
@Stable
internal class MessageMenuComposerFocus {
    val requester = FocusRequester()
    var request by mutableStateOf(MessageMenuFocusRequest())
        private set
    var inputReady by mutableStateOf(false)
        private set
    private var menuOwner: Any? = null

    fun inputPlaced() { inputReady = true }
    fun inputDetached() { inputReady = false; cancel() }
    fun cancel() { request = request.cancelled() }
    fun menuChanged(owner: Any?) {
        if (owner != null && owner != menuOwner) cancel()
        menuOwner = owner
    }

    fun choose(action: () -> Unit, dismiss: () -> Unit) {
        cancel()
        action()
        dismiss()
        request = request.requested(inputReady)
    }

    fun consume(ticket: Long, menuOpen: Boolean, windowFocused: Boolean): Boolean {
        if (!request.canFocus(ticket, menuOpen, inputReady, windowFocused)) return false
        cancel()
        return true
    }
}

/** Bound to the actual text field, so a hidden/removed input cannot receive a stale request. */
@Composable
internal fun Modifier.messageMenuFocusTarget(focus: MessageMenuComposerFocus?): Modifier {
    if (focus == null) return this
    DisposableEffect(focus) { onDispose { focus.inputDetached() } }
    return focusRequester(focus.requester).onGloballyPositioned { focus.inputPlaced() }
}

@Composable
internal fun rememberMessageMenuComposerFocus(owner: Any, menuKey: Any?): MessageMenuComposerFocus {
    val focus = remember(owner) { MessageMenuComposerFocus() }
    val menuOpen = menuKey != null
    val currentMenuOpen by rememberUpdatedState(menuOpen)
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect { focus.menuChanged(menuKey) }
    DisposableEffect(focus) { onDispose { focus.cancel() } }
    val request = focus.request
    if (request.pending) {
        // Observe window focus only during a requested handoff. No idle listener or frame loop.
        val window = LocalWindowInfo.current
        val windowFocused = window.isWindowFocused
        val inputReady = focus.inputReady
        LaunchedEffect(focus, request.revision, menuOpen, inputReady, windowFocused) {
            if (!request.canFocus(request.revision, menuOpen, inputReady, windowFocused)) return@LaunchedEffect
            // Let Dialog disposal and the updated reply/edit composition finish before focusing.
            withFrameNanos { }
            if (focus.consume(request.revision, currentMenuOpen, window.isWindowFocused)) {
                focus.requester.requestFocus()
                keyboard?.show()
            }
        }
    }
    return focus
}

/** Window-owned native blur. No screenshot buffers, frame callbacks or hidden composition. */
@Composable
internal fun ChatMenuWindow(): Boolean {
    val view = LocalView.current
    val radius = with(LocalDensity.current) { 24.dp.roundToPx() }
    val window = (view.parent as? DialogWindowProvider)?.window
    var blurred by remember(window) { mutableStateOf(false) }
    DisposableEffect(window, radius) {
        val original = window?.attributes?.let { WindowManager.LayoutParams().apply { copyFrom(it) } }
        val manager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val power = view.context.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun update(available: Boolean) {
            blurred = available && !power.isPowerSaveMode
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= 31 && window != null) {
                if (blurred) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = if (blurred) radius else 0 }
            }
        }
        // The menu window covers the whole screen, system bars included, so its frame matches the
        // full-height content and the panels' own system-bar padding is right. MIUI (POCO X6, 20 Nov)
        // otherwise fits this dialog window between the status and navigation bars ([0,122]-[1220,2570])
        // while the content is laid out 2712 px tall: the bottom panel ran under the three-button
        // navigation bar and Compose insets inside the dialog read zero.
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            window.attributes = window.attributes.apply {
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val listener = Consumer<Boolean> { update(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            update(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener)
        } else update(false)
        onDispose {
            if (Build.VERSION.SDK_INT >= 31) manager.removeCrossWindowBlurEnabledListener(listener)
            if (original != null) window?.attributes = original
        }
    }
    return blurred
}

@Composable
internal fun Modifier.chatMenuPanel(shape: Shape, blurred: Boolean, outlined: Boolean = true): Modifier {
    val colors = LocalCocoColors.current
    val base = if (blurred) colors.glassPrimary.copy(alpha = .88f) else colors.deepPanel
    return shadow(24.dp, shape).clip(shape).background(base)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.White.copy(alpha = .03f))))
        .then(if (outlined) Modifier.border(.5.dp, Color.White.copy(alpha = .10f), shape) else Modifier)
}

@Composable
internal fun ChatMenuBackdrop(blurred: Boolean, enabled: Boolean = true, onDismiss: () -> Unit) {
    val close = stringResource(R.string.chat_controls_close)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (blurred) .25f else .60f))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = close, onClick = onDismiss)
        .semantics { contentDescription = close })
}

@Composable
internal fun ChatHeaderMenu(actions: List<ChatHeaderAction>, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    val title = stringResource(R.string.chat_menu_title)
    DismissChatMenuKeyboard()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val blurred = ChatMenuWindow()
        val view = LocalView.current
        Box(Modifier.fillMaxSize()) {
            ChatMenuBackdrop(blurred, onDismiss = onDismiss)
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                val top = minOf(56.dp, maxHeight / 8)
                Column(Modifier.align(Alignment.TopEnd).padding(top = top).width(minOf(300.dp, maxWidth))
                    .heightIn(max = minOf(620.dp, (maxHeight - top - 8.dp).coerceAtLeast(48.dp)))
                    .testTag("chat-header-menu").semantics { paneTitle = title }
                    .chatMenuPanel(RoundedCornerShape(22.dp), blurred)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    actions.forEachIndexed { index, item ->
                        if (index > 0 && item.group != actions[index - 1].group) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = .5.dp, color = colors.primaryText.copy(alpha = .08f))
                        }
                        val ink = item.tint.copy(alpha = if (item.enabled) 1f else .4f)
                        val click = { CocoHaptics.select(view); item.action() }
                        val interaction = if (item.checked == null) Modifier.clickable(enabled = item.enabled, role = Role.Button, onClick = click)
                            else Modifier.toggleable(value = item.checked, enabled = item.enabled, role = Role.Switch, onValueChange = { click() })
                        Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                Icon(item.icon, null, tint = ink, modifier = Modifier.size(20.dp))
                            }
                            Text(item.title, modifier = Modifier.weight(1f).padding(start = 14.dp), fontSize = 16.sp,
                                lineHeight = 20.sp, color = ink)
                            // The entire labeled row owns Switch semantics and its touch target.
                            // The visual track follows S09's 63x28 native control footprint.
                            if (item.checked != null) Box(Modifier.padding(start = 8.dp).size(63.dp, 28.dp)
                                .clip(CircleShape).background(Color(if (item.checked) 0xFF858585 else 0xFF737373))
                                .padding(2.dp).clearAndSetSemantics { }) {
                                Box(Modifier.align(if (item.checked) Alignment.CenterEnd else Alignment.CenterStart)
                                    .size(36.dp, 24.dp).background(Color.White, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}
