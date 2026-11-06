package com.messenger.app.ui.theme

import android.content.SharedPreferences
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.chats.CapsuleChatStyle
import com.messenger.app.ui.chats.SavedChatBubbleShape
import com.messenger.app.ui.chats.savedChatFontFamily
import org.json.JSONObject

/** Local presentation preferences only. Draft edits stay in the settings screen until saved. */
@Immutable
internal data class ChatAppearanceSettings(
    val fontSize: Int = CapsuleChatStyle.fontSize.toInt(),
    val fontFamily: String = CapsuleChatStyle.fontFamilyId,
    val mediumText: Boolean = CapsuleChatStyle.mediumText,
    val lineHeight: Float = CapsuleChatStyle.lineHeight,
    val density: String = "relaxed",
    val cornerRadius: Float? = null,
    val outgoingColor: String = "default",
    val transparency: Int? = null,
    val patterned: Boolean = CocoSavedPattern.enabledByDefault,
)

internal fun defaultChatAppearanceSettings() = ChatAppearanceSettings()

internal const val CHAT_APPEARANCE_OVERRIDES_KEY = "chat-appearance-overrides-v1"
internal val CHAT_APPEARANCE_PREFERENCE_KEYS = setOf(
    "chat-text-size", "subtle-pattern", CHAT_APPEARANCE_OVERRIDES_KEY,
)

private val familyIds = setOf("system", "serif", "monospace")
private val densityIds = setOf("default", "compact", "relaxed")
private val outgoingColorIds = setOf("default", "blue", "violet", "green", "graphite")

/** Validate once at storage/preview boundaries, never while drawing a message. */
internal fun ChatAppearanceSettings.sanitized(): ChatAppearanceSettings {
    val defaults = defaultChatAppearanceSettings()
    return copy(
        fontSize = fontSize.coerceIn(13, 22),
        fontFamily = fontFamily.takeIf { it in familyIds } ?: defaults.fontFamily,
        lineHeight = lineHeight.takeIf { it.isFinite() }?.coerceIn(1.05f, 1.7f) ?: defaults.lineHeight,
        density = density.takeIf { it in densityIds } ?: defaults.density,
        cornerRadius = cornerRadius?.takeIf { it.isFinite() }?.coerceIn(0f, 42f),
        outgoingColor = outgoingColor.takeIf { it in outgoingColorIds } ?: "default",
        transparency = transparency?.coerceIn(0, 85),
    )
}

internal fun readChatAppearanceSettings(preferences: SharedPreferences): ChatAppearanceSettings {
    val defaults = defaultChatAppearanceSettings()
    val json = runCatching {
        preferences.getString(CHAT_APPEARANCE_OVERRIDES_KEY, null)?.let(::JSONObject)
    }.getOrNull()?.takeIf { it.optInt("version", 1) == 1 }
    return ChatAppearanceSettings(
        // Keep the established keys authoritative, including older controls that still write them.
        fontSize = runCatching { preferences.getInt("chat-text-size", defaults.fontSize) }.getOrDefault(defaults.fontSize),
        patterned = runCatching { preferences.getBoolean("subtle-pattern", defaults.patterned) }.getOrDefault(defaults.patterned),
        fontFamily = json?.optString("fontFamily", defaults.fontFamily) ?: defaults.fontFamily,
        mediumText = json?.optBoolean("mediumText", defaults.mediumText) ?: defaults.mediumText,
        lineHeight = json?.optDouble("lineHeight", defaults.lineHeight.toDouble())?.toFloat() ?: defaults.lineHeight,
        density = json?.optString("density", defaults.density) ?: defaults.density,
        cornerRadius = json?.takeIf { it.has("cornerRadius") && !it.isNull("cornerRadius") }
            ?.optDouble("cornerRadius", Double.NaN)?.toFloat(),
        outgoingColor = json?.optString("outgoingColor", "default") ?: "default",
        transparency = (json?.opt("transparency") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() }?.toInt(),
    ).sanitized()
}

/** One atomic edit. Merely opening or dragging the preview never reaches this function. */
internal fun saveChatAppearanceSettings(preferences: SharedPreferences, settings: ChatAppearanceSettings) {
    val chosen = settings.sanitized()
    val json = JSONObject()
        .put("version", 1)
        .put("fontFamily", chosen.fontFamily)
        .put("mediumText", chosen.mediumText)
        .put("lineHeight", chosen.lineHeight.toDouble())
        .put("density", chosen.density)
        .put("outgoingColor", chosen.outgoingColor)
    chosen.cornerRadius?.let { json.put("cornerRadius", it.toDouble()) }
    chosen.transparency?.let { json.put("transparency", it) }
    val editor = preferences.edit()
        .putInt("chat-text-size", chosen.fontSize)
        .putBoolean("subtle-pattern", chosen.patterned)
    if (chosen == defaultChatAppearanceSettings().sanitized()) editor.remove(CHAT_APPEARANCE_OVERRIDES_KEY)
    else editor.putString(CHAT_APPEARANCE_OVERRIDES_KEY, json.toString())
    editor.apply()
}

/** One immutable snapshot shared by chat rows and the settings preview. */
@Immutable
internal data class ResolvedChatAppearance(
    val messageFontFamily: FontFamily,
    val mediumText: Boolean,
    val lineHeight: Float,
    val padX: Float,
    val padY: Float,
    val voicePadY: Float,
    val gap: Float,
    val groupGap: Float,
    val incoming: Color,
    val outgoing: Color,
    val voiceIncoming: Color,
    val voiceOutgoing: Color,
    val fileIncoming: Color,
    val fileOutgoing: Color,
    val documentAlbumShape: Shape,
    private val incomingBodyShape: Shape,
    private val outgoingBodyShape: Shape,
    private val incomingVoiceShape: Shape,
    private val outgoingVoiceShape: Shape,
    private val incomingFileShape: Shape,
    private val outgoingFileShape: Shape,
) {
    fun bodyShape(own: Boolean) = if (own) outgoingBodyShape else incomingBodyShape
    fun voiceShape(own: Boolean) = if (own) outgoingVoiceShape else incomingVoiceShape
    fun fileShape(own: Boolean) = if (own) outgoingFileShape else incomingFileShape
    fun voiceBackground(own: Boolean) = if (own) voiceOutgoing else voiceIncoming
    fun fileBackground(own: Boolean) = if (own) fileOutgoing else fileIncoming
}

internal fun chatAppearanceOutgoingTint(id: String): Color = when (id) {
    "blue" -> Color(0xFF1C2D50)
    "violet" -> Color(0xFF2C2655)
    "green" -> Color(0xFF173F36)
    "graphite" -> Color(0xFF2A2A2A)
    else -> CapsuleChatStyle.outgoing.copy(alpha = 1f)
}

internal fun resolveChatAppearance(settings: ChatAppearanceSettings, appColorStyle: AppColorStyle = AppColorStyle.COLORFUL): ResolvedChatAppearance {
    val selected = settings.sanitized()
    val spacing = when (selected.density) {
        "compact" -> floatArrayOf(10f, 6f, 3f, 3f, 3f)
        "relaxed" -> floatArrayOf(16f, 10f, 7f, 8f, 8f)
        else -> floatArrayOf(CapsuleChatStyle.padX, CapsuleChatStyle.padY, CapsuleChatStyle.voicePadY,
            CapsuleChatStyle.gap, CapsuleChatStyle.groupGap)
    }
    fun surface(base: Color, outgoing: Boolean): Color {
        val alpha = selected.transparency?.let { 1f - it / 100f } ?: base.alpha
        val chosenColor = if (outgoing && selected.outgoingColor != "default") {
            chatAppearanceOutgoingTint(selected.outgoingColor).copy(alpha = alpha)
        } else base.copy(alpha = alpha)
        if (appColorStyle == AppColorStyle.COLORFUL) return chosenColor
        // A reversible rendering choice. Never rewrite the user's saved hue,
        // transparency, shape or the colors of media, emoji and read receipts.
        val rgb = appColorStyle.materialRgb(chosenColor.toArgb() and 0xFFFFFF)
        return Color(0xFF000000L or rgb.toLong()).copy(alpha = alpha)
    }
    fun rounded(base: Shape): Shape {
        val radius = selected.cornerRadius?.dp ?: return base
        return when (base) {
            is SavedChatBubbleShape -> base.copy(
                topStart = if (base.topStart.value == 0f) 0.dp else radius,
                topEnd = if (base.topEnd.value == 0f) 0.dp else radius,
                bottomEnd = if (base.bottomEnd.value == 0f) 0.dp else radius,
                bottomStart = if (base.bottomStart.value == 0f) 0.dp else radius,
            )
            is RoundedCornerShape -> {
                // Generated voice/file corners are dp values. Preserve any intentional zero end.
                fun replace(corner: CornerSize) = if (corner.toPx(Size(1000f, 1000f), Density(1f)) == 0f)
                    CornerSize(0.dp) else CornerSize(radius)
                RoundedCornerShape(replace(base.topStart), replace(base.topEnd), replace(base.bottomEnd), replace(base.bottomStart))
            }
            else -> base
        }
    }
    return ResolvedChatAppearance(
        messageFontFamily = savedChatFontFamily(selected.fontFamily),
        mediumText = selected.mediumText,
        lineHeight = selected.lineHeight,
        padX = spacing[0], padY = spacing[1], voicePadY = spacing[2], gap = spacing[3], groupGap = spacing[4],
        incoming = surface(CapsuleChatStyle.incoming, false),
        outgoing = surface(CapsuleChatStyle.outgoing, true),
        voiceIncoming = surface(CapsuleChatStyle.voiceIncoming, false),
        voiceOutgoing = surface(CapsuleChatStyle.voiceOutgoing, true),
        fileIncoming = surface(CapsuleChatStyle.fileIncoming, false),
        fileOutgoing = surface(CapsuleChatStyle.fileOutgoing, true),
        documentAlbumShape = selected.cornerRadius?.let { RoundedCornerShape(it.dp) } ?: CapsuleChatStyle.mediaShape,
        incomingBodyShape = rounded(CapsuleChatStyle.bodyShape(false)),
        outgoingBodyShape = rounded(CapsuleChatStyle.bodyShape(true)),
        incomingVoiceShape = rounded(CapsuleChatStyle.voiceShape(false)),
        outgoingVoiceShape = rounded(CapsuleChatStyle.voiceShape(true)),
        incomingFileShape = rounded(CapsuleChatStyle.fileShape(false)),
        outgoingFileShape = rounded(CapsuleChatStyle.fileShape(true)),
    )
}

internal val LocalChatAppearanceSettings = staticCompositionLocalOf { defaultChatAppearanceSettings() }
internal val LocalChatAppearanceStyle = staticCompositionLocalOf { resolveChatAppearance(defaultChatAppearanceSettings()) }
