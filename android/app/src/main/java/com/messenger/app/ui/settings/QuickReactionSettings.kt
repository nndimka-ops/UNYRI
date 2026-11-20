package com.messenger.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus

internal object QuickReactionPolicy {
    val choices = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    fun normalize(value: String?): String = if (value == "" || value in choices) value.orEmpty() else "👍"
    fun eligible(message: Message, selecting: Boolean): Boolean = !selecting &&
        !message.isDeletedForEveryone && message.serverId != null &&
        message.status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)
}

@Composable
internal fun rememberQuickReaction(): String {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("chat-interaction", Context.MODE_PRIVATE) }
    var value by remember(prefs) { mutableStateOf(QuickReactionPolicy.normalize(prefs.getString("quick-reaction", "👍"))) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "quick-reaction") value = QuickReactionPolicy.normalize(prefs.getString(key, "👍"))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = QuickReactionPolicy.normalize(prefs.getString("quick-reaction", "👍"))
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
fun QuickReactionSettings() {
    val selected = rememberQuickReaction()
    val context = LocalContext.current
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.quick_reaction), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.quick_reaction_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            fun select(emoji: String) {
                context.getSharedPreferences("chat-interaction", Context.MODE_PRIVATE).edit().putString("quick-reaction", emoji).apply()
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickReactionPolicy.choices.forEach { emoji ->
                    FilterChip(selected = selected == emoji, onClick = { select(emoji) },
                        label = { Text(emoji) }, modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.categoryMint.copy(alpha = .2f)))
                }
            }
            FilterChip(selected = selected.isEmpty(), onClick = { select("") },
                label = { Text(stringResource(R.string.quick_reaction_off)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
