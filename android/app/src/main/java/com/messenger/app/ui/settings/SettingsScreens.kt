package com.messenger.app.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
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
                    Text(text = lang.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.primaryText, modifier = Modifier.weight(1f))
                    RadioButton(selected = selected, onClick = null)
                }
            }
        }
        }
    }
}

/** Shared top bar for settings screens. */
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = CocoSpacing.lg)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = title,
                modifier = Modifier.fillMaxWidth().padding(vertical = CocoSpacing.sm),
                start = { com.messenger.app.ui.components.CocoBackButton(onClick = onBack) },
            )
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
