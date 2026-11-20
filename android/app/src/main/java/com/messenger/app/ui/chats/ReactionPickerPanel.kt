package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ReactionPickerPill(
    emojis: List<String>, selected: Set<String>, tracker: ReactionPressTracker?,
    onExpand: () -> Unit, onChoose: (String, Offset) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    val motion = rememberChatMotionSettings()
    val cells = remember { mutableMapOf<String, Rect>() }
    var hover by remember { mutableStateOf<String?>(null) }
    val latestChoose by rememberUpdatedState(onChoose)
    val latestExpanded by rememberUpdatedState(onExpand)
    val language = LocalContext.current.resources.configuration.locales[0].language
    var pillBounds by remember { mutableStateOf(Rect.Zero) }
    val hoverChanged: (String?) -> Unit = { next ->
        if (hover != next) { hover = next; if (next != null) CocoHaptics.select(view) }
    }
    DisposableEffect(tracker) {
        tracker?.attach { position, released ->
            val hit = cells.entries.firstOrNull { it.value.contains(position) }?.key
            hoverChanged(hit)
            if (released) {
                hover = null
                if (hit != null) latestChoose(hit, cells.getValue(hit).center)
            } else if (pillBounds.width > 0 && position.x in pillBounds.left..pillBounds.right &&
                position.y < pillBounds.top - 28f * view.resources.displayMetrics.density) {
                tracker.detach(); latestExpanded()
            }
        }
        onDispose { tracker?.detach() }
    }
    val all = stringResource(R.string.reaction_picker_all)
    BoxWithConstraints(modifier) {
    // Seven 44 dp targets fit a normal phone; narrow windows expose fewer, never smaller targets.
    val shown = emojis.take(((maxWidth.value - 48f) / 44f).toInt().coerceIn(0, 7))
    val options = stringResource(R.string.reaction_picker_options, shown.size)
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(colors.deepPanel)
        .border(.5.dp, colors.primaryText.copy(alpha = .10f), CircleShape)
        .onGloballyPositioned { pillBounds = it.boundsInWindow() }
        .semantics { contentDescription = options }.testTag("reaction_picker_pill")
        .pointerInput(Unit) {
            var drag = 0f
            detectVerticalDragGestures(onDragStart = { drag = 0f },
                onVerticalDrag = { change, amount -> drag += amount; if (drag < -28.dp.toPx()) { change.consume(); latestExpanded() } })
        }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { emoji ->
            val mine = emoji in selected
            val p = remember(emoji) { Animatable(1f) }
            LaunchedEffect(hover == emoji, motion.reduceMotion) {
                p.animateTo(if (hover == emoji && !motion.reduceMotion) 1.35f else 1f, tween(100))
            }
            val spoken = ReactionPickerCatalog.cached()?.entry(emoji)?.localizedName(language) ?: emoji
            val label = stringResource(R.string.react_with, spoken)
            DisposableEffect(emoji) { onDispose { cells.remove(emoji) } }
            Box(Modifier.weight(1f).height(48.dp).onGloballyPositioned { cells[emoji] = it.boundsInWindow() }
                .semantics(mergeDescendants = true) { this.selected = mine; contentDescription = label }
                .testTag("reaction_pill_$emoji").clickable(role = Role.Button) {
                    latestChoose(emoji, cells[emoji]?.center ?: pillBounds.center)
                }, contentAlignment = Alignment.Center) {
                if (mine) Box(Modifier.size(36.dp).background(colors.primaryText.copy(alpha = .20f), CircleShape))
                Text(emoji, fontSize = (30f / LocalDensity.current.fontScale).sp,
                    modifier = Modifier.graphicsLayer { scaleX = p.value; scaleY = p.value }.clearAndSetSemantics {})
            }
        }
        Box(Modifier.size(40.dp, 48.dp).testTag("reaction_picker_expand")
            .semantics { contentDescription = all }.clickable(role = Role.Button) { CocoHaptics.select(view); latestExpanded() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = colors.primaryText,
                modifier = Modifier.size(32.dp).background(colors.primaryText.copy(alpha = .10f), CircleShape).padding(5.dp))
        }
    }
    }
}

@Composable internal fun ReactionPickerPanel(
    store: ReactionPickerStore?, history: ReactionPickerHistory, onDismiss: () -> Unit,
    onChoose: (String, Offset) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalCocoColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val language = context.resources.configuration.locales[0].language
    val motion = rememberChatMotionSettings()
    var catalog by remember { mutableStateOf<ReactionPickerCatalog?>(null) }
    var results by remember { mutableStateOf<List<ReactionEmoji>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("people") }
    val grid = rememberLazyGridState()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val downwardDismiss = remember(density) {
        object : NestedScrollConnection {
            private var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    if (available.y > 0f) {
                        distance += available.y
                        if (distance >= with(density) { 32.dp.toPx() }) { distance = 0f; latestDismiss() }
                    } else distance = 0f
                }
                return Offset.Zero
            }
        }
    }
    var currentHistory by remember(store, history) { mutableStateOf(history) }
    LaunchedEffect(store, history) { currentHistory = withContext(Dispatchers.IO) { store?.snapshot() ?: history } }
    val chooseTone: (String, String, Offset) -> Unit = { base, variant, origin ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store?.setTone(base, variant) } }
            currentHistory = currentHistory.setTone(base, variant)
            onChoose(variant, origin)
        }
    }
    LaunchedEffect(Unit) { catalog = ReactionPickerCatalog.load(context) }
    LaunchedEffect(query, catalog) {
        searching = true
        val text = query
        val loaded = catalog ?: return@LaunchedEffect
        val found = loaded.search(text)
        if (text == query) { results = found; searching = false; grid.scrollToItem(0) }
    }
    val all = stringResource(R.string.reaction_picker_all)
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(colors.deepPanel)
        .border(.5.dp, colors.primaryText.copy(alpha = .10f), RoundedCornerShape(24.dp))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        .testTag("reaction_picker_panel").semantics { paneTitle = all; dismiss { onDismiss(); true } }) {
        Box(Modifier.fillMaxWidth().height(20.dp).pointerInput(onDismiss) {
            var amount = 0f
            detectVerticalDragGestures(onDragStart = { amount = 0f }, onVerticalDrag = { change, dy ->
                amount += dy; if (amount > 32.dp.toPx()) { change.consume(); onDismiss() }
            })
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(34.dp, 4.dp).background(colors.secondaryText.copy(alpha = .55f), CircleShape))
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .heightIn(min = 44.dp).testTag("reaction_picker_search"), singleLine = true,
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(CocoIcons.Search, null, tint = colors.secondaryText) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }, modifier = Modifier.testTag("reaction_picker_clear")) {
                    Icon(CocoIcons.Close, stringResource(R.string.search_clear))
                }
            }) else null,
            placeholder = { Text(stringResource(R.string.emoji_find)) })
        if (query.isBlank() && currentHistory.recent.isNotEmpty()) {
            Text(stringResource(R.string.emoji_recent), Modifier.padding(start = 12.dp, top = 4.dp),
                color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            LazyRow(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp).testTag("reaction_recent_row")) {
                items(currentHistory.recent, key = { it }) { emoji ->
                    ReactionEmojiCell(emoji, catalog?.entry(emoji)
                        ?.localizedName(language) ?: emoji, emoji, onChoose, { variant, origin ->
                            chooseTone(emoji, variant, origin)
                        }, Modifier.width(44.dp), "reaction_recent_$emoji")
                }
            }
        }
        val loaded = catalog
        if (loaded != null) {
            if (query.isBlank()) LazyRow(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp).testTag("reaction_category_bar")) {
                items(loaded.categories, key = { it.id }) { category ->
                    val categoryLabel = stringResource(reactionCategoryLabel(category.id))
                    TextButton(onClick = {
                        selectedCategory = category.id
                        val position = loaded.categories.takeWhile { it.id != category.id }
                            .sumOf { 1 + loaded.entriesByCategory[it.id].orEmpty().size }
                        scope.launch { if (motion.reduceMotion) grid.scrollToItem(position) else grid.animateScrollToItem(position) }
                    }, modifier = Modifier.size(44.dp).background(
                        if (selectedCategory == category.id) colors.primaryText.copy(alpha = .10f) else Color.Transparent, CircleShape)
                        .testTag("reaction_category_${category.id}").semantics { contentDescription = categoryLabel },
                        contentPadding = PaddingValues(0.dp)) {
                        Text(category.symbol, fontSize = (20 / LocalDensity.current.fontScale).sp)
                    }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(44.dp), state = grid,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp).testTag("reaction_picker_grid")
                    .nestedScroll(downwardDismiss)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent),
                            startY = (size.height - 8.dp.toPx()).coerceAtLeast(0f), endY = size.height), blendMode = BlendMode.DstIn)
                    }, contentPadding = PaddingValues(bottom = 8.dp)) {
                if (query.isBlank()) loaded.categories.forEach { category ->
                    item(key = "heading_${category.id}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(reactionCategoryLabel(category.id)), Modifier.padding(top = 4.dp).semantics { heading() },
                            color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                    }
                    items(loaded.entriesByCategory[category.id].orEmpty(), key = { it.emoji }) { entry ->
                        ReactionEmojiCell(entry.emoji, entry.localizedName(language), currentHistory.preferred(entry.emoji),
                            onChoose, { variant, origin -> chooseTone(entry.emoji, variant, origin) })
                    }
                } else if (results.isEmpty() && !searching) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.emoji_no_results), Modifier.padding(16.dp))
                } else items(results, key = { it.emoji }) { entry ->
                    val exact = ReactionTones.base(query.trim()) == ReactionTones.base(entry.emoji)
                    ReactionEmojiCell(entry.emoji, entry.localizedName(language), if (exact) query.trim() else currentHistory.preferred(entry.emoji),
                        onChoose, { variant, origin -> chooseTone(entry.emoji, variant, origin) })
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ReactionEmojiCell(base: String, name: String, preferred: String,
    choose: (String, Offset) -> Unit, rememberTone: (String, Offset) -> Unit,
    modifier: Modifier = Modifier, tag: String = "reaction_emoji_$base") {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var tones by remember { mutableStateOf(false) }
    val variants = remember(base) { ReactionTones.variants(base) }
    val view = LocalView.current
    val toneLabel = stringResource(R.string.reaction_skin_tone)
    Box(modifier.heightIn(min = 44.dp).testTag(tag).onGloballyPositioned { bounds = it.boundsInWindow() }
        .semantics(mergeDescendants = true) {
            contentDescription = name; stateDescription = preferred
            if (variants.isNotEmpty()) customActions = listOf(CustomAccessibilityAction(toneLabel) { tones = true; true })
        }.combinedClickable(onClick = { choose(preferred, bounds.center) }, onLongClick = {
            if (variants.isNotEmpty()) { CocoHaptics.select(view); tones = true }
        }), contentAlignment = Alignment.Center) {
        Text(preferred, fontSize = (30 / LocalDensity.current.fontScale).sp, modifier = Modifier.clearAndSetSemantics {})
        DropdownMenu(expanded = tones, onDismissRequest = { tones = false }) {
            Row(Modifier.padding(horizontal = 4.dp).testTag("reaction_tone_popover")) {
                variants.forEach { variant ->
                    var toneBounds by remember { mutableStateOf(bounds) }
                    Box(Modifier.size(44.dp, 48.dp).onGloballyPositioned { toneBounds = reactionBoundsInHostWindow(it, view) }
                        .testTag("reaction_tone_$variant").semantics { contentDescription = "$name $variant" }
                        .clickable { tones = false; rememberTone(variant, toneBounds.center) },
                        contentAlignment = Alignment.Center) {
                        Text(variant, fontSize = (30 / LocalDensity.current.fontScale).sp)
                    }
                }
            }
        }
    }
}

private fun reactionCategoryLabel(id: String): Int = when (id) {
    "people" -> R.string.emoji_people
    "nature" -> R.string.emoji_nature
    "food" -> R.string.emoji_food
    "activity" -> R.string.emoji_activity
    "travel" -> R.string.emoji_travel
    "objects" -> R.string.emoji_objects
    "symbols" -> R.string.emoji_symbols
    else -> R.string.emoji_flags
}
