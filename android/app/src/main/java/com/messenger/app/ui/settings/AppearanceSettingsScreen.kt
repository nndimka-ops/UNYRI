package com.messenger.app.ui.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.chats.CapsuleChatStyle
import com.messenger.app.ui.chats.MessageBubble
import com.messenger.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val AppearanceDraftSaver = listSaver<ChatAppearanceSettings, Any>(
    save = { listOf(it.fontSize, it.fontFamily, it.mediumText, it.lineHeight, it.density,
        it.cornerRadius ?: -1f, it.outgoingColor, it.transparency ?: -1, it.patterned) },
    restore = { saved -> runCatching {
        ChatAppearanceSettings(saved[0] as Int, saved[1] as String, saved[2] as Boolean,
            saved[3] as Float, saved[4] as String, (saved[5] as Float).takeIf { radius -> radius >= 0f },
            saved[6] as String, (saved[7] as Int).takeIf { alpha -> alpha >= 0 }, saved[8] as Boolean).sanitized()
    }.getOrNull() },
)

@Composable
internal fun ChatAppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE) }
    val active = LocalChatAppearanceSettings.current
    SettingsScaffold(stringResource(R.string.appearance_title), onBack) {
        ChatAppearanceEditor(active, onApply = {
            saveChatAppearanceSettings(preferences, it)
            onBack()
        }, onCancel = onBack)
    }
}

/** Only Apply crosses the persistence boundary. Every slider otherwise changes this local draft. */
@Composable
internal fun ChatAppearanceEditor(
    initial: ChatAppearanceSettings,
    onApply: (ChatAppearanceSettings) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    showConnectionSetting: Boolean = true,
) {
    var draft by rememberSaveable(stateSaver = AppearanceDraftSaver) { mutableStateOf(initial.sanitized()) }
    val colors = LocalCocoColors.current
    val view = LocalView.current
    fun choose(next: ChatAppearanceSettings) { CocoHaptics.select(view); draft = next }

    val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Column(controlsModifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("appearance-controls"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showConnectionSetting) AppearanceGroup(stringResource(R.string.app_color_style_title)) {
                AppColorStyleSetting()
            }
            AppearanceGroup(stringResource(R.string.appearance_text_group)) {
                AppearanceChoiceRow(stringResource(R.string.appearance_font_family), draft.fontFamily,
                    listOf("system" to stringResource(R.string.appearance_family_system),
                        "serif" to stringResource(R.string.appearance_family_serif),
                        "monospace" to stringResource(R.string.appearance_family_mono)),
                    "appearance-family") { choose(draft.copy(fontFamily = it)) }
                AppearanceSlider(stringResource(R.string.chat_text_size_title), "${draft.fontSize}",
                    draft.fontSize.toFloat(), 13f..22f, 8, "appearance-font-size",
                    { draft = draft.copy(fontSize = it.roundToInt()) })
                AppearanceSwitch(stringResource(R.string.appearance_medium_text), draft.mediumText,
                    "appearance-medium") { choose(draft.copy(mediumText = it)) }
                AppearanceSlider(stringResource(R.string.appearance_line_height),
                    String.format(java.util.Locale.ROOT, "%.2f", draft.lineHeight), draft.lineHeight,
                    1.05f..1.7f, 12, "appearance-line-height", { draft = draft.copy(lineHeight = it) })
            }
            AppearanceGroup(stringResource(R.string.appearance_bubbles_group)) {
                AppearanceChoiceRow(stringResource(R.string.appearance_density), draft.density,
                    listOf("compact" to stringResource(R.string.appearance_density_compact),
                        "default" to stringResource(R.string.appearance_density_default),
                        "relaxed" to stringResource(R.string.appearance_density_relaxed)),
                    "appearance-density") { choose(draft.copy(density = it)) }
                val radius = draft.cornerRadius ?: CapsuleChatStyle.radiusForSettings()
                AppearanceSlider(stringResource(R.string.appearance_rounding),
                    if (draft.cornerRadius == null) stringResource(R.string.appearance_standard_value)
                    else "${radius.roundToInt()}", radius, 0f..42f, 41, "appearance-radius",
                    { draft = draft.copy(cornerRadius = it) })
                AppearanceColorChoices(draft.outgoingColor) { choose(draft.copy(outgoingColor = it)) }
                val transparency = draft.transparency ?: ((1f - CapsuleChatStyle.outgoing.alpha) * 100f).roundToInt()
                AppearanceSlider(stringResource(R.string.appearance_transparency), "$transparency%",
                    transparency.toFloat(), 0f..85f, 16, "appearance-transparency",
                    { draft = draft.copy(transparency = it.roundToInt()) })
            }
            AppearanceGroup(stringResource(R.string.wallpaper_title)) {
                AppearanceSwitch(stringResource(R.string.appearance_pattern_toggle), draft.patterned,
                    "appearance-pattern") { choose(draft.copy(patterned = it)) }
            }
            Surface(color = colors.surface, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(stringResource(R.string.appearance_restore_title), fontWeight = FontWeight.Medium,
                        color = colors.primaryText)
                    Text(stringResource(R.string.appearance_restore_detail), style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    SettingsSecondaryButton(onClick = { choose(defaultChatAppearanceSettings()) },
                        modifier = Modifier.fillMaxWidth().testTag("appearance-reset")) {
                        Text(stringResource(R.string.appearance_restore_action))
                    }
                }
            }
            if (showConnectionSetting) AppearanceGroup(stringResource(R.string.appearance_additional_group)) {
                ConnectionPingSetting()
                Text(stringResource(R.string.appearance_connection_immediate), style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("appearance-editor")) {
        val availableHeight = maxHeight
        val sideBySide = availableHeight < 360.dp && maxWidth >= 480.dp
        var compactPreview by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            when {
                sideBySide -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppearanceChatPreview(draft, Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
                availableHeight < 340.dp -> {
                    TextButton(onClick = { CocoHaptics.select(view); compactPreview = !compactPreview },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("appearance-compact-preview")) {
                        Text(stringResource(if (compactPreview) R.string.appearance_back_to_controls else R.string.appearance_show_preview))
                    }
                    if (compactPreview) AppearanceChatPreview(draft, Modifier.weight(1f).fillMaxWidth())
                    else controls(Modifier.weight(1f))
                }
                else -> {
                    val previewHeight = ((availableHeight - 70.dp) * .42f).coerceIn(150.dp, 290.dp)
                    AppearanceChatPreview(draft, Modifier.fillMaxWidth().height(previewHeight))
                    Spacer(Modifier.height(10.dp))
                    controls(Modifier.weight(1f))
                }
            }
            AppearanceEditorFooter(onApply = { CocoHaptics.tap(view); onApply(draft) },
                onCancel = { CocoHaptics.tap(view); onCancel() })
        }
    }
}

/** App-wide colors apply immediately; the message-layout draft stays behind Apply. */
@Composable
private fun AppColorStyleSetting() {
    val context = LocalContext.current
    val view = LocalView.current
    val preference = remember(context) { AppColorStylePreference(context) }
    AppColorStyleSelector(LocalAppColorStyle.current, onSelected = {
        CocoHaptics.select(view)
        preference.set(it)
    })
    Text(stringResource(R.string.app_color_style_detail), color = LocalCocoColors.current.secondaryText,
        style = MaterialTheme.typography.bodySmall)
    if (LocalAppColorStyle.current == AppColorStyle.MONOCHROME) {
        Text(stringResource(R.string.app_color_style_chat_preview_detail), color = LocalCocoColors.current.secondaryText,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Shared production selector; synthetic previews may supply a local callback. */
@Composable
internal fun AppColorStyleSelector(selected: AppColorStyle, onSelected: (AppColorStyle) -> Unit) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth().testTag("app-color-style-selector"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (style in AppColorStyle.entries) {
            val checked = selected == style
            val label = stringResource(if (style == AppColorStyle.COLORFUL) R.string.app_color_style_colorful else R.string.app_color_style_monochrome)
            val preview = if (style == AppColorStyle.COLORFUL) CocoColorfulColors else CocoMonochromeColors
            Surface(Modifier.fillMaxWidth().selectable(selected = checked, role = Role.RadioButton,
                onClick = { if (!checked) onSelected(style) }).testTag("app-color-style-${style.preferenceValue}"),
                color = if (checked) colors.surfaceVariant else colors.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (checked) colors.categoryBlue else colors.divider)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioButton(selected = checked, onClick = null)
                    Text(label, modifier = Modifier.weight(1f), color = colors.primaryText,
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (tint in listOf(preview.categoryBlue, preview.categoryViolet, preview.categoryMint)) {
                            Box(Modifier.size(10.dp).background(tint, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

/** Large text gets full-width actions instead of clipped labels in two narrow columns. */
@Composable
private fun AppearanceEditorFooter(onApply: () -> Unit, onCancel: () -> Unit) {
    val applyLabel = stringResource(R.string.appearance_apply)
    val cancelLabel = stringResource(R.string.cancel)
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val labelWidths = remember(applyLabel, cancelLabel, labelStyle, density.density, density.fontScale, measurer) {
        val apply = measurer.measure(applyLabel, style = labelStyle, maxLines = 1, softWrap = false).size.width
        val cancel = measurer.measure(cancelLabel, style = labelStyle, maxLines = 1, softWrap = false).size.width
        apply to cancel
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val perButtonWidth = (availableWidth - with(density) { 10.dp.toPx() }) / 2f
        val stacked = labelWidths.first + with(density) { 36.dp.toPx() } > perButtonWidth ||
            labelWidths.second + with(density) { 32.dp.toPx() } > perButtonWidth ||
            (density.fontScale >= 1.5f && maxWidth < 420.dp)
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsPrimaryButton(onClick = onApply, modifier = Modifier.fillMaxWidth().testTag("appearance-apply")) {
                    Text(applyLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                SettingsSecondaryButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("appearance-cancel")) {
                    Text(cancelLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("appearance-cancel")) {
                    Text(cancelLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false)
                }
                SettingsPrimaryButton(onClick = onApply, modifier = Modifier.weight(1f).testTag("appearance-apply")) {
                    Text(applyLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// All nonzero saved body corners use this baseline; the fixed zero notch is retained by the resolver.
private fun CapsuleChatStyle.radiusForSettings() = incomingBodyShape.topStart.value

@Composable
private fun AppearanceGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Surface(Modifier.fillMaxWidth(), color = colors.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.primaryText)
            content()
        }
    }
}

@Composable
private fun AppearanceSlider(title: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, tag: String, onValue: (Float) -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.primaryText, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 10.dp))
            Text(display, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue,
            valueRange = range, steps = steps, onValueChangeFinished = { CocoHaptics.select(view) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = title })
    }
}

@Composable
private fun AppearanceSwitch(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.primaryText, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun AppearanceChoiceRow(title: String, selected: String, choices: List<Pair<String, String>>,
    tag: String, onChoose: (String) -> Unit) {
    val colors = LocalCocoColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
        // A vertical list survives large text and makes all three real families readable.
        choices.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected == id, role = Role.RadioButton, onClick = { onChoose(id) })
                .background(if (selected == id) colors.primaryText.copy(alpha = .05f) else Color.Transparent,
                    RoundedCornerShape(12.dp)).padding(horizontal = 8.dp).testTag("$tag-$id"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = colors.primaryText, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
                RadioButton(selected == id, onClick = null)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceColorChoices(selected: String, onChoose: (String) -> Unit) {
    val colors = LocalCocoColors.current
    val choices = listOf("default" to stringResource(R.string.appearance_color_default),
        "blue" to stringResource(R.string.appearance_color_blue), "violet" to stringResource(R.string.appearance_color_violet),
        "green" to stringResource(R.string.appearance_color_green), "graphite" to stringResource(R.string.appearance_color_graphite))
    Text(stringResource(R.string.appearance_outgoing_color), color = colors.primaryText, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { (id, label) ->
            Column(Modifier.widthIn(min = 50.dp).selectable(selected == id, role = Role.RadioButton,
                onClick = { onChoose(id) }).padding(3.dp).testTag("appearance-color-$id"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(Modifier.size(48.dp), shape = CircleShape, color = chatAppearanceOutgoingTint(id),
                    border = if (selected == id) BorderStroke(2.dp, colors.primaryText) else BorderStroke(1.dp, colors.outlineSecondary)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected == id) Icon(CocoIcons.Check, null, tint = colors.primaryText, modifier = Modifier.size(18.dp))
                    }
                }
                Text(label, color = colors.secondaryText, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
internal fun AppearanceChatPreview(settings: ChatAppearanceSettings, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    val fontScale = LocalDensity.current.fontScale
    val view = LocalView.current
    var attachments by rememberSaveable { mutableStateOf(false) }
    val safeSettings = remember(settings) { settings.sanitized() }
    val resolved = remember(safeSettings) { resolveChatAppearance(safeSettings) }
    Surface(modifier.testTag("appearance-preview"), shape = RoundedCornerShape(22.dp), color = colors.background,
        border = BorderStroke(1.dp, colors.outlineSecondary)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.appearance_live_preview), style = MaterialTheme.typography.labelLarge,
                    color = colors.primaryText, modifier = Modifier.weight(1f))
                if (fontScale <= 1.3f) Text(stringResource(R.string.appearance_preview_only), style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { CocoHaptics.select(view); attachments = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("appearance-preview-text")) {
                    Text(stringResource(R.string.appearance_preview_messages), style = MaterialTheme.typography.labelMedium,
                        color = if (!attachments) colors.primaryText else colors.secondaryText)
                }
                TextButton(onClick = { CocoHaptics.select(view); attachments = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("appearance-preview-attachments")) {
                    Text(stringResource(R.string.appearance_preview_media), style = MaterialTheme.typography.labelMedium,
                        color = if (attachments) colors.primaryText else colors.secondaryText)
                }
            }
            CompositionLocalProvider(LocalChatAppearanceSettings provides safeSettings, LocalChatAppearanceStyle provides resolved,
                LocalChatTextSize provides safeSettings.fontSize, LocalCocoPatternEnabled provides safeSettings.patterned,
                LocalCocoColors provides CapsuleChatStyle.colors) {
                val previewColors = LocalCocoColors.current
                Column(Modifier.fillMaxWidth().weight(1f).cocoConversationBackground(previewColors)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("appearance-preview-conversation")) {
                    if (attachments) AttachmentPreviewMessages() else TextPreviewMessages()
                }
            }
        }
    }
}

@Composable
private fun TextPreviewMessages() {
    val incomingText = stringResource(R.string.appearance_preview_incoming)
    val outgoingText = stringResource(R.string.appearance_preview_outgoing)
    val incoming = remember(incomingText) { previewMessage("incoming", incomingText, own = false) }
    val outgoing = remember(outgoingText) { previewMessage("outgoing", outgoingText).copy(
        reactions = listOf(ReactionSummary("❤️", 1, true), ReactionSummary("👍", 1, false))) }
    PreviewMessage(incoming, own = false)
    Spacer(Modifier.height(LocalChatAppearanceStyle.current.gap.dp))
    PreviewMessage(outgoing)
}

@Composable
private fun AttachmentPreviewMessages() {
    val media by rememberAppearancePreviewMedia()
    val photo = remember(media) { Attachment("appearance-photo", "appearance-photo", AttachmentMediaType.IMAGE,
        "image/jpeg", "Photo.jpg", 23615, 280, 175, null, null, media?.photo?.path,
        if (media == null) AttachmentDownloadState.NOT_DOWNLOADED else AttachmentDownloadState.DOWNLOADED,
        AttachmentUploadState.SENT, 100) }
    val voice = remember(media) { Attachment("appearance-voice", "appearance-voice", AttachmentMediaType.VOICE,
        "audio/wav", "Voice.wav", 16044, null, null, 12_000, null, media?.voice?.path,
        if (media == null) AttachmentDownloadState.NOT_DOWNLOADED else AttachmentDownloadState.DOWNLOADED,
        AttachmentUploadState.SENT, 100) }
    val filename = stringResource(R.string.appearance_preview_filename)
    val file = remember(filename) { Attachment("appearance-file", "appearance-file", AttachmentMediaType.FILE,
        "application/pdf", filename, 245760, null, null, null, null, null,
        AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 100) }
    PreviewMessage(remember { previewMessage("photo", "", own = false) }, photo, own = false)
    Spacer(Modifier.height(LocalChatAppearanceStyle.current.gap.dp))
    PreviewMessage(remember { previewMessage("voice", "") }, voice)
    Spacer(Modifier.height(LocalChatAppearanceStyle.current.groupGap.dp))
    PreviewMessage(remember { previewMessage("file", "") }, file)
}

private fun previewMessage(id: String, text: String, own: Boolean = true) = Message("appearance-$id", "appearance-$id",
    "appearance-preview", if (own) "preview-me" else "preview-peer", text, 1, "2026-10-26T14:28:00Z", MessageStatus.READ)

@Composable
private fun PreviewMessage(message: Message, attachment: Attachment? = null, own: Boolean = true) {
    MessageBubble(message = message, attachment = attachment, isOwnMessage = own, playingAttachmentId = null,
        onRetry = {}, onDownload = {}, onTogglePlay = { _, _ -> }, onOpenFile = { _, _ -> }, onOpenImage = {},
        onLongPress = {}, onSelectionTap = {}, onToggleReaction = {}, onQuickReaction = null, onScrollToReply = {})
}

private data class AppearancePreviewMedia(val photo: File, val voice: File)

/** Small app-owned fixtures, prepared once off the main thread. No user history or remote media. */
@Composable
private fun rememberAppearancePreviewMedia(): State<AppearancePreviewMedia?> {
    val context = LocalContext.current
    return produceState<AppearancePreviewMedia?>(null, context) {
        value = try { withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "appearance-preview-v1").apply { mkdirs() }
            val photo = File(folder, "sample.jpg")
            if (!photo.isFile || photo.length() != 23615L) context.resources.openRawResource(R.raw.appearance_preview).use { input ->
                photo.outputStream().use { input.copyTo(it) }
            }
            val voice = File(folder, "sample.wav")
            if (!voice.isFile || voice.length() != 16044L) {
                val rate = 8000
                val samples = rate
                val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(samples * 2)
                repeat(samples) { index ->
                    val envelope = .15 + .7 * (.5 + .5 * sin(2 * PI * index / (rate / 3.5)))
                    buffer.putShort((sin(2 * PI * 240 * index / rate) * envelope * 14000).toInt().toShort())
                }
                voice.writeBytes(buffer.array())
            }
            AppearancePreviewMedia(photo, voice)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
        catch (_: android.content.res.Resources.NotFoundException) { null }
    }
}
