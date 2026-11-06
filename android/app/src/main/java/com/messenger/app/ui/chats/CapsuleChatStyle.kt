package com.messenger.app.ui.chats

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoDarkColors
import com.messenger.app.ui.theme.CocoColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path

/** Approved coco-capsules-theme (48).json, 26 October 2026.
 * Immutable presentation tokens only; queue, encryption and media ownership
 * never depend on this object. Android is the first validation target.
 */
internal object CapsuleChatStyle {
    // BEGIN SAVED CHAT STYLE
    const val radius = 15f
    const val padX = 12f
    const val padY = 8f
    const val width = 0.96f
    const val gap = 5f
    const val side = 8f
    const val fontSize = 17f
    const val lineHeight = 1.3f
    const val timeSize = 10f
    const val timeOffset = 2f
    const val emojiSize = 14f
    const val reactionHeight = 22f
    const val reactionOffset = 2f
    const val voiceWidth = 195f
    const val playSize = 33f
    const val waveHeight = 15f
    const val fileWidth = 195f
    const val mediaWidth = 220f
    const val mediaRadius = 18f
    const val avatarSize = 36f
    const val iconSize = 21f
    const val entryFont = 15f
    const val albumGap = 4f
    const val mediumText = true
    const val showTimes = true
    const val showReactions = true
    const val metadataMode = "below"
    const val borderWidth = 0f
    const val groupGap = 8f
    const val voicePadY = 5f
    const val fontFamilyId = "system"
    val messageFontFamily = savedChatFontFamily(fontFamilyId)
    const val reactionWidth = 25f
    const val metaGap = CapsuleChatChrome.metaGap
    const val headerHeight = CapsuleChatChrome.topHeight
    const val entryHeight = CapsuleChatChrome.entryHeight
    const val entryRadius = CapsuleChatChrome.entryRadius
    val background = Color(0xFF000000)
    val incoming = Color(0xC42A2A2A)
    val outgoing = Color(0xC4003D4D)
    val text = Color(0xFFF1F1F1)
    val outText = Color(0xFFFFFFFF)
    val muted = Color(0xFFB0B0B0)
    val accent = Color(0xFFFFFFFF)
    val buttonInk = Color(0xFF000000)
    val border = Color(0xFF494949)
    val entry = Color(0xFF282828)
    val reaction = Color(0x00353535)
    val voiceIncoming = Color(0xBF2A2A2A)
    val voiceOutgoing = Color(0xBF003D4D)
    val fileIncoming = Color(0xBF2A2A2A)
    val fileOutgoing = Color(0xBF003D4D)
    val ticks = CapsuleChatChrome.read
    val waveQuiet = Color(0xFF737373)
    val incomingBodyShape = SavedChatBubbleShape(25f.dp, 25f.dp, 25f.dp, 0f.dp)
    val bodyShape = SavedChatBubbleShape(25f.dp, 25f.dp, 0f.dp, 25f.dp)
    val mediaShape = RoundedCornerShape(mediaRadius.dp)
    val voiceShape = RoundedCornerShape(25f.dp, 25f.dp, 25f.dp, 25f.dp)
    private val outgoingVoiceShape = RoundedCornerShape(25f.dp, 25f.dp, 25f.dp, 25f.dp)
    val fileShape = RoundedCornerShape(25f.dp, 25f.dp, 25f.dp, 25f.dp)
    private val outgoingFileShape = RoundedCornerShape(25f.dp, 25f.dp, 25f.dp, 25f.dp)
    fun bodyShape(isOwnMessage: Boolean) = if (isOwnMessage) bodyShape else incomingBodyShape
    fun voiceShape(isOwnMessage: Boolean) = if (isOwnMessage) outgoingVoiceShape else voiceShape
    fun fileShape(isOwnMessage: Boolean) = if (isOwnMessage) outgoingFileShape else fileShape
    fun voiceBackground(isOwnMessage: Boolean) = if (isOwnMessage) voiceOutgoing else voiceIncoming
    fun fileBackground(isOwnMessage: Boolean) = if (isOwnMessage) fileOutgoing else fileIncoming
    // END SAVED CHAT STYLE
    val back: ImageVector by lazy {
        ImageVector.Builder("CapsuleBack", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f)
            }
        }.build()
    }
    val video: ImageVector by lazy {
        ImageVector.Builder("CapsuleVideo", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 5f); lineTo(13f, 5f); quadTo(15f, 5f, 15f, 7f)
                lineTo(15f, 17f); quadTo(15f, 19f, 13f, 19f)
                lineTo(5f, 19f); quadTo(3f, 19f, 3f, 17f)
                lineTo(3f, 7f); quadTo(3f, 5f, 5f, 5f); close()
                moveTo(15f, 10f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(15f, 14f)
            }
        }.build()
    }
    val file: ImageVector by lazy {
        ImageVector.Builder("CapsuleFile", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 2f); lineTo(6f, 2f); quadTo(4f, 2f, 4f, 4f)
                lineTo(4f, 20f); quadTo(4f, 22f, 6f, 22f)
                lineTo(18f, 22f); quadTo(20f, 22f, 20f, 20f)
                lineTo(20f, 8f); lineTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
                moveTo(8f, 12f); lineTo(16f, 12f); moveTo(8f, 16f); lineTo(16f, 16f)
            }
        }.build()
    }

    val colors = CocoDarkColors.copy(
        background = background, surface = entry, surfaceVariant = CocoDarkColors.surfaceVariant,
        elevated = entry, inputSurface = entry, primaryText = text,
        secondaryText = muted, accent = accent, onAccent = buttonInk,
        outgoingBubble = outgoing, onOutgoingBubble = outText,
        incomingBubble = incoming, onIncomingBubble = text,
        divider = border, deepPanel = entry, elevatedBackground = entry,
        accentBright = accent, accentSoft = accent, accentPressed = muted,
        supportBlue = accent, supportCyan = accent, mutedText = muted,
        veryMutedText = waveQuiet, glassPrimary = entry,
        glassSecondary = incoming, glassFloating = entry,
        outlineActive = accent, outlineSelected = accent,
    )
}

/** Keep the user's saved chat/chrome intact while shared menus and attachment
 * actions inherit the app's selected semantic category colors. */
internal fun capsuleChatPalette(app: CocoColors): CocoColors = CapsuleChatStyle.colors.copy(
    categoryBlue = app.categoryBlue, categoryMint = app.categoryMint,
    categoryViolet = app.categoryViolet, categoryAmber = app.categoryAmber,
    categoryRose = app.categoryRose, isMonochrome = app.isMonochrome,
)

@Composable
internal fun CapsuleComposerButton(icon: ImageVector, label: String, filled: Boolean = false,
    enabled: Boolean = true, compact:Boolean=false, onClick: () -> Unit) {
    val height=if(compact)CapsuleChatChrome.entryHeight.dp else 44.dp
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (filled) height else 36.dp, height)) {
        Box(Modifier.size(if (filled) height else 36.dp)
            .then(if (filled) Modifier.background(if(compact)CapsuleChatChrome.micColor else CapsuleChatStyle.accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = (if(compact) {if(filled)CapsuleChatChrome.micInk else CapsuleChatChrome.bottomInk}
                else if (filled) CapsuleChatStyle.buttonInk else CapsuleChatStyle.accent)
                .copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(if(compact)20.dp else CapsuleChatStyle.iconSize.dp))
        }
    }
}

/** Compatibility entry for physical component fixtures. */
@Composable
internal fun CapsuleMediaAlbumRow(messages: List<com.messenger.app.domain.model.Message>, outgoing: Boolean,
    cell: @Composable (com.messenger.app.domain.model.Message) -> Unit) = CapsuleChatAlbum(messages,outgoing,cell=cell)
