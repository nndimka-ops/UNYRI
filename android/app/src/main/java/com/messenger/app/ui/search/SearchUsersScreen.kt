package com.messenger.app.ui.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.*

internal enum class SearchScope(val label: Int) {
    ALL(R.string.chats_filter_all), CHATS(R.string.nav_chats),
    MESSAGES(R.string.search_scope_messages), PEOPLE(R.string.search_scope_people);
    val searchesMessages get() = this == ALL || this == MESSAGES
    val searchesPeople get() = this == PEOPLE
}

/** Owns the local query while visible and refreshes its one-shot snapshot after catch-up.
 * Leaving/changing the query cancels a pending refresh; rebinding on resume also covers
 * a sync completed while hidden. All waits are flow/event driven, never polling. */
internal suspend fun collectLocalSearchWithSync(
    syncPhases: Flow<com.messenger.app.data.sync.SyncPhase>,
    queries: Flow<String?>,
    resumed: Flow<Boolean>,
    setQuery: (String) -> Unit,
    refreshAfterSync: (String) -> Unit,
) {
    combine(queries, resumed) { query, visible -> query?.takeIf { visible && it.isNotBlank() } }
        .distinctUntilChanged()
        .collectLatest { activeQuery ->
            if (activeQuery == null) {
                setQuery("")
                return@collectLatest
            }
            setQuery(activeQuery)
            try {
                var previous: com.messenger.app.data.sync.SyncPhase? = null
                syncPhases.collectLatest { phase ->
                    val completed = previous == com.messenger.app.data.sync.SyncPhase.RUNNING &&
                        phase == com.messenger.app.data.sync.SyncPhase.IDLE
                    previous = phase
                    if (completed) {
                        // A following sync pass, query change or lifecycle stop cancels this delay.
                        delay(200)
                        refreshAfterSync(activeQuery)
                    }
                }
            } finally {
                // Invalidate the view model's pending/in-flight one-shot result as well.
                setQuery("")
            }
        }
}

/** Same scope identity as iOS: violet local all/messages, blue chats/people. */
internal fun searchScopeTint(scope: SearchScope, colors: CocoColors): Color = when (scope) {
    SearchScope.ALL, SearchScope.MESSAGES -> colors.categoryViolet
    SearchScope.CHATS, SearchScope.PEOPLE -> colors.categoryBlue
}

private fun searchScopeIcon(scope: SearchScope) = when (scope) {
    SearchScope.ALL -> com.messenger.app.ui.settings.AuxiliaryIcons.Grid
    SearchScope.CHATS -> com.messenger.app.ui.settings.AuxiliaryIcons.Chats
    SearchScope.MESSAGES -> CocoIcons.Chat
    SearchScope.PEOPLE -> com.messenger.app.ui.settings.AuxiliaryIcons.People
}

@Composable
internal fun CompactSearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit,
    onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    val label = stringResource(R.string.settings_search_label)
    Row(modifier.background(colors.elevatedBackground, RoundedCornerShape(16.dp)).heightIn(min = 48.dp)
        .padding(start = 13.dp, end = if (query.isEmpty()) 13.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(CocoIcons.Search, null, tint = colors.secondaryText, modifier = Modifier.size(18.dp))
        BasicTextField(query, onQuery, singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.primaryText),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { field -> Box { if (query.isEmpty()) Text(label, color = colors.mutedText); field() } })
        if (query.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
            Icon(CocoIcons.Close, stringResource(R.string.search_clear), tint = colors.secondaryText, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
internal fun SearchScopeControl(selected: SearchScope, onSelect: (SearchScope) -> Unit) {
    val colors = LocalCocoColors.current
    val colorStyle = LocalAppColorStyle.current
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
        .clip(shape).background(Color(0xFF000000.toInt() or colorStyle.materialRgb(0x10151D)))
        .border(.5.dp, Color(0xFF000000.toInt() or colorStyle.materialRgb(0x27313F)), shape)
        .horizontalScroll(rememberScrollState()).selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchScope.entries.filter { it != SearchScope.PEOPLE }.forEach { option ->
            val tint = searchScopeTint(option, colors)
            val isSelected = selected == option
            Row(Modifier
                .selectable(selected == option, role = Role.Tab, onClick = { onSelect(option) })
                .heightIn(min = 48.dp).padding(vertical = 6.dp)
                .background(tint.copy(alpha = if (isSelected) .14f else 0f), RoundedCornerShape(12.dp))
                .border(.75.dp, tint.copy(alpha = if (isSelected) .32f else 0f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(searchScopeIcon(option), null, Modifier.size(14.dp), tint = if (isSelected) tint else colors.secondaryText)
                Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) tint else colors.secondaryText, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable private fun SettingsSearchPeopleAction(onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = colors.elevatedBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Icon(com.messenger.app.ui.settings.AuxiliaryIcons.People, null, tint = colors.secondaryText, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.search_people_action), color = colors.primaryText, fontSize = 14.sp)
        }
    }
}

/** Uses the same safe local snippet/highlight policy as message search, with the iOS two-line row. */
@Composable private fun CompactMessageResult(result: com.messenger.app.domain.model.MessageSearchResult,
    query: String, title: String, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val displayText = searchDisplayText(result)
    val text = remember(displayText, query) { auxiliarySearchHighlight(displayText, query) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val yesterday = stringResource(R.string.history_list_yesterday)
    val zone = java.time.ZoneId.systemDefault()
    val date = remember(result.createdAt, locale, yesterday, zone, java.time.LocalDate.now(zone)) {
        com.messenger.app.ui.calls.auxiliaryListDate(result.createdAt, java.time.Instant.now(), zone, locale, yesterday)
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = colors.elevatedBackground, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 68.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(CocoIcons.Chat, null, tint = colors.secondaryText, modifier = Modifier.size(32.dp)
                .background(colors.glassHighlight, RoundedCornerShape(10.dp)).padding(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), color = colors.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    date?.let { Text(it, color = colors.mutedText, fontSize = 11.sp) }
                }
                Text(text, color = colors.primaryText, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun auxiliarySearchHighlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val terms = com.messenger.app.data.search.SearchQueryBuilder.highlightTerms(query)
    val snippet = com.messenger.app.data.search.SearchQueryBuilder.snippet(text, query)
    return androidx.compose.ui.text.buildAnnotatedString {
        var index = 0
        while (index < snippet.length) {
            val match = terms.firstOrNull { snippet.regionMatches(index, it, 0, it.length, ignoreCase = true) }
            if (match != null) {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append(snippet.substring(index, index + match.length)); pop(); index += match.length
            } else { append(snippet[index]); index++ }
        }
    }
}

@Composable
private fun SearchIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = com.messenger.app.ui.theme.cocoQuietPressFeedback(interaction).size(48.dp).background(tint.copy(alpha = .08f), CircleShape)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable private fun SearchSectionTitle(title: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = LocalCocoColors.current.primaryText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SearchStateMessage(text: String) {
    val colors = LocalCocoColors.current
    Row(modifier = Modifier.fillMaxWidth().background(colors.elevatedBackground, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(CocoIcons.Search, null, tint = colors.secondaryText, modifier = Modifier.size(32.dp).background(colors.glassHighlight, RoundedCornerShape(10.dp)).padding(6.dp))
        Text(text = text, fontSize = 14.sp, color = colors.secondaryText)
    }
}

@Composable
private fun SearchErrorState(message: String, onRetry: () -> Unit, actionLabel: Int = R.string.retry) {
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    Surface(
        shape = CocoShapes.radiusCard,
        color = colors.danger.copy(alpha = .07f),
        border = BorderStroke(.5.dp, colors.danger.copy(alpha = .3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(CocoSpacing.lg),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(CocoSpacing.md))
            TextButton(onClick = { CocoHaptics.tap(hapticView); onRetry() }) { Text(stringResource(actionLabel), color = colors.accentSoft) }
        }
    }
}

/** One full-row target with human-readable identity, no duplicated action button. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchUserRow(user: PublicUser, enabled: Boolean = true, status: com.messenger.app.data.websocket.PeerPresence? = null, showsPresence: Boolean = false, contactActionLabel: String? = null, onContactChange: () -> Unit = {}, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    var menu by remember(user.id) { mutableStateOf(false) }
    Surface(
        shape = CocoShapes.radiusChatRow,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled,
            onClick = { CocoHaptics.tap(hapticView); onClick() },
            onLongClick = if (contactActionLabel != null) { { menu = true } } else null)
            .semantics { if (contactActionLabel != null) customActions = listOf(CustomAccessibilityAction(contactActionLabel) { onContactChange(); true }) },
    ) {
        Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 68.dp).padding(horizontal = CocoSpacing.md, vertical = 10.dp),
        ) {
            UserAvatar(
                userId = user.id,
                avatarVersion = user.avatarVersion,
                hasAvatar = user.hasAvatar,
                name = user.visibleName,
                color = colors.surfaceVariant,
                contentColor = colors.primaryText,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(CocoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.visibleName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = com.messenger.app.ui.publicHandle(user),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val presenceText = com.messenger.app.ui.chats.presenceLabel(status)
                if (showsPresence) Text(
                    text = presenceText.ifEmpty { stringResource(if (status == null) R.string.presence_unknown else R.string.presence_offline) },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status?.online == true) Color(0xFF63D7A5) else colors.mutedText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(CocoSpacing.sm))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
        }
        if (contactActionLabel != null) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(contactActionLabel) }, onClick = { menu = false; onContactChange() })
        }
        HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), thickness = .5.dp, color = colors.divider.copy(alpha = .4f))
        }
    }
}
