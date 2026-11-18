package com.messenger.app.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.ThemeMode
import java.util.Locale

/** Keep the existing navigation contract; appearance uses a draft and an exact chat preview. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppearanceScreen(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    ChatAppearanceSettingsScreen(onBack)
}

/** COCO FLOW Language selector (Этап N.1 §24): System default + 20 languages.
 *  Changing applies without logout (activity recreation is allowed). */
@Composable
fun LanguageScreen(
    currentTag: String?,
    onSelect: (Locale?) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    SettingsScaffold(title = stringResource(R.string.language_label), onBack = onBack) {
        Surface(shape=RoundedCornerShape(20.dp),color=colors.surface) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).selectableGroup()) {
            CocoLanguages.all.forEach { lang ->
                val selected = lang.tag == currentTag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(CocoLanguages.localeFor(lang.tag)) })
                        .background(if(selected)colors.accent.copy(alpha=.07f) else Color.Transparent)
                        .padding(horizontal = CocoSpacing.md, vertical = CocoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = lang.localizedName(), style = MaterialTheme.typography.bodyLarge, color = colors.primaryText, modifier = Modifier.weight(1f))
                    RadioButton(selected = selected, onClick = null)
                }
            }
        }
        }
    }
}

/** Shared top bar for settings screens. */
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, compactHeader: Boolean = false, end: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = CocoSpacing.lg)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = title,
                titleStyle = if (compactHeader) MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp) else MaterialTheme.typography.titleLarge,
                titleFontWeight = if (compactHeader) FontWeight.SemiBold else null,
                modifier = Modifier.fillMaxWidth().padding(vertical = CocoSpacing.sm),
                start = { com.messenger.app.ui.components.CocoBackButton(onClick = onBack) },
                end = end,
            )
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** Equal reserved side slots keep the status centered without overlapping Menu's hit area. */
@Composable
internal fun AuxiliaryConnectionToolbar(modifier: Modifier = Modifier, menu: @Composable () -> Unit,
    connection: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) { menu() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { connection() }
        Spacer(Modifier.width(48.dp))
    }
}

/** Explicit header taps always request socket/catch-up recovery, even if the results refresh is a no-op. */
internal fun auxiliaryConnectionRetry(repository: com.messenger.app.domain.repository.MessageRepository,
    refreshResults: () -> Unit): () -> Unit = {
    repository.retryConnectionSync()
    refreshResults()
}

/** Local iOS UnyriSettingsGroup/settingsPanel values, independent of chat and global surfaces. */
internal object AuxiliaryCardStyle {
    val shape = RoundedCornerShape(18.dp)
    val background = Color(0xFF1B1B1D)
    val border = BorderStroke(1.dp, Color(0xFF343437))
}

/** History's grouped cards are static; work/progress remains owned by the existing sessions. */
@Composable
internal fun HistorySettingsCard(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 4.dp).semantics { heading() },
            color = colors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Surface(Modifier.fillMaxWidth(), shape = AuxiliaryCardStyle.shape, color = AuxiliaryCardStyle.background,
            border = AuxiliaryCardStyle.border) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        if (footer != null) Text(footer, Modifier.padding(horizontal = 4.dp), color = colors.secondaryText, fontSize = 13.sp)
    }
}

@Composable
internal fun HistoryNumberedStep(number: Int, text: String) {
    val colors = LocalCocoColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(20.dp).background(colors.accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = colors.primaryText, fontSize = 13.sp)
    }
}

/** Outline equivalents for the auxiliary routes; no SF Symbols assets or animated painters. */
internal object AuxiliaryIcons {
    private fun outline(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val Grid by lazy { outline("SearchAll") {
        moveTo(3f,3f); lineTo(9f,3f); lineTo(9f,9f); lineTo(3f,9f); close()
        moveTo(15f,3f); lineTo(21f,3f); lineTo(21f,9f); lineTo(15f,9f); close()
        moveTo(3f,15f); lineTo(9f,15f); lineTo(9f,21f); lineTo(3f,21f); close()
        moveTo(15f,15f); lineTo(21f,15f); lineTo(21f,21f); lineTo(15f,21f); close()
    } }
    val Chats by lazy { outline("SearchChats") {
        moveTo(15f,15f); lineTo(7f,15f); lineTo(3f,19f); lineTo(3f,4f); lineTo(17f,4f); lineTo(17f,13f)
        moveTo(9f,17f); lineTo(16f,17f); lineTo(21f,21f); lineTo(21f,8f); lineTo(19f,8f)
    } }
    val People by lazy { outline("People") {
        moveTo(14f,7f); curveTo(14f,12.3f,6f,12.3f,6f,7f); curveTo(6f,1.7f,14f,1.7f,14f,7f); close()
        moveTo(2f,21f); lineTo(2f,18f); curveTo(2f,12f,18f,12f,18f,18f); lineTo(18f,21f); close()
        moveTo(17f,4f); curveTo(23f,4f,23f,11f,18f,11f); moveTo(20f,14f); curveTo(23f,15f,23f,18f,23f,21f)
    } }
    val Account by lazy { outline("AccountCircle") {
        moveTo(22f,12f); curveTo(22f,25.3f,2f,25.3f,2f,12f); curveTo(2f,-1.3f,22f,-1.3f,22f,12f); close()
        moveTo(15f,8f); curveTo(15f,12f,9f,12f,9f,8f); curveTo(9f,4f,15f,4f,15f,8f); close()
        moveTo(5f,19f); curveTo(6f,12f,18f,12f,19f,19f)
    } }
    val Palette by lazy { outline("AppearancePalette") {
        moveTo(12f,2f); curveTo(5f,2f,2f,7f,2f,12f); curveTo(2f,19f,7f,22f,12f,22f)
        curveTo(18f,22f,12f,16f,17f,16f); lineTo(19f,16f); curveTo(24f,16f,23f,2f,12f,2f); close()
        moveTo(7f,8f); lineTo(7.1f,8f); moveTo(12f,6f); lineTo(12.1f,6f); moveTo(17f,8f); lineTo(17.1f,8f); moveTo(6f,13f); lineTo(6.1f,13f)
    } }
    val Bell by lazy { outline("SettingsBell") {
        moveTo(5f,16f); curveTo(7f,14f,6f,11f,7f,8f); curveTo(8f,2f,16f,2f,17f,8f)
        curveTo(18f,11f,17f,14f,19f,16f); lineTo(20f,18f); lineTo(4f,18f); close()
        moveTo(10f,21f); curveTo(11f,23f,13f,23f,14f,21f)
    } }
    val LockShield by lazy { outline("SettingsLockShield") {
        moveTo(12f,2f); lineTo(21f,6f); lineTo(21f,12f); curveTo(21f,18f,16f,21f,12f,23f)
        curveTo(8f,21f,3f,18f,3f,12f); lineTo(3f,6f); close()
        moveTo(8f,11f); lineTo(16f,11f); lineTo(16f,17f); lineTo(8f,17f); close()
        moveTo(9f,11f); lineTo(9f,8f); curveTo(9f,4f,15f,4f,15f,8f); lineTo(15f,11f)
    } }
    val Transfer by lazy { outline("TransferPhones") {
        moveTo(3f,2f); lineTo(10f,2f); lineTo(10f,10f); moveTo(10f,18f); lineTo(10f,22f); lineTo(3f,22f); close()
        moveTo(14f,6f); lineTo(14f,2f); lineTo(21f,2f); lineTo(21f,22f); lineTo(14f,22f); lineTo(14f,14f)
        moveTo(7f,10f); lineTo(17f,10f); lineTo(14f,7f); moveTo(17f,14f); lineTo(7f,14f); lineTo(10f,17f)
    } }
    val Cloud by lazy { outline("BackupCloud") {
        moveTo(6f,19f); curveTo(0f,19f,0f,10f,6f,10f); curveTo(6f,0f,19f,0f,19f,10f)
        curveTo(25f,10f,25f,19f,19f,19f); close()
    } }
    val Key by lazy { outline("RecoveryKey") {
        moveTo(15f,7f); curveTo(15f,13.7f,5f,13.7f,5f,7f); curveTo(5f,.3f,15f,.3f,15f,7f); close()
        moveTo(10f,12f); lineTo(10f,22f); lineTo(14f,22f); lineTo(14f,19f); lineTo(12f,19f); lineTo(12f,16f)
    } }
    val Unsaved by lazy { outline("UnsavedKey") {
        moveTo(7f,3f); lineTo(10f,2f); moveTo(14f,2f); lineTo(17f,3f)
        moveTo(20f,6f); lineTo(22f,9f); moveTo(22f,13f); lineTo(21f,16f)
        moveTo(18f,20f); lineTo(15f,22f); moveTo(11f,22f); lineTo(8f,21f)
        moveTo(4f,18f); lineTo(2f,15f); moveTo(2f,11f); lineTo(3f,8f)
    } }
    val Qr by lazy { outline("TransferQr") {
        moveTo(3f,3f); lineTo(9f,3f); lineTo(9f,9f); lineTo(3f,9f); close()
        moveTo(15f,3f); lineTo(21f,3f); lineTo(21f,9f); lineTo(15f,9f); close()
        moveTo(3f,15f); lineTo(9f,15f); lineTo(9f,21f); lineTo(3f,21f); close()
        moveTo(15f,15f); lineTo(18f,15f); lineTo(18f,18f); lineTo(21f,18f); lineTo(21f,21f); lineTo(15f,21f); lineTo(15f,18f)
    } }
    val Scan by lazy { outline("TransferScan") {
        moveTo(3f,8f); lineTo(3f,3f); lineTo(8f,3f); moveTo(16f,3f); lineTo(21f,3f); lineTo(21f,8f)
        moveTo(21f,16f); lineTo(21f,21f); lineTo(16f,21f); moveTo(8f,21f); lineTo(3f,21f); lineTo(3f,16f)
        moveTo(8f,7f); lineTo(11f,7f); lineTo(11f,10f); lineTo(8f,10f); close()
        moveTo(14f,7f); lineTo(17f,7f); lineTo(17f,10f); lineTo(14f,10f); close()
        moveTo(7f,14f); lineTo(17f,14f); moveTo(8f,17f); lineTo(11f,17f); moveTo(15f,17f); lineTo(17f,17f)
    } }
}
