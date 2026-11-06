package com.messenger.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.search.SearchQueryBuilder
import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoHaptics
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun MessageSearchScreen(
    viewModel: MessageSearchViewModel,
    onBack: () -> Unit,
    onOpenResult: (MessageSearchResult) -> Unit,
) {
    val query by viewModel.queryState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current

    MaterialTheme {
        com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding().padding(16.dp).fillMaxSize()) {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = stringResource(R.string.search_messages_title),
                    start = { com.messenger.app.ui.components.CocoBackButton(onClick = { CocoHaptics.tap(hapticView); onBack() }) },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_messages_title)) },
                    leadingIcon = { Icon(CocoIcons.Search,null,tint=colors.accent) },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor=colors.inputSurface,
                        unfocusedContainerColor=colors.inputSurface,focusedBorderColor=colors.accent,
                        unfocusedBorderColor=colors.divider,focusedTextColor=colors.primaryText,
                        unfocusedTextColor=colors.primaryText,cursorColor=colors.accent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (val state = uiState) {
                    MessageSearchUiState.Idle -> Text(stringResource(R.string.search_messages_hint), style = MaterialTheme.typography.bodySmall)
                    MessageSearchUiState.Loading -> androidx.compose.material3.CircularProgressIndicator()
                    MessageSearchUiState.Error -> Text(stringResource(R.string.unified_search_local_error))
                    MessageSearchUiState.NoResults -> Text(stringResource(R.string.search_no_results))
                    is MessageSearchUiState.Results -> {
                        LazyColumn {
                            items(state.results, key = { it.messageId }) { result ->
                                SearchResultRow(result, query) { CocoHaptics.tap(hapticView); onOpenResult(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchResultRow(
    result: MessageSearchResult,
    query: String,
    onOpenResult: (MessageSearchResult) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min=48.dp)
            .clickable { onOpenResult(result) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = androidx.compose.runtime.remember(result.text, query) { highlight(result.text, query) },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val date = androidx.compose.runtime.remember(result.createdAt, locale, java.time.LocalDate.now()) {
            com.messenger.app.ui.chats.chatTimestamp(result.createdAt, java.time.Instant.now(), java.time.ZoneId.systemDefault(), locale)
        }
        if (date != null) Text(text = date, style = MaterialTheme.typography.bodySmall,color=LocalCocoColors.current.secondaryText)
    }
}

/** Local, HTML-free highlight: bolds the matched query fragments directly in
 *  Compose text. Never interpolates FTS output into markup (M.11). */
private fun highlight(text: String, query: String): AnnotatedString {
    val terms = SearchQueryBuilder.highlightTerms(query)
    val snippet = SearchQueryBuilder.snippet(text, query)
    val annotated = buildAnnotatedString {
        if (terms.isEmpty()) {
            append(snippet)
            return@buildAnnotatedString
        }
        var index = 0
        while (index < snippet.length) {
            val match = terms.firstOrNull { term -> snippet.regionMatches(index, term, 0, term.length, ignoreCase = true) }
            if (match != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(snippet.substring(index, index + match.length)) }
                index += match.length
            } else {
                append(snippet[index])
                index++
            }
        }
    }
    return annotated
}
