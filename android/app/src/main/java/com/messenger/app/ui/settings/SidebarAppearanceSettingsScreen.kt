package com.messenger.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.R
import com.messenger.app.ui.GraphiteDrawer
import com.messenger.app.ui.RootDestination
import com.messenger.app.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

private val SidebarDraftSaver = Saver<SidebarAppearanceSettings, String>(
    save = { SidebarAppearanceCodec.encode(it) }, restore = { SidebarAppearanceCodec.decode(it) },
)

@Composable
internal fun SidebarAppearanceSettingsScreen(
    showCalls: Boolean = true,
    showSaved: Boolean = true,
    onShowCallsChange: (Boolean) -> Unit = {},
    onShowSavedChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preference = remember(context) { SidebarAppearancePreference(context) }
    val initial = remember(preference, showCalls, showSaved) {
        preference.load().withLegacySidebarVisibility(showCalls, showSaved)
    }
    var saveError by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.sidebar_appearance_title), onBack) {
        SidebarAppearanceEditor(initial, onApply = {
            if (preference.save(it)) {
                onShowCallsChange("calls" !in it.hiddenItems)
                onShowSavedChange("saved" !in it.hiddenItems)
                onBack()
            } else saveError = true
        }, onCancel = onBack, saveError = saveError)
    }
}

/** Older visibility switches and the new editor represent the same two menu choices. */
internal fun SidebarAppearanceSettings.withLegacySidebarVisibility(showCalls: Boolean, showSaved: Boolean): SidebarAppearanceSettings {
    var hidden = hiddenItems
    hidden = if (showCalls) hidden - "calls" else hidden + "calls"
    hidden = if (showSaved) hidden - "saved" else hidden + "saved"
    return copy(hiddenItems = hidden).sanitized()
}

/** A saveable local draft. Only the Apply callback may cross the persistence boundary. */
@Composable
internal fun SidebarAppearanceEditor(
    initial: SidebarAppearanceSettings,
    onApply: (SidebarAppearanceSettings) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    saveError: Boolean = false,
) {
    var draft by rememberSaveable(stateSaver = SidebarDraftSaver) { mutableStateOf(initial.sanitized()) }
    var openGroups by rememberSaveable { mutableStateOf(listOf("dimensions")) }
    var fullPreview by rememberSaveable { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<SidebarColorTarget?>(null) }
    val colors = LocalCocoColors.current
    val view = LocalView.current
    BackHandler { onCancel() }
    fun choose(value: SidebarAppearanceSettings) { draft = value.sanitized() }

    Column(modifier.fillMaxSize().testTag("sidebar-appearance-editor")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("sidebar-appearance-controls"),
            contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("preview") {
                Surface(color = colors.surface, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sidebar_appearance_preview), color = colors.primaryText,
                            style = MaterialTheme.typography.titleSmall)
                        SidebarAppearancePreview(draft, Modifier.fillMaxWidth().height(310.dp))
                        SettingsSecondaryButton(onClick = { CocoHaptics.select(view); fullPreview = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sidebar_appearance_enlarge))
                        }
                        Text(stringResource(R.string.sidebar_appearance_preview_note), color = colors.secondaryText,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item("draft-note") { Text(stringResource(R.string.sidebar_appearance_draft_note), color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall) }
            for (group in sidebarEditorGroups) {
                item("group-${group.key}") {
                    Surface(onClick = {
                        CocoHaptics.select(view)
                        openGroups = if (group.key in openGroups) openGroups - group.key else openGroups + group.key
                    }, color = colors.surface, shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.testTag("sidebar-group-${group.key}")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(group.title), color = colors.primaryText, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f))
                            Icon(if (group.key in openGroups) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null, tint = colors.categoryViolet)
                        }
                    }
                }
                if (group.key in openGroups) {
                    items(sidebarEditorFields.filter { it.group == group.key }, key = { it.key }) { field ->
                        SidebarFieldControl(field, draft, onChange = ::choose, onColor = { target -> colorTarget = target })
                    }
                    if (group.key == "profile") item("profile-note") {
                        Text(stringResource(R.string.sidebar_appearance_avatar_note), color = colors.secondaryText,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (group.key == "menu") {
                        item("protected-note") { Text(stringResource(R.string.sidebar_appearance_protected),
                            color = colors.secondaryText, style = MaterialTheme.typography.bodySmall) }
                        items(draft.itemOrder, key = { "item-$it" }) { id ->
                            SidebarMenuItemControl(id, draft, onChange = ::choose, onColor = { colorTarget = it })
                        }
                    }
                }
            }
            item("reset") {
                SettingsSecondaryButton(onClick = { CocoHaptics.select(view); choose(SidebarAppearanceSettings.defaults) },
                    modifier = Modifier.fillMaxWidth().testTag("sidebar-appearance-reset")) {
                    Text(stringResource(R.string.sidebar_appearance_reset))
                }
                Text(stringResource(R.string.sidebar_appearance_reset_note), modifier = Modifier.padding(top = 6.dp),
                    color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (saveError) Text(stringResource(R.string.sidebar_appearance_save_error), color = colors.danger,
            modifier = Modifier.padding(vertical = 6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val large = LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp
            if (large) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SidebarEditorActionButton(stringResource(R.string.appearance_apply), primary = true,
                    enabled = draft.hasValidColors, modifier = Modifier.fillMaxWidth().testTag("sidebar-appearance-apply")) {
                    CocoHaptics.select(view); onApply(draft.sanitized())
                }
                SidebarEditorActionButton(stringResource(R.string.cancel), primary = false,
                    modifier = Modifier.fillMaxWidth().testTag("sidebar-appearance-cancel"), onClick = onCancel)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SidebarEditorActionButton(stringResource(R.string.cancel), primary = false,
                    modifier = Modifier.weight(1f).testTag("sidebar-appearance-cancel"), onClick = onCancel)
                SidebarEditorActionButton(stringResource(R.string.appearance_apply), primary = true,
                    enabled = draft.hasValidColors, modifier = Modifier.weight(1f).testTag("sidebar-appearance-apply")) {
                    CocoHaptics.select(view); onApply(draft.sanitized())
                }
            }
        }
    }
    if (fullPreview) Dialog(onDismissRequest = { fullPreview = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.background, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                TextButton(onClick = { fullPreview = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sidebar_appearance_back_to_editor))
                }
                SidebarAppearancePreview(draft, Modifier.weight(1f).fillMaxWidth(), fullSize = true)
            }
        }
    }
    colorTarget?.let { target ->
        SidebarColorPicker(target.value, target.label, onCancel = { colorTarget = null }, onChoose = { chosen ->
            CocoHaptics.select(view); choose(target.write(draft, chosen)); colorTarget = null
        })
    }
}

/** Content determines height, including large accessibility fonts and wrapped labels. */
@Composable private fun SidebarEditorActionButton(label: String, primary: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = RoundedCornerShape(20.dp), border = if (primary) null else BorderStroke(1.dp, colors.outlineSecondary),
        color = if (primary) colors.accent.copy(alpha = if (enabled) 1f else .15f) else colors.surfaceVariant,
        contentColor = if (!enabled) colors.mutedText else if (primary) colors.onAccent else colors.accentSoft,
        modifier = modifier.then(cocoPressFeedback(interaction, haptic = false)).heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, softWrap = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** The production drawer, with only its avatar IO and navigation callbacks replaced. */
@Composable
internal fun SidebarAppearancePreview(draft: SidebarAppearanceSettings, modifier: Modifier = Modifier, fullSize: Boolean = false) {
    val style = remember(draft) { draft.resolved }
    CompositionLocalProvider(LocalSidebarAppearanceSettings provides draft, LocalResolvedSidebarStyle provides style,
        LocalAppColorStyle provides AppColorStyle.COLORFUL) {
        BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black).testTag("sidebar-appearance-preview"),
            contentAlignment = Alignment.Center) {
            val viewportWidth = if (fullSize) maxWidth.value else 390f
            val viewportHeight = if (fullSize) maxHeight.value else 700f
            val scale = if (fullSize) 1f else minOf(maxWidth.value / viewportWidth, maxHeight.value / viewportHeight)
            Box(Modifier.requiredSize(viewportWidth.dp, viewportHeight.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                GraphiteDrawer(style.metrics(viewportWidth).panelWidth.dp, null, "UNYRI", "preview", 0, false,
                    RootDestination.CHATS, 3, true, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onPeople = {}, isOnline = false, avatar = { size, fill, ink ->
                        Surface(Modifier.size(size), color = fill, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) { Text("C", color = ink, style = MaterialTheme.typography.titleMedium) }
                        }
                    })
            }
        }
    }
}

private data class SidebarEditorGroup(val key: String, @StringRes val title: Int)
private val sidebarEditorGroups = listOf(
    SidebarEditorGroup("dimensions", R.string.sidebar_group_dimensions),
    SidebarEditorGroup("colors", R.string.sidebar_group_colors),
    SidebarEditorGroup("icons", R.string.sidebar_group_icons),
    SidebarEditorGroup("profile", R.string.sidebar_group_profile),
    SidebarEditorGroup("menu", R.string.sidebar_group_menu),
    SidebarEditorGroup("details", R.string.sidebar_group_details),
)

private sealed interface SidebarEditorField {
    val key: String; val group: String; val label: Int
    data class Number(override val key: String, override val group: String, override val label: Int,
        val min: Float, val max: Float, val step: Float, val unit: String,
        val read: (SidebarAppearanceSettings) -> Float, val write: (SidebarAppearanceSettings, Float) -> SidebarAppearanceSettings) : SidebarEditorField
    data class Toggle(override val key: String, override val group: String, override val label: Int,
        val read: (SidebarAppearanceSettings) -> Boolean, val write: (SidebarAppearanceSettings, Boolean) -> SidebarAppearanceSettings) : SidebarEditorField
    data class Choice(override val key: String, override val group: String, override val label: Int, val values: List<String>,
        val read: (SidebarAppearanceSettings) -> String, val write: (SidebarAppearanceSettings, String) -> SidebarAppearanceSettings) : SidebarEditorField
    data class Color(override val key: String, override val group: String, override val label: Int,
        val read: (SidebarAppearanceSettings) -> String, val write: (SidebarAppearanceSettings, String) -> SidebarAppearanceSettings) : SidebarEditorField
}

private data class SidebarColorTarget(val value: String, val label: String,
    val write: (SidebarAppearanceSettings, String) -> SidebarAppearanceSettings)

private fun sidebarFieldEnabled(key: String, draft: SidebarAppearanceSettings): Boolean = when (key) {
    "materialAngle", "materialTop", "materialBottom" -> draft.materialGradient
    "iconsColor" -> !draft.iconsColored
    "iconsTileSize", "iconsTileRadius" -> draft.iconsTiles
    "patternColor", "patternOpacity", "patternSize", "patternSpacing" -> draft.patternKind != "none"
    else -> true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SidebarFieldControl(field: SidebarEditorField, draft: SidebarAppearanceSettings,
    onChange: (SidebarAppearanceSettings) -> Unit, onColor: (SidebarColorTarget) -> Unit) {
    val colors = LocalCocoColors.current
    val title = stringResource(field.label)
    val view = LocalView.current
    val enabled = sidebarFieldEnabled(field.key, draft)
    val tag = if (field.key == "geometryWidth") "sidebar-appearance-width" else "sidebar-field-${field.key}"
    Surface(color = colors.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (field) {
                is SidebarEditorField.Number -> {
                    val value = field.read(draft)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = colors.primaryText, modifier = Modifier.weight(1f))
                        val suffix = when (field.unit) { "percent" -> "%"; "angle" -> "°"; "ms" -> stringResource(R.string.sidebar_unit_ms); else -> field.unit }
                        val display = if (field.step >= 1f) value.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
                        Text("$display$suffix", color = colors.categoryBlue, modifier = Modifier.padding(start = 8.dp))
                    }
                    Slider(value.coerceIn(field.min, field.max), onValueChange = {
                        onChange(field.write(draft, ((it / field.step).roundToInt() * field.step).coerceIn(field.min, field.max)))
                    }, valueRange = field.min..field.max, steps = (((field.max - field.min) / field.step).roundToInt() - 1).coerceAtLeast(0),
                        onValueChangeFinished = { CocoHaptics.select(view) }, enabled = enabled, modifier = Modifier.testTag(tag))
                }
                is SidebarEditorField.Toggle -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(field.read(draft), role = Role.Switch) { CocoHaptics.select(view); onChange(field.write(draft, it)) }.testTag(tag),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = colors.primaryText, modifier = Modifier.weight(1f))
                    Switch(checked = field.read(draft), onCheckedChange = null)
                }
                is SidebarEditorField.Choice -> {
                    Text(title, color = colors.primaryText)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        field.values.forEach { value ->
                            FilterChip(selected = field.read(draft) == value,
                                onClick = { CocoHaptics.select(view); onChange(field.write(draft, value)) },
                                label = { Text(sidebarOptionName(value)) }, modifier = Modifier.testTag("$tag-$value"))
                        }
                    }
                }
                is SidebarEditorField.Color -> SidebarColorButton(title, field.read(draft), enabled, tag) {
                    onColor(SidebarColorTarget(field.read(draft), title, field.write))
                }
            }
        }
    }
}

@Composable private fun SidebarColorButton(label: String, value: String, enabled: Boolean = true, tag: String,
    onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(sidebarRgb(value)))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) colors.primaryText else colors.mutedText)
            Text(value, color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun SidebarMenuItemControl(id: String, draft: SidebarAppearanceSettings,
    onChange: (SidebarAppearanceSettings) -> Unit, onColor: (SidebarColorTarget) -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    val title = sidebarItemName(id)
    val mandatory = id == "chats" || id == "settings"
    val sameSection = draft.itemOrder.filter { !draft.rowsGrouped || (it in listOf("chats", "calls", "saved", "settings")) == (id in listOf("chats", "calls", "saved", "settings")) }
    val position = sameSection.indexOf(id)
    fun move(delta: Int) {
        val other = sameSection.getOrNull(position + delta) ?: return
        val order = draft.itemOrder.toMutableList()
        val a = order.indexOf(id); val b = order.indexOf(other)
        order[a] = other; order[b] = id
        CocoHaptics.select(view); onChange(draft.copy(itemOrder = order))
    }
    val itemDefault = SavedSidebarStyle.items.first { it.id == id }
    val value = draft.itemColors[id] ?: String.format(Locale.ROOT, "#%06X", itemDefault.color and 0xFFFFFFL)
    Surface(color = colors.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("sidebar-item-$id")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.primaryText, modifier = Modifier.weight(1f))
                Switch(checked = id !in draft.hiddenItems, enabled = !mandatory, onCheckedChange = { visible ->
                    CocoHaptics.select(view)
                    onChange(draft.copy(hiddenItems = if (visible) draft.hiddenItems - id else draft.hiddenItems + id))
                }, modifier = Modifier.testTag("sidebar-item-visible-$id"))
            }
            SidebarColorButton(stringResource(R.string.sidebar_appearance_item_color), value, draft.iconsColored, "sidebar-item-color-$id") {
                onColor(SidebarColorTarget(value, title) { settings, color -> settings.copy(itemColors = settings.itemColors + (id to color)) })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { move(-1) }, enabled = position > 0, modifier = Modifier.testTag("sidebar-item-up-$id")) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.sidebar_appearance_move_up), tint = if (position > 0) colors.categoryBlue else colors.mutedText)
                }
                IconButton(onClick = { move(1) }, enabled = position in 0 until sameSection.lastIndex, modifier = Modifier.testTag("sidebar-item-down-$id")) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.sidebar_appearance_move_down), tint = if (position < sameSection.lastIndex) colors.categoryBlue else colors.mutedText)
                }
            }
        }
    }
}

private fun sidebarRgb(value: String): Color = Color(0xFF000000L or value.removePrefix("#").toLong(16))

@Composable private fun SidebarColorPicker(initial: String, title: String, onCancel: () -> Unit, onChoose: (String) -> Unit) {
    var hex by rememberSaveable(initial) { mutableStateOf(initial.uppercase(Locale.ROOT)) }
    val valid = Regex("^#[0-9A-Fa-f]{6}$").matches(hex)
    val color = if (valid) sidebarRgb(hex) else sidebarRgb(initial)
    val rgb = if (valid) hex.drop(1).toInt(16) else initial.drop(1).toInt(16)
    fun channel(shift: Int, value: Float) {
        val changed = (rgb and (0xFF shl shift).inv()) or (value.roundToInt() shl shift)
        hex = String.format(Locale.ROOT, "#%06X", changed)
    }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("sidebar-color-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(color))
                OutlinedTextField(value = hex, onValueChange = { hex = it.uppercase(Locale.ROOT).take(7) },
                    label = { Text(stringResource(R.string.sidebar_appearance_hex)) }, singleLine = true, isError = !valid,
                    supportingText = { if (!valid) Text(stringResource(R.string.sidebar_appearance_hex_error)) }, modifier = Modifier.testTag("sidebar-color-hex"))
                for ((label, shift) in listOf("R" to 16, "G" to 8, "B" to 0)) {
                    val value = (rgb shr shift) and 255
                    Text("$label  $value")
                    Slider(value.toFloat(), { channel(shift, it) }, valueRange = 0f..255f, steps = 254,
                        modifier = Modifier.testTag("sidebar-color-$label"))
                }
                listOf("#202630", "#171D26", "#FFFFFF", "#77C5E5", "#7BCBB0", "#D9B876", "#AC9CDD", "#D99AA3").chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { swatch ->
                            Box(Modifier.weight(1f).heightIn(min = 44.dp).clickable { hex = swatch }
                                .semantics { contentDescription = swatch }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(sidebarRgb(swatch)))
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { onChoose(hex) }, enabled = valid, modifier = Modifier.testTag("sidebar-color-use")) {
            Text(stringResource(R.string.sidebar_appearance_use_color))
        } }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun sidebarItemName(id: String): String = stringResource(when (id) {
    "chats" -> R.string.nav_chats; "calls" -> R.string.nav_calls; "saved" -> R.string.saved_messages
    "settings" -> R.string.nav_settings; "server" -> R.string.server_label; "devices" -> R.string.devices_title
    "help" -> R.string.drawer_help; else -> R.string.feedback_title
})

@Composable private fun sidebarOptionName(value: String): String = stringResource(when (value) {
    "system" -> R.string.sidebar_option_system; "serif" -> R.string.sidebar_option_serif; "mono" -> R.string.sidebar_option_mono
    "compact" -> R.string.sidebar_option_compact; "center" -> R.string.sidebar_option_center
    "row" -> R.string.sidebar_option_row; "stack" -> R.string.sidebar_option_stack
    "none" -> R.string.sidebar_option_none; "dots" -> R.string.sidebar_option_dots
    "lines" -> R.string.sidebar_option_lines; "diamonds" -> R.string.sidebar_option_diamonds
    "400" -> R.string.sidebar_option_regular; "500" -> R.string.sidebar_option_medium; else -> R.string.sidebar_option_semibold
})

// Field descriptors below are explicit typed .copy calls, not reflection or runtime JSON editing.
private val sidebarEditorFields: List<SidebarEditorField> = listOf(
    SidebarEditorField.Number("geometryWidth", "dimensions", R.string.sidebar_field_geometry_width, 34f, 80f, 1f, "percent", { it.geometryWidth }, { s, v -> s.copy(geometryWidth = v) }),
    SidebarEditorField.Number("geometryCorner", "dimensions", R.string.sidebar_field_geometry_corner, 0f, 40f, 1f, "dp", { it.geometryCorner }, { s, v -> s.copy(geometryCorner = v) }),
    SidebarEditorField.Number("geometryInset", "dimensions", R.string.sidebar_field_geometry_inset, 6f, 24f, 1f, "dp", { it.geometryInset }, { s, v -> s.copy(geometryInset = v) }),
    SidebarEditorField.Number("geometryRowHeight", "dimensions", R.string.sidebar_field_geometry_row_height, 44f, 66f, 1f, "dp", { it.geometryRowHeight }, { s, v -> s.copy(geometryRowHeight = v) }),
    SidebarEditorField.Number("geometrySecondaryHeight", "dimensions", R.string.sidebar_field_geometry_secondary_height, 44f, 66f, 1f, "dp", { it.geometrySecondaryHeight }, { s, v -> s.copy(geometrySecondaryHeight = v) }),
    SidebarEditorField.Number("geometryRowGap", "dimensions", R.string.sidebar_field_geometry_row_gap, 0f, 10f, 1f, "dp", { it.geometryRowGap }, { s, v -> s.copy(geometryRowGap = v) }),
    SidebarEditorField.Number("geometryRowPaddingX", "dimensions", R.string.sidebar_field_geometry_row_padding_x, 6f, 22f, 1f, "dp", { it.geometryRowPaddingX }, { s, v -> s.copy(geometryRowPaddingX = v) }),
    SidebarEditorField.Number("geometryRowPaddingY", "dimensions", R.string.sidebar_field_geometry_row_padding_y, 4f, 12f, 1f, "dp", { it.geometryRowPaddingY }, { s, v -> s.copy(geometryRowPaddingY = v) }),
    SidebarEditorField.Number("geometryIconGap", "dimensions", R.string.sidebar_field_geometry_icon_gap, 6f, 20f, 1f, "dp", { it.geometryIconGap }, { s, v -> s.copy(geometryIconGap = v) }),
    SidebarEditorField.Color("materialBackground", "colors", R.string.sidebar_field_material_background, { it.materialBackground }, { s, v -> s.copy(materialBackground = v) }),
    SidebarEditorField.Color("materialTop", "colors", R.string.sidebar_field_material_top, { it.materialTop }, { s, v -> s.copy(materialTop = v) }),
    SidebarEditorField.Color("materialBottom", "colors", R.string.sidebar_field_material_bottom, { it.materialBottom }, { s, v -> s.copy(materialBottom = v) }),
    SidebarEditorField.Toggle("materialGradient", "colors", R.string.sidebar_field_material_gradient, { it.materialGradient }, { s, v -> s.copy(materialGradient = v) }),
    SidebarEditorField.Number("materialAngle", "colors", R.string.sidebar_field_material_angle, 0f, 360f, 1f, "angle", { it.materialAngle }, { s, v -> s.copy(materialAngle = v) }),
    SidebarEditorField.Number("materialOpacity", "colors", R.string.sidebar_field_material_opacity, 40f, 100f, 1f, "percent", { it.materialOpacity }, { s, v -> s.copy(materialOpacity = v) }),
    SidebarEditorField.Color("materialBorderColor", "colors", R.string.sidebar_field_material_border_color, { it.materialBorderColor }, { s, v -> s.copy(materialBorderColor = v) }),
    SidebarEditorField.Number("materialBorderWidth", "colors", R.string.sidebar_field_material_border_width, 0f, 2f, 0.25f, "dp", { it.materialBorderWidth }, { s, v -> s.copy(materialBorderWidth = v) }),
    SidebarEditorField.Number("materialBorderOpacity", "colors", R.string.sidebar_field_material_border_opacity, 0f, 100f, 1f, "percent", { it.materialBorderOpacity }, { s, v -> s.copy(materialBorderOpacity = v) }),
    SidebarEditorField.Number("materialShadow", "colors", R.string.sidebar_field_material_shadow, 0f, 12f, 1f, "dp", { it.materialShadow }, { s, v -> s.copy(materialShadow = v) }),
    SidebarEditorField.Choice("typographyFamily", "icons", R.string.sidebar_field_typography_family, listOf("system", "serif", "mono"), { it.typographyFamily }, { s, v -> s.copy(typographyFamily = v) }),
    SidebarEditorField.Color("typographyText", "icons", R.string.sidebar_field_typography_text, { it.typographyText }, { s, v -> s.copy(typographyText = v) }),
    SidebarEditorField.Color("typographyMuted", "icons", R.string.sidebar_field_typography_muted, { it.typographyMuted }, { s, v -> s.copy(typographyMuted = v) }),
    SidebarEditorField.Number("typographySize", "icons", R.string.sidebar_field_typography_size, 11f, 18f, 1f, "sp", { it.typographySize }, { s, v -> s.copy(typographySize = v) }),
    SidebarEditorField.Number("typographySecondarySize", "icons", R.string.sidebar_field_typography_secondary_size, 10f, 16f, 1f, "sp", { it.typographySecondarySize }, { s, v -> s.copy(typographySecondarySize = v) }),
    SidebarEditorField.Choice("typographyWeight", "icons", R.string.sidebar_field_typography_weight, listOf("400", "500", "600"), { it.typographyWeight }, { s, v -> s.copy(typographyWeight = v) }),
    SidebarEditorField.Number("typographyLetterSpacing", "icons", R.string.sidebar_field_typography_letter_spacing, 0f, 1f, 0.05f, "sp", { it.typographyLetterSpacing }, { s, v -> s.copy(typographyLetterSpacing = v) }),
    SidebarEditorField.Choice("profileLayout", "profile", R.string.sidebar_field_profile_layout, listOf("compact", "center"), { it.profileLayout }, { s, v -> s.copy(profileLayout = v) }),
    SidebarEditorField.Toggle("profileShowAvatar", "profile", R.string.sidebar_field_profile_show_avatar, { it.profileShowAvatar }, { s, v -> s.copy(profileShowAvatar = v) }),
    SidebarEditorField.Toggle("profileShowUsername", "profile", R.string.sidebar_field_profile_show_username, { it.profileShowUsername }, { s, v -> s.copy(profileShowUsername = v) }),
    SidebarEditorField.Toggle("profileShowEdit", "profile", R.string.sidebar_field_profile_show_edit, { it.profileShowEdit }, { s, v -> s.copy(profileShowEdit = v) }),
    SidebarEditorField.Number("avatarSize", "profile", R.string.sidebar_field_avatar_size, 26f, 68f, 1f, "dp", { it.avatarSize }, { s, v -> s.copy(avatarSize = v) }),
    SidebarEditorField.Toggle("avatarOnline", "profile", R.string.sidebar_field_avatar_online, { it.avatarOnline }, { s, v -> s.copy(avatarOnline = v) }),
    SidebarEditorField.Number("iconsSize", "icons", R.string.sidebar_field_icons_size, 14f, 26f, 1f, "dp", { it.iconsSize }, { s, v -> s.copy(iconsSize = v) }),
    SidebarEditorField.Color("iconsColor", "icons", R.string.sidebar_field_icons_color, { it.iconsColor }, { s, v -> s.copy(iconsColor = v) }),
    SidebarEditorField.Toggle("iconsColored", "icons", R.string.sidebar_field_icons_colored, { it.iconsColored }, { s, v -> s.copy(iconsColored = v) }),
    SidebarEditorField.Toggle("iconsTiles", "icons", R.string.sidebar_field_icons_tiles, { it.iconsTiles }, { s, v -> s.copy(iconsTiles = v) }),
    SidebarEditorField.Number("iconsTileSize", "icons", R.string.sidebar_field_icons_tile_size, 0f, 8f, 1f, "dp", { it.iconsTileSize }, { s, v -> s.copy(iconsTileSize = v) }),
    SidebarEditorField.Number("iconsTileRadius", "icons", R.string.sidebar_field_icons_tile_radius, 0f, 16f, 1f, "dp", { it.iconsTileRadius }, { s, v -> s.copy(iconsTileRadius = v) }),
    SidebarEditorField.Toggle("iconsChevrons", "icons", R.string.sidebar_field_icons_chevrons, { it.iconsChevrons }, { s, v -> s.copy(iconsChevrons = v) }),
    SidebarEditorField.Number("rowsRadius", "dimensions", R.string.sidebar_field_rows_radius, 0f, 32f, 1f, "dp", { it.rowsRadius }, { s, v -> s.copy(rowsRadius = v) }),
    SidebarEditorField.Color("rowsFill", "colors", R.string.sidebar_field_rows_fill, { it.rowsFill }, { s, v -> s.copy(rowsFill = v) }),
    SidebarEditorField.Number("rowsFillOpacity", "colors", R.string.sidebar_field_rows_fill_opacity, 0f, 100f, 1f, "percent", { it.rowsFillOpacity }, { s, v -> s.copy(rowsFillOpacity = v) }),
    SidebarEditorField.Toggle("rowsGrouped", "menu", R.string.sidebar_field_rows_grouped, { it.rowsGrouped }, { s, v -> s.copy(rowsGrouped = v) }),
    SidebarEditorField.Color("selectedFill", "colors", R.string.sidebar_field_selected_fill, { it.selectedFill }, { s, v -> s.copy(selectedFill = v) }),
    SidebarEditorField.Number("selectedOpacity", "colors", R.string.sidebar_field_selected_opacity, 0f, 100f, 1f, "percent", { it.selectedOpacity }, { s, v -> s.copy(selectedOpacity = v) }),
    SidebarEditorField.Color("selectedBadge", "colors", R.string.sidebar_field_selected_badge, { it.selectedBadge }, { s, v -> s.copy(selectedBadge = v) }),
    SidebarEditorField.Toggle("quickVisible", "details", R.string.sidebar_field_quick_visible, { it.quickVisible }, { s, v -> s.copy(quickVisible = v) }),
    SidebarEditorField.Choice("quickLayout", "details", R.string.sidebar_field_quick_layout, listOf("row", "stack"), { it.quickLayout }, { s, v -> s.copy(quickLayout = v) }),
    SidebarEditorField.Toggle("dividerVisible", "details", R.string.sidebar_field_divider_visible, { it.dividerVisible }, { s, v -> s.copy(dividerVisible = v) }),
    SidebarEditorField.Toggle("footerShowLogout", "details", R.string.sidebar_field_footer_show_logout, { it.footerShowLogout }, { s, v -> s.copy(footerShowLogout = v) }),
    SidebarEditorField.Toggle("footerShowVersion", "details", R.string.sidebar_field_footer_show_version, { it.footerShowVersion }, { s, v -> s.copy(footerShowVersion = v) }),
    SidebarEditorField.Choice("patternKind", "details", R.string.sidebar_field_pattern_kind, listOf("none", "dots", "lines", "diamonds"), { it.patternKind }, { s, v -> s.copy(patternKind = v) }),
    SidebarEditorField.Color("patternColor", "details", R.string.sidebar_field_pattern_color, { it.patternColor }, { s, v -> s.copy(patternColor = v) }),
    SidebarEditorField.Number("patternOpacity", "details", R.string.sidebar_field_pattern_opacity, 0f, 12f, 1f, "percent", { it.patternOpacity }, { s, v -> s.copy(patternOpacity = v) }),
    SidebarEditorField.Number("patternSize", "details", R.string.sidebar_field_pattern_size, 8f, 40f, 1f, "dp", { it.patternSize }, { s, v -> s.copy(patternSize = v) }),
    SidebarEditorField.Number("patternSpacing", "details", R.string.sidebar_field_pattern_spacing, 24f, 120f, 1f, "dp", { it.patternSpacing }, { s, v -> s.copy(patternSpacing = v) }),
    SidebarEditorField.Number("behaviorDuration", "details", R.string.sidebar_field_behavior_duration, 100f, 280f, 1f, "ms", { it.behaviorDuration }, { s, v -> s.copy(behaviorDuration = v) }),
    SidebarEditorField.Toggle("behaviorSwipeOpen", "details", R.string.sidebar_field_behavior_swipe_open, { it.behaviorSwipeOpen }, { s, v -> s.copy(behaviorSwipeOpen = v) }),
    SidebarEditorField.Toggle("behaviorSwipeClose", "details", R.string.sidebar_field_behavior_swipe_close, { it.behaviorSwipeClose }, { s, v -> s.copy(behaviorSwipeClose = v) }),
    SidebarEditorField.Number("behaviorScrim", "details", R.string.sidebar_field_behavior_scrim, 0f, 70f, 1f, "percent", { it.behaviorScrim }, { s, v -> s.copy(behaviorScrim = v) }),
)
