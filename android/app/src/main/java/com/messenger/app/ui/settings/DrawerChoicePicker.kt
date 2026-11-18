package com.messenger.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.*

/** The structural choice takes effect immediately; other appearance controls keep their draft. */
@Composable internal fun DrawerChoicePicker() {
    val context = LocalContext.current
    val preference = remember(context) { DrawerChoicePreference(context) }
    val current = LocalDrawerChoice.current
    val colors = LocalCocoColors.current
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sidebar_appearance_title), color = colors.primaryText, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.drawer_choice_hint), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
        val option: @Composable (DrawerChoice, Modifier) -> Unit = { choice, modifier ->
            Surface(color = colors.surface, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(if (current == choice) 2.dp else .5.dp, if (current == choice) colors.accent else colors.outlineSecondary),
                modifier = modifier.selectable(current == choice, role = Role.RadioButton,
                    onClick = { failed = !preference.save(choice) }).testTag("drawer-choice-${choice.name.lowercase()}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawerMiniPreview(choice)
                    Text(stringResource(if (choice == DrawerChoice.A) R.string.drawer_choice_a else R.string.drawer_choice_c), color = colors.primaryText)
                }
            }
        }
        if (LocalDensity.current.fontScale >= 1.6f) Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            option(DrawerChoice.A, Modifier.fillMaxWidth()); option(DrawerChoice.C, Modifier.fillMaxWidth())
        } else Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            option(DrawerChoice.A, Modifier.weight(1f)); option(DrawerChoice.C, Modifier.weight(1f))
        }
        if (failed) Text(stringResource(R.string.sidebar_appearance_save_error), color = colors.danger)
    }
}

/** Static geometry preview, with no repositories, subscriptions, image loading or animation. */
@Composable private fun DrawerMiniPreview(choice: DrawerChoice) {
    Column(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF242424)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().then(if (choice == DrawerChoice.C) Modifier.background(Color.White.copy(alpha = .06f), RoundedCornerShape(12.dp)).padding(7.dp) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(if (choice == DrawerChoice.A) 20.dp else 28.dp).background(Color(0xFF666666), CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(40.dp).height(5.dp).background(Color(0xFFDDDDDD), CircleShape))
                Box(Modifier.width(28.dp).height(3.dp).background(Color.Gray, CircleShape))
            }
        }
        Box(Modifier.fillMaxWidth().height(12.dp).background(Color.White.copy(alpha = .12f), CircleShape))
        repeat(if (choice == DrawerChoice.A) 4 else 2) {
            Box(Modifier.fillMaxWidth(.8f).height(5.dp).background(Color(0xFF777777), CircleShape))
        }
        if (choice == DrawerChoice.C) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) { Box(Modifier.weight(1f).height(12.dp).background(Color.White.copy(alpha = .1f), CircleShape)) }
        }
    }
}
